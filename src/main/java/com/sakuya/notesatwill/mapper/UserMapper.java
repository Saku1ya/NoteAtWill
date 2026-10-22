package com.sakuya.notesatwill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sakuya.notesatwill.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 用户表 Mapper。
 *
 * <p>继承 {@code BaseMapper<User>} 即获得单表 CRUD 能力
 * （insert / deleteById / updateById / selectById / selectList 等），
 * 无需手写任何 SQL。
 *
 * <p>复杂查询再额外声明方法，或在 {@code resources/mapper/} 下配 XML。
 *
 * <p><b>为什么要显式加 {@code @Mapper}</b>：虽然 {@code @MapperScan} 已能扫到，
 * 但显式标注在 IDE 里能获得更好的导航与提示，也避免包路径调整时失效。
 *
 * @author sakuya
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

}
