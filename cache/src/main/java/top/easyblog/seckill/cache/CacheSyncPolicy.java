package top.easyblog.seckill.cache;

import org.apache.rocketmq.client.producer.SendResult;
import top.easyblog.seckill.cache.sync.CacheMessage;

/**
 * 缓存同步策略
 *
 * @author chenck
 * @date 2020/6/29 17:45
 */
public interface CacheSyncPolicy {


    /**
     * 发布，缓存变更时通知其他节点清理本地缓存
     *
     * @param message
     */
    SendResult publish(CacheMessage message);


}
