package com.sakuya.notesatwill.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
/**
 * 全局错误码。
 *
 * <p>分段规则
 * <pre>
 *   200       成功
 *   1000-1999 通用 / 参数校验
 *   2000-2999 用户与认证（2002-2005 为既有习惯保留）
 *   3000-3999 账本、分类、账户等基础数据
 *   4000-4999 记账与预算
 *   5000-5999 AI 服务（调用 Python FastAPI 相关）
 *   6000-6999 对话与问候
 *   9000-9999 系统内部错误
 * </pre>
 *
 * <p>code 与 message 都会返回给前端。message 是给用户看的错误提示，
 * 前端也可以按 code 做特殊处理（例如 2003 跳登录页）。
 *
 * @author sakuya
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ======================== 成功 ========================
    SUCCESS(200, "操作成功"),

    // ======================== 通用（1000-1999） ========================
    PARAM_ERROR(1000, "参数校验失败"),
    PARAM_MISSING(1001, "缺少必要参数"),
    PARAM_TYPE_ERROR(1002, "参数类型不正确"),
    REQUEST_METHOD_NOT_SUPPORTED(1003, "请求方法不支持"),
    RESOURCE_NOT_FOUND(1004, "请求的资源不存在"),
    OPERATION_NOT_ALLOWED(1005, "当前操作不被允许"),
    DATA_NOT_FOUND(1006, "数据不存在"),
    DATA_ALREADY_EXISTS(1007, "数据已存在"),
    REPEAT_SUBMIT(1008, "请勿重复提交"),
    RATE_LIMIT(1009, "操作过于频繁，请稍后再试"),

    // ======================== 用户与认证（2000-2999） ========================
    // 2002-2005 为既有项目沿用段位，含义保持不变，便于前后端保持一致
    UNAUTHORIZED(2002, "未登录或登录已过期"),
    TOKEN_INVALID(2003, "登录凭证无效"),
    TOKEN_EXPIRED(2004, "登录凭证已过期"),
    FORBIDDEN(2005, "没有操作权限"),
    // 2010-2014：账号与登录
    PHONE_ALREADY_EXISTS(2010, "该手机号已注册"),
    USER_NOT_FOUND(2011, "用户不存在"),
    // 手机号是本项目唯一的登录标识（验证码登录与密码登录都用它）。
    // 注意：这里刻意不区分「账号不存在」与「密码错误」，防止被用来枚举已注册手机号
    PASSWORD_ERROR(2012, "手机号或密码错误"),
    USER_DISABLED(2013, "账号已被禁用"),
    // ⚠️ 已废弃，当前无任何使用者，保留仅为不破坏既有错误码编号（前端可能已按号段做映射）。
    // 废弃原因（2026-10-13）：改密码流程改为「手机验证码」校验后，
    // 不再向用户索要原密码，「原密码不正确」这个错误状态在业务上已不存在。
    // 新流程见 /api/auth/reset-password 与 AuthService.resetPassword。
    @Deprecated
    OLD_PASSWORD_ERROR(2014, "原密码不正确"),
    // 2015-2018：验证码与密码设置
    CODE_INVALID(2015, "验证码错误"),
    CODE_EXPIRED(2016, "验证码已过期，请重新获取"),
    CODE_SEND_TOO_FREQUENT(2017, "发送过于频繁，请稍后再试"),
    // 验证码自动注册的用户没有密码，密码登录时要给出专门提示，
    // 否则用户会误以为「密码输错了」而反复尝试
    PASSWORD_NOT_SET(2018, "该账号未设置密码，请使用验证码登录"),
    // 注：原先预留的 EMAIL_ALREADY_EXISTS(2019) 已删除 ——
    // email 不再唯一、不参与登录，因此不会有「邮箱被占用」这种错误
    // 会员功能（二期）
    MEMBER_REQUIRED(2020, "该功能为会员专属"),

    // ======================== 基础数据（3000-3999） ========================
    BOOK_NOT_FOUND(3000, "账本不存在"),
    BOOK_DEFAULT_CANNOT_DELETE(3001, "默认账本不可删除"),
    BOOK_HAS_BILLS(3002, "该账本下还有账单，无法删除"),
    // "至少要留一个账本"：与"默认账本不可删"是两条独立规则。
    // 前者防止删空导致记账页没有账本可选，后者防止失去记账落点，
    // 两者可能同时成立，但触发条件不同，所以用独立错误码便于前端精确提示。
    BOOK_MIN_ONE_REQUIRED(3003, "至少需要保留一个账本"),
    CATEGORY_NOT_FOUND(3010, "分类不存在"),
    CATEGORY_PRESET_READONLY(3011, "系统预置分类不可修改或删除"),
    CATEGORY_NAME_DUPLICATED(3012, "同级下已存在同名分类"),
    CATEGORY_HAS_CHILDREN(3013, "该分类下还有子分类，无法删除"),
    CATEGORY_HAS_BILLS(3014, "该分类下还有账单，无法删除"),
    CATEGORY_TYPE_MISMATCH(3015, "分类的收支类型与账单不匹配"),
    /**
     * 该分类下还有预算，无法删除。
     *
     * <p><b>★ 为什么需要这一条</b>：删掉被预算引用的分类后，预算会变成
     * "指向不存在分类"的悬空记录 —— 它的已用量永远是 0、分类名也补不出来。
     * 金额上无害，但界面上会显示一条莫名其妙的预算。
     * 与 {@link #CATEGORY_HAS_BILLS} 同一套思路：<b>宁可拒绝删除，也不留悬空引用</b>。
     */
    CATEGORY_HAS_BUDGET(3016, "该分类已设置预算，无法删除"),
    ACCOUNT_NOT_FOUND(3020, "账户不存在"),
    ACCOUNT_NOT_ENOUGH_BALANCE(3021, "账户余额不足"),
    ACCOUNT_SAME_TRANSFER(3022, "转出与转入账户不能相同"),
    ACCOUNT_TRANSFER_NOT_FOUND(3023, "转账记录不存在"),
    ACCOUNT_IN_USE(3024, "该账户存在资金流水，无法删除"),

    // ======================== 记账与预算（4000-4999） ========================
    BILL_NOT_FOUND(4000, "账单不存在"),
    BILL_AMOUNT_INVALID(4001, "金额必须大于 0"),
    BILL_TIME_FUTURE(4002, "记账时间不能晚于当前时间"),
    BILL_TYPE_INVALID(4003, "账单类型不合法"),
    BILL_AI_LOG_NOT_FOUND(4004, "关联的 AI 解析记录不存在"),
    BILL_TIME_REQUIRED(4005, "记账时间不能为空"),
    BUDGET_NOT_FOUND(4010, "预算不存在"),
    BUDGET_ALREADY_EXISTS(4011, "该账本下此分类已设置过预算"),
    BUDGET_AMOUNT_INVALID(4012, "预算额度必须大于 0"),
    BUDGET_MONTH_INVALID(4013, "月份格式不正确，应为 yyyy-MM"),

    // ======================== AI 服务（5000-5999） ========================
    AI_SERVICE_UNAVAILABLE(5000, "AI 服务暂时不可用，请稍后再试"),
    AI_SERVICE_TIMEOUT(5001, "AI 服务响应超时，请稍后再试"),
    AI_PARSE_FAILED(5002, "AI 解析失败，请换一种说法试试"),
    AI_PARSE_EMPTY(5003, "没有识别出任何账单，请补充金额与用途"),
    AI_RESULT_INVALID(5004, "AI 返回结果格式不正确"),
    AI_LOG_NOT_FOUND(5005, "AI 解析记录不存在"),
    AI_LOG_ALREADY_CONFIRMED(5006, "该解析结果已确认过，请勿重复提交"),
    AI_QUOTA_EXCEEDED(5007, "今日 AI 解析次数已用完"),

    // ======================== 对话与问候（6000-6999） ========================
    CHAT_MESSAGE_NOT_FOUND(6000, "消息不存在"),
    CHAT_MESSAGE_NOT_DELETABLE(6001, "该消息不支持删除"),
    PERSONA_NOT_FOUND(6010, "角色设定不存在"),
    PERSONA_PRESET_NOT_FOUND(6011, "角色预设不存在"),
    GREETING_ALREADY_SENT(6020, "今日问候已发送"),

    // ======================== 系统（9000-9999） ========================
    SYSTEM_ERROR(9000, "系统繁忙，请稍后再试"),
    DATABASE_ERROR(9001, "数据操作失败"),
    THIRD_PARTY_ERROR(9002, "外部服务异常");

    /** 业务状态码 */
    private final Integer code;

    /** 提示文案 */
    private final String message;
}
