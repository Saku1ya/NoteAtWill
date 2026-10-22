"""Python AI 服务的业务编排层。

★★ 本模块做两类事，共用一个**「调模型 + 校验 + 带错误信息重试」**机制：
  1. `ParseService`   —— 账单解析（输出结构化账单）
  2. `AnalyzeService` —— 消费点评（输出一段人话）

★★ 重试机制的要点（两个服务共用 `call_with_feedback_retry`）：
  - **结果不合法 → 重试一次**，并把"上一次错在哪"回灌给模型
  - **调用失败（超时/网络/鉴权）→ 不重试**，直接降级
  这两类失败的处理策略完全相反，混成一个异常就会"超时了还在重试"。

★ 为什么分类幻觉要单独校验、而不是只靠 Prompt：
  模型非常擅长编出"奶茶""房租"这种听起来合理但系统里没有的分类。
  Prompt 只是"请求它别编"，**真正的防线是候选集校验**。
"""

import logging
from typing import Callable, TypeVar

from config import Settings
from llm_client import LlmClient, LlmError, strip_code_fence
from prompt import build_analyze_messages, build_greeting_messages, build_parse_messages
from schemas import (
    AiBillItem,
    AnalyzeLlmOutput,
    AnalyzeRequest,
    AnalyzeResponse,
    GreetingLlmOutput,
    GreetingRequest,
    GreetingResponse,
    LlmOutput,
    ParseBillRequest,
    ParseBillResponse,
)

logger = logging.getLogger(__name__)

# 校验失败后允许的最大总尝试次数（首次 + 1 次带错误信息重试）
_MAX_ATTEMPTS = 2

# 点评正文的长度上限（超过就截断，避免前端撑爆）
_MAX_COMMENT_LENGTH = 500

# 问候语的长度上限（首屏内容，必须短；模型有时会写成一大段）
_MAX_GREETING_LENGTH = 120

T = TypeVar("T")


class _InvalidOutput(Exception):
    """模型输出不合法（JSON 坏了 / schema 不符）。

    ★ 用私有异常而不是 LlmError：它和"调用失败"要触发**相反**的处理 ——
      这个可以重试，LlmError 不能。
    """


def call_with_feedback_retry(
    client: LlmClient,
    messages: list[dict[str, str]],
    parse_fn: Callable[[str], T],
    label: str,
) -> T:
    """调模型 → `parse_fn` 校验；不合法则**带着错误信息**重试一次。

    ★★ 为什么重试必须"带上错误信息"，这是重试有价值的唯一原因：
       把上一次的坏输出作为 assistant 消息回灌，再用 user 消息指出错在哪，
       模型多半能自己改对。只重试不带错误信息，模型大概率犯同样的错 ——
       那样重试纯属浪费一次调用和十几秒等待。

    ★ `parse_fn` 抛 `_InvalidOutput` 表示"结果不合法，值得重试"；
      抛其它异常（包括 LlmError）会**原样向上抛**，由调用方决定怎么降级。

    :param client:    大模型客户端
    :param messages:  初始 messages
    :param parse_fn:  把模型原始输出转成结果；不合法时抛 `_InvalidOutput`
    :param label:     日志前缀（`parse-bill` / `analyze`），便于区分来源
    :return:          `parse_fn` 的结果
    :raises _InvalidOutput: 两次尝试都不合法
    :raises LlmError:       调用本身失败（**不重试**）
    """
    current = messages
    last_error = ""
    raw = ""   # 占位，避免 except 分支里引用未赋值变量

    for attempt in range(1, _MAX_ATTEMPTS + 1):
        try:
            raw = client.complete_json(current)
            return parse_fn(raw)
        except _InvalidOutput as exc:
            last_error = str(exc)
            logger.warning("[%s] 第 %d 次结果不合法：%s", label, attempt, last_error)
            if attempt < _MAX_ATTEMPTS:
                current = current + [
                    {"role": "assistant", "content": raw},
                    {
                        "role": "user",
                        "content": (
                            f"上一次输出不符合要求：{last_error}。\n"
                            "请严格按前面给定的 JSON schema 重新输出，只输出 JSON，不要解释。"
                        ),
                    },
                ]

    raise _InvalidOutput(last_error)


class ParseService:
    """账单解析服务。客户端从外部注入，便于在没有 Key 的环境下测试。"""

    def __init__(self, client: LlmClient | None, settings: Settings) -> None:
        # ★ client 允许为 None：表示"没配 Key"。
        #   这样服务照样能启动，接口返回明确错误，而不是整个起不来。
        self._client = client
        self._settings = settings

    # ------------------------------------------------------------------
    # 对外入口
    # ------------------------------------------------------------------
    def parse(self, request: ParseBillRequest) -> ParseBillResponse:
        """解析入口：入参防御 → 调用(可重试一次) → 校验 → 后处理。"""

        # ---------- ① 入参防御（不花一次 LLM 调用）----------
        if len(request.text) > self._settings.max_text_length:
            return ParseBillResponse(
                success=False,
                error=f"输入过长（{len(request.text)} 字），"
                      f"请控制在 {self._settings.max_text_length} 字以内",
            )
        if self._client is None:
            return ParseBillResponse(success=False, error="AI 服务未配置大模型 Key")

        # ---------- ② 调用 + 重试（与点评共用同一套机制）----------
        messages = build_parse_messages(request)
        try:
            return call_with_feedback_retry(
                self._client,
                messages,
                lambda raw: self._post_process(request, self._validate(raw)),
                "parse-bill",
            )
        except _InvalidOutput as exc:
            logger.error("[parse-bill] 重试后仍不合法：%s", exc)
            return ParseBillResponse(
                success=False,
                error=f"AI 返回结果格式不正确（{exc}）",
            )
        except LlmError as exc:
            # ★★ 调用本身失败（超时/网络/鉴权）→ 重试没意义，直接降级。
            logger.error("[parse-bill] 大模型调用失败：%s", exc)
            return ParseBillResponse(success=False, error=str(exc))

    # ------------------------------------------------------------------
    # 校验
    # ------------------------------------------------------------------
    def _validate(self, raw: str) -> LlmOutput:
        """把模型文本解析成 LlmOutput；任何不合法都抛 _InvalidOutput（可重试）。"""
        text = strip_code_fence(raw)
        try:
            return LlmOutput.model_validate_json(text)
        except Exception as exc:  # noqa: BLE001
            # ★ 截断后放进错误信息：它既会进日志，也会被回灌给模型，
            #   模型看到自己错在哪，第二次往往能改对。
            #   但一定要截断 —— 整段输出可能几千字，白白烧 token。
            snippet = text[:300].replace("\n", " ")
            raise _InvalidOutput(f"JSON 或字段不符合 schema（片段：{snippet}）") from exc

    # ------------------------------------------------------------------
    # 后处理：防幻觉 + 归一化
    # ------------------------------------------------------------------
    def _post_process(
        self, request: ParseBillRequest, output: LlmOutput
    ) -> ParseBillResponse:
        """校验分类合法性并做归一化。"""

        # ---------- ① 建候选映射（防幻觉的唯一依据）----------
        by_id = {c.id: c for c in request.categories}

        kept: list[AiBillItem] = []
        for bill in output.bills:
            # ---- 分类校验：三种情况 ----
            if bill.category_id is not None:
                cand = by_id.get(bill.category_id)
                if cand is None:
                    # 模型编了一个不存在的 id。
                    # ★ 置 None 而不是整批失败：其余几笔是对的，
                    #   不该因为一个分类连累它们。前端会提示用户手选。
                    logger.info("丢弃幻觉分类 id=%s（不在候选集内）", bill.category_id)
                    bill.category_id = None
                    bill.category_name = None
                elif cand.type != bill.type:
                    # 分类存在但收支类型相反（例如支出用了收入分类）。
                    # 数据库层面没有这个约束，只能在这里拦。
                    logger.info("丢弃类型不匹配的分类 id=%s（分类是 %s，账单是 %s）",
                                bill.category_id, cand.type, bill.type)
                    bill.category_id = None
                    bill.category_name = None
                else:
                    # ★ 名字由服务端从候选集回填，不用模型给的名字 ——
                    #   这样 id 与 name 永远一致，模型编不出"id=1001 但名字叫奶茶"。
                    bill.category_name = cand.name

            # ---- billTime 兜底（三层约定的第二层）----
            # Prompt 已要求"没提时间填当前时刻"，但模型未必听话，这里再兜一次。
            # （第三层在 Java：因为 t_bill.bill_time 是 NOT NULL，null 会报 SQL 错误）
            if bill.bill_time is None:
                bill.bill_time = request.now
                logger.info("模型未给出 billTime，兜底为当前时刻 %s", request.now)

            # ---- confidence 夹到 0~1 ----
            # 不因越界就整批失败（模型的 1.8 只是个夸张的自信度），夹一下即可
            if bill.confidence is not None:
                bill.confidence = max(0.0, min(1.0, bill.confidence))

            kept.append(bill)

        # ---------- ② 条数截断 ----------
        truncated = 0
        if len(kept) > self._settings.max_bills:
            truncated = len(kept) - self._settings.max_bills
            kept = kept[: self._settings.max_bills]
            logger.warning("解析出 %d 笔，截断到 %d 笔",
                           len(kept) + truncated, self._settings.max_bills)

        # ---------- ③ 结果判定 ----------
        if not kept:
            # 模型给出了合法结构但一笔都没识别出来 —— 调用是成功的，只是没内容。
            # 算 success=False：它不是"成功但为空"，而是"这句话里没有账单"。
            # Java 侧据此提示用户补充信息（对应错误码 AI_PARSE_EMPTY）。
            return ParseBillResponse(
                success=False,
                bills=[],
                unmatched=output.unmatched,
                error="没有识别出任何账单，请补充金额与用途",
            )

        logger.info("解析成功：%d 笔（截断 %d），未识别 %d 段",
                    len(kept), truncated, len(output.unmatched))
        return ParseBillResponse(
            success=True,
            bills=kept,
            unmatched=output.unmatched,
        )


# ======================================================================
# 消费点评（D8 6.5）
# ======================================================================

class AnalyzeService:
    """消费点评服务：把 Java 算好的数字变成一段人话。

    ★★ **数字全部由 Java 计算，本服务不做任何算术**。
      这是"点评里的金额与图表一致"的实现保证：
      模型只能引用我们喂给它的数字，没有机会算错，也没有机会编造
      一个图上不存在的分类或金额。

    ★ 输出仍然是 JSON（`{"comment": "..."}`）而不是纯文本：
      纯文本时模型爱加"好的，以下是点评："这类前缀，还得写正则去剥；
      包一层 JSON 后拿到的是干净字符串，且能复用与解析完全相同的重试机制。
    """

    def __init__(self, client: LlmClient | None, settings: Settings) -> None:
        # 与 ParseService 一样：client 为 None 表示没配 Key，
        # 服务照样启动，接口返回明确错误（而不是整个服务起不来）。
        self._client = client
        self._settings = settings

    def analyze(self, request: AnalyzeRequest) -> AnalyzeResponse:
        """生成消费点评。

        :param request: 已由 Java 聚合好的统计数据
        :return: success=True + comment，或 success=False + error
        """
        if self._client is None:
            return AnalyzeResponse(success=False, error="AI 服务未配置大模型 Key")

        messages = build_analyze_messages(request)
        try:
            output = call_with_feedback_retry(
                self._client, messages, self._validate, "analyze"
            )
            comment = output.comment.strip()
            logger.info("点评生成成功：%d 字", len(comment))
            return AnalyzeResponse(success=True, comment=comment)
        except _InvalidOutput as exc:
            logger.error("[analyze] 重试后仍不合法：%s", exc)
            return AnalyzeResponse(success=False, error=f"AI 点评格式不正确（{exc}）")
        except LlmError as exc:
            # 调用失败不重试（已在 call_with_feedback_retry 里明确）
            logger.error("[analyze] 大模型调用失败：%s", exc)
            return AnalyzeResponse(success=False, error=str(exc))

    def _validate(self, raw: str) -> AnalyzeLlmOutput:
        """校验模型输出：必须是 {"comment": "非空字符串"}。"""
        text = strip_code_fence(raw)
        try:
            output = AnalyzeLlmOutput.model_validate_json(text)
        except Exception as exc:  # noqa: BLE001
            snippet = text[:200].replace("\n", " ")
            raise _InvalidOutput(f"不是合法的 {{comment: ...}} JSON（片段：{snippet}）") from exc

        if not output.comment or not output.comment.strip():
            # ★ 空点评等于没点评，必须重试（而不是返回一条空字符串给前端）
            raise _InvalidOutput("comment 为空")

        # 超长截断：模型偶尔会写小作文，前端展示不下
        if len(output.comment) > _MAX_COMMENT_LENGTH:
            logger.warning("点评过长（%d 字），截断到 %d 字",
                           len(output.comment), _MAX_COMMENT_LENGTH)
            output.comment = output.comment[:_MAX_COMMENT_LENGTH]

        return output


# ======================================================================
# 每日问候（D9 7.6）
# ======================================================================

class GreetingService:
    """每日问候：生成一句"按时段 + 带数字"的招呼。

    ★★ **本服务只试一次，不重试**（与 parse/analyze 不同）：
      问候是首屏内容，Java 侧只等 3 秒。若在这里重试一次，
      最坏情况会变成"6 秒才返回" —— 那时前端早就降级成模板了，
      重试纯属浪费一次调用和 token。
      **超时敏感的接口不该重试。**

    ★ 时段与数字全部由 Java 传入，模型只负责把它们说成人话。
    """

    def __init__(self, client: LlmClient | None, settings: Settings) -> None:
        self._client = client
        self._settings = settings

    def greeting(self, request: GreetingRequest) -> GreetingResponse:
        """生成问候语。

        :param request: 时段 + 本月数字（均由 Java 算好）
        :return: success=True + greeting，或 success=False + error
        """
        if self._client is None:
            return GreetingResponse(success=False, error="AI 服务未配置大模型 Key")

        messages = build_greeting_messages(request)
        try:
            raw = self._client.complete_json(messages)
            output = self._validate(raw)
            text = output.greeting.strip()
            logger.info("问候生成成功：bucket=%s，%d 字", request.time_bucket, len(text))
            return GreetingResponse(success=True, greeting=text)
        except _InvalidOutput as exc:
            # ★ 不重试：直接把原因告诉 Java，让它用模板兜底
            logger.warning("[greeting] 输出不合法（不重试）：%s", exc)
            return GreetingResponse(success=False, error=f"AI 问候格式不正确（{exc}）")
        except LlmError as exc:
            logger.warning("[greeting] 大模型调用失败（不重试）：%s", exc)
            return GreetingResponse(success=False, error=str(exc))

    def _validate(self, raw: str) -> GreetingLlmOutput:
        """校验模型输出：必须是 {"greeting": "非空字符串"}。"""
        text = strip_code_fence(raw)
        try:
            output = GreetingLlmOutput.model_validate_json(text)
        except Exception as exc:  # noqa: BLE001
            snippet = text[:200].replace("\n", " ")
            raise _InvalidOutput(f"不是合法的 {{greeting: ...}} JSON（片段：{snippet}）") from exc

        if not output.greeting or not output.greeting.strip():
            raise _InvalidOutput("greeting 为空")

        # ★ 问候必须短：模型偶尔会写成一整段，截断到 120 字
        if len(output.greeting) > _MAX_GREETING_LENGTH:
            logger.warning("问候过长（%d 字），截断到 %d 字",
                           len(output.greeting), _MAX_GREETING_LENGTH)
            output.greeting = output.greeting[:_MAX_GREETING_LENGTH]

        return output
