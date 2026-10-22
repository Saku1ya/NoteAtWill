package com.sakuya.notesatwill.controller;

import com.sakuya.notesatwill.common.result.Result;
import com.sakuya.notesatwill.entity.DTO.PersonaRequest;
import com.sakuya.notesatwill.entity.VO.PersonaPresetVO;
import com.sakuya.notesatwill.entity.VO.PersonaVO;
import com.sakuya.notesatwill.service.PersonaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色设定接口。
 *
 * <p><b>★★ 核心约束：用户可以有多条角色，但同一时间只有一个生效</b>
 * <ul>
 *   <li>切换生效只能走 {@code PUT /{id}/activate}（内部会取消其它角色的激活）</li>
 *   <li><b>修改角色不会改变"哪个在生效"</b> —— 改配置和选角色是两件事</li>
 *   <li>删除正在使用的角色后<b>不会自动切换到别的角色</b>
 *       （避免 AI 人格被悄悄换掉），前端应提示用户重新选择</li>
 * </ul>
 *
 * <p><b>★ 预设是全局只读的</b>：{@code /presets} 返回的 5 条数据所有用户共用，
 * 没有任何写接口。用户"选一个"时是把它<b>复制</b>到自己的角色表，
 * 之后改的只是自己那份，预设永远不变。
 *
 * @author sakuya
 */
@RestController
@RequestMapping("/api/persona")
@RequiredArgsConstructor
public class PersonaController {

    private final PersonaService personaService;

    /**
     * 查系统预设角色（<b>只读，全局共享</b>）。
     *
     * <pre>
     * GET /api/persona/presets
     * →
     * [{ "id": 3001, "name": "小小鹦鹉", "gender": "FEMALE",
     *    "relation": "朋友", "address": "主人",
     *    "personality": "真诚友善，说话轻快带点俏皮…", "sort": 1 }, ...]
     * </pre>
     *
     * <p>前端展示这 5 个让用户挑；选中后调 {@code POST /api/persona} 复制成自己的。
     */
    @GetMapping("/presets")
    public Result<List<PersonaPresetVO>> presets() {
        return Result.success(personaService.listPresets());
    }

    /**
     * 查我的全部角色（<b>激活的排最前</b>）。
     *
     * <pre>
     * GET /api/persona
     * →
     * [{ "id": 7, "name": "小小鹦鹉", "isActive": true, "presetId": 3001, ... },
     *  { "id": 9, "name": "备用角色", "isActive": false, "presetId": null, ... }]
     * </pre>
     *
     * @param userId 当前登录用户 id
     */
    @GetMapping
    public Result<List<PersonaVO>> listMine(@AuthenticationPrincipal Long userId) {
        return Result.success(personaService.listMine(userId));
    }

    /**
     * 查当前生效的角色。
     *
     * <pre>
     * GET /api/persona/active
     * → { "id": 7, "name": "小小鹦鹉", "isActive": true, ... }
     * </pre>
     *
     * <p><b>★ 可能返回 {@code data: null}</b>（用户还没建角色，或刚删掉正在用的那个）。
     * 前端据此处理"未选择角色"的状态 —— <b>这不是错误</b>，HTTP 仍是 200。
     *
     * @param userId 当前登录用户 id
     */
    @GetMapping("/active")
    public Result<PersonaVO> active(@AuthenticationPrincipal Long userId) {
        return Result.success(personaService.getActive(userId));
    }

    /**
     * 创建角色。
     *
     * <pre>
     * 从预设复制：  POST /api/persona  { "presetId": 3001 }
     * 空白创建：    POST /api/persona  { "name": "我的小助手", "gender": "OTHER" }
     * 预设 + 覆盖：  POST /api/persona  { "presetId": 3001, "name": "鹦鹉改名了" }
     * </pre>
     *
     * <p><b>★ 激活规则</b>：用户<b>还没有</b>任何激活角色时，新建的自动生效；
     * <b>已有</b>激活角色时，新建的不改变当前生效的那个
     * （想换用新建的，请调 {@code /activate}）。
     *
     * @param userId  当前登录用户 id
     * @param request 角色信息
     */
    @PostMapping
    public Result<PersonaVO> create(@AuthenticationPrincipal Long userId,
                                    @Valid @RequestBody PersonaRequest request) {
        return Result.success(personaService.create(userId, request));
    }

    /**
     * 修改角色（<b>整体替换</b>）。
     *
     * <pre>
     * PUT /api/persona/7
     * { "name": "鹦鹉改名了", "gender": "FEMALE", "relation": "朋友",
     *   "address": "主人", "personality": "…", "chatReplyEnabled": 1 }
     * </pre>
     *
     * <p><b>★★ 本接口不会改变"哪个角色在生效"</b>。切换请用 {@code /activate}。
     * 这样设计是为了避免"编辑 A 的时候把正在用的 B 顶掉"。
     *
     * <p><b>★ 可选字段传 null 表示清空</b>（如把"称呼"清掉）。
     *
     * @param userId  当前登录用户 id
     * @param id      角色 id
     * @param request 新的角色信息
     */
    @PutMapping("/{id}")
    public Result<PersonaVO> update(@AuthenticationPrincipal Long userId,
                                    @PathVariable Long id,
                                    @Valid @RequestBody PersonaRequest request) {
        return Result.success(personaService.update(userId, id, request));
    }

    /**
     * 切换当前生效的角色（<b>会把其它角色取消激活</b>）。
     *
     * <pre>
     * PUT /api/persona/9/activate
     * → { "id": 9, "isActive": true, ... }
     * </pre>
     *
     * <p><b>★ 这是"同一时间只有一个角色生效"的唯一切换入口。</b>
     * 调用后该用户其它角色的 {@code is_active} 都会变成 0。
     *
     * @param userId 当前登录用户 id
     * @param id     要激活的角色 id
     */
    @PutMapping("/{id}/activate")
    public Result<PersonaVO> activate(@AuthenticationPrincipal Long userId,
                                      @PathVariable Long id) {
        return Result.success(personaService.activate(userId, id));
    }

    /**
     * 删除角色。
     *
     * <pre>
     * DELETE /api/persona/9
     * </pre>
     *
     * <p><b>★★ 删除正在使用的角色时，不会自动切换到别的角色</b> ——
     * 之后 {@code GET /api/persona/active} 会返回 null。
     * 理由：自动切换会让用户的 AI 伙伴<b>悄悄换了人格</b>，比"暂时没有角色"更困惑。
     * 前端应提示用户重新选择一个。
     *
     * @param userId 当前登录用户 id
     * @param id     角色 id
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal Long userId,
                               @PathVariable Long id) {
        personaService.delete(userId, id);
        return Result.success();
    }
}
