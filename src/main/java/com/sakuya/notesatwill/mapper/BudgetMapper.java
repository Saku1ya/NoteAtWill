package com.sakuya.notesatwill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sakuya.notesatwill.entity.Budget;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预算 Mapper。
 *
 * <p><b>★ 没有自定义 SQL</b>：预算的查询条件都是简单的等值比较，
 * MyBatis-Plus 的 {@code LambdaQueryWrapper} 足够，而且能自动带 {@code deleted = 0}。
 *
 * <p><b>★★ 特别注意：{@code category_id} 可能为 NULL</b>（账本级预算），
 * 这带来两个坑：
 * <ol>
 *   <li><b>查询时必须用 {@code isNull()}，不能用 {@code eq(col, null)}</b> ——
 *       后者生成的是 {@code category_id = NULL}，在 SQL 里永远为 UNKNOWN，
 *       <b>查不到任何行</b>（这个项目此前实测确认过 MyBatis-Plus 的这个行为）。</li>
 *   <li><b>数据库无法用唯一索引防重</b>：MySQL 的 UNIQUE 索引允许多个 NULL，
 *       所以同账本可以插入多条 {@code category_id IS NULL} 的预算。
 *       <b>去重必须在 Service 层做</b>（见 {@code BudgetService#ensureNotDuplicated}）。</li>
 * </ol>
 *
 * @author sakuya
 */
@Mapper
public interface BudgetMapper extends BaseMapper<Budget> {
}
