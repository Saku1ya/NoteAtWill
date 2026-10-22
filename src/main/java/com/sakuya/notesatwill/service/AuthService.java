package com.sakuya.notesatwill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.enums.MemberLevel;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.DTO.ChangePasswordRequest;
import com.sakuya.notesatwill.entity.DTO.LoginByCodeRequest;
import com.sakuya.notesatwill.entity.DTO.LoginByPasswordRequest;
import com.sakuya.notesatwill.entity.DTO.LoginResponse;
import com.sakuya.notesatwill.entity.DTO.RegisterRequest;
import com.sakuya.notesatwill.entity.DTO.ResetPasswordRequest;
import com.sakuya.notesatwill.entity.DTO.UserVO;
import com.sakuya.notesatwill.entity.User;
import com.sakuya.notesatwill.mapper.UserMapper;
import com.sakuya.notesatwill.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;

/**
 * 认证服务：负责注册、登录、登出等账号相关的业务逻辑。
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final SmsCodeService smsCodeService;
    private final TokenBlacklistService tokenBlacklistService;
    private final BookService bookService;
    private final AccountService accountService;
    /** 账号状态：正常 */
    private static final int STATUS_NORMAL = 1;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** 默认昵称的前缀 */
    private static final String PREFIX = "用户";

    /**
     * 生成默认昵称：前缀 + 随机 6-8 位数字。
     */
    public static String generateNickname() {
        // 随机决定位数：6、7 或 8
        int length = SECURE_RANDOM.nextInt(3) + 6;
        int min = (int) Math.pow(10, length - 1);
        int max = (int) Math.pow(10, length);
        int code = SECURE_RANDOM.nextInt(max - min) + min;
        return PREFIX + code;
    }

    /**
     * 签发 token 并组装登录响应。
     */
    public LoginResponse issueToken(User user) {
        String token = jwtUtil.generateToken(user.getId(), user.getPhone(),user.getMemberLevel());
        return new LoginResponse(token, jwtUtil.getExpireSeconds());
    }

    /**
     * 用户注册：校验验证码后创建账号，注册成功即视为登录。<p>
     *
     * @param registerRequest
     */
    @Transactional
    public LoginResponse register(RegisterRequest registerRequest) {
        // 先查手机号，避免已注册的情况下白白消费掉一条验证码
        boolean phoneExists = userMapper.exists(
                new LambdaQueryWrapper<User>()
                        .eq(User::getPhone, registerRequest.phone())
        );
        if (phoneExists) {
            throw new BusinessException(ErrorCode.PHONE_ALREADY_EXISTS);
        }

        // 校验并消费验证码
        smsCodeService.verifyCode(registerRequest.phone(), registerRequest.code());

        User user = new User();
        user.setPhone(registerRequest.phone());
        // 密码必须加密存储，库里绝不能出现明文
        user.setPassword(passwordEncoder.encode(registerRequest.password()));
        user.setEmail(registerRequest.email());
        // 未填昵称时自动生成一个
        if (!StringUtils.hasText(registerRequest.nickname())) {
            user.setNickname(generateNickname());
        } else {
            user.setNickname(registerRequest.nickname());
        }
        user.setStatus(1);                                  // 1 正常，0 禁用
        user.setMemberLevel(MemberLevel.NORMAL.getCode());  // 新用户默认普通用户

        try {
            userMapper.insert(user);
            // 新用户必须同时拥有「默认账本」和「默认账户」，
            // 否则登录后记账页没有账本/账户可选，用户会以为程序坏了。
            // 三张表（t_user / t_book / t_account）在同一事务内，
            // 任一失败整体回滚 —— 这就是 register() 上要加 @Transactional 的原因。
            bookService.createDefaultBook(user.getId());
            accountService.createDefaultAccount(user.getId());
        } catch (DuplicateKeyException e) {
            // 上面查过一次，走到这里说明是并发注册，由唯一索引兜底
            throw new BusinessException(ErrorCode.PHONE_ALREADY_EXISTS);
        }

        return issueToken(user);
    }

    public LoginResponse loginByPassword(LoginByPasswordRequest request){
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getPhone, request.phone())
        );

        //参数校验
        if(user == null){
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }else if(user.getStatus() == null || user.getStatus() != STATUS_NORMAL){
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }else if(user.getPassword() == null){
            //不提示密码未设置，防止攻击者暴力获取信息
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }else if(!passwordEncoder.matches(request.password(),user.getPassword())){
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }

        return issueToken(user);
    }

    /**
     * 业务顺序一定为先检验验证码，再查询手机号是否存在<p>
     * 手机号不存在不是失败（要自动注册），所以验证码必须消耗
     * @param request
     */
    @Transactional
    public LoginResponse loginByCode(LoginByCodeRequest request){
        smsCodeService.verifyCode(request.phone(),request.code());
        log.info("验证码校验成功");

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getPhone, request.phone())
        );

        if(user == null){
            User newUser = new User();
            newUser.setPhone(request.phone());
            newUser.setNickname(generateNickname());
            newUser.setStatus(STATUS_NORMAL);
            newUser.setMemberLevel(MemberLevel.NORMAL.getCode());

            try {
                userMapper.insert(newUser);
                user = newUser;
                //新用户需要实现默认数据
                bookService.createDefaultBook(user.getId());
                accountService.createDefaultAccount(user.getId());
            }catch (DuplicateKeyException e){
                // 并发：另一个请求刚用同一手机号注册了,导致数据插入失败
                // 处理：重新进行二次查询获取用户数据
                user = userMapper.selectOne(
                        new LambdaQueryWrapper<User>()
                                .eq(User::getPhone, request.phone())
                );
            }
        }

        if (user.getStatus() == null || user.getStatus() != STATUS_NORMAL) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        return issueToken(user);
    }

    /**
     * 重置密码（<b>忘记密码场景，匿名可访问</b>）：
     * 用户未登录，身份只能来自请求体里的手机号。
     *
     * <p>本方法只负责"确定身份属于谁"，随后交给共用的
     * {@link #applyPasswordReset} 完成验码与改密。
     *
     * <p><b>★ 为什么必须先按手机号验码、再查用户（顺序不能反）</b>：
     * <pre>
     *   若先查用户：
     *     查用户(phone) 不存在 → 返回 USER_NOT_FOUND
     *     查用户(phone) 存在   → 返回验证码错误
     * </pre>
     * 攻击者无需持有任何手机号，就能靠这两个<b>不同的响应</b>批量探测
     * 「哪些手机号已注册」——这就是账号枚举漏洞。
     * 先验码则两种情况的响应完全一样（都是验证码错误），无从区分。
     *
     * <p>顺序由 {@link #applyPasswordReset} 内部保证，本方法不重复实现。
     * 这与 {@code loginByCode}（先验码、再查手机号）是同一个思路。
     *
     * <p>验码通过后才查库：走到那一步说明对方确实持有该手机号，
     * 此时告诉他"未注册"是安全的。
     *
     * @param request 手机号 + 验证码 + 新密码
     * @throws BusinessException 验证码错误/过期；手机号未注册；账号被禁用
     */
    public void resetPassword(ResetPasswordRequest request) {
        // 先验码，再查用户 —— 顺序即防枚举（见方法注释）
        smsCodeService.verifyCode(request.phone(), request.code());

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getPhone, request.phone())
        );
        if (user == null) {
            // 与验证码登录不同：这里【不自动注册】。
            // 忘记密码的前提是账号已存在；自动注册会让"忘记密码"变成"创建账号"，
            // 用户会莫名多出一个空账号，且他自己并不知道。
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        applyPasswordReset(user, request.newPassword());
    }

    /**
     * 修改密码（<b>系统内场景，需登录</b>）：
     * 身份来自当前会话，<b>手机号不接受前端传入</b>。
     *
     * <p><b>为什么这个方法和 {@link #resetPassword} 是分开的两个入口</b>：
     * 两者的凭据相同（短信验证码）、核心流程也共用
     * （{@link #applyPasswordReset}），<b>但身份来源不同</b>：
     * <ul>
     *   <li>本方法：身份来自 token，用户只能改自己的密码</li>
     *   <li>{@code resetPassword}：用户未登录，身份只能来自请求体手机号</li>
     * </ul>
     * 「身份从哪来」是 Controller 适配层的职责，不该塞进 DTO 让 Service 去猜。
     * 所以这里拆开，但<b>核心逻辑仍然只有一份</b>——不是两套实现。
     *
     * <p>配合 {@link ChangePasswordRequest} 里<b>根本没有 phone 字段</b>，
     * 前端连"指向他人账号"的表达能力都不存在，比运行期校验一致性更彻底。
     *
     * <p><b>验证码发给谁</b>：{@code user.getPhone()}，即当前登录账号绑定的手机号。
     * 即使前端传了个别的号码去申请验证码，这里也验不过——
     * 因为校验用的号码取自数据库，不取自任何请求参数。
     *
     * @param userId  当前登录用户 id，<b>只来自 token</b>
     * @param request 验证码 + 新密码（无手机号字段）
     * @throws BusinessException 用户不存在；验证码错误/过期；账号被禁用
     */
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            // 用户可能已被删除，而 token 还没过期（最长 7 天）
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        // 验码用的手机号取自数据库，不接受任何前端输入
        smsCodeService.verifyCode(user.getPhone(), request.code());

        applyPasswordReset(user, request.newPassword());
    }

    /**
     * 改密的共用核心：校验账号状态 → 写入新密码。两条入口最终都走到这里。
     *
     * <p><b>调用前提</b>：调用方<b>已经完成验证码校验</b>，且已确定 {@code user}
     * 就是要改密的那个账号。本方法不再做任何身份判断——
     * 身份判定属于各入口的适配逻辑，混进来会让这里承担它不该承担的职责。
     *
     * <p><b>为什么不需要 {@code @Transactional}</b>：全流程只 update 一张表的一行，
     * 没有跨表写入，单条 UPDATE 本身就是原子的。事务注解应留给真正需要的
     * 多表写操作（如阶段 2 的注册建账本 + 建账户）。
     *
     * @param user        目标用户（已确认身份）
     * @param newPassword 新密码明文，将 BCrypt 后入库
     */
    private void applyPasswordReset(User user, String newPassword) {
        // 校验账号状态：禁用账号不应能通过改密码"复活"
        if (user.getStatus() == null || user.getStatus() != STATUS_NORMAL) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        // 只 set id 与新密码，不整个 User 覆盖。
        // MyBatis-Plus 的 update-strategy=not_null（见 application.yaml）会让 SQL
        // 只更新非 null 字段，因此这里天然不会把 phone / nickname / memberLevel
        // 等未赋值的字段冲成 null——这正是"部分更新"想要的效果。
        boolean hadPassword = StringUtils.hasText(user.getPassword());
        User update = new User();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(update);

        log.info("密码已{}：userId={}", hadPassword ? "重置" : "首次设置", user.getId());

        // TODO（安全增强，一期先不做）：改密后应让该用户所有旧 token 失效。
        //   ★ 本功能是事实上的「账号找回」入口，这个 TODO 比一般改密码更紧迫：
        //     用户发现账号被盗、用验证码改完密码后，会强烈期望攻击者的 token 立刻失效，
        //     但 JWT 是无状态的，做不到。
        //   为什么黑名单方案解决不了：
        //     ① 匿名入口（resetPassword）根本没有"当前 token"可拉黑；
        //     ② 攻击者的 token 在别的设备上，拉黑当前这一个毫无意义。
        //   正确做法：t_user 加 password_update_time，过滤器校验 token 的 iat
        //     是否早于该时间，早于则视为失效。
        //   代价：每个请求都要查一次库或缓存，牺牲无状态带来的零 IO 优势。
        //   一期未做，属于明确接受的残余风险。
    }

    /**
     * 查询当前登录用户信息。
     *
     * <p><b>为什么必须查库，不能直接从 token 拼出来</b>：
     * token 里只有 {@code uid} / {@code ml} / {@code sub}，属于<b>签发那一刻的快照</b>。
     * 昵称、头像、邮箱以及 {@code hasPassword} 都不在 token 里；
     * 且用户可能已被禁用或删除，而 token 尚未过期（最长 7 天）。
     * 所以要以库里的实时数据为准。
     *
     * <p><b>可优化点</b>：会员等级其实 token 里有，若将来此接口被高频调用，
     * 可以不查库、直接用 {@code JwtAuthenticationFilter} 解析出的等级组装。
     * 但那会让"刚升级会员"的用户在旧 token 过期前看不到新等级，
     * 一期数据量小，以正确性优先。
     *
     * @param userId 当前登录用户 id（<b>来自 token</b>）
     * @return 已剔除密码字段的 VO
     * @throws BusinessException 用户不存在或已被删除
     */
    public UserVO getCurrentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return toUserVO(user);
    }

    /**
     * 实体 → VO。集中在这里做，保证所有对外出口的字段口径一致。
     *
     * <p><b>为什么 {@code memberLevel} 用枚举归一化后的值，而不是直接回传数据库原值</b>：
     * {@code JwtAuthenticationFilter} 判定权限时用的就是 {@code MemberLevel.of(...)}，
     * 它对未知值（如手工改库写进 99）会回退成 NORMAL。
     * 若这里回传原始值 99，会出现「前端显示是会员、后端按普通用户拒绝」的诡异现象。
     * 两边用同一个枚举，口径就永远一致。
     */
    private UserVO toUserVO(User user) {
        MemberLevel level = MemberLevel.of(user.getMemberLevel());
        return new UserVO(
                user.getId(),
                user.getPhone(),
                user.getNickname(),
                user.getAvatar(),
                user.getEmail(),
                level.getCode(),
                level.isMember(),
                // 必须用 hasText 同时挡住 null 与空串：
                // 只判 null 的话，若库里曾出现空串（历史数据/手工改库），
                // 会把"从未设过密码"误判成"已设置密码"。
                StringUtils.hasText(user.getPassword()));
    }

    public void logout(String jti,long expireAtMillis){
        tokenBlacklistService.blacklist(jti,expireAtMillis);
    }
}
