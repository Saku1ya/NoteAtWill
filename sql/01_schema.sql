-- ============================================================================
-- NotesAtWill 记账项目 · 建表脚本
-- ----------------------------------------------------------------------------
-- 目标数据库：MySQL 8.4 LTS
-- 字符集    ：utf8mb4 / utf8mb4_0900_ai_ci（MySQL 8.x 默认排序规则）
--
-- 全局约定：
--   1. 金额字段统一 DECIMAL(12,2)，禁止使用 FLOAT / DOUBLE
--   2. 业务表统一带逻辑删除字段 deleted（0 正常 / 1 已删除），查询必须带 deleted = 0
--   3. 时间字段统一 create_time / update_time，由数据库自动维护
--   4. 主键统一 BIGINT UNSIGNED 自增；系统预置数据占用 1000 以下的段位，
--      用户自建数据从自增序列获得，两者不会冲突
--   5. 本脚本可重复执行（全部 IF NOT EXISTS），且不会误删已有数据
--
-- 执行方式（三选一）：
--   a) docker compose up -d   → 容器首次初始化时自动执行 sql/ 目录下脚本
--   b) mysql -u root -p < sql/01_schema.sql
--   c) 在客户端工具（Navicat / DataGrip / Apifox 的 DB 工具）里整段执行
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `notes_at_will`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `notes_at_will`;


-- ============================================================================
-- 1. t_user 用户表
--
-- ★ 「手机号」是唯一的身份标识，两种登录方式都以它为准：
--   · 验证码登录  phone + code   （未注册时自动创建账号）
--   · 密码登录    phone + password
--   · email 退化为【可选的联系方式】，不参与登录、不要求唯一
--
-- ★ 唯一性约束只加在 phone 上。email 刻意不加唯一索引：
--   多个账号共用同一邮箱是允许的。
--   ⚠️ 该选择的代价：「用邮箱找回密码」无法实现（收到邮件的账号不止一个，
--      不知道该重置哪个）。若将来要做，需先强制 email 唯一并清理历史重复数据。
--   ✅ 但「忘记密码」本身仍可解 —— 走手机验证码即可（见下方 reset-password 说明），
--      手机号唯一且经过短信验证，是比邮箱更可靠的找回渠道。
--
-- ★ password 允许 NULL：验证码登录会自动注册，这类用户【没有密码】，
--   之后可自愿补设。禁止存明文，一律 BCrypt。
--
-- ★ 密码相关接口（2026-10-13 定稿）：
--   一个功能、两个入口，凭据都是短信验证码，不再索要原密码。
--   · POST /api/auth/reset-password 【匿名】忘记密码
--       用户未登录，token 不存在，身份只能来自请求体里的手机号。
--   · PUT  /api/user/password      【需登录】系统内修改密码
--       身份来自 token；其 DTO 刻意不含 phone 字段，
--       前端连"指向他人账号"这种表达都不存在，比运行期校验更彻底。
--   ⚠️ 匿名入口的实现顺序必须是「先验码、再查用户」，否则未注册与已注册手机号
--      会返回不同错误码，形成账号枚举漏洞。
--   ⚠️ 匿名入口【不自动注册】账号（与验证码登录不同）：忘记密码的前提是账号已存在。
--   为什么不用一个接口：两者凭据相同、Service 核心流程共用一份，
--      但「身份从哪来」不同（会话 vs 请求体）。若匿名接口也从请求体读手机号，
--      已登录用户就能在个人设置页指向别人的账号。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `t_user` (
    `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `phone`              VARCHAR(20)     NOT NULL                COMMENT '手机号（唯一身份标识）',
    `password`           VARCHAR(100)             DEFAULT NULL   COMMENT 'BCrypt 加密后的密码（禁止存明文）；验证码自动注册的用户为 NULL',
    `email`              VARCHAR(100)             DEFAULT NULL   COMMENT '邮箱（可选联系方式，不用于登录、不要求唯一）',
    `nickname`           VARCHAR(50)              DEFAULT NULL   COMMENT '昵称',
    `avatar`             VARCHAR(255)             DEFAULT NULL   COMMENT '头像地址',
    `status`             TINYINT         NOT NULL DEFAULT 1      COMMENT '账号状态：1 正常，0 禁用',
    `member_level`       TINYINT         NOT NULL DEFAULT 0      COMMENT '会员等级：0 普通，1 会员（二期双向聊天用，一期只预留）',
    `create_time`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';


-- ============================================================================
-- 2. t_book 账本表
--    多账本的实现核心：book_id 贯穿 t_bill / t_budget 两张业务表
-- ============================================================================
CREATE TABLE IF NOT EXISTS `t_book` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '归属用户',
    `name`        VARCHAR(50)     NOT NULL                COMMENT '账本名：日常 / 家庭 / 娱乐…',
    `icon`        VARCHAR(50)              DEFAULT NULL   COMMENT '图标标识（前端图标名，不存 emoji）',
    `color`       VARCHAR(20)              DEFAULT NULL   COMMENT '主题色',
    `is_default`  TINYINT         NOT NULL DEFAULT 0      COMMENT '是否默认账本：1 是。默认账本不可删除',
    `sort`        INT             NOT NULL DEFAULT 0      COMMENT '排序值，越小越靠前',
    `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_deleted` (`user_id`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账本表';


-- ============================================================================
-- 3. t_category 分类表（二级树）
--    user_id IS NULL  → 系统预置，只读，所有用户共享
--    user_id = 具体值 → 用户自建，可改可删
--
--    注意：这里刻意没有建 (user_id, parent_id, name) 唯一索引。
--    因为 MySQL 的唯一索引把 NULL 视为互不相等，系统预置分类（user_id = NULL）
--    根本挡不住重名。重名校验统一放在 Service 层做。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `t_category` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED          DEFAULT NULL   COMMENT '归属用户：NULL 表示系统预置只读分类',
    `parent_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '父分类 id：0 表示一级分类',
    `name`        VARCHAR(50)     NOT NULL                COMMENT '分类名',
    `icon`        VARCHAR(50)              DEFAULT NULL   COMMENT '图标标识',
    `type`        VARCHAR(10)     NOT NULL                COMMENT '收支类型：EXPENSE 支出，INCOME 收入。两棵树完全隔离',
    `sort`        INT             NOT NULL DEFAULT 0      COMMENT '排序值',
    `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_type` (`user_id`, `type`, `deleted`),
    KEY `idx_parent` (`parent_id`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '分类表（二级树，系统预置 + 用户自建）';


-- =============================================================================
-- 4. t_account 资金账户表（全局共享，不按账本隔离）
--
--    余额设计：
--      init_balance 期初余额 —— 用户建账户时填写的初始金额，此后永不改变
--      balance      当前余额 —— 冗余字段，避免每次查账户列表都做聚合
--
--    balance 只能由「资金流水服务」在写 t_account_flow 时同步维护，
--    禁止在其它 Service 里直接 update。这条纪律是账户数据不出错的前提。
--
--    对账公式（因为资金流水已独立成表，这个公式只需查一张表）：
--      balance == init_balance + SUM(方向 IN 的金额) - SUM(方向 OUT 的金额)
-- ============================================================================
CREATE TABLE IF NOT EXISTS `t_account` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`      BIGINT UNSIGNED NOT NULL                COMMENT '归属用户',
    `name`         VARCHAR(50)     NOT NULL                COMMENT '账户名：现金 / 微信零钱 / 支付宝 / 招商银行卡…',
    `type`         VARCHAR(20)     NOT NULL                COMMENT '账户类型：CASH / WECHAT / ALIPAY / BANK / OTHER',
    `init_balance` DECIMAL(12, 2)  NOT NULL DEFAULT 0.00   COMMENT '期初余额，建账户时填写，之后不变',
    `balance`      DECIMAL(12, 2)  NOT NULL DEFAULT 0.00   COMMENT '当前余额（冗余字段，只能由余额服务修改）',
    `icon`         VARCHAR(50)              DEFAULT NULL   COMMENT '图标标识',
    `sort`         INT             NOT NULL DEFAULT 0      COMMENT '排序值',
    `is_default`   TINYINT         NOT NULL DEFAULT 0      COMMENT '是否默认账户：1 是',
    `deleted`      TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_deleted` (`user_id`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '资金账户表';


-- ============================================================================
-- 5. 流水表（两张，职责分离）
--
--      5.1 t_bill          记账流水 —— "我花了什么 / 赚了什么"
--                                     带分类、带账本，是统计与预算的唯一数据源
--      5.2 t_account_flow  资金流水 —— "钱怎么动的"
--                                     带账户、带方向，是账户余额的唯一数据源
--
--    ★ 记账与账户是两个独立功能，由用户自主决定是否关联。
--      记账时【账户是选填的】：
--        不选账户 → 只写 t_bill，纯消费记录，不影响任何账户余额
--        选了账户 → 额外写一条 t_account_flow，扣减 / 增加该账户余额
--      界面上的表现就是：账单卡片只显示分类、金额、日期、备注；
--      输入栏的"资产账户"是个可选的选择器，不是必填项。
--
--    ★ 转账不在 t_bill 里。转账是"账户之间搬钱"，属于账户模块的操作，
--      不是消费行为。因此 t_bill 只有 EXPENSE / INCOME 两种类型，
--      category_id 恒为必填。统计天然不含转账，不需要额外写过滤条件来排除。
--
--    注意：只有"用户为记账选了账户"这一种情况才会同时写两张表，
--          此时必须在同一事务内完成；改、删账单也要同步处理对应的资金流水。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 5.1 t_bill 记账流水表（核心表）
--
--     只描述"我买了什么 / 赚了什么"，不存任何账户字段。
--     账户信息（如果用户选了的话）在 t_account_flow 里。
--
--     类型只有 EXPENSE / INCOME 两种，没有转账，所以 category_id 恒为必填。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_bill` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`       BIGINT UNSIGNED NOT NULL                COMMENT '归属用户（冗余字段，便于权限校验与索引）',
    `book_id`       BIGINT UNSIGNED NOT NULL                COMMENT '所属账本',
    `category_id`   BIGINT UNSIGNED NOT NULL                COMMENT '分类，必填。转账不属于记账，不会出现在本表',
    `type`          VARCHAR(10)     NOT NULL                COMMENT '类型：EXPENSE 支出 / INCOME 收入',
    `amount`        DECIMAL(12, 2)  NOT NULL                COMMENT '金额，恒为正数',
    `remark`        VARCHAR(255)             DEFAULT NULL   COMMENT '备注',
    `bill_time`     DATETIME        NOT NULL                COMMENT '记账时间（可与创建时间不同，支持补记）',
    `source`        VARCHAR(10)     NOT NULL DEFAULT 'MANUAL' COMMENT '来源：MANUAL 手动 / AI 智能识别 / IMPORT 账单导入（二期预留）',
    `ai_log_id`     BIGINT UNSIGNED          DEFAULT NULL   COMMENT '关联的 AI 解析记录，便于追溯',
    `deleted`       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_book_time` (`user_id`, `book_id`, `deleted`, `bill_time`),
    KEY `idx_category_time` (`user_id`, `category_id`, `deleted`, `bill_time`),
    KEY `idx_ai_log` (`ai_log_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '记账流水表';


-- ----------------------------------------------------------------------------
-- 5.2 t_account_flow 账户资金流水表
--
--     只描述"钱怎么动的"，不带分类、不带账本。
--     amount 恒为正数，方向由 direction 决定：IN 转入，OUT 转出。
--
--     三种来源，由 flow_type 区分：
--       BILL      用户在记账时选了账户 → 自动产生一条流水，bill_id 指向该账单
--       TRANSFER  用户在账户模块做转账 → 产生【两条】流水（转出 OUT + 转入 IN），
--                 两条共享同一个 transfer_no，删除时据此把两条一起删
--       ADJUST    用户手动校正账户余额 → 既不关联账单也不关联转账
--
--     关于 bill_id 为什么【不能建唯一索引】：
--       1. 转账与手动调整都没有对应账单，bill_id 为 NULL。MySQL 唯一索引把多行
--          NULL 视为互不相等，起不到约束作用。
--       2. 表里有逻辑删除字段，唯一索引会在"删除后重建"时误报冲突。
--       "一笔账单最多对应一条资金流水"这个约束，由 Service 层保证。
--
--     账户余额的对账公式就建立在这张表上：
--       balance == init_balance + SUM(IN 的金额) - SUM(OUT 的金额)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_account_flow` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '归属用户（冗余字段，便于权限校验与索引）',
    `account_id`  BIGINT UNSIGNED NOT NULL                COMMENT '发生变动的账户',
    `flow_type`   VARCHAR(10)     NOT NULL                COMMENT '来源：BILL 记账产生 / TRANSFER 转账 / ADJUST 手动调整',
    `bill_id`     BIGINT UNSIGNED          DEFAULT NULL   COMMENT '关联的记账记录，仅 BILL 类型有值',
    `transfer_no` VARCHAR(32)              DEFAULT NULL   COMMENT '转账批次号，仅 TRANSFER 类型有值；一次转账的两条流水共享',
    `direction`   VARCHAR(3)      NOT NULL                COMMENT '方向：IN 转入，OUT 转出',
    `amount`      DECIMAL(12, 2)  NOT NULL                COMMENT '变动金额，恒为正数，方向看 direction',
    `flow_time`   DATETIME        NOT NULL                COMMENT '资金变动时间；BILL 类型与 t_bill.bill_time 一致',
    `remark`      VARCHAR(255)             DEFAULT NULL   COMMENT '备注，转账时可记"银行卡 转 微信零钱"',
    `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_account_time` (`user_id`, `account_id`, `deleted`, `flow_time`),
    KEY `idx_bill` (`bill_id`),
    KEY `idx_transfer` (`transfer_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账户资金流水表';


-- ============================================================================
-- 7. t_budget 月度预算表
--
--    ★ 一个账本一个月一条预算，周期就是自然月，不再有"周预算 / 年预算"的概念，
--      所以这里【没有 period_type 字段】——一个恒等于 MONTH 的字段没有表达力，
--      只会让读代码的人反复确认它到底有没有别的取值。
--
--    category_id IS NULL → 账本级预算（整个账本这个月多少钱）
--    category_id 有值    → 该分类这个月的预算
--
--    时间范围一律用 bill_time 落在自然月内来界定：
--      [当月 1 日 00:00:00, 次月 1 日 00:00:00)
--    这样界定的好处是能直接映射到日历图表：一个月一张网格，
--    每天一格的消费额累加成月合计，正好对上预算消耗进度。
--
--    剩余额度 = 额度 - 本月已消费合计，【每次查询实时聚合】。
--    绝不落库扣减：一旦用户改了历史账单，扣减值就对不上，还要写一整套回滚逻辑。
--
--    同样因为 NULL 的问题，(book_id, category_id) 唯一性在 Service 层校验，
--    不建唯一索引（category_id 为 NULL 的账本级预算挡不住重复建）。
--
--    如果将来要做周预算 / 年预算，加回 period_type 并调整聚合口径即可。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `t_budget` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         BIGINT UNSIGNED NOT NULL                COMMENT '归属用户',
    `book_id`         BIGINT UNSIGNED NOT NULL                COMMENT '所属账本',
    `category_id`     BIGINT UNSIGNED          DEFAULT NULL   COMMENT '分类：NULL 表示账本级月度预算',
    `amount`          DECIMAL(12, 2)  NOT NULL                COMMENT '每月额度',
    `alert_threshold` INT             NOT NULL DEFAULT 80     COMMENT '预警阈值百分比，默认 80 表示用到 80% 就提醒',
    `deleted`         TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_book` (`user_id`, `book_id`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '月度预算表';


-- ============================================================================
-- 8. t_ai_parse_log AI 解析记录表
--
--    价值：记录每一次 AI 解析的原始输入、模型返回、token 消耗、用户是否采纳、
--    用户修正了什么。这既是排查问题的依据，也是后续优化提示词的语料来源。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `t_ai_parse_log` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`           BIGINT UNSIGNED NOT NULL                COMMENT '归属用户',
    `raw_text`          TEXT            NOT NULL                COMMENT '用户原始输入文本',
    `model_name`        VARCHAR(50)              DEFAULT NULL   COMMENT '实际调用的大模型名',
    `prompt_tokens`     INT                      DEFAULT NULL   COMMENT '输入 token 数（成本统计用）',
    `completion_tokens` INT                      DEFAULT NULL   COMMENT '输出 token 数（成本统计用）',
    `result_json`       JSON                     DEFAULT NULL   COMMENT '大模型返回的原始结构',
    `parsed_count`      INT             NOT NULL DEFAULT 0      COMMENT '解析出的笔数',
    `status`            VARCHAR(10)     NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待确认 / CONFIRMED 已采纳 / REJECTED 已放弃',
    `corrected_json`    JSON                     DEFAULT NULL   COMMENT '用户修正后的结果，用于优化提示词',
    `cost_ms`           INT                      DEFAULT NULL   COMMENT '本次调用耗时（毫秒）',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 解析记录表';


-- ============================================================================
-- 9. t_persona_preset AI 角色预设表（系统内置，只读）
--    用户选中某个预设后，会复制一份到 t_ai_persona 再修改，
--    与 t_category 的系统预置数据是同一套思路：系统预设永远不会被改坏。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `t_persona_preset` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(20)     NOT NULL                COMMENT '角色名字',
    `avatar`      VARCHAR(255)             DEFAULT NULL   COMMENT '角色头像地址',
    `gender`      VARCHAR(10)     NOT NULL DEFAULT 'OTHER' COMMENT '性别：MALE / FEMALE / OTHER',
    `relation`    VARCHAR(20)              DEFAULT NULL   COMMENT '与用户的关系：朋友 / 家人 / 管家…',
    `address`     VARCHAR(20)              DEFAULT NULL   COMMENT '对用户的称呼：主人 / 老板…',
    `personality` VARCHAR(200)             DEFAULT NULL   COMMENT '角色设定（性格描述）',
    `sort`        INT             NOT NULL DEFAULT 0      COMMENT '排序值',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 角色预设表（系统内置只读）';


-- ============================================================================
-- 10. t_ai_persona 用户 AI 角色设定表
--    这五个字段（name / gender / relation / address / personality）
--    会直接拼进系统提示词，是所有 AI 输出的语气来源。
--    提示词模板建议固化为常量并写单元测试，防止后续误改导致人设崩坏。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `t_ai_persona` (
    `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`            BIGINT UNSIGNED NOT NULL                COMMENT '归属用户',
    `preset_id`          BIGINT UNSIGNED          DEFAULT NULL   COMMENT '基于哪个预设修改而来',
    `avatar`             VARCHAR(255)             DEFAULT NULL   COMMENT '角色头像',
    `name`               VARCHAR(20)     NOT NULL                COMMENT '角色名字（界面限制 20 字）',
    `gender`             VARCHAR(10)     NOT NULL DEFAULT 'OTHER' COMMENT '性别：MALE / FEMALE / OTHER',
    `relation`           VARCHAR(20)              DEFAULT NULL   COMMENT '与用户的关系',
    `address`            VARCHAR(20)              DEFAULT NULL   COMMENT '对用户的称呼',
    `personality`        VARCHAR(200)             DEFAULT NULL   COMMENT '角色设定（性格描述）',
    `chat_reply_enabled` TINYINT         NOT NULL DEFAULT 0      COMMENT '记账后 AI 是否回复：1 回复，0 不回复。默认 0',
    `is_active`          TINYINT         NOT NULL DEFAULT 1      COMMENT '当前启用：1 启用。一期只允许一个启用',
    `deleted`            TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户 AI 角色设定表';


-- ============================================================================
-- 11. t_chat_message 对话消息表
--
--    关键设计：账单卡片【只存 bill_id，不存金额快照】。
--    因为账单与卡片要求双向级联删除，存快照必然导致两边数据打架；
--    只存引用则卡片永远是账单的实时投影，账单改了卡片跟着变。
--
--    字段名用 trigger_type 而不是 trigger：TRIGGER 是 MySQL 保留字。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `t_chat_message` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`      BIGINT UNSIGNED NOT NULL                COMMENT '归属用户',
    `book_id`      BIGINT UNSIGNED          DEFAULT NULL   COMMENT '统计卡片记录当时的账本口径',
    `role`         VARCHAR(10)     NOT NULL                COMMENT '发送方：USER / AI（预留 SYSTEM）',
    `type`         VARCHAR(20)     NOT NULL                COMMENT '消息类型：TEXT / BILL_CARD / STAT_CARD / GREETING',
    `content`      TEXT                      DEFAULT NULL   COMMENT '文本内容',
    `bill_id`      BIGINT UNSIGNED          DEFAULT NULL   COMMENT 'BILL_CARD 时指向账单，不存金额快照',
    `meta_json`    JSON                      DEFAULT NULL   COMMENT '卡片附加信息（统计卡片的数字、账本名、统计时间等）',
    `trigger_type` VARCHAR(20)               DEFAULT NULL   COMMENT '触发来源：MANUAL_BILL / AI_PARSE / DAILY_OPEN',
    `deleted`      TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消息时间，前端据此做时间分隔',
    PRIMARY KEY (`id`),
    KEY `idx_user_msg` (`user_id`, `deleted`, `id`),
    KEY `idx_bill` (`bill_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 对话消息表';


-- ============================================================================
-- 12. t_daily_greeting 每日问候记录表
--
--    uk_user_date 唯一索引是「每天只发一次」的【唯一可靠保证】。
--    刷新页面、开多个标签页、并发请求都会重复触发，
--    应用层「先查后插」在并发下必然漏；唯一索引冲突则一定拦得住。
--    冲突时直接跳过，【并且不调用大模型】（既防重复又省钱）。
--
--    时间分隔（截图里那些 18:37、01:06）由前端按相邻消息时间差计算，不落库。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `t_daily_greeting` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '归属用户',
    `greet_date`  DATE            NOT NULL                COMMENT '问候日期',
    `time_bucket` VARCHAR(10)              DEFAULT NULL   COMMENT '时段：DAWN / MORNING / FORENOON / NOON / AFTERNOON / EVENING / NIGHT',
    `content`     TEXT                     DEFAULT NULL   COMMENT '生成的问候语',
    `model_name`  VARCHAR(50)              DEFAULT NULL   COMMENT '生成用的模型名',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_date` (`user_id`, `greet_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '每日问候记录表（唯一索引保证每天只发一次）';
