package com.sakuya.notesatwill.controller;

import com.sakuya.notesatwill.common.result.Result;
import com.sakuya.notesatwill.entity.Book;
import com.sakuya.notesatwill.entity.DTO.BookRequest;
import com.sakuya.notesatwill.service.BookService;
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
 * 账本接口。
 *
 * <p><b>本类所有接口都需要登录</b>，无需在 {@code SecurityConfig} 里逐个放行：
 * 它们都属于 {@code anyRequest().authenticated()}，未登录访问会由
 * {@code authenticationEntryPoint} 统一返回 {@code Result} 结构的 401（错误码 2002）。
 *
 * <p><b>★ 身份一律用 {@code @AuthenticationPrincipal Long userId} 取，绝不接受前端传入。</b>
 * {@code JwtAuthenticationFilter} 已把 userId 直接作为 principal 放进
 * {@code UsernamePasswordAuthenticationToken}，所以这里声明成 {@code Long} 即可拿到，
 * 不必去 {@code SecurityContextHolder} 手动取再转型。
 *
 * <p>反面写法（切勿使用）：{@code @PathVariable Long userId} 或从请求体读 userId，
 * 那等于允许前端指定"改谁的账本"，是最典型的越权漏洞。
 *
 * <p><b>统一响应纪律</b>：业务失败一律 HTTP 200 + {@code Result.code}，
 * 因此本类<b>不加任何 {@code @ResponseStatus}</b>；
 * {@code BusinessException} 直接抛出，交给 {@code GlobalExceptionHandler}。
 *
 * @author sakuya
 */
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;

    /**
     * 查询当前用户的账本列表。
     *
     * <p>默认账本排在最前（Service 层负责排序）。
     * 前端记账页的账本选择器直接消费本接口。
     *
     * @param userId 当前登录用户 id（来自 token）
     */
    @GetMapping
    public Result<List<Book>> list(@AuthenticationPrincipal Long userId) {
        return Result.success(bookService.list(userId));
    }

    /**
     * 新建账本。
     *
     * <p>{@code @Valid} 会触发 {@link BookRequest} 上的校验注解，
     * 校验失败由 {@code GlobalExceptionHandler#handleMethodArgumentNotValid}
     * 转成错误码 1000，不会出现 500。
     *
     * <p>注意响应是 {@code Result<Book>}：返回新建的账本（含回填的 id），
     * 前端可直接插入列表，不必重新拉一次。
     *
     * @param userId  当前登录用户 id（来自 token，不接受前端传入）
     * @param request 账本信息（不含 userId / isDefault）
     */
    @PostMapping
    public Result<Book> create(@AuthenticationPrincipal Long userId,
                               @Valid @RequestBody BookRequest request) {
        return Result.success(bookService.create(userId, request));
    }

    /**
     * 修改账本。
     *
     * <p>路径里的 {@code id} 是"要改哪个账本"，
     * 而"这个账本是不是你的"由 Service 层用 token 里的 userId 校验 ——
     * 两者职责不同，不要混为一谈。
     *
     * @param userId  当前登录用户 id（来自 token）
     * @param id      账本 id
     * @param request 新的账本信息
     */
    @PutMapping("/{id}")
    public Result<Book> update(@AuthenticationPrincipal Long userId,
                               @PathVariable Long id,
                               @Valid @RequestBody BookRequest request) {
        return Result.success(bookService.update(userId, id, request));
    }

    /**
     * 删除账本（逻辑删除）。
     *
     * <p>返回 {@code Result<Void>}：没有需要回传的数据。
     * {@code Result.success()} 的 data 为 null，
     * 而 {@code Result} 上有 {@code @JsonInclude(NON_NULL)}，data 字段不会出现在 JSON 里。
     *
     * <p>业务拒绝（默认账本、最后一个账本）由 Service 抛 {@code BusinessException}，
     * 前端按 {@code Result.code} 提示，HTTP 状态仍是 200。
     *
     * @param userId 当前登录用户 id（来自 token）
     * @param id     要删除的账本 id
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal Long userId,
                               @PathVariable Long id) {
        bookService.delete(userId, id);
        // Result.success() 的 data 为 null，配合 @JsonInclude(NON_NULL)
        // 响应体里不会出现 data 字段
        return Result.success();
    }
}
