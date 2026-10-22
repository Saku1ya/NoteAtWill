package com.sakuya.notesatwill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sakuya.notesatwill.entity.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分类表 Mapper。
 *
 * <p>注意收入的分类树与支出的分类树是<b>完全隔离的两棵树</b>，
 * 查询时必须带 {@code type} 条件，否则会把两者混在一起。
 *
 * @author sakuya
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
