package com.sakuya.notesatwill;

import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.DTO.PersonaRequest;
import com.sakuya.notesatwill.entity.VO.PersonaPresetVO;
import com.sakuya.notesatwill.entity.VO.PersonaVO;
import com.sakuya.notesatwill.service.PersonaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色设定（任务 7.1）回归测试。
 *
 * <p><b>★★ 核心验收点：同一用户「最多一个 active」</b>
 * 这条不变量数据库无法强制（{@code UNIQUE(user_id, is_active)} 会让多条 0 冲突），
 * 只能靠 Service 保证 —— 所以必须逐个入口验证：
 * <ol>
 *   <li>首个角色创建 → 自动激活</li>
 *   <li>第二个角色创建 → <b>不</b>改变当前激活</li>
 *   <li>切换激活 → 旧的那个必须变 inactive</li>
 *   <li>修改角色 → <b>不能</b>改变激活状态</li>
 *   <li>删除激活角色 → 不自动切换（允许"无 active"）</li>
 * </ol>
 *
 * <p>另外验证：预设复制（不是引用）、预设只读、归属隔离、名字/性别校验。
 *
 * @author sakuya
 */
@SpringBootTest
class PersonaServiceTest {

    private static final Long MY_ID = 987500001L;
    private static final Long OTHER_ID = 987500002L;
    /** 预置角色 id（seed 数据） */
    private static final Long PRESET_PARROT = 3001L;    // 小小鹦鹉
    private static final Long PRESET_BUTLER = 3002L;    // 稳重管家

    @Autowired
    private PersonaService personaService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (Long u : new Long[]{MY_ID, OTHER_ID}) {
            jdbcTemplate.update("DELETE FROM t_ai_persona WHERE user_id = ?", u);
        }
    }

    /** 该用户当前激活的角色条数（应为 0 或 1） */
    private int activeCount(Long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_ai_persona WHERE user_id = ? AND is_active = 1 AND deleted = 0",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    private int totalCount(Long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_ai_persona WHERE user_id = ? AND deleted = 0",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    private PersonaRequest fromPreset(Long presetId) {
        return new PersonaRequest(presetId, null, null, null, null, null, null, null);
    }

    private PersonaRequest blank(String name) {
        return new PersonaRequest(null, name, null, "OTHER", null, null, null, null);
    }

    // ==================== 预设 ====================

    @Test
    void listsSystemPresets() {
        System.out.println("\n===== 系统预设 5 条（按 sort 升序）=====");
        List<PersonaPresetVO> presets = personaService.listPresets();

        for (PersonaPresetVO p : presets) {
            System.out.printf("  [%d] %s（%s / %s → %s）%n",
                    p.sort(), p.name(), p.gender(), p.relation(), p.address());
        }

        assertEquals(5, presets.size(), "seed 里有 5 个预设");
        assertEquals("小小鹦鹉", presets.get(0).name(), "sort=1 的是小小鹦鹉");
        // 按 sort 升序
        for (int i = 1; i < presets.size(); i++) {
            assertTrue(presets.get(i).sort() >= presets.get(i - 1).sort(), "必须按 sort 升序");
        }
        System.out.println("  ✓ 5 条，顺序正确");
    }

    @Test
    void creatingFromPresetCopiesFields() {
        System.out.println("\n===== ★ 从预设创建：字段被【复制】过来 =====");
        PersonaVO vo = personaService.create(MY_ID, fromPreset(PRESET_PARROT));

        System.out.println("  复制结果：" + vo.name() + " / " + vo.gender()
                + " / " + vo.relation() + " → " + vo.address());
        assertEquals("小小鹦鹉", vo.name(), "名字从预设复制");
        assertEquals("FEMALE", vo.gender());
        assertEquals("朋友", vo.relation());
        assertEquals("主人", vo.address());
        assertNotNull(vo.personality(), "角色设定也要复制");
        assertEquals(PRESET_PARROT, vo.presetId(), "presetId 保留作溯源");
        System.out.println("  ✓ 字段全部复制，presetId 保留溯源");
    }

    @Test
    void editingMyPersonaDoesNotAffectPreset() {
        System.out.println("\n===== ★★ 改自己的角色不影响系统预设（复制 vs 引用）=====");
        PersonaVO mine = personaService.create(MY_ID, fromPreset(PRESET_PARROT));
        String presetNameBefore = personaService.listPresets().stream()
                .filter(p -> p.id().equals(PRESET_PARROT)).findFirst().orElseThrow().name();

        PersonaRequest update = new PersonaRequest(
                null, "我改的名字", null, "FEMALE", "朋友", "主人", "改过的设定", 1);
        personaService.update(MY_ID, mine.id(), update);

        String presetNameAfter = personaService.listPresets().stream()
                .filter(p -> p.id().equals(PRESET_PARROT)).findFirst().orElseThrow().name();

        System.out.println("  预设名 前=" + presetNameBefore + " 后=" + presetNameAfter);
        assertEquals(presetNameBefore, presetNameAfter,
                "★★ 用户改自己的角色，绝不能改到所有用户共享的预设");
        assertEquals("小小鹦鹉", presetNameAfter);
        System.out.println("  ✓ 预设完全没被影响");
    }

    @Test
    void nonexistentPresetIsRejected() {
        System.out.println("\n===== 不存在的预设 → 6011 =====");
        BusinessException e = assertThrows(BusinessException.class,
                () -> personaService.create(MY_ID, fromPreset(99999999L)));
        assertEquals(ErrorCode.PERSONA_PRESET_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    @Test
    void presetFieldsCanBeOverridden() {
        System.out.println("\n===== 预设 + 覆盖：只改想改的字段 =====");
        PersonaRequest req = new PersonaRequest(
                PRESET_PARROT, "鹦鹉改名了", null, null, null, null, null, null);
        PersonaVO vo = personaService.create(MY_ID, req);

        System.out.println("  名字=" + vo.name() + "（覆盖），性别=" + vo.gender() + "（来自预设）");
        assertEquals("鹦鹉改名了", vo.name(), "覆盖生效");
        assertEquals("FEMALE", vo.gender(), "未覆盖的字段仍来自预设");
        System.out.println("  ✓ 覆盖 + 继承都正确");
    }

    // ==================== ★★ 唯一激活 ====================

    @Test
    void firstPersonaIsAutoActivated() {
        System.out.println("\n===== ★ 首个角色自动激活 =====");
        assertEquals(0, activeCount(MY_ID), "开始时没有激活角色");

        PersonaVO vo = personaService.create(MY_ID, fromPreset(PRESET_PARROT));

        assertTrue(vo.isActive(), "★ 用户还没有可用角色时，新建的应该自动生效");
        assertEquals(1, activeCount(MY_ID));
        System.out.println("  ✓ 自动激活（否则用户建完还要再点一次「使用」）");
    }

    @Test
    void secondPersonaDoesNotStealActivation() {
        System.out.println("\n===== ★★ 第二个角色不抢占当前激活 =====");
        PersonaVO first = personaService.create(MY_ID, fromPreset(PRESET_PARROT));
        PersonaVO second = personaService.create(MY_ID, fromPreset(PRESET_BUTLER));

        System.out.println("  第一个 isActive=" + first.isActive()
                + "，第二个 isActive=" + second.isActive());
        assertTrue(first.isActive(), "★ 原来生效的仍然生效");
        assertFalse(second.isActive(), "★ 新建的不该把正在用的顶掉");
        assertEquals(1, activeCount(MY_ID), "★ 激活数必须仍是 1");
        System.out.println("  ✓ 用户可多角色，但生效的仍是第一个（共 " + totalCount(MY_ID) + " 条记录）");
    }

    @Test
    void activatingSwitchesUniqueActive() {
        System.out.println("\n===== ★★ 切换激活：旧的必须变 inactive =====");
        PersonaVO a = personaService.create(MY_ID, fromPreset(PRESET_PARROT));
        PersonaVO b = personaService.create(MY_ID, fromPreset(PRESET_BUTLER));
        assertEquals(1, activeCount(MY_ID));

        PersonaVO activated = personaService.activate(MY_ID, b.id());

        System.out.println("  激活 B(=" + b.id() + ") 后：activeCount=" + activeCount(MY_ID));
        assertTrue(activated.isActive());
        assertEquals(1, activeCount(MY_ID), "★★ 无论怎么切，active 永远只有 1 条");

        PersonaVO activeNow = personaService.getActive(MY_ID);
        assertEquals(b.id(), activeNow.id(), "生效角色应换成 B");

        // A 必须已经变成 inactive
        Integer aActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM t_ai_persona WHERE id = ?", Integer.class, a.id());
        assertEquals(0, aActive, "★ 原来的 A 必须被取消激活");
        System.out.println("  ✓ A 已取消激活，只有 B 生效");
    }

    @Test
    void repeatedActivateIsIdempotent() {
        System.out.println("\n===== 重复激活同一个角色（幂等）=====");
        PersonaVO a = personaService.create(MY_ID, fromPreset(PRESET_PARROT));

        personaService.activate(MY_ID, a.id());
        personaService.activate(MY_ID, a.id());

        assertEquals(1, activeCount(MY_ID), "不应产生多条激活");
        System.out.println("  ✓ 仍是 1 条");
    }

    @Test
    void updateDoesNotChangeActivation() {
        System.out.println("\n===== ★★ 修改角色不能改变激活状态 =====");
        PersonaVO a = personaService.create(MY_ID, fromPreset(PRESET_PARROT));
        PersonaVO b = personaService.create(MY_ID, fromPreset(PRESET_BUTLER));
        personaService.activate(MY_ID, b.id());
        System.out.println("  当前生效的是 B(=" + b.id() + ")");

        // 修改【未激活】的 A
        personaService.update(MY_ID, a.id(), new PersonaRequest(
                null, "改过的A", null, "FEMALE", null, null, null, null));

        System.out.println("  修改 A 后：activeCount=" + activeCount(MY_ID)
                + "，生效的是 " + personaService.getActive(MY_ID).id());
        assertEquals(1, activeCount(MY_ID), "★ 改配置不能产生第二条激活");
        assertEquals(b.id(), personaService.getActive(MY_ID).id(),
                "★★ 改 A 不该把正在用的 B 顶掉");
        System.out.println("  ✓ 生效角色仍是 B，A 只是配置变了");
    }

    @Test
    void deletingActivePersonaLeavesNoActive() {
        System.out.println("\n===== ★ 删除激活角色 → 不自动切换（允许无 active）=====");
        PersonaVO a = personaService.create(MY_ID, fromPreset(PRESET_PARROT));
        PersonaVO b = personaService.create(MY_ID, fromPreset(PRESET_BUTLER));
        personaService.activate(MY_ID, a.id());
        assertEquals(1, activeCount(MY_ID));

        personaService.delete(MY_ID, a.id());

        System.out.println("  删掉生效的 A 后：剩余记录=" + totalCount(MY_ID)
                + "，activeCount=" + activeCount(MY_ID)
                + "，getActive=" + personaService.getActive(MY_ID));
        assertEquals(1, totalCount(MY_ID), "B 还在");
        assertEquals(0, activeCount(MY_ID), "★ 不自动切换到 B");
        assertNull(personaService.getActive(MY_ID), "★ 此时没有生效角色，返回 null");
        System.out.println("  ✓ 不自动切换（避免 AI 人格被悄悄换掉）；前端应提示重新选择");
    }

    @Test
    void deletingInactivePersonaKeepsActive() {
        System.out.println("\n===== 删除未激活的角色 → 激活状态不变 =====");
        PersonaVO a = personaService.create(MY_ID, fromPreset(PRESET_PARROT));
        PersonaVO b = personaService.create(MY_ID, fromPreset(PRESET_BUTLER));

        personaService.delete(MY_ID, b.id());

        assertEquals(1, activeCount(MY_ID));
        assertEquals(a.id(), personaService.getActive(MY_ID).id());
        System.out.println("  ✓ A 仍生效");
    }

    @Test
    void manyPersonasStillOnlyOneActive() {
        System.out.println("\n===== ★★ 建 5 个角色，激活数必须仍是 1 =====");
        for (Long presetId : new Long[]{3001L, 3002L, 3003L, 3004L, 3005L}) {
            personaService.create(MY_ID, fromPreset(presetId));
        }

        System.out.println("  总记录=" + totalCount(MY_ID) + "，激活数=" + activeCount(MY_ID));
        assertEquals(5, totalCount(MY_ID), "5 个角色都能存在");
        assertEquals(1, activeCount(MY_ID), "★★ 但只有 1 个生效");
        System.out.println("  ✓ 多角色共存 + 唯一激活，不冲突");
    }

    // ==================== 归属隔离 ====================

    @Test
    void cannotSeeOthersPersonas() {
        System.out.println("\n===== 用户隔离：看不到别人的角色 =====");
        PersonaVO mine = personaService.create(MY_ID, blank("我的角色"));
        personaService.create(OTHER_ID, blank("别人的角色"));

        List<PersonaVO> myList = personaService.listMine(MY_ID);

        assertEquals(1, myList.size());
        assertEquals(mine.id(), myList.get(0).id());
        System.out.println("  ✓ 只返回自己的 1 条");
    }

    @Test
    void cannotActivateOthersPersona() {
        System.out.println("\n===== 越权：不能激活别人的角色 =====");
        PersonaVO others = personaService.create(OTHER_ID, fromPreset(PRESET_PARROT));

        BusinessException e = assertThrows(BusinessException.class,
                () -> personaService.activate(MY_ID, others.id()));
        assertEquals(ErrorCode.PERSONA_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    @Test
    void cannotUpdateOthersPersona() {
        System.out.println("\n===== 越权：不能改别人的角色 =====");
        PersonaVO others = personaService.create(OTHER_ID, fromPreset(PRESET_PARROT));

        BusinessException e = assertThrows(BusinessException.class,
                () -> personaService.update(MY_ID, others.id(), blank("篡改")));
        assertEquals(ErrorCode.PERSONA_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    @Test
    void cannotDeleteOthersPersona() {
        System.out.println("\n===== 越权：不能删别人的角色 =====");
        PersonaVO others = personaService.create(OTHER_ID, fromPreset(PRESET_PARROT));

        BusinessException e = assertThrows(BusinessException.class,
                () -> personaService.delete(MY_ID, others.id()));
        assertEquals(ErrorCode.PERSONA_NOT_FOUND.getCode(), e.getCode());
        assertEquals(1, totalCount(OTHER_ID), "别人的角色不能被删掉");
        System.out.println("  ✓ 被拒(" + e.getCode() + ")，对方角色完好");
    }

    @Test
    void cannotFindNonexistentPersona() {
        System.out.println("\n===== 不存在的角色 → 6010 =====");
        BusinessException e = assertThrows(BusinessException.class,
                () -> personaService.activate(MY_ID, 99999999L));
        assertEquals(ErrorCode.PERSONA_NOT_FOUND.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")");
    }

    // ==================== 校验 ====================

    @Test
    void blankNameIsRejected() {
        System.out.println("\n===== 空白创建时名字为空 → 1000 =====");
        BusinessException e = assertThrows(BusinessException.class,
                () -> personaService.create(MY_ID, blank("   ")));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), e.getCode());
        System.out.println("  ✓ 被拒(" + e.getCode() + ")" + e.getMessage());
    }

    @Test
    void nameIsTrimmed() {
        System.out.println("\n===== 名字前后空格被去掉 =====");
        PersonaVO vo = personaService.create(MY_ID, blank("  我的助手  "));
        System.out.println("  [" + vo.name() + "]");
        assertEquals("我的助手", vo.name());
        System.out.println("  ✓ 已 trim");
    }

    @Test
    void updateCanClearOptionalFields() {
        System.out.println("\n===== ★ 修改时传 null 可以清空可选字段 =====");
        PersonaVO vo = personaService.create(MY_ID, fromPreset(PRESET_PARROT));
        assertNotNull(vo.relation());

        // 全部可选字段传 null（= 清空）
        PersonaVO updated = personaService.update(MY_ID, vo.id(), new PersonaRequest(
                null, "保留名字", null, "OTHER", null, null, null, 0));

        System.out.println("  relation=" + updated.relation()
                + "，address=" + updated.address()
                + "，personality=" + updated.personality());
        assertNull(updated.relation(), "★ 传 null 应该是清空，而不是「没改」");
        assertNull(updated.address());
        assertNull(updated.personality());
        assertEquals("保留名字", updated.name(), "名字没传则保留原值");
        System.out.println("  ✓ 可选字段被清空（验证 LambdaUpdateWrapper 显式 set 生效）");
    }

    @Test
    void chatReplyEnabledIsMappedToBoolean() {
        System.out.println("\n===== chatReplyEnabled 1/0 → true/false =====");
        PersonaVO off = personaService.create(MY_ID, new PersonaRequest(
                null, "不主动", null, "OTHER", null, null, null, 0));
        assertFalse(off.chatReplyEnabled());

        personaService.activate(MY_ID, off.id());
        PersonaVO on = personaService.update(MY_ID, off.id(), new PersonaRequest(
                null, "要主动", null, "OTHER", null, null, null, 1));
        assertTrue(on.chatReplyEnabled());
        System.out.println("  ✓ 0 → false，1 → true");
    }

    @Test
    void getActiveReturnsNullWhenNoPersona() {
        System.out.println("\n===== 没有角色时 getActive 返回 null（不是报错）=====");
        assertNull(personaService.getActive(MY_ID));
        assertTrue(personaService.listMine(MY_ID).isEmpty());
        System.out.println("  ✓ null + 空列表");
    }

    @Test
    void activeIsSortedFirst() {
        System.out.println("\n===== 列表里激活的排最前 =====");
        PersonaVO a = personaService.create(MY_ID, fromPreset(PRESET_PARROT));
        PersonaVO b = personaService.create(MY_ID, fromPreset(PRESET_BUTLER));
        // 让 b（id 更大、更新）变成激活
        personaService.activate(MY_ID, b.id());

        List<PersonaVO> list = personaService.listMine(MY_ID);
        System.out.println("  顺序: " + list.stream().map(PersonaVO::name).toList());
        assertTrue(list.get(0).isActive(), "★ 第一条必须是生效的那个");
        assertEquals(b.id(), list.get(0).id());
        System.out.println("  ✓ " + list.get(0).name() + " 排最前");
    }
}
