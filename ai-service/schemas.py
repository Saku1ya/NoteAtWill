"""Pydantic 模型：与 Java 侧的 JSON 契约。

★ 这个文件是"跨语言契约"的唯一定义处。改任何字段都要同步 Java 侧的 DTO，
  否则会出现「Python 输出了，Java 静默拿到 null」这种最难排查的问题。

★ 为什么"静默为 null"是最难查的：Jackson 遇到不认识的字段不会报错
  （Java 侧配了 fail-on-unknown-properties=false），它只会让那个字段保持 null。
  于是接口返回 200、日志干净、账单却少了分类 —— 只能靠肉眼发现。
"""

from datetime import datetime
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

# 收支类型。用 Literal 而不是 str：让 Pydantic 直接拒绝 "支出"/"expense" 这类变体，
# 模型吐错值时会在校验阶段暴露，而不是流到数据库里。
BillType = Literal["EXPENSE", "INCOME"]


# ======================================================================
# 基类 —— 三项配置缺一不可
# ======================================================================

class CamelModel(BaseModel):
    """所有模型的基类。"""

    model_config = ConfigDict(
        # ① 自动把 snake_case 转成 camelCase 输出。
        #    不配的话 Java 的 DTO 会静默拿到 null（不报错），最难查的一类 bug。
        alias_generator=to_camel,
        # ② 允许用字段名（snake_case）构造对象，不必写别名。
        #    不配的话自己代码里只能写 categoryId=... 很别扭。
        populate_by_name=True,
        # ③ 模型偶尔会多吐字段 —— 不要因此整批失败。
        #    与 Java 侧 fail-on-unknown-properties=false 对齐。
        extra="ignore",
    )


# ======================================================================
# 请求模型（Java → Python）
# ======================================================================

class CategoryCandidate(CamelModel):
    """候选分类。

    带 type 是为了让模型能做"支出不能配收入分类"的自我检查；
    更重要的是：服务端后处理时也用它来校验，因为数据库层面没有这个约束。
    """

    id: int
    name: str
    type: BillType


class AccountCandidate(CamelModel):
    """候选账户。

    ★ id 会下发给模型仅作参考，但 Prompt 里明确要求它**不要输出 id** ——
      账户匹配交给前端 + 用户确认，原因见 ParseBillRequest 的说明。
    """

    id: int
    name: str


class ParseBillRequest(CamelModel):
    """解析请求。

    ★★ now 为什么必须由 Java 注入，而不是用 Python 机器的时钟：
        "今天是几号"必须只有一个来源。若 Java 与 Python 各自的时钟/时区不同，
        "昨天"会被算到不同的日期上，而且这种错极难复现 ——
        只在跨时区或时钟漂移时才暴露，平时完全看不出来。

    ★★ accounts 为什么只给名字、不要求模型输出 id：
        模型对"微信""招行"这类口语词的匹配很不可靠，
        强行让它输出 accountId 必然产生幻觉（指向不存在或别人的账户）。
        所以只把名字作为"提示"下发，真正的账户匹配放在前端预览界面由用户确认。
    """

    text: str = Field(min_length=1, description="用户原文")
    now: datetime = Field(description="当前时间，由 Java 注入")
    categories: list[CategoryCandidate] = Field(default_factory=list)
    accounts: list[AccountCandidate] = Field(default_factory=list)

    # ★★ 这里【没有】角色字段，是刻意的：
    #   parse-bill 是结构化抽取任务，输出是金额/类型/分类/时间，
    #   不需要也不应该带口吻。带人设的是 /ai/analyze（本月消费点评）。


# ======================================================================
# 账单条目（模型输出 + 返回给 Java 共用）
# ======================================================================

class AiBillItem(CamelModel):
    """一笔解析结果。

    它同时承担两个角色：模型输出的单条账单，以及返回给 Java 的单条草稿。

    ★ category_name 为什么由服务端回填、而不是让模型给：
        模型可能输出 {"categoryId": 1001, "categoryName": "奶茶"} —— id 和名字对不上。
        服务端拿 id 去候选集查名字，两个字段就**永远一致**。
        所以它在模型输出里是可选的，在 service 层后处理时被赋值。

    ★ amount 用 Decimal 而不是 float：
        金额不能用二进制浮点。Pydantic 会把 Decimal 序列化成**字符串**（"25.50"），
        而 Jackson 的 BigDecimal 反序列化同时接受字符串与数字 ——
        所以 Java 侧无需特殊处理，但这是契约细节，D6 要实测一次。

    ★ confidence 故意不加 ge/le 约束：
        模型给个 1.8 是很常见的，若用 Field(le=1.0) 会触发一次**没必要的重试**
        （每次重试 15 秒）。它只是前端高亮用的提示，夹到 [0,1] 就够了。
    """

    amount: Decimal = Field(gt=0, description="金额，恒为正数，方向由 type 表达")
    type: BillType
    category_id: int | None = Field(default=None, description="必须落在候选集内，否则为 null")
    category_name: str | None = Field(default=None, description="由服务端从候选集回填")
    account_hint: str | None = Field(default=None, description="账户名提示，不是 accountId")
    bill_time: datetime | None = Field(default=None, description="缺省时服务端兜底为 now")
    remark: str | None = None
    confidence: float | None = Field(default=None, description="0~1，越低越需要用户核对")


class LlmOutput(CamelModel):
    """模型必须输出的顶层结构。

    ★ unmatched 的作用是"不静默丢弃"：模型把看不懂的片段塞进这里，
      前端就能提示"这段没识别出来，请手动记账"，而不是让用户以为全都记上了。
    """

    bills: list[AiBillItem] = Field(default_factory=list)
    unmatched: list[str] = Field(default_factory=list, description="无法归类的原始片段")


class ParseBillResponse(CamelModel):
    """返回给 Java 的结构。

    ★ 用 success 标志位而不是抛异常来表达业务失败：
        FastAPI 抛异常会返回 {"detail": "..."} 这种另一套结构，
        Java 侧就要写两套解析逻辑。
        约定"永远返回 200 + success 字段"，Java 只看一个布尔值就能决定走不走降级。
    """

    success: bool
    bills: list[AiBillItem] = Field(default_factory=list)
    unmatched: list[str] = Field(default_factory=list)
    error: str | None = Field(default=None, description="success=false 时的具体原因")


# ======================================================================
# 消费点评（D8 6.5）
# ======================================================================

class CategoryStat(CamelModel):
    """一个分类的占比数据（Java 算好后传进来）。

    ★ 这些数字由 Java 聚合，模型只能【引用】不能【重算】——
      让模型自己算"餐饮占多少"必然出错，而且每次错得不一样。
    """

    name: str
    amount: Decimal
    percent: Decimal


class AnalyzeRequest(CamelModel):
    """消费点评请求：Java 已算好全部数字，模型只负责"说人话"。

    ★★ 这是本接口最重要的架构约束：
      **数字由 Java 算，模型不参与任何计算。**
      这样"点评里提到的金额"与"页面上的图表"必然一致（验收要求），
      而且模型也无法编造出一个图上不存在的数字。
    """

    now: datetime = Field(description="当前时间，由 Java 注入")
    month: str = Field(description="统计月份，形如 2026-10")

    income: Decimal = Field(description="本月总收入")
    expense: Decimal = Field(description="本月总支出")
    surplus: Decimal = Field(description="结余 = 收入 - 支出")
    daily_average: Decimal = Field(description="日均支出（分母是已过天数）")
    days: int = Field(description="日均的分母天数")

    top_categories: list[CategoryStat] = Field(
        default_factory=list, description="支出占比 Top N（已由 Java 排序好）"
    )
    max_expense: Decimal | None = Field(default=None, description="最大单笔支出")
    max_expense_category: str | None = Field(default=None, description="最大单笔的分类名")

    # ★ 角色口吻（可选）：让点评带上用户选择的角色风格。
    #   不传就用中性口吻 —— 不能因为没配角色就不给点评。
    persona_name: str | None = None
    persona_personality: str | None = None
    persona_relation: str | None = None
    persona_address: str | None = None
    persona_gender: str | None = None


class AnalyzeLlmOutput(CamelModel):
    """模型必须输出的结构（点评内容包在一层 JSON 里）。

    ★ 为什么要求 JSON 而不是纯文本：
      纯文本时模型爱加"好的，以下是点评："这类前缀，还得写正则去剥。
      包成 {"comment": "..."} 后拿到的是干净字符串，
      而且能复用与 /parse-bill 完全相同的"校验失败带错误信息重试"机制。
    """

    comment: str


class AnalyzeResponse(CamelModel):
    """点评接口的出参（与 ParseBillResponse 同构：永远 200 + success 标志）。"""

    success: bool
    comment: str | None = Field(default=None, description="success=true 时的点评正文")
    error: str | None = Field(default=None, description="success=false 时的具体原因")


# ======================================================================
# 每日问候（D9 7.6）
# ======================================================================

class GreetingRequest(CamelModel):
    """每日问候请求：时段由 Java 算好，数字也由 Java 聚合好。

    ★★ **时段必须由 Java 传，不能让模型自己判断"现在是几点"**：
      模型不知道真实时间（也拿不到用户时区），让它猜必然出错。
      Java 侧按服务器时钟算出 DAWN/MORNING/…/NIGHT 再传进来。

    ★ 数字同样由 Java 聚合传入 —— 与点评同理，模型只负责"说人话"。
    """

    now: datetime = Field(description="当前时间，由 Java 注入")
    time_bucket: str = Field(
        description="时段：DAWN / MORNING / FORENOON / NOON / AFTERNOON / EVENING / NIGHT"
    )
    weekday: str = Field(description="星期几的中文写法，如「星期一」")

    month: str = Field(description="统计月份，形如 2026-10")
    expense: Decimal = Field(description="本月支出")
    daily_average: Decimal = Field(description="日均支出")
    days: int = Field(description="日均的分母天数")

    # ★ 今天还没记账 —— 用来让问候更贴合实际（"今天还没记哦"）
    today_expense: Decimal = Field(default=Decimal("0"), description="今天已花")
    has_bill_today: bool = Field(default=False, description="今天是否已经记过账")

    persona_name: str | None = None
    persona_personality: str | None = None
    persona_relation: str | None = None
    persona_address: str | None = None
    persona_gender: str | None = None


class GreetingLlmOutput(CamelModel):
    """模型输出结构（包一层 JSON，理由同点评）。"""

    greeting: str


class GreetingResponse(CamelModel):
    """问候接口出参（同构：永远 200 + success 标志）。"""

    success: bool
    greeting: str | None = Field(default=None, description="success=true 时的问候语")
    error: str | None = Field(default=None, description="success=false 时的具体原因")
