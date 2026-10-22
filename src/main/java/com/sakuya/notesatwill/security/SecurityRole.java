package com.sakuya.notesatwill.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * 权限标识常量与预置权限集合。
 *
 * <p><b>命名约定</b>：常量值统一带 {@code ROLE_} 前缀
 *
 * <p><b>权限模型</b>：
 * <ul>
 *   <li>所有已登录用户 → {@link #USER}</li>
 *   <li>会员（member_level = 1）→ {@link #USER} + {@link #MEMBER}</li>
 * </ul>
 * 也就是说会员权限是「叠加」而非「替换」，因为会员首先是普通用户.
 * 通过of方法返回用户权限列表
 * @author sakuya
 */
public final class SecurityRole {

    /** 基础角色：所有已登录用户都拥有。 */
    public static final String USER = "ROLE_USER";

    /** 会员专属角色：member_level = 1 的用户额外拥有。 */
    public static final String MEMBER = "ROLE_MEMBER";

    /**
     * 普通登录用户的权限集合（预构建常量）。
     *
     * <p>{@link SimpleGrantedAuthority} 是不可变对象，可以安全地在所有请求间共享，
     * 因此在这里构建一次即可，避免每个请求都 new 一遍。
     */
    public static final List<GrantedAuthority> USER_AUTHORITIES =
            List.of(new SimpleGrantedAuthority(USER));

    /** 会员的权限集合（预构建常量），含基础角色。 */
    public static final List<GrantedAuthority> MEMBER_AUTHORITIES =
            List.of(new SimpleGrantedAuthority(USER), new SimpleGrantedAuthority(MEMBER));

    private SecurityRole() {
    }

    /**
     * 按是否会员返回对应的权限集合。
     *
     * @param isMember 是否为会员
     * @return 预构建的不可变权限集合，可直接传给 Authentication
     */
    public static List<GrantedAuthority> of(boolean isMember) {
        return isMember ? MEMBER_AUTHORITIES : USER_AUTHORITIES;
    }
}
