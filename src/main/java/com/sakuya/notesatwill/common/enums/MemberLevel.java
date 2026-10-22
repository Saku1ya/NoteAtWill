package com.sakuya.notesatwill.common.enums;

/**
 * 会员等级。
 *
 * <p><b>会员为终身制，不设到期时间</b>（需求已确认）。一期只预留等级字段与权限校验位，不做支付。
 *
 * <p>若将来改为订阅制：补一个 {@code member_expire_time} 字段，
 * 判定条件由「等级 = MEMBER」改为「等级 = MEMBER 且 到期时间 > NOW()」即可，
 * {@link #isMember()} 之外的代码基本不用动。
 *
 * <p>取值与数据库 {@code t_user.member_level} 一致：0 普通 / 1 终身会员。
 *
 * @author sakuya
 */
public enum MemberLevel {

    /** 普通用户 */
    NORMAL(0),

    /** 终身会员 */
    MEMBER(1);

    private final int code;

    MemberLevel(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 按数据库中的数值解析等级。
     *
     * <p><b>未知值或 null 一律回退为 {@link #NORMAL}，不抛异常</b>——
     * 等级只影响"能不能用会员功能"，属于附加能力而非关键路径，
     * 没必要因为一个脏数据让整个请求失败。
     */
    public static MemberLevel of(Integer code) {
        if (code != null) {
            for (MemberLevel level : values()) {
                if (level.code == code) {
                    return level;
                }
            }
        }
        return NORMAL;
    }

    /**
     * 是否为会员。
     *
     * <p>用 {@code >=} 而非 {@code ==}：将来若新增更高等级（如 SUPER_MEMBER = 2），
     * 它们显然也应算作会员，此时不必回来修改这个方法。
     */
    public boolean isMember() {
        return this.code >= MEMBER.code;
    }
}
