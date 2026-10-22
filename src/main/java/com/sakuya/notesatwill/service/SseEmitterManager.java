package com.sakuya.notesatwill.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * SSE 连接管理器：<b>按用户维护活跃的推送通道</b>。
 *
 * <p><b>★★ 本类存在的唯一理由：防止连接泄漏</b>
 * <pre>
 *   SseEmitter 不设超时会【永久】挂着一个 TCP 连接。
 *   用户开 3 个标签页、再关掉，不清理就是 3 条僵尸连接；
 *   反复开关几十次，连接池/线程就被占满了。
 * </pre>
 * 所以每次创建都必须在三个回调（完成/超时/出错）里调用 {@link #remove}。
 * <b>三选一漏掉任何一个，都会漏连接。</b>
 *
 * <p><b>★ 一个用户可以有多个连接</b>：
 * 同一个账号在多标签页 / 多设备打开时，每个页面都是一条独立连接。
 * 推送时要发给该用户的<b>全部</b>连接，而不只是最后一个 ——
 * 否则"在 A 标签页记账，B 标签页收不到卡片"。
 *
 * <p><b>★ 数据结构选择</b>：
 * <ul>
 *   <li>外层 {@code ConcurrentHashMap}：多线程并发连接/断开</li>
 *   <li>内层 {@code CopyOnWriteArraySet}：推送时要遍历、连接变动相对少，
 *       写时复制正好避免"遍历中被修改"的并发异常</li>
 * </ul>
 *
 * @author sakuya
 */
@Slf4j
@Component
public class SseEmitterManager {

    /**
     * 连接超时（毫秒）。
     *
     * <p><b>★ 30 分钟的含义</b>：不是"聊天会话时长"，而是<b>单条连接的存活上限</b>。
     * 超过后浏览器会自动重连（SSE 协议自带），所以对用户无感。
     * <b>这个值必须存在</b> —— 设成 {@code 0L} 表示永不超时，那才是真正的泄漏源头。
     */
    private static final long TIMEOUT_MILLIS = 30 * 60 * 1000L;

    /** userId → 该用户的所有活跃连接 */
    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * 为一个用户注册新连接。
     *
     * <p><b>★★ 三个回调缺一不可</b>：
     * <ul>
     *   <li>{@code onCompletion} —— 正常结束（浏览器关闭 EventSource）</li>
     *   <li>{@code onTimeout} —— 到达 {@link #TIMEOUT_MILLIS}</li>
     *   <li>{@code onError} —— 网络中断 / 客户端异常</li>
     * </ul>
     * 其中<b>最容易漏的是 onTimeout</b>：不处理的话连接虽然超时了，
     * 但我们的 map 里还留着它，下次推送往里写才发现已经死掉。
     *
     * @param userId 用户 id
     * @return 供 Controller 直接返回的 emitter
     */
    public SseEmitter create(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);

        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(emitter);

        // ★★★ 必须在返回前先发一个首包（注释行），否则浏览器拿不到响应头。
        //
        //   原因：Spring 的 SseEmitter 在**第一次 send 之前不会提交并 flush 响应头**。
        //   而浏览器的 fetch 要等响应头到达才会 resolve —— 于是前端
        //   `connectSse` 的 onOpen 永远不触发，界面一直显示"未连接"；
        //   更糟的是 fetch 挂在那里不 reject，连失败都看不见（onError 也就不会调用）。
        //
        //   实测（2026-10-20）：直连 8080 与经 Vite 代理 5173 表现一致，
        //   6 秒内都收不到任何响应字节，所以**不是代理缓冲的锅**，是后端没 flush。
        //
        //   SSE 协议里以 `:` 开头的行是注释，客户端按规范忽略，
        //   所以这条不会污染事件流，只用来把连接"打开"。
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            // 首包就失败说明连接立刻断了，直接清理，不留在 map 里
            log.debug("SSE 首包发送失败，连接已断开：userId={}, err={}", userId, e.getMessage());
            remove(userId, emitter);
            emitter.completeWithError(e);
            return emitter;
        }

        emitter.onCompletion(() -> {
            log.debug("SSE 连接完成：userId={}", userId);
            remove(userId, emitter);
        });
        emitter.onTimeout(() -> {
            // ★ 超时后必须主动 complete()，否则连接不会真正释放
            log.debug("SSE 连接超时：userId={}", userId);
            emitter.complete();
            remove(userId, emitter);
        });
        emitter.onError(e -> {
            log.debug("SSE 连接出错：userId={}, err={}", userId, e.getMessage());
            remove(userId, emitter);
        });

        log.info("SSE 已连接：userId={}，该用户当前连接数={}", userId, count(userId));
        return emitter;
    }

    /**
     * 向某用户的<b>全部</b>连接推送一条事件。
     *
     * <p><b>★★ 推送失败必须剔除该连接，不能只是打日志</b>：
     * 往一个已经断开的 emitter 写会抛 {@link IOException}。
     * 如果只是 catch 住不管，这条死连接会永远留在 map 里，
     * 之后每次推送都要为它抛一次异常 —— 越积越慢。
     *
     * <p><b>★ 推送是"尽力而为"</b>：失败不抛给调用方。
     * 记账成功不能因为"推送失败"而回滚 —— 数据落库才是主线，
     * 前端刷新页面时照样能从数据库读到卡片。
     *
     * @param userId 目标用户
     * @param event  事件名（前端用 {@code addEventListener('xxx')} 监听）
     * @param data   事件数据（会被序列化成 JSON）
     * @return 实际推送成功的连接数
     */
    public int send(Long userId, String event, Object data) {
        Set<SseEmitter> set = emitters.get(userId);
        if (set == null || set.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
                sent++;
            } catch (IOException | IllegalStateException e) {
                // ★ 连接已死：立刻剔除，否则会一直拖累后续推送
                log.debug("推送失败，剔除该连接：userId={}, event={}, err={}",
                        userId, event, e.getMessage());
                remove(userId, emitter);
            }
        }
        return sent;
    }

    /**
     * 移除一条连接；该用户没有连接时把外层 key 也删掉（避免 map 无限增长）。
     */
    public void remove(Long userId, SseEmitter emitter) {
        Set<SseEmitter> set = emitters.get(userId);
        if (set == null) {
            return;
        }
        set.remove(emitter);
        // ★ 最后一个连接断开时移除整个 key —— 否则 map 里会留下一堆空 Set
        if (set.isEmpty()) {
            emitters.remove(userId, set);
        }
    }

    /** 某用户当前的活跃连接数（供监控/测试用）。 */
    public int count(Long userId) {
        Set<SseEmitter> set = emitters.get(userId);
        return set == null ? 0 : set.size();
    }

    /** 全部活跃连接数（供监控用）。 */
    public int totalConnections() {
        return emitters.values().stream().mapToInt(Set::size).sum();
    }
}
