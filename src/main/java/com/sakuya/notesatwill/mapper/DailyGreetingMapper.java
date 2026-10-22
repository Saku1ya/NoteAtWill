package com.sakuya.notesatwill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sakuya.notesatwill.entity.DailyGreeting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 每日问候 Mapper。
 *
 * <p><b>★★ 幂等靠数据库唯一索引 {@code uk_user_date(user_id, greet_date)}</b>，
 * 不靠"先查再插"：
 * <pre>
 *   用户连点 10 次刷新 / 5 个标签页同时打开
 *        ↓
 *   "先查再插"：两个请求同时查不到 → 都插入 → 两条问候 ❌
 *   "唯一索引"：第二个插入抛 DuplicateKeyException → 忽略并复用第一条 ✅
 * </pre>
 * 这是本项目唯一"宁可让数据库报错也要保证正确性"的地方 ——
 * 因为应用层的检查在并发下没有任何保证。
 *
 * <p>本接口不需要自定义 SQL：按 {@code user_id + greet_date} 查询用
 * {@code LambdaQueryWrapper} 即可，且能自动带 {@code deleted}（本表无逻辑删除，但保持一致写法）。
 *
 * @author sakuya
 */
@Mapper
public interface DailyGreetingMapper extends BaseMapper<DailyGreeting> {
}
