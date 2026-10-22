package com.sakuya.notesatwill.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 「事务提交后再执行」的工具。
 *
 * <p><b>★★ 为什么需要它：SSE 推送必须在事务提交之后</b>
 * <pre>
 *   ❌ 事务内推送：
 *        写账单 → 推送卡片 → 提交
 *        用户看到卡片出现，然后提交失败 → 刷新页面卡片没了
 *        （用户已被误导，以为记账成功了）
 *
 *   ✅ 提交后推送：
 *        写账单 → 提交 → 推送
 *        数据库里确实有这笔账，卡片一定是对的
 * </pre>
 * 这是"先持久化、再通知"的通用原则：<b>通知代表事实，事实必须先落地。</b>
 *
 * <p><b>★ 为什么不能简单地把推送写在 {@code @Transactional} 方法末尾</b>：
 * 方法返回时事务还没真正提交（提交发生在代理层），
 * 此时推送等于在事务内推送，问题依旧。
 *
 * <p><b>★ 没有事务时怎么办</b>：
 * 如果当前不在事务里（比如将来某个非事务路径调用），
 * {@code TransactionSynchronizationManager} 会返回 false，
 * 此时<b>直接执行</b> —— 因为没有事务就没有"未提交"的问题。
 *
 * @author sakuya
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * 注册一个在<b>当前事务成功提交后</b>执行的动作。
     *
     * <p><b>★ 事务回滚时不会执行</b>（{@code afterCommit} 只在提交后触发），
     * 这正是我们要的：回滚了就没有"账单已创建"这个事实，不该推送卡片。
     *
     * <p><b>★ 动作里抛出的异常会被吞掉并记日志</b>：
     * 此时事务已经提交成功，业务已经完成。
     * 推送失败只是"前端没实时收到"，刷新一下照样能看到 ——
     * <b>不能因为通知失败而让整个记账请求报错</b>。
     *
     * @param action 提交后要执行的动作
     * @param desc   描述（失败时写进日志，便于定位是哪一类通知挂了）
     */
    public static void run(Runnable action, String desc) {
        if (action == null) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 没有事务上下文 → 直接执行（不存在"未提交"的问题）
            safeRun(action, desc);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeRun(action, desc);
            }
        });
    }

    private static void safeRun(Runnable action, String desc) {
        try {
            action.run();
        } catch (Exception e) {
            // ★ 事务已提交，业务已成功 —— 通知失败不能反过来让请求失败
            log.warn("事务提交后动作执行失败（已忽略）：{}, err={}", desc, e.getMessage());
        }
    }
}
