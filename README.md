 

基于微服务SpringBoot的商城高并发抢单系统（秒杀系统）
===

#### 项目简介

该项目是模拟互联网高并发场景实现了一套商城秒杀系统，项目前后端分离，实现的功能包括用户登录、查看商品列表、查看秒杀商品详情、秒杀商品下单、下单结果通过邮件（短信）通知用户、用户超时未支付取消订单等业务功能。同时利用页面缓存,url缓存,对象缓存,页面静态化,RocketMQ异步下单,Nginx+lua访问redis提前返回无用请求等一系列措施来提高项目的并发能力,使用ApacheBench(简称ab)对系统进行压测时,以5k的并发度共发出10w个请求,单机（2核 4G内存）TPS由最初的不足1000优化到2500+。


##### 系统架构
<center><img src="http://image.easyblog.top/seckill.jpg" style="width: 70%"></center>

##### 开发工具： IDEA、Maven、Git

##### 项目技术： SpringBoot、MyBatis、MySQL、Redis、RocketMQ、OpenResty、Bootstrap、Themeleaf

##### 数据库设计： 共6张表——item(商品信息表)、item_stock(商品库存表)、promo(秒杀商品表)、stock_log(订单流水状态表)、order_info(订单表)、user_info(用户信息表)

#### 实现和优化细节

##### 1、实现分布式Session

在秒杀抢购之前我们用户登录，因为我们需要在用户下单的知道它的身份信息

一般来说，应用服务器的高可用架构设计最为理想的是服务无状态，但实际上业务总会有状态的，以session记录用户信息的例子来讲，未登入时，服务器没有记入用户信息的session访问网站都是以游客方式访问的，账号密码登入网站后服务器必须要记录你的用户信息记住你是登入后的状态，以该状态分配给你更多的权限。

目前常用的分布式Session解决方案

一种是基于Spring提供的的分布式Session,导入对应的jar包，简单配置一下就可以使用了，这里不再赘述！

另一种方式就是在用户登录的时候生成一个唯一的token（比如UUID，甚至为了保险起见，还可以拼接上当前毫秒时间戳）,以这个token为key,用户的信息为value把它set到Redis中，并在客户端本地前端使用localSession保存这个token，每次用户请求需要登录状态的业务的时候带上token，服务器收到token到Redis中检查一下是否有这个token，如果有就表示用户登录了(同时可以获取到用户的登录信息)，就继续处理业务；否者表示用户的登录状态过期或者没有登录过，那就将用户重定向到登录页面，然让其登录.

##### 2、 防脚本刷单处理

首先我们思考一个问题，对于秒杀活动，会不会有人作弊呢？

比如写好一段代码，在秒杀活动开始之前就不停的循环刷新页面，抢购商品。

可以明确的告诉大家，这种情况是一定会发生的。其实我们看看各种抢票软件就明白了，每次高峰期抢票不也会有很多的渠道去刷票吗，这么看来12306能支持这么多的并发确实做得还不错。

那么如何针对这种作弊的行为呢，其实我们可以在秒杀成功之后做一个验证用户身份的功能，保证你是人而不是代码。

**方式可以是弹出框做个验证码验证，或者做个答题功能，需要人工答对之后才能进行下一步的操作**。

这个办法是非常有效的，不仅可以对作弊行为进行过滤，而且每个人回答的速度是不一样的，所以用户发起的请求就不会全部的积压在一个时间点上。

##### 3、基于redis扣减库存的优化

后台系统在用户抢购成功后，应该先做什么操作呢？

第一步操作就是扣减库存，因为大家知道，参与秒杀活动的商品都是有数量限制的，所以大量用户抢购成功后的第一步操作就是扣减库存。

那么如何进行扣减库存的操作呢？

小伙伴们可能会回答，可以在秒杀系统集群中调用库存系统接口，连接数据库，更新库存数量。但这样一来不就又面临着数据库压力过大的问题了吗？

**其实我们可以在活动开始前，把要秒杀的商品库存存放到Redis集群中，然后扣减库存的时候只操作Redis集群，就可以大大降低数据库压力了**。

当商品的库存扣减完毕之后，用户发送过来抢购的请求其实就不必再发送给秒杀系统了，可以直接在Nginx中过滤掉。

**问题1**：这里会有一个数据库库存和Redis库存不一致的问题，即在redis中预减数据库方案是可以降低数据库压力，但是一旦Redis崩溃了，用户请求就又请求到MySql,之前Mysql和redis库存不同步，这就会造成商品超卖的问题，一般来说，秒杀活动我们的原则应该是“宁可少买，不可超卖”，因为秒杀活动中商品的价格应该是不平时低很多的，商家设定一定数量的商品来参与秒杀肯定是仅供周密计算不会亏本甚至可以有盈利的，但是系统一旦发生超卖现象，就有可能会导致商家严重亏本，这种情况是一定要防止的。

**对策1**：**对于这个问题，可以采用rocketmq发送事务消息异步扣减库存，当redis扣减库存之后，立即给mq发送事务消息通知减库存服务减库存，这一步操作由于有事务，因此可以保证数据库和redis库存的最终一致性**



##### 4、 nginx+lua实现过滤请求

当商品的库存扣减完毕之后，用户发送过来抢购的请求其实就不必再发送给秒杀系统了，可以直接在Nginx中过滤掉。

Nginx具体如何过滤呢？这里提出一点思路，我们可以通过nginx-lua脚本访问redis来实现。

当商品库存为0后，我们可以在redis中设置一个标志（比如：`promo_item_stock_invalid_{商品id}`），然后Lua脚本访问redis查找对应的标志，如果标志返回true则直接过滤掉无效的请求，并返回用户一个“库存已售空”的响应信息就可以了。

这样可以很大幅度的减少海量请求对后台秒杀系统的压力。



##### 5、引入RocketMQ进行流量削峰

通过之前的优化，已经过滤掉了大量的无用请求，那么针对正常参加秒杀，发送给后台的请求我们应该怎么进行架构优化呢？

**这个时候我们就可以引入RocketMQ，来进行流量削峰了**。

也就是说，当用户发送请求，经过Redis扣减库存的操作后发现库存数量还是大于0的，那么这个时候就可以把创建订单的操作发送消息给RocketMQ，然后我们平时使用的订单系统从RocketMQ中限流获取消息，进行常规的操作（生成订单、支付等等）。这样就不会对数据库有太大的压力了。

由于订单系统限流获取消息，所以会造成RockeMQ的消息积压问题，但RocketMQ是高可用的集群，可以保证消息的不丢失。所以完全可以让订单系统每秒几千条的速度去消费，顶多可能会延迟个几十秒才会生成订单而已。



#####  6、安全方面的优化

* （1）**秒杀地址隐藏**：将秒杀地址隐藏起来，防止用户知道秒杀地址后通过秒杀地址在活动未开始之前通过url直接访问后端，从而在秒杀还未还是就给服务器造成巨大的压力

  解决的的方法是配合前面秒杀下单验证码，在验证码验证通过之后生成一个秒杀token(key=`promo_token_promoId_{秒杀商品ID}_userId_{用户ID}_itemId_{商品ID}`)，将此秒杀token放到redis中（设定5分钟的过期时间）并返回给前端，前端随即连带着秒杀token去请求秒杀接口，秒杀接口内首先验证秒杀token是否正确（redis中是否存在token）,如果存在那就继续，否则直接拦截请求，因为很有可能这个请求是非法请求（即不同通过点击前端页面发过来的请求）。

* （2）**接口限流防刷**：以用户的IP地址为key（key的生命周期设置为1分钟），用户访问秒杀下单接口的次数为vlaue，将用户访问次数保存在redis中，直接在nginx层使用lua脚本用户访问一次就使用incr将value+1，1分钟内用户的访问次数超过100次，就通过nginx将用户加入黑名单（key=`ip_black_list`）并将黑名单保存到redis中，限制访问。



##### 7、Google Guava+Redis二级缓存方案

二级缓存结构：

1、`L1`：一级缓存，内存缓存，Caffeine 和 Guava Cache。

2、`L2`：二级缓存，集中式缓存，支持Redis。

**为什么需要引入本地cache？**

由于大量的缓存读取会导致 L2 的网络成为整个系统的瓶颈，因此 L1 的目标是降低对 L2 的读取次数。避免使用独立缓存系统所带来的网络IO开销问题。

L2 可以避免应用重启后导致的 L1数据丢失的问题，同时无需担心L1会增加太多的内存消耗，因为你可以设置 L1中缓存数据的数量。

<center><img src="C:\Users\Administrator\Pictures\QQ浏览器截图\QQ浏览器截图20210103191425.png" style="width:40%;" /></center>



工作流程就是优先到本地缓存中查询，如果本地缓存中有直接返回，如果本地缓存没有再去查redis缓存，reids缓存有则返回并且写本地缓存，redis缓存没有就去查数据库，查到之后写reids缓存和本地缓存

**分布式集群部署时本地缓存的同步问题**

二级缓存在满足高并发的同时也引入了一些新的问题，比如怎么保证分布式场景下各个节点中**本地缓存的一致性问题**，本项目采用`数据变更通知+定期刷新过期缓存`的策略来尽可能的保证缓存的一致性。

首先，明确的一点是在对缓存的所有操作中读缓存是不会产生不一致的，产生不一致的情况都是由写缓存操作导致的，主要包括添加新缓存、缓存值更新以及缓存过期失效，其实概括起来就是两种操作：**刷新缓存和清理缓存**，对应的我在定义了两个对应的缓存操作：`CacheOpt.CACHE_REFRESH`和`CacheOpt.CACHE_CLEAR`

当对本地缓存发生变化时，就立即给mq发送广播消息通知其他节点更新缓存，这里发送的缓存更新消息是专门定制化的一个类`CacheMessage`，一个缓存消息主要包括：缓存全局唯一ID（有UUID+系统时间戳生成），缓存操作类型（CacheOpt），缓存的key，缓存的value

这里重点说一下设计一个全局唯一ID的原因：

由于在缓存发生变化时我们发送的是**广播消息**，这会导致消息发送节点也会重复接收到消息，为了阻止事件循环，我们需要在每个节点消费者接收到消息后首先判断一下这个消息是否是自己发出去的消息，如果是则直接将消息丢弃，否则执行缓存同步操作。

**如何判断这个消息是否是自己发出去的呢**？这里我改造了一下LinkedHashMap，继承LinkedHashMap并重写它get、put和remove方法都加上锁，并且实现它的`removeEldestEntry`方法实现了一个线程安全的LRU，之后在每次发送消息的时候将以消息全局ID为key，消息内容为value将消息存放到本地的这个线程安全的LinkedHashMap中，当收到消息从map重查是否存在相同的key，如果存在则丢弃消息，不存在则处理消息。由于实现了它的removeEldestEntry方法，当这个map满了的时候会自动移除最先进入的消息记录，理论上是没有问题的。

##### 8、Tomcat参数调优

针对SpringBoot嵌入式tomcat容器的优化，可以从以下几点考虑：

***1、线程数*** ***2、超时时间*** ***3、JVM优化***

首先，线程数是一个重点，每一次HTTP请求到达Web服务器，Web服务器都会创建一个线程来处理该请求，该参数决定了应用服务同时可以处理多少个HTTP请求。

比较重要的有两个：*初始线程数*和*最大线程数*。

初始线程数：**保障启动的时候，如果有大量用户访问，能够很稳定的接受请求。**最大线程数：**用来保证系统的稳定性。**

超时时间：用来保障连接数不容易被压垮。如果大批量的请求过来，延迟比较高，很容易把线程数用光，这时就需要提高超时时间。这种情况在生产中是比较常见的 ，**一旦网络不稳定，宁愿丢包也不能把服务器压垮**。

* min-spare-threads：最小线程数，tomcat启动时的初始化的线程数。

* max-threads：Tomcat可创建的最大的线程数，每一个线程处理一个请求，超过这个请求数后，客户端请求只能排队，等有线程释放才能处理。**（<font color=red>建议这个配置数可以在服务器CUP核心数的200~250倍之间）</font>**

* accept-count：当调用Web服务的HTTP请求数达到tomcat的最大线程数时，还有新的HTTP请求到来，这时tomcat会将该请求放在等待队列中，这个acceptCount就是指能够接受的最大等待数，默认100。如果等待队列也被放满了，这个时候再来新的请求就会被tomcat拒绝（connection refused）。

* max-connections：这个参数是指在同一时间，tomcat能够接受的最大连接数。一般这个值要大于(max-threads)+(accept-count)。

* connection-timeout：最长等待时间，如果没有数据进来，等待一段时间后断开连接，释放线程。

JVM优化一般来说没有太多场景，无非就是加大初始的堆，和最大限制堆,当然也不能无限增大，要根据实际情况优化。

1.使用-server模式：设置JVM使用server模式。64位JDK默认启动该模式。

2.指定堆参数：这个根据服务器的内存大小，来设置堆参数。-Xms :设置Java堆栈的初始化大小   -Xmx :设置最大的java堆大小 一把会将这两个参数设置成一样的，为的是避免动态申请释放内存带来的系统开销。

以本系统线上部署测试时的2核 4G的云主机为例，tomcat参数可组如下设置：

```yml
# Tomcat
server:
  tomcat:
    uri-encoding: UTF-8
    #最小线程数 100个，保证在应对高并发是由足够的应对能力
    min-spare-threads: 100
    #最大线程数
    max-threads: 400
    #最大链接数
    max-connections: 2000
    #最大等待队列长度
    accept-count: 1000
  #服务http端口
  port: 9090
  #链接建立超时时间
  connection-timeout: 12000
```





##### 9、在大型的应用集群中若对Redis访问过度依赖，会否产生应用服务器到Redis之间的网络带宽产生瓶颈？若会产生瓶颈，如何解决这样的问题？

(1)如果nginx服务器内存还算充裕，热点数据估量可以承受的话，可以使用nginx的 lua sharedic来降低redis的依赖

(2)如果单台nginx内存不足，则采用 lvs+keepalived+ n 台nginx服务器对内存进行横向拓展

(3)如果lua sharedic成本过高无法承受，则将redis改造为cluster架构，应用集群只连接到n台slave上来均摊网络带宽消耗，且使redis集群的各主机尽量不处在同一个机房或网段，避免使用同一个出入口导致网络带宽瓶颈





##### 7.4 MQ流量削峰

![](http://image.easyblog.top/QQ%E6%B5%8F%E8%A7%88%E5%99%A8%E6%88%AA%E5%9B%BE20201223111651.png)

消息高可用对MQ的可用提出了极高的要求，对于一个秒杀服务，使用MQ来异步削峰**如何保证全链路消息不丢失**是其中一个比较重要的问题，既要体现在：

1. 生产者发送消息到MQ有可能丢失消息
3. MQ接收到消息后，写入硬盘时消息丢失
3. MQ接收到消息后，写入硬盘后，硬盘损坏，也有可能丢失消息
4. 消费者消息MQ，如果进行一步消费，也有可能丢失消息


**路由中心（nameServer）挂了怎么办？**

可以考虑在发送消息经过一定重试次数和等待时间之后如果消息还没有发送成功，那就将消息存储暂时存储在本地，比如存储在一个文本文件中，之后待服务中心恢复之后再启动一个定时扫描的线程，扫描本地文本文件中的消息并发送到MQ中


**生产者发送消息到MQ消息丢失**

方案1：同步发送+多次重试，最通用的方案

方案2：使用RocketMQ提供的事物型消息机制（目前RocketMQ独有的,以性能换取安全性）

![](http://image.easyblog.top/QQ%E6%B5%8F%E8%A7%88%E5%99%A8%E6%88%AA%E5%9B%BE20201223121434.png)

(1)为什么要发送这个half消息?

(2)half消息发送失败咋办?

(3)如果half发送成功，但是没有对应的响应咋办?

(4)half消息如何做到对消费者不可见的?

![](http://image.easyblog.top/QQ%E6%B5%8F%E8%A7%88%E5%99%A8%E6%88%AA%E5%9B%BE20201223124108.png)

(4)订单系统写数据库失败，咋办?

(5)下单成功只有如何等待支付成功?

