package com.sakuya.notesatwill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NotesAtWill 启动类。
 *
 * <p>{@code @MapperScan} 指定 Mapper 接口所在包。
 * <b>这个注解不能省</b>——MyBatis-Plus 的 Boot4 starter 不会自动扫描任意包下的 Mapper，
 * 缺了它启动时会报 {@code No qualifying bean of type 'XxxMapper' available}，
 * 或者只在日志里给一句 "No MyBatis mapper was found" 的 WARN。
 *
 * @author sakuya
 */
@SpringBootApplication
@MapperScan("com.sakuya.notesatwill.mapper")
public class NotesAtWillApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotesAtWillApplication.class, args);
    }

}



















