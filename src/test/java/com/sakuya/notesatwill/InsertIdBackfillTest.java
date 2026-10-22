package com.sakuya.notesatwill;

import com.sakuya.notesatwill.entity.User;
import com.sakuya.notesatwill.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 临时验证：确认 MyBatis-Plus 在 insert 后会自动回填自增主键。
 * 验证完可删除。
 */
@SpringBootTest
class InsertIdBackfillTest {

    private static final String TEST_PHONE = "13900000001";

    @Autowired
    private UserMapper userMapper;

    @Test
    void checkIdBackfill() {
        // 清理可能残留的测试数据
        userMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                .eq(User::getPhone, TEST_PHONE));

        User user = new User();
        user.setPhone(TEST_PHONE);
        user.setNickname("临时测试");
        user.setStatus(1);
        user.setMemberLevel(0);

        System.out.println("插入前：user.getId() = " + user.getId());

        userMapper.insert(user);

        System.out.println("插入后：user.getId() = " + user.getId());

        assertNotNull(user.getId(), "MP 应自动回填自增主键，无需再查一次");

        // 清理
        userMapper.deleteById(user.getId());
        System.out.println("测试数据已清理 ✓");
    }
}
