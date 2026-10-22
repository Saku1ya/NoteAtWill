package com.sakuya.notesatwill.entity.DTO;

/**
 * 当前登录用户信息（{@code GET /api/user/me} 的响应体）。
 *
 * <p><b>为什么必须单独建一个 VO，不能直接返回 {@code User} 实体</b>：
 * <ol>
 *   <li>{@code User} 上有 {@code password} 字段（BCrypt 哈希）。
 *       直接返回会把哈希序列化进响应体——虽然 BCrypt 不可逆，
 *       但哈希泄露可被离线爆破，且明文暴露在任何日志/代理/浏览器缓存里，
 *       属于明确的敏感信息泄漏。</li>
 *   <li>{@code hasPassword} 是<b>派生字段</b>，实体上根本不存在，
 *       只能由 Service 根据 {@code password} 是否为空算出来。</li>
 *   <li>实体字段会随数据库演进变化，直接暴露等于把表结构钉死成接口契约，
 *       以后加个内部字段就会静默泄漏给前端。</li>
 * </ol>
 *
 * <p><b>关于 {@code memberLevel} 与 {@code isMember} 同时返回</b>：
 * 前者给前端做逻辑判断（将来可能有更高等级），后者给前端做条件渲染
 * （{@code v-if="user.isMember"} 比 {@code v-if="user.memberLevel >= 1"} 更直观，
 * 且等级规则变化时不必改前端）。两者由同一个 {@code MemberLevel} 枚举算出，
 * 不存在不一致的风险。
 *
 * @param id          用户 id
 * @param phone       手机号（唯一身份标识）
 * @param nickname    昵称
 * @param avatar      头像地址，可能为 null
 * @param email       邮箱（可选联系方式，不参与登录），可能为 null
 * @param memberLevel 会员等级原始值：0 普通 / 1 终身会员
 * @param isMember    是否会员，供前端直接做条件渲染
 * @param hasPassword 是否已设置密码。验证码自动注册的用户为 false。
 *                    前端据此决定"设置密码"入口的文案是「设置密码」还是「修改密码」
 *                    （两者调用的是同一个接口，见 {@code ResetPasswordRequest}）
 * @author sakuya
 */
public record UserVO(
        Long id,
        String phone,
        String nickname,
        String avatar,
        String email,
        Integer memberLevel,
        boolean isMember,
        boolean hasPassword) {
}
