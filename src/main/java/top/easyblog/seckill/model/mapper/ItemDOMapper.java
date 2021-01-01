package top.easyblog.seckill.model.mapper;

import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;
import top.easyblog.seckill.model.entity.ItemDO;

import java.util.List;

/**
 * @author huangxin
 */
@Repository
public interface ItemDOMapper {
    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table item
     *
     * @mbg.generated Sun Nov 18 19:15:18 CST 2018
     */
    List<ItemDO> listItem();

    int deleteByPrimaryKey(Integer id);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table item
     *
     * @mbg.generated Sun Nov 18 19:15:18 CST 2018
     */
    int insert(ItemDO record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table item
     *
     * @mbg.generated Sun Nov 18 19:15:18 CST 2018
     */
    int insertSelective(ItemDO record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table item
     *
     * @mbg.generated Sun Nov 18 19:15:18 CST 2018
     */
    ItemDO selectByPrimaryKey(Integer id);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table item
     *
     * @mbg.generated Sun Nov 18 19:15:18 CST 2018
     */
    int updateByPrimaryKeySelective(ItemDO record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table item
     *
     * @mbg.generated Sun Nov 18 19:15:18 CST 2018
     */
    int updateByPrimaryKey(ItemDO record);
    int increaseSales(@Param("id")Integer id,@Param("amount")Integer amount);
}