package com.sakuya.notesatwill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sakuya.notesatwill.common.enums.ErrorCode;
import com.sakuya.notesatwill.common.exception.BusinessException;
import com.sakuya.notesatwill.entity.Book;
import com.sakuya.notesatwill.entity.DTO.BookRequest;
import com.sakuya.notesatwill.mapper.BookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 账本服务。
 *
 * <p><b>作用域纪律</b>：账本归属用户，<b>所有查询都必须带 {@code userId} 条件</b>。
 * 这一点与分类 / 账户不同（那两者全局共享），账本是真正的私有数据。
 *
 * <p><b>关于逻辑删除</b>：{@code Book.deleted} 上有 {@code @TableLogic}，
 * 配合 {@code application.yaml} 的 {@code logic-delete-field}，
 * MP 会自动完成两件事：
 * <ul>
 *   <li>所有查询自动追加 {@code AND deleted = 0}</li>
 *   <li>{@code deleteById} 实际执行 {@code UPDATE ... SET deleted = 1}</li>
 * </ul>
 * 所以本类<b>不需要</b>手写 {@code .eq(Book::getDeleted, 0)}，写了反而冗赘。
 *
 * @author sakuya
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookService {

    private final BookMapper bookMapper;
    /** {@code is_default} 字段取值：1 是默认账本 */
    private static final int IS_DEFAULT = 1;
    /** {@code is_default} 字段取值：0 非默认账本 */
    private static final int NOT_DEFAULT = 0;
    /**
     * 查询指定用户的账本列表。
     *
     * <p><b>排序规则（三段式，顺序不能乱）</b>：
     * <ol>
     *   <li>默认账本置顶：{@code ORDER BY is_default DESC}</li>
     *   <li>再按 {@code sort} 升序</li>
     *   <li>最后按 {@code id} 升序 —— <b>这一项是为了"顺序稳定"</b>：
     *       {@code sort} 相同的账本若不加 tie-breaker，
     *       MySQL 的返回顺序不保证稳定，前端列表会出现"刷新一次换个位置"</li>
     * </ol>
     *
     * @param userId 当前登录用户 id（来自 token）
     * @return 该用户的账本列表，默认账本在最前
     */
    public List<Book> list(Long userId) {
        // 空列表是【正常结果】而非异常：新用户可能还没有账本，
        // 前端据此渲染"暂无账本"的空状态。
        // 这里刻意不抛异常 —— 一旦抛了，调用本方法的 nextSort() 也会跟着崩，
        // 导致"用户建第一个账本时失败"这种连锁故障。
        return bookMapper.selectList(
                new LambdaQueryWrapper<Book>()
                        .eq(Book::getUserId, userId)
                        .orderByDesc(Book::getIsDefault)
                        .orderByAsc(Book::getSort)
                        .orderByAsc(Book::getId)
        );
    }

    /**
     * 按 id 查询账本，<b>并校验归属当前用户</b>。
     *
     * <p><b>★ 实现要点：把 {@code userId} 写进 where 条件，而不是查出来再 if 比较。</b>
     * <pre>
     *   bookMapper.selectOne(new LambdaQueryWrapper&lt;Book&gt;()
     *           .eq(Book::getId, bookId)
     *           .eq(Book::getUserId, userId));
     * @param userId 当前登录用户 id（来自 token）
     * @param bookId 账本 id
     * @return 该用户拥有的账本实体
     * @throws BusinessException 账本不存在或不属于该用户（{@code BOOK_NOT_FOUND}）
     */
    public Book getOwned(Long userId, Long bookId) {
        if (userId == null || bookId == null) {
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }

        Book book = bookMapper.selectOne(
                new LambdaQueryWrapper<Book>()
                        .eq(Book::getId, bookId)
                        .eq(Book::getUserId, userId)
        );

        // 走到这里 book 为 null 说明"不存在"或"不属于该用户"，
        if (book == null) {
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }
        return book;
    }


    /**
     * 新建账本。
     *
     * <p><b>{@code isDefault} 固定为 {@link #NOT_DEFAULT}</b>：
     * 用户不能通过接口把自己新建的账本设为默认。否则可先提升新账本为默认、
     * 再删掉原默认账本，绕过"默认账本不可删除"的保护，
     * 甚至让库里出现两个 {@code is_default = 1} 的账本。
     *
     * <p><b>排序值缺省处理</b>：{@code request.sort()} 为 null 时，
     * 取"当前最大 sort + 1"，把新账本追加到末尾 —— 否则 null 会落到 0，
     * 与默认账本的 sort 撞车，列表顺序变得不可预期。
     *
     * <p>插入后无需再查一次：MP 会把自增主键回填到入参对象的 id 上
     * （项目里已有 {@code InsertIdBackfillTest} 验证过这一点）。
     *
     * @param userId  当前登录用户 id（来自 token，<b>不接受前端传入</b>）
     * @param request 账本信息
     * @return 已回填 id 的账本实体
     */
    public Book create(Long userId, BookRequest request) {
        //   1. new Book()，set userId / name / icon / color
        //   2. setIsDefault(NOT_DEFAULT)
        //   3. sort：request.sort() != null ? 取传入值 : 调 nextSort(userId)
        //   4. bookMapper.insert(book) 后直接 return book
        Book book = new Book();
        book.setUserId(userId);
        book.setName(request.name());
        book.setIcon(request.icon());
        book.setColor(request.color());
        book.setIsDefault(NOT_DEFAULT);
        book.setSort(request.sort() != null ? request.sort() : nextSort(userId));

        bookMapper.insert(book);

        return book;
    }

    /**
     * 修改账本（只能改名称、图标、颜色、排序）。
     *
     * <p><b>为什么先调 {@link #getOwned} 而不是直接 updateById</b>：
     * {@code updateById} 只按主键匹配，若不做归属校验，
     * 任何人都能改别人的账本名 —— 越权写漏洞比越权读更严重。
     *
     * <p><b>实现方式：只 set 要改的字段，不整个覆盖。</b>
     * 因为 {@code application.yaml} 配了 {@code update-strategy: not_null}，
     * 未 set 的字段不会出现在 SQL 里，天然实现"部分更新"。
     * 这正是"不要直接收实体、也不要把查出来的对象整个 update"的原因 ——
     * 否则 {@code isDefault} / {@code userId} 会被一并写回，产生状态错乱。
     *
     * <p>返回最新的实体：前端改完可直接用返回值刷新列表，省一次查询。
     *
     * @param userId  当前登录用户 id
     * @param bookId  要修改的账本 id
     * @param request 新的账本信息
     * @return 修改后的账本实体
     * @throws BusinessException 账本不存在或不属于该用户
     */
    public Book update(Long userId, Long bookId, BookRequest request) {
        // ① 校验归属。利用getOwned方法判断是否归属本人
        getOwned(userId, bookId);

        Book update = new Book();
        update.setId(bookId);
        update.setName(request.name());
        update.setIcon(request.icon());
        update.setColor(request.color());
        // sort 仅在显式传入时才更新，否则保持原值不动
        if (request.sort() != null) {
            update.setSort(request.sort());
        }

        bookMapper.updateById(update);

        // ③ 返回最新数据，前端可直接用它刷新列表，省一次额外查询
        return getOwned(userId, bookId);
    }

    /**
     * 删除账本（逻辑删除）。
     *
     * <p><b>两条业务规则
     * <ol>
     *   <li><b>默认账本不可删</b>
     *   <li><b>至少保留一个账本</b>
     * </ol>
     *
     * <p><b>⚠ TODO（等 D2 建好 BillMapper 后补）</b>：
     * 账本下还有账单时应拒绝删除（{@link ErrorCode#BOOK_HAS_BILLS} 已预留 3002）。
     * 现在 {@code t_bill} 还没有 Mapper，无法查询，先留这个位置。
     *
     * <p>删除调用 {@code deleteById}，由 {@code @TableLogic} 自动转成
     * {@code UPDATE t_book SET deleted = 1}，不会真删数据 ——
     * 历史账单要能追溯，账本名也要能显示。
     *
     * @param userId 当前登录用户 id
     * @param bookId 要删除的账本 id
     * @throws BusinessException 账本不存在/不属于该用户；是默认账本；是最后一个账本
     */
    public void delete(Long userId, Long bookId) {
        // 先校验归属。
        Book book = getOwned(userId, bookId);

        // 默认账本不可删：否则用户会失去"记一笔账"的落点。
        if (book.getIsDefault() != null && book.getIsDefault() == IS_DEFAULT) {
            throw new BusinessException(ErrorCode.BOOK_DEFAULT_CANNOT_DELETE);
        }

        // 至少保留一个账本：允许删到 0 个的话，记账页会没有账本可选，
        Long remaining = bookMapper.selectCount(
                new LambdaQueryWrapper<Book>().eq(Book::getUserId, userId)
        );
        if (remaining != null && remaining <= 1) {
            throw new BusinessException(ErrorCode.BOOK_MIN_ONE_REQUIRED);
        }

        // 逻辑删除：@TableLogic 会把 deleteById 改写成
        // UPDATE t_book SET deleted = 1 WHERE id = ? AND deleted = 0
        bookMapper.deleteById(book.getId());
        log.info("删除账本：userId={}, bookId={}, name={}", userId, book.getId(), book.getName());
    }


    /**
     * 为用户创建默认账本（供注册流程调用）。
     * @param userId 新注册用户 id
     * @return 已回填 id 的默认账本
     */
    public Book createDefaultBook(Long userId) {
        Book book = new Book();
        book.setUserId(userId);
        book.setName("默认账本");
        book.setIcon("wallet");
        book.setColor("#409EFF");
        book.setIsDefault(IS_DEFAULT);
        book.setSort(0);
        bookMapper.insert(book);
        return book;
    }

    /**
     * 计算下一个排序值 = 当前最大 sort + 1。
     *
     * <p>空列表时返回 0（{@code orElse(-1) + 1}）。
     * 若列表中存在 {@code sort} 为 null 的脏数据，用 {@code filter} 跳过，
     * 避免 {@code NullPointerException}。
     */
    private int nextSort(Long userId) {
        List<Book> books = list(userId);
        return books.stream()
                .map(Book::getSort)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(-1) + 1;
    }
}
