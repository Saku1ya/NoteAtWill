package com.sakuya.notesatwill.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 统计结果缓存（Redis）。
 *
 * <p><b>★★ 本类最关键的设计：缓存 key 的粒度决定了"能不能被正确失效"</b>
 * <pre>
 *   key = stats:{userId}:{bookId}:{参数摘要}
 * </pre>
 * 为什么不在 key 里带上完整的查询参数作为"可枚举的失效依据"？
 * 因为<b>无法枚举</b>—— 用户可能查过 ["本月"、"上月"、任意区间、三种粒度] 的任意组合，
 * 失效时不可能知道要删哪些具体 key。
 *
 * <p><b>解法：key 粗到 {@code userId:bookId}，失效时按前缀批量删</b>：
 * <pre>
 *   keys  stats:{userId}:{bookId}:*   →  全部删除
 * </pre>
 * 粒度选择理由：
 * <ul>
 *   <li>比 {@code userId} 细 —— 刷 A 账本的账单不会连累 B 账本的缓存</li>
 *   <li>比"参数级"粗 —— 一次前缀删除即可覆盖所有区间/粒度组合</li>
 * </ul>
 *
 * <p><b>★★ TTL 是安全网，不是主要机制</b>：
 * 正常情况下 {@link #evictBook} 会在账单变动时精确删除；
 * TTL（60 秒）只在<b>漏删</b>时保证数据最多错 60 秒。
 * 所以：<b>不要因为"有 TTL 兜底"就省掉失效调用</b> ——
 * 那会把"实时"变成"最坏延迟 60 秒"，用户改完账看不到变化。
 *
 * <p><b>★ Redis 故障不能影响主流程</b>：所有操作都吞掉异常并打日志。
 * 缓存是优化，不是数据源 —— 因为 Redis 挂了就让统计接口 500 是本末倒置。
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsCacheService {

    /** key 前缀：stats:{userId}:{bookId}:{摘要} */
    private static final String KEY_PREFIX = "stats:";

    /**
     * 缓存有效期（秒）。
     *
     * <p>取 60 秒的理由：正常失效由 {@link #evictBook} 精确完成，
     * TTL 只兜"漏删"。太短会让缓存形同虚设（几乎每次都 miss），
     * 太长会让漏删的代价变大。60 秒是"用户感知不到 + 兜底够快"的折中。
     */
    private static final long TTL_SECONDS = 60;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 读缓存（单个对象）；未命中时用 {@code loader} 查库并回填。
     *
     * <p><b>★ 为什么用"回填"而不是让调用方自己 get/set</b>：
     * 调用方只需写一行 {@code cache.getOrLoad(key, VO.class, () -> 查库())}，
     * 不会漏掉回填，也不会重复写 try/catch。
     *
     * <p><b>★ 缓存的是 JSON 字符串而不是 Java 对象</b>：
     * 用 {@code StringRedisTemplate} 存字符串，Redis 里的内容<b>可读可调试</b>
     * （redis-cli 能直接看到 JSON），也不会因类结构变化而反序列化失败。
     *
     * @param key    完整 key（由 {@link #key} 生成）
     * @param type   反序列化目标类型
     * @param loader 未命中时的查库逻辑
     * @return 缓存或查库结果；<b>Redis 异常时直接返回 loader 结果</b>
     */
    public <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader) {
        T cached = read(key, type);
        if (cached != null) {
            return cached;
        }
        T value = loader.get();
        write(key, value);
        return value;
    }

    /**
     * 读缓存（集合类型）；未命中时查库并回填。
     *
     * <p>单独一个方法是因为泛型擦除：{@code List<CategoryRatioVO>} 无法用
     * {@code Class<T>} 表达，必须用 {@link TypeReference} 保留元素类型。
     *
     * @param key     完整 key
     * @param typeRef 反序列化类型引用
     * @param loader  未命中时的查库逻辑
     */
    public <T> T getOrLoad(String key, TypeReference<T> typeRef, Supplier<T> loader) {
        T cached = read(key, typeRef);
        if (cached != null) {
            return cached;
        }
        T value = loader.get();
        write(key, value);
        return value;
    }

    /** 反序列化单个对象；任何异常都按"未命中"处理。 */
    private <T> T read(String key, Class<T> type) {
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                return objectMapper.readValue(cached, type);
            }
        } catch (Exception e) {
            // ★ Redis 挂了 / 内容坏了 → 当作未命中，继续查库
            log.warn("读取统计缓存失败（按未命中处理）：key={}, err={}", key, e.getMessage());
        }
        return null;
    }

    /** 反序列化集合；任何异常都按"未命中"处理。 */
    private <T> T read(String key, TypeReference<T> typeRef) {
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                return objectMapper.readValue(cached, typeRef);
            }
        } catch (Exception e) {
            log.warn("读取统计缓存失败（按未命中处理）：key={}, err={}", key, e.getMessage());
        }
        return null;
    }

    /** 序列化并写入；失败只打日志（不影响返回值）。 */
    private void write(String key, Object value) {
        write(key, value, TTL_SECONDS);
    }

    /**
     * 指定 TTL 写入（供 AI 点评这类"生成成本高、但不像统计那样秒级变化"的数据使用）。
     *
     * <p><b>★ 为什么不复用 60 秒 TTL</b>：AI 点评调一次大模型要花 token 和十几秒，
     * 若 60 秒就过期，用户点两次就重复烧两次钱。给它 10 分钟，
     * 期间同一区间重复点"看看这个月怎么说"直接命中缓存。
     *
     * <p><b>★ 数据变了怎么办</b>：账单写操作会调 {@link #evictBook} 清掉整个前缀，
     * 所以"记了新账后点评过时"会被主动清掉，不依赖 TTL。
     */
    public void write(String key, Object value, long ttlSeconds) {
        if (value == null) {
            return;   // 不缓存 null：无法区分"未命中"和"确实为空"
        }
        try {
            redisTemplate.opsForValue().set(
                    key, objectMapper.writeValueAsString(value), ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入统计缓存失败（忽略）：key={}, err={}", key, e.getMessage());
        }
    }

    /** 读取单个对象（按 60 秒 TTL 的常规统计缓存口径读，实际由写入方决定 TTL）。 */
    public <T> T get(String key, Class<T> type) {
        return read(key, type);
    }

    /**
     * 使某账本下的<b>所有</b>统计缓存失效。
     *
     * <p><b>★★ 必须在账单的每次写操作后调用</b>：
     * <ul>
     *   <li>{@code BillService.createBill}</li>
     *   <li>{@code BillService.updateBill}</li>
     *   <li>{@code BillService.deleteBill}</li>
     *   <li>{@code BillService.confirmAiBills}（AI 批量落库）</li>
     * </ul>
     * <b>漏掉任何一处，用户就会看到"改了账但统计没变"。</b>
     * 这是缓存功能最容易出现的 bug，而且它<b>不会让任何测试变红</b> ——
     * 除非专门测"改账后统计是否更新"。
     *
     * <p><b>★ 为什么用 {@code keys(pattern)} 而不是 {@code scan}</b>：
     * 本项目是单机 Redis、统计 key 数量有限（一个用户一个账本最多几十个），
     * {@code KEYS} 的阻塞风险可忽略。<b>若将来上生产集群，这里必须换成 SCAN</b>
     * —— {@code KEYS} 在大库上会阻塞整个 Redis。
     *
     * <p><b>★ 也接受"删不掉"</b>：异常只会打日志。缓存最多错 60 秒（TTL 兜底），
     * 绝不能因为缓存清理失败而让记账失败。
     *
     * @param userId 用户 id
     * @param bookId 账本 id
     */
    public void evictBook(Long userId, Long bookId) {
        if (userId == null || bookId == null) {
            return;
        }
        evictByPattern(KEY_PREFIX + userId + ":" + bookId + ":*");
    }

    /**
     * 使某用户<b>全部</b>账本的统计缓存失效。
     *
     * <p>用于"影响范围跨账本"的场景（目前没有，预留给将来的批量导入）。
     * 日常账单变动请用 {@link #evictBook}，不要动辄清全部。
     *
     * @param userId 用户 id
     */
    public void evictUser(Long userId) {
        if (userId == null) {
            return;
        }
        evictByPattern(KEY_PREFIX + userId + ":*");
    }

    private void evictByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("已清理统计缓存：pattern={}, 数量={}", pattern, keys.size());
            }
        } catch (Exception e) {
            log.warn("清理统计缓存失败（忽略，TTL 会兜底）：pattern={}, err={}",
                    pattern, e.getMessage());
        }
    }

    /**
     * 生成缓存 key。
     *
     * <p><b>★ 为什么把参数用 {@code |} 拼起来而不是 hash</b>：
     * <b>可读性</b>。用 redis-cli 排查时能直接看出"这条是哪次查询的缓存"；
     * 用 MD5 虽然更短，但排查时只能猜。
     * 本项目 key 长度可控（参数就几个），不必为省几字节牺牲可调试性。
     *
     * <p><b>★ 参数为 null 也要写占位符 {@code _}</b>：
     * 否则 {@code start=null} 和 {@code end=null} 的不同组合会撞出同一个 key。
     *
     * @param userId 用户 id
     * @param bookId 账本 id
     * @param parts  参与区分的参数（顺序必须稳定）
     * @return 形如 {@code stats:1:2:EXPENSE|2026-10-01|2026-10-31}
     */
    public String key(Long userId, Long bookId, Object... parts) {
        StringBuilder sb = new StringBuilder(KEY_PREFIX)
                .append(userId).append(':').append(bookId).append(':');
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(parts[i] == null ? "_" : parts[i]);
        }
        return sb.toString();
    }
}
