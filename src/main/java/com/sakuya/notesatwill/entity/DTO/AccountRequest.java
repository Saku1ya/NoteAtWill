package com.sakuya.notesatwill.entity.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 新建 / 修改资金账户请求。
 *
 * <p><b>★★ 本类刻意【没有】{@code balance} 字段，这是本项目最重要的约束之一。</b>
 *
 * <p>{@code t_account} 有两个金额字段，性质完全不同：
 * <pre>
 *   initBalance  期初余额 —— 建账户时填写，此后【永不改变】
 *   balance      当前余额 —— 冗余字段，【只能由资金流水服务】在写流水时同步维护
 * </pre>
 *
 * <p>如果接口直接接收 {@code Account} 实体，前端就能传这两个字段，后果是：
 * <ul>
 *   <li>传 {@code initBalance} → 期初余额被改，
 *       <b>对账公式立刻失效</b>（公式是
 *       {@code balance == initBalance + SUM(IN) - SUM(OUT)}，
 *       改了期初值，等式两边就永远对不上）</li>
 *   <li>传 {@code balance} → 直接改余额，<b>绕过资金流水服务</b>，
 *       账户余额与流水脱钩，D3 的对账自测接口会永久性报错，
 *       而且很难定位是哪一次操作弄坏的</li>
 * </ul>
 *
 * <p>所以更新账户时只允许改<b>名称、类型、图标、排序</b>这些展示性字段。
 * 余额的修正只有一个合法入口：账户模块的「手动校正」接口（生成 ADJUST 流水）。
 *
 * <p><b>关于 {@code initBalance} 只在新建时生效</b>：它出现在这里是因为
 * 新建账户需要填写期初金额，且此时 {@code balance} 应初始化为同一个值。
 * 但 Service 层在<b>修改</b>时会忽略它 —— 否则上面第一个问题照样发生。
 *
 * @param name        账户名：现金 / 微信零钱 / 支付宝 / 招商银行卡…
 * @param type        账户类型：CASH / WECHAT / ALIPAY / BANK / OTHER
 * @param initBalance 期初余额，<b>仅新建时生效</b>；不传按 0 处理
 * @param icon        图标标识
 * @param sort        排序值，越小越靠前
 * @author sakuya
 */
public record AccountRequest(
        @NotBlank(message = "账户名不能为空")
        @Size(max = 50, message = "账户名不能超过 50 个字符")
        String name,

        @NotBlank(message = "账户类型不能为空")
        @Pattern(regexp = "^(CASH|WECHAT|ALIPAY|BANK|OTHER)$",
                message = "账户类型只能是 CASH / WECHAT / ALIPAY / BANK / OTHER")
        String type,

        /*
         * 仅新建时生效；修改时被 Service 忽略。
         * 用 BigDecimal 而不是 double：金额一律用 BigDecimal，
         * double 的浮点误差会让对账永远对不上。
         */
        BigDecimal initBalance,

        @Size(max = 50, message = "图标标识不能超过 50 个字符")
        String icon,

        Integer sort) {
}
