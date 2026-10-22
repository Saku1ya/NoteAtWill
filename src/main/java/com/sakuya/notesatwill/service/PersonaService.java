package com.sakuya.notesatwill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.AiPersona;
import com.sakuya.notesatwill.entity.DTO.PersonaRequest;
import com.sakuya.notesatwill.entity.PersonaPreset;
import com.sakuya.notesatwill.entity.VO.PersonaPresetVO;
import com.sakuya.notesatwill.entity.VO.PersonaVO;
import com.sakuya.notesatwill.mapper.AiPersonaMapper;
import com.sakuya.notesatwill.mapper.PersonaPresetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 角色设定服务（<b>预设只读 + 用户角色增删改 + 唯一激活</b>）。
 *
 * <p><b>★★ 全类最核心的不变量：同一用户最多一条 {@code is_active = 1}</b>
 * <pre>
 *   用户可以有【多个】角色记录
 *   但同一时间只有【一个】在生效
 * </pre>
 * 这条约束<b>数据库无法强制</b>（{@code UNIQUE(user_id, is_active)} 不行 ——
 * 多条 0 会冲突；MySQL 也没有部分唯一索引），
 * 所以任何"置为激活"的操作都必须在<b>同一事务</b>里把其它角色置 0。
 * 本类通过 {@link #activateOnly} 统一收口，<b>不允许在别处直接 set isActive=1</b>。
 *
 * <p><b>★ 三个会导致"多激活"的入口，全部经过 activateOnly</b>：
 * <ol>
 *   <li>{@link #create} —— 用户的第一条角色自动激活（否则他没得用）</li>
 *   <li>{@link #activate} —— 显式切换</li>
 *   <li>{@link #update} —— <b>不碰 isActive</b>，避免"改个名字把别的角色顶掉"</li>
 * </ol>
 *
 * <p><b>★ 删除激活角色【不自动】激活别的</b>：见 {@link #delete} 的说明。
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaService {

    private static final String GENDER_DEFAULT = "OTHER";
    private static final int ACTIVE = 1;
    private static final int INACTIVE = 0;

    private final AiPersonaMapper personaMapper;
    private final PersonaPresetMapper presetMapper;

    // ========================================================================
    // 系统预设（只读）
    // ========================================================================

    /**
     * 查全部系统预设角色。
     *
     * <p>按 {@code sort} 升序 —— 预设的展示顺序由 seed 数据的 sort 决定
     * （小小鹦鹉、稳重管家、毒舌损友、温柔学姐、记账猫猫）。
     *
     * @return 预设列表（全局共享，与当前用户无关）
     */
    public List<PersonaPresetVO> listPresets() {
        List<PersonaPreset> presets = presetMapper.selectList(
                new LambdaQueryWrapper<PersonaPreset>().orderByAsc(PersonaPreset::getSort));

        List<PersonaPresetVO> result = new ArrayList<>(presets.size());
        for (PersonaPreset p : presets) {
            result.add(new PersonaPresetVO(
                    p.getId(), p.getName(), p.getAvatar(), p.getGender(),
                    p.getRelation(), p.getAddress(), p.getPersonality(), p.getSort()));
        }
        return result;
    }

    // ========================================================================
    // 我的角色
    // ========================================================================

    /**
     * 查我的全部角色（激活的排在最前）。
     *
     * <p><b>★ 为什么把激活的排最前</b>：前端列表通常把"当前使用"的置顶并打标记。
     * 在 SQL 里排好序比让前端再排一次更省事，也更不容易排错。
     *
     * @param userId 当前登录用户
     */
    public List<PersonaVO> listMine(Long userId) {
        List<AiPersona> list = personaMapper.selectList(
                new LambdaQueryWrapper<AiPersona>()
                        .eq(AiPersona::getUserId, userId)
                        .orderByDesc(AiPersona::getIsActive)
                        .orderByDesc(AiPersona::getId));

        List<PersonaVO> result = new ArrayList<>(list.size());
        for (AiPersona p : list) {
            result.add(toVO(p));
        }
        return result;
    }

    /**
     * 查当前生效的角色。
     *
     * <p><b>★ 可能返回 null</b>：用户还没有角色，或者刚把正在用的那个删了。
     * 调用方（D9 的 AI 对话、每日问候）必须能处理"没有角色"的情况，
     * 而不是假设一定有。
     *
     * @param userId 当前登录用户
     * @return 激活的角色；没有则 {@code null}
     */
    public PersonaVO getActive(Long userId) {
        AiPersona active = personaMapper.selectOne(
                new LambdaQueryWrapper<AiPersona>()
                        .eq(AiPersona::getUserId, userId)
                        .eq(AiPersona::getIsActive, ACTIVE)
                        .orderByDesc(AiPersona::getId)
                        // ★ 万一历史脏数据里有多条 active，取最新一条而不是抛异常 ——
                        //   selectOne 在多行时会抛异常，这里用 last limit 1 兜住
                        .last("LIMIT 1"));

        return active == null ? null : toVO(active);
    }

    // ========================================================================
    // 创建
    // ========================================================================

    /**
     * 创建角色。
     *
     * <p><b>★★ 激活规则（重要）</b>：
     * <ul>
     *   <li>用户<b>还没有</b>任何激活角色 → 新建的这条自动激活
     *       （否则用户建完角色还要再点一次"使用"，多此一举）</li>
     *   <li>用户<b>已有</b>激活角色 → 新建的这条<b>不自动激活</b>
     *       （用户可能只是想多建几个备选，不该悄悄换掉他正在用的 AI 人格）</li>
     * </ul>
     * 想换用新建的那个，显式调 {@code PUT /{id}/activate}。
     *
     * @param userId  当前登录用户
     * @param request 角色信息（可带 {@code presetId} 从预设复制）
     * @return 新建的角色
     * @throws BusinessException 预设不存在（6011）/ 名字为空或过长（1000）/ 性别非法（1000）
     */
    @Transactional
    public PersonaVO create(Long userId, PersonaRequest request) {
        // ---------- ① 解析字段：预设复制 + 请求覆盖 ----------
        PersonaPreset preset = null;
        if (request.presetId() != null) {
            preset = presetMapper.selectById(request.presetId());
            if (preset == null) {
                throw new BusinessException(ErrorCode.PERSONA_PRESET_NOT_FOUND);
            }
        }

        String name = firstNonBlank(request.name(), preset == null ? null : preset.getName());
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "角色名字不能为空");
        }

        AiPersona persona = new AiPersona();
        persona.setUserId(userId);
        // presetId 只作溯源，不参与读取
        persona.setPresetId(preset == null ? null : preset.getId());
        persona.setName(name.trim());
        persona.setAvatar(firstNonBlank(request.avatar(), preset == null ? null : preset.getAvatar()));
        persona.setGender(firstNonBlank(
                request.gender(), preset == null ? null : preset.getGender(), GENDER_DEFAULT));
        persona.setRelation(firstNonBlank(
                request.relation(), preset == null ? null : preset.getRelation()));
        persona.setAddress(firstNonBlank(
                request.address(), preset == null ? null : preset.getAddress()));
        persona.setPersonality(firstNonBlank(
                request.personality(), preset == null ? null : preset.getPersonality()));
        persona.setChatReplyEnabled(
                request.chatReplyEnabled() == null ? INACTIVE : request.chatReplyEnabled());

        // ---------- ② 决定是否激活 ----------
        boolean hasActive = getActive(userId) != null;
        // 先按"不激活"插入，若需要激活再走 activateOnly（统一收口）
        persona.setIsActive(INACTIVE);
        personaMapper.insert(persona);

        if (!hasActive) {
            activateOnly(userId, persona.getId());
            persona.setIsActive(ACTIVE);
            log.info("新建角色并自动激活（用户首个角色）：userId={}, personaId={}, name={}",
                    userId, persona.getId(), persona.getName());
        } else {
            log.info("新建角色（保持原有激活角色不变）：userId={}, personaId={}, name={}",
                    userId, persona.getId(), persona.getName());
        }

        return toVO(persona);
    }

    // ========================================================================
    // 修改
    // ========================================================================

    /**
     * 修改角色（<b>整体替换</b>）。
     *
     * <p><b>★★ 本方法绝不修改 {@code isActive}</b>：
     * 改配置（名字、性格）与"哪个在生效"是两件事。
     * 如果修改时允许传 isActive，就会出现"编辑角色 A 时不小心把 B 顶掉"。
     * 切换激活请用 {@link #activate}。
     *
     * <p><b>★ 为什么用 {@code LambdaUpdateWrapper} 而不是 {@code updateById}</b>：
     * {@code update-strategy: not_null} 会跳过 null 字段 ——
     * 用户想把"称呼"清空时，传 null 会被静默忽略，改不掉。
     * 显式 {@code .set()} 才能写入 null（这个坑在账单/预算模块已实测过两次）。
     *
     * @param userId  当前登录用户
     * @param id      角色 id
     * @param request 新的角色信息
     * @return 修改后的角色
     * @throws BusinessException 角色不存在/不属于我（6010）/ 名字为空或过长（1000）
     */
    @Transactional
    public PersonaVO update(Long userId, Long id, PersonaRequest request) {
        AiPersona existing = getOwned(userId, id);

        String name = firstNonBlank(request.name(), existing.getName());
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "角色名字不能为空");
        }

        personaMapper.update(null, new LambdaUpdateWrapper<AiPersona>()
                .eq(AiPersona::getId, id)
                .eq(AiPersona::getUserId, userId)
                .set(AiPersona::getName, name.trim())
                // ★ 显式 set：允许把可选字段清空（传 null 就是清空）
                .set(AiPersona::getAvatar, request.avatar())
                .set(AiPersona::getGender, firstNonBlank(request.gender(), GENDER_DEFAULT))
                .set(AiPersona::getRelation, request.relation())
                .set(AiPersona::getAddress, request.address())
                .set(AiPersona::getPersonality, request.personality())
                .set(AiPersona::getChatReplyEnabled,
                        request.chatReplyEnabled() == null
                                ? existing.getChatReplyEnabled()
                                : request.chatReplyEnabled()));

        log.info("修改角色：userId={}, personaId={}, name={}（isActive 未改动）", userId, id, name);
        return toVO(personaMapper.selectById(id));
    }

    // ========================================================================
    // 切换激活
    // ========================================================================

    /**
     * 把指定角色设为当前生效，<b>并取消该用户其它角色的激活</b>。
     *
     * <p><b>★ 这是"最多一个 active"的唯一合法入口</b>：
     * 无论何时需要切换，都必须走这里 —— 直接在别处 set isActive=1 会破坏不变量。
     *
     * @param userId 当前登录用户
     * @param id     要激活的角色 id
     * @return 激活后的角色
     * @throws BusinessException 角色不存在/不属于我（6010）
     */
    @Transactional
    public PersonaVO activate(Long userId, Long id) {
        getOwned(userId, id);   // 归属校验：不能激活别人的角色
        activateOnly(userId, id);
        log.info("切换激活角色：userId={}, personaId={}", userId, id);
        return toVO(personaMapper.selectById(id));
    }

    // ========================================================================
    // 删除
    // ========================================================================

    /**
     * 删除角色（逻辑删除）。
     *
     * <p><b>★★ 为什么删除激活角色时【不自动】激活别的</b>：
     * 假设用户有「稳重管家」和「毒舌损友」，正在用的是管家。他删掉管家后，
     * 如果系统自动把「毒舌损友」设为生效，用户会发现自己的 AI 伙伴
     * <b>突然换了人格、说话风格完全不同</b> —— 这比"暂时没有角色"更让人困惑。
     *
     * <p>所以删除后允许出现"有角色记录但没有 active"的中间状态，
     * 前端应在 AI 入口提示"请先选择一个角色"。
     * <b>让状态可预测，优于替用户做决定。</b>
     *
     * @param userId 当前登录用户
     * @param id     角色 id
     * @throws BusinessException 角色不存在/不属于我（6010）
     */
    @Transactional
    public void delete(Long userId, Long id) {
        AiPersona existing = getOwned(userId, id);
        personaMapper.deleteById(id);
        log.info("删除角色：userId={}, personaId={}, 原本是否激活={}（不自动切换）",
                userId, id, Objects.equals(existing.getIsActive(), ACTIVE));
    }

    // ========================================================================
    // 内部
    // ========================================================================

    /**
     * <b>唯一允许写入 is_active 的地方</b>：把 {@code targetId} 置 1，其余置 0。
     *
     * <p>两步顺序不能反：
     * <ol>
     *   <li>先把该用户<b>其它</b>角色置 0</li>
     *   <li>再把目标置 1</li>
     * </ol>
     * 反过来的话，中间会短暂出现"两条都是 1"的状态 —— 虽然在事务内外部看不到，
     * 但保持"任意时刻至多一条 1"的习惯更安全。
     *
     * <p><b>⚠ 已知的并发窗口</b>：两个请求同时激活不同角色时，
     * 理论上可能出现两条都是 1（两个事务各自"先置 0 别人"看到的是旧快照）。
     * 要彻底杜绝需要给用户行加锁（如 {@code SELECT ... FOR UPDATE} 一条用户记录）。
     * 本项目的实际影响是"AI 用哪个角色"这种软状态，<b>且 {@link #getActive}
     * 已按 id 取最新一条兜底</b>，不会报错。接受该风险。
     */
    private void activateOnly(Long userId, Long targetId) {
        // ① 其它角色全部置 0
        personaMapper.update(null, new LambdaUpdateWrapper<AiPersona>()
                .eq(AiPersona::getUserId, userId)
                .ne(AiPersona::getId, targetId)
                .eq(AiPersona::getIsActive, ACTIVE)
                .set(AiPersona::getIsActive, INACTIVE));

        // ② 目标置 1
        personaMapper.update(null, new LambdaUpdateWrapper<AiPersona>()
                .eq(AiPersona::getId, targetId)
                .eq(AiPersona::getUserId, userId)
                .set(AiPersona::getIsActive, ACTIVE));
    }

    /** 取属于我的角色，否则抛 6010（"不存在"与"不是我的"合并，防 id 枚举）。 */
    private AiPersona getOwned(Long userId, Long id) {
        AiPersona persona = personaMapper.selectById(id);
        if (persona == null || !Objects.equals(persona.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.PERSONA_NOT_FOUND);
        }
        return persona;
    }

    /** 返回第一个非空白字符串；全部为空则返回 null。 */
    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 实体转 VO（Integer 1/0 → Boolean）。 */
    private PersonaVO toVO(AiPersona p) {
        return new PersonaVO(
                p.getId(),
                p.getPresetId(),
                p.getName(),
                p.getAvatar(),
                p.getGender(),
                p.getRelation(),
                p.getAddress(),
                p.getPersonality(),
                Objects.equals(p.getChatReplyEnabled(), ACTIVE),
                Objects.equals(p.getIsActive(), ACTIVE),
                p.getCreateTime(),
                p.getUpdateTime());
    }
}
