package com.sakuya.notesatwill.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 3 序列化配置。
 *
 * <p><b>为什么必须有这个类（而不是只写 yaml）</b>：
 * {@code spring.jackson.date-format} 只作用于 {@code java.util.Date}，
 * <b>对 {@code LocalDateTime} / {@code LocalDate} 完全无效</b>。
 * 本项目实体全部用 Java 8 时间类型，所以时间格式必须在这里配。
 *
 * <p><b>关于 Jackson 3 的三个变更（实测确认，别照抄 Boot 3 教程）</b>：
 * <ol>
 *   <li><b>JavaTime 支持已内置进 databind</b>
 *       （{@code tools.jackson.databind.ext.javatime}），
 *       不再需要 {@code jackson-datatype-jsr310} 依赖，也没有 {@code JavaTimeModule} 可加
 *       ——它由 {@code JavaTimeInitializer} 自动注册。
 *       好在其序列化器/反序列化器都提供了接收 {@code DateTimeFormatter} 的公共构造器，
 *       直接 new 出来注册即可，不用自己实现。</li>
 *   <li><b>{@code WRITE_DATES_AS_TIMESTAMPS} 搬家了</b>：从
 *       {@code SerializationFeature} 迁到了
 *       {@code tools.jackson.databind.cfg.DateTimeFeature}。
 *       而 Spring Boot 4 的 {@code JacksonProperties} <b>没有暴露 datetime 前缀</b>，
 *       所以 yaml 里写 {@code serialization.write-dates-as-timestamps} 会启动报错：
 *       <pre>无法解析枚举常量 'write-dates-as-timestamps'</pre>
 *       好在 Jackson 3 默认就不写时间戳，这个开关根本不用配。</li>
 *   <li><b>包名只迁了一半</b>：类在 {@code tools.jackson.*}，
 *       但注解仍在 {@code com.fasterxml.jackson.annotation.*}
 *       （{@code @JsonInclude} 等导入路径不变）。</li>
 * </ol>
 *
 * @author sakuya
 */
@Slf4j
@Configuration
public class JacksonConfig {

    /** 日期时间格式：2026-10-10 13:45:00 */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 日期格式：2026-10-10 */
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    /** 时间格式：13:45:00 */
    public static final String TIME_PATTERN = "HH:mm:ss";

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern(DATE_PATTERN);
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern(TIME_PATTERN);

    /**
     * 定制 JsonMapper。
     *
     * <p>注意接口是 {@code JsonMapperBuilderCustomizer}（Boot 4 的），
     * <b>不是</b> Boot 3 的 {@code Jackson2ObjectMapperBuilderCustomizer}——后者在 Boot 4 已不存在。
     *
     * <p>注册顺序说明：这里注册的序列化器优先级高于 Jackson 内置的，
     * 所以能覆盖掉默认的 ISO-8601 输出。如果某个字段需要用别的格式，
     * 在字段上加 {@code @JsonFormat(pattern = "...")} 单独覆盖即可。
     */
    @Bean
    public JsonMapperBuilderCustomizer notesAtWillJacksonCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("NotesAtWillModule");

            // 序列化：Java 对象 -> JSON 字符串
            module.addSerializer(LocalDateTime.class,
                    new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
            module.addSerializer(LocalDate.class,
                    new LocalDateSerializer(DATE_FORMATTER));
            module.addSerializer(LocalTime.class,
                    new LocalTimeSerializer(TIME_FORMATTER));

            // 反序列化：JSON 字符串 -> Java 对象
            module.addDeserializer(LocalDateTime.class,
                    new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
            module.addDeserializer(LocalDate.class,
                    new LocalDateDeserializer(DATE_FORMATTER));

            builder.addModule(module);

            log.info("Jackson 3 时间格式已定制：LocalDateTime={}，LocalDate={}，LocalTime={}",
                    DATE_TIME_PATTERN, DATE_PATTERN, TIME_PATTERN);
        };
    }
}
