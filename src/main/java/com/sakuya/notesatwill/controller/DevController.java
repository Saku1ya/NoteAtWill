package com.sakuya.notesatwill.controller;

import com.sakuya.notesatwill.common.result.Result;
import com.sakuya.notesatwill.entity.VO.AccountCheckVO;
import com.sakuya.notesatwill.service.AccountFlowService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 开发期诊断接口。
 *
 * <p><b>★ 本类下的接口【没有前端消费者】，唯一的使用者是开发者（通过 Apifox）。</b>
 * 它们不属于业务接口，也不应出现在任何用户界面上 ——
 * 但它们是<b>真实存在的 HTTP 端点</b>，所以同样要登记、同样要遵守鉴权纪律。
 *
 * <p><b>为什么必须做成 HTTP 端点而不是单元测试</b>：
 * 单元测试查的是"测试自己造的数据，跑完就清理"；
 * 而这里要查的是"<b>我在 Apifox / 浏览器里手动点了半天之后，开发库的当前状态</b>"。
 * 余额 bug 恰恰是多步手动操作（记一笔 → 改账户 → 删账单）才会暴露的，
 * 测试套件完全不知道你做过这些操作。只有能从外部读取当前状态的入口才能发现它。
 *
 * <p><b>★ 鉴权</b>：本类<b>不加</b> {@code permitAll}，自动落到
 * {@code anyRequest().authenticated()}。所有接口只操作<b>当前登录用户自己的</b>数据，
 * 绝不提供"检查全库"的能力。
 *
 * <p><b>关于 {@code @Profile("dev")}：本类加了它</b>，因此
 * <b>只在 {@code dev} profile 下注册</b>。
 * <ul>
 *   <li>{@code application.yaml} 里写了 {@code spring.profiles.active: dev}，
 *       所以<b>日常开发（{@code mvnw spring-boot:run}）与测试都能访问</b></li>
 *   <li>打包部署时用 {@code --spring.profiles.active=prod} 覆盖，
 *       这些诊断端点<b>根本不会被注册</b> —— 内部排查工具不该出现在线上环境</li>
 * </ul>
 *
 * <p><b>⚠ 排查提示</b>：如果访问本类接口得到 <b>404</b>，
 * 第一件事是确认当前激活的 profile 是不是 {@code dev}，
 * 而不是怀疑路由写错了。启动日志里有一行
 * {@code The following 1 profile is active: "dev"} 可以直接核对。
 *
 * @author sakuya
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
public class DevController {

    private final AccountFlowService accountFlowService;

    /**
     * 对账自测：校验每个账户的余额是否等于按资金流水算出来的值。
     *
     * <p><b>用法</b>：阶段 3 起<b>每天点一次</b>，以及<b>每写完一个改动余额的方法就跑一次</b>。
     * 正常结果里每个账户的 {@code matched} 都是 {@code true}、{@code diff} 都是 {@code 0.00}。
     *
     * <p>返回每一项的字段含义见 {@link AccountCheckVO}。
     * 用户没有账户时返回空数组（不是错误）。
     *
     * <pre>
     * GET /api/dev/account-check
     * →
     * {
     *   "code": 200,
     *   "data": [
     *     { "accountId": 5, "name": "招行卡",   "balance": 975.00,
     *       "calculated": 975.00, "diff": 0.00,  "matched": true },
     *     { "accountId": 6, "name": "微信零钱", "balance": 480.00,
     *       "calculated": 500.00, "diff": -20.00, "matched": false }
     *   ]
     * }
     * </pre>
     *
     * <p>上例中 6 号账户 {@code diff = -20.00} 表示"库里比流水算出来的少了 20"，
     * 即有一次扣减没有对应流水，或某条流水被删了但余额没回滚。
     *
     * @param userId 当前登录用户 id（来自 token）
     */
    @GetMapping("/account-check")
    public Result<List<AccountCheckVO>> accountCheck(@AuthenticationPrincipal Long userId) {
        return Result.success(accountFlowService.reconcile(userId));
    }
}
