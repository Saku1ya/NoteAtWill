# NotesAtWill（随心记）

> 说一句话就能记账 —— AI 把它拆成结构化账单，你逐笔确认后入库。

一个**全栈**的个人记账平台：多账本、资金账户与转账、月度预算、统计报表，外加一个会用角色化口吻跟你聊天、每天主动打招呼的 AI 伙伴。

**三端构成**

| 端 | 技术栈 | 职责 |
|---|---|---|
| 主服务 | Spring Boot 4.1 / Java 21 / MyBatis-Plus | 认证、账务、预算、统计 —— **数据的唯一入口** |
| AI 服务 | Python FastAPI | 自然语言解析、消费点评、角色对话 —— **无状态，不碰数据库** |
| 前端 | Vue 3 / TypeScript / Pinia | 记账台、账单、预算、统计、AI 对话流 |

**几个和常见记账项目不一样的地方**

- **金额不交给 AI 算** —— 模型只做「一句话 → 结构化字段」的抽取，所有聚合都由 SQL 完成
- **AI 解析绝不直接落库** —— 必须经前端逐笔预览、用户确认后才批量入库
- **余额是派生值** —— 只能由资金流水服务写入，对账公式 `balance = init + ΣIN − ΣOUT`，天生可校验

> 更多设计取舍见下方[核心设计](#核心设计)。

---

## 目录

- [技术栈](#技术栈)
- [架构](#架构)
- [快速开始](#快速开始)
- [环境变量](#环境变量)
- [接口概览](#接口概览)
- [核心设计](#核心设计)
- [项目结构](#项目结构)
- [测试](#测试)
- [常见问题](#常见问题)

---

## 技术栈

### Java 侧（主服务）

| 技术 | 版本 | 用途 |
|---|---|---|
| Spring Boot | **4.1.1** | Web / 依赖注入 |
| Java | **21** | 语言版本 |
| MyBatis-Plus | 3.5.17 | ORM（`spring-boot4-starter` + `jsqlparser`） |
| MySQL | 8.4 LTS | 主存储 |
| Redis | 8 | 验证码 / token 黑名单 / 统计缓存 |
| Spring Security | 7 | JWT 无状态鉴权 |
| JJWT | 0.13.0 | token 签发与校验 |
| Jackson | 3（`tools.jackson.*`） | JSON 序列化 |

### Python 侧（AI 服务）

| 技术 | 版本 | 用途 |
|---|---|---|
| FastAPI | 0.143.3 | HTTP 服务 + 自动 OpenAPI |
| Pydantic | 2.14.1 | 结构化校验 |
| OpenAI SDK | 3.33.0 | 调 DeepSeek（OpenAI 兼容接口） |
| Python | 3.14 | 语言版本 |

**AI 服务是无状态的**：不连数据库、不持有业务数据，只接收「文本 + 候选集」、返回「结构化草稿」。

---

## 架构

```
┌──────────────────┐
│  前端（Vue 3）   │
└────────┬─────────┘
         │ HTTP / JSON / SSE
         ▼
┌──────────────────────────────────────────┐
│  Spring Boot（8080）                     │
│  ┌────────────────────────────────────┐  │
│  │ Controller（54 个端点）            │  │
│  ├────────────────────────────────────┤  │
│  │ Service                            │  │
│  │  Account / Bill / Budget / Stats   │  │
│  │  Ai / Chat / Greeting / Persona    │  │
│  ├────────────────────────────────────┤  │
│  │ Mapper（MyBatis-Plus）             │  │
│  └────────────────────────────────────┘  │
└───────┬───────────────────┬──────────────┘
        │                   │
        ▼                   ▼
   ┌─────────┐      ┌──────────────┐
   │ MySQL   │      │ Redis        │
   │ 12 张表 │      │ 缓存/黑名单  │
   └─────────┘      └──────────────┘
                            ▲
                            │
                    ┌───────┴────────┐
                    │ FastAPI（8000）│
                    │ 无状态 AI 服务 │
                    └───────┬────────┘
                            ▼
                     ┌────────────┐
                     │  DeepSeek  │
                     └────────────┘
```

### 三条铁律

1. **AI 服务不连数据库** —— 只做"文本 → 结构化数据"，落库和权限校验全在 Spring Boot。
2. **AI 解析禁止直接落库** —— 必须走「解析 → 前端预览 → 用户确认 → 批量落库」。
3. **余额只能由 `AccountFlowService` 修改** —— `balance` 是派生缓存，唯一真相是流水。

---

## 快速开始

### 前置要求

- JDK 21
- Docker（用于 MySQL / Redis）
- Python 3.14（仅 AI 功能需要）

### 步骤

**1. 准备环境变量**

```bash
# 项目根目录创建 .env
MYSQL_ROOT_PASSWORD=123456
REDIS_PASSWORD=redis123456
```

**2. 启动 MySQL 与 Redis**

```bash
docker compose up -d
```

首次启动会自动执行 `sql/01_schema.sql` 与 `sql/02_seed.sql`（12 张表 + 72 条分类 + 5 个角色预设）。

> 若容器已存在但数据为空，手动执行：
> ```bash
> docker exec -i notesatwill-mysql mysql -uroot -p123456 < sql/01_schema.sql
> docker exec -i notesatwill-mysql mysql -uroot -p123456 < sql/02_seed.sql
> ```
> 两个脚本**可重复执行**（全 `IF NOT EXISTS` / 幂等插入），不会破坏已有数据。

**3. 启动 Java 服务**

```bash
./mvnw spring-boot:run
```

访问 <http://127.0.0.1:8080/actuator/health> 确认启动成功。

**4. 启动 AI 服务（可选，但没有它就没有 AI 功能）**

```bash
cd ai-service
python -m venv .venv
.venv\Scripts\activate          # Windows
# source .venv/bin/activate     # macOS / Linux

pip install -r requirements.txt

cp .env.example .env            # 然后填 LLM_API_KEY
uvicorn main:app --port 8000
```

访问 <http://127.0.0.1:8000/docs> 查看 AI 服务的 Swagger。

> **没有 `LLM_API_KEY` 时 AI 服务仍能启动**，只是 `/ai/parse-bill` 等接口会返回明确错误。
> 这样联调时不会误以为"服务挂了"。

---

## 环境变量

### 项目根 `.env`（Docker 用）

| 变量 | 说明 |
|---|---|
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 |
| `REDIS_PASSWORD` | Redis 密码 |

### `ai-service/.env`（AI 服务用）

| 变量 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `LLM_API_KEY` | ✅ | — | DeepSeek API Key |
| `LLM_BASE_URL` | | `https://api.deepseek.com` | 兼容其他 OpenAI 格式服务 |
| `LLM_MODEL` | | `deepseek-flash` | 模型名 |
| `LLM_TIMEOUT_SECONDS` | | `15.0` | 调用超时 |
| `LLM_TEMPERATURE` | | `0.0` | 抽取任务要确定性 |
| `MAX_TEXT_LENGTH` | | `500` | 用户输入上限 |
| `MAX_BILLS` | | `20` | 一次最多返回几笔 |

### Spring Boot（`application.yaml` 可覆盖）

| 配置 | 默认 | 说明 |
|---|---|---|
| `notes-at-will.ai.base-url` | `http://127.0.0.1:8000` | AI 服务地址（环境变量 `AI_SERVICE_URL`） |
| `notes-at-will.ai.timeouts.parse-bill` | `15000` | 账单解析超时 |
| `notes-at-will.ai.timeouts.analyze` | `15000` | 消费点评超时 |
| `notes-at-will.ai.timeouts.greeting` | **`3000`** | 每日问候超时（首屏，必须短） |
| `notes-at-will.ai.timeouts.chat` | `30000` | 对话超时（二期） |

---

## 接口概览

共 **54 个端点**，全部返回 `{ code, message, data }` 结构（**业务错误也是 HTTP 200**，靠 `code` 区分）。

### 认证 `/api/auth`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/send-code` | 发送短信验证码 |
| POST | `/register` | 注册（自动建默认账本 + 账户） |
| POST | `/login/password` | 密码登录 |
| POST | `/login/code` | 验证码登录 |
| POST | `/reset-password` | 忘记密码（手机号 + 验证码 + 新密码） |
| POST | `/logout` | 登出（token 进黑名单） |

### 用户 `/api/user`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/me` | 当前用户信息 |
| PUT | `/password` | 修改密码（已登录，需旧密码） |

### 账本 / 分类 / 账户

| 模块 | 端点 |
|---|---|
| 账本 `/api/books` | `GET` / `POST` / `PUT /{id}` / `DELETE /{id}` |
| 分类 `/api/categories` | `GET`（树） / `POST` / `PUT /{id}` / `DELETE /{id}` |
| 账户 `/api/accounts` | `GET` / `POST` / `PUT /{id}` / `DELETE /{id}` |
| | `POST /transfer` 转账 |
| | `DELETE /transfer/{transferNo}` 撤销转账 |
| | `POST /{id}/adjust` 手动校正余额 |
| | `GET /{id}/flows` 账户流水 |

### 账单 `/api/bills`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 分页查询（6 种筛选） |
| GET | `/{id}` | 详情 |
| POST | `/` | 新增 |
| PUT | `/{id}` | 修改（**整体替换**，`accountId` 必须显式传） |
| DELETE | `/{id}` | 删除（回滚余额 + 删卡片） |
| GET | `/summary` | 本月汇总 |

### 预算 `/api/budgets`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 预算列表 |
| POST | `/` | 新建（`categoryId=null` 即账本级） |
| PUT | `/{id}` | 修改 |
| DELETE | `/{id}` | 删除 |
| GET | `/status` | **执行状态（实时聚合）** |
| GET | `/calendar` | 日历视图（补 0 到整月） |

### 统计 `/api/stats`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/summary` | 区间汇总（收入/支出/结余/**日均**/最大单笔） |
| GET | `/category-ratio` | 分类占比（可下钻） |
| GET | `/trend` | 趋势（日/周/月） |
| GET | `/ai-comment` | **AI 消费点评** |

### AI 记账 `/api/ai`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/parse` | 解析成草稿（**不落库**） |
| POST | `/confirm` | 用户确认后批量落库 |

### AI 伙伴 `/api/chat`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/messages` | 对话历史（**游标分页**） |
| GET | `/stream` | **SSE 推送通道** |
| POST | `/daily-open` | 每日首次打开（**幂等**问候） |
| DELETE | `/messages/{id}` | 删除消息（`?cascadeBill=true` 连带删账单） |

### 角色 `/api/persona`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/presets` | 系统预设（5 个，只读） |
| GET | `/` | 我的角色列表 |
| GET | `/active` | 当前生效角色（**可能为 null**） |
| POST | `/` | 创建（可带 `presetId` 复制预设） |
| PUT | `/{id}` | 修改（**不改激活状态**） |
| PUT | `/{id}/activate` | 切换激活（**唯一入口**） |
| DELETE | `/{id}` | 删除（不自动切换） |

### 开发辅助 `/api/dev`（仅 dev profile）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/account-check` | 对账自检 |

---

## 核心设计

这些是项目里**最容易做错、且最值得讲**的几处。

### 1. 余额是派生缓存，唯一真相是流水

```
balance == init_balance + SUM(IN) - SUM(OUT)
```

- 余额只能通过 `AccountFlowService`（内部用 `UPDATE ... SET balance = balance + ?` 数据库端自增）修改，**并发安全**。
- 任何时刻都能用 `GET /api/dev/account-check` 对账验证。
- **不这样做会怎样**：余额一旦算错就是静默的 —— 接口 200、界面正常，但钱少了。对账是唯一能发现它的手段。

### 2. 剩余额度不落库

预算的 `remaining` / `usedPercent` **每次现算**。
存进库就要在"账单增删改 + 预算改额度"每条路径同步，漏一处就出现"还剩 800 实际已花超"，而且**没有对账手段能发现**。

### 3. 统计口径只有一处

`summary` / `category-ratio` / `trend` / `bill-summary` 四个接口**都走 `BillMapper.sumAmount`**，
所以同一个月的支出必然一致。测试 `statisticsConsistentAcrossAllEndpoints` 钉死了这点。

### 4. AI 数字由 Java 算，模型只"说人话"

点评和问候的数字全部由 `StatsService` 聚合后传给 Python。
Prompt 里明确要求"只能引用、不许重算"。
**这样模型没有机会算错，也没机会编造出图上不存在的数字。**

### 5. AI 解析不让模型决定账户

模型只输出 `accountHint`（如"微信"），**不给 `accountId`**。
账户匹配交给前端 + 用户确认 —— 模型对口语词的匹配不可靠，强行输出 id 必然产生幻觉。

### 6. 三种超时按接口区分

| 接口 | 超时 | 理由 |
|---|---|---|
| `/parse-bill` | 15s | 用户在等结果 |
| `/analyze` | 15s | 生成一段话 |
| `/greeting` | **3s** | 首屏，宁可降级也不白屏 |
| `/chat` | 30s | 生成较长回复 |

**每个接口一个客户端 Bean** —— 声明式 HTTP 接口无法按方法设超时。

### 7. 每日问候幂等靠唯一索引

`uk_user_date(user_id, greet_date)` 保证一天一条。
"先查再插"在并发下必漏（两个请求同时查不到就会插两条）。实测 8 并发 → 1 条记录。

### 8. 推送在事务提交之后

记账后写卡片在同一事务内，但 **SSE 推送用 `AfterCommit` 放到提交后**。
否则会出现"用户看到卡片出现 → 提交失败 → 刷新后消失" —— **用户已经被误导**。

---

## 项目结构

```
NotesAtWill/
├── src/main/java/com/sakuya/notesatwill/
│   ├── common/           # 错误码、异常、Result、工具（MonthRange / AfterCommit）
│   ├── config/           # Security / Jackson / MyBatis-Plus / AI 客户端
│   ├── controller/       # 12 个 Controller，54 个端点
│   ├── client/           # 声明式 HTTP 客户端（AiParse / AiAnalyze / AiGreeting）
│   ├── entity/
│   │   ├── DTO/          # 请求对象（含 ai/ 子包：Python 契约）
│   │   ├── VO/           # 响应对象
│   │   └── row/          # 聚合查询结果行
│   ├── mapper/           # MyBatis-Plus Mapper（9 个）
│   └── service/          # 业务逻辑（17 个 Service / 管理器）
├── src/test/java/.../    # 41 个测试类，438 个用例
├── sql/
│   ├── 01_schema.sql     # 12 张表（可重复执行）
│   └── 02_seed.sql       # 72 分类 + 5 角色预设（幂等）
├── ai-service/           # Python AI 服务
│   ├── config.py         # 配置（pydantic-settings）
│   ├── schemas.py        # Pydantic 模型（camelCase 契约）
│   ├── prompt.py         # 三套 Prompt（解析 / 点评 / 问候）
│   ├── llm_client.py     # 大模型客户端（Protocol + 实现）
│   ├── service.py        # 业务编排（重试 / 防幻觉 / 归一化）
│   ├── main.py           # FastAPI 路由
│   └── tests/            # 68 个用例（不需要 API Key）
├── docs/                 # 设计文档（未随仓库提供）
└── docker-compose.yaml
```

---

## 测试

```bash
# Java：438 个用例
./mvnw test

# Python：68 个用例（不需要 LLM_API_KEY）
cd ai-service
pytest tests/ -q
```

**测试设计原则**

| 原则 | 体现 |
|---|---|
| **不依赖真实 AI** | Python 侧用 `FakeLlmClient`，Java 侧用 `@MockitoBean` 打桩客户端 |
| **不依赖"今天是几号"** | 造"今天"的数据一律用 `now().minusMinutes(1)`，不用 `atTime(12,0)`（上午跑就会失败） |
| **不留垃圾数据** | 每个测试类清理**所有**出现过的 `user_id`（包括越权用例的第二个用户） |
| **跑完全量测试后所有表应为 0 行** | 这是暴露清理遗漏最直接的手段 |

**关键测试类**

| 测试类 | 验证什么 |
|---|---|
| `FullFlowIntegrationTest` | **全链路**：建账本 → 记账 → 查询 → 统计 → 对账 → 删除 |
| `AccountAddBalanceTest` | 余额数据库端自增，20 线程并发无丢更新 |
| `AccountReconcileTest` | 对账能发现"余额与流水不一致" |
| `AiConfirmTest` | AI 批量落库：非法分类**整体回滚** |
| `StatsCacheTest` | 四个写入口都正确**清理缓存** |
| `GreetingServiceTest` | 问候**并发幂等**（8 并发 → 1 条） |
| `BillCardCascadeTest` | **双向级联删除** + 余额回滚 |

---

## 常见问题

### 启动时报"无法解析枚举常量 write-dates-as-timestamps"

Spring Boot 4 的 `JacksonProperties` **没有 `datetime` 前缀**，yaml 里不能写这个配置。
本项目用 `JacksonConfig` 以代码方式配置时间格式，**不要在 yaml 里加这个键**。

### MySQL 连不上

- 本项目 MySQL 映射到**宿主 3307**（不是 3306），避免与本机已有 MySQL 冲突。
- JDBC URL 必须带 `allowPublicKeyRetrieval=true`（8.4 默认 `caching_sha2_password`）。

### 分类是乱码

`sql/02_seed.sql` 里的中文由容器内 mysql 客户端执行，若客户端 locale 不是 UTF-8 会**双重编码**。
校验方式：`SELECT HEX(name) FROM t_category WHERE id=1001`，应为 `E9A490E9A5AE`（"餐饮"）；
若是 `C3A9...` 就是双重编码，需要 `docker compose down -v` 重建卷。

### AI 功能不工作

1. 检查 `ai-service/.env` 里有没有 `LLM_API_KEY`
2. 访问 `GET http://127.0.0.1:8000/health`，看 `llmConfigured` 是否为 true
3. **AI 服务挂了不影响记账、统计等主流程** —— 对应接口会降级（点评返回 null、问候用模板）

### 修改账单时账户关联变了

`PUT /api/bills/{id}` 是**整体替换**语义：
- `accountId` 传 id = 关联该账户
- `accountId` 传 `null` = **有意解除关联**
- **不要因为"这次没改账户"就省略这个字段**，否则会被当成"要解除关联"

---

## 已知限制

| 项 | 说明 |
|---|---|
| 改密后旧 token 仍有效 | 最长 7 天。正确修法是加 `password_update_time` + 过滤器比较 `iat` |
| 短信验证码 | 开发环境把验证码写日志 + Redis，未接真实短信服务 |
| 无数据导入 | CSV/微信支付宝账单导入属二期，`t_bill.source` 已预留 `IMPORT` |
| 无移动端 | 纯 Web，PWA/App 属二期 |
| Redis 用 `KEYS` 清缓存 | 单机可接受；**上生产集群须换 SCAN**（`KEYS` 会阻塞整个 Redis） |

---

## 文档

项目在设计阶段产出了完整的需求与设计文档（用例说明、时序图、状态图、类图等），**未随仓库提供**——核心结论已提炼进本 README 的[架构](#架构)与[核心设计](#核心设计)章节。

| 文档 | 主要内容 |
|---|---|
| 需求清单 | 功能清单与优先级 |
| 项目计划与技术选型 | 12 张表、13 条架构铁律、接口清单 |
| 开发计划 | 逐日任务与完成情况 |
| 开发执行手册 | 分阶段开发指引 |

---

## License

个人学习项目，未指定开源协议。
