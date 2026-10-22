r"""FastAPI 应用入口。

启动（在 ai-service 目录下，uvicorn 的工作目录必须是这里）：
    .venv\Scripts\python.exe -m uvicorn main:app --reload --port 8000

起来后：
    http://127.0.0.1:8000/docs     Swagger
    http://127.0.0.1:8000/health   健康检查（供 Java 探活）

★★ 本服务的定位（架构铁律第 1 条）：
  **无状态、不连数据库、不持有任何业务数据。**
  它只接收「文本 + 候选集」、返回「结构化草稿」，
  真正的落库与权限校验全部在 Spring Boot 侧。
  这样可以随时重启/替换本服务，且它无法绕过业务校验改数据。
"""

import logging

from fastapi import FastAPI

from config import settings
from llm_client import LlmError, OpenAiLlmClient
from schemas import (
    AnalyzeRequest,
    AnalyzeResponse,
    GreetingRequest,
    GreetingResponse,
    ParseBillRequest,
    ParseBillResponse,
)
from service import AnalyzeService, GreetingService, ParseService

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s [%(name)s] %(message)s",
)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="NotesAtWill AI Service",
    version="0.1.0",
    description="无状态 AI 处理服务：账单解析。不连数据库、不持有业务数据。",
)


def _build_client() -> OpenAiLlmClient | None:
    """按需构造大模型客户端。

    ★★ 缺 Key 时返回 None 而不是让服务崩掉，原因：
        本服务还有 /health（以及后续的 /ai/greeting 等）接口。
        缺 Key 就整个起不来的话，联调时会误以为是"服务本身挂了"，
        白白浪费时间去查网络和端口。
        让 /health 如实报告 llmConfigured=false 更好 —— 一眼看出是配置问题。
    """
    if not settings.llm_configured:
        logger.warning(
            "未配置 LLM_API_KEY —— /ai/parse-bill 会返回明确错误。"
            "请把 .env.example 复制成 .env 并填写 Key。"
        )
        return None

    try:
        client = OpenAiLlmClient(settings)
    except LlmError as exc:
        logger.error("初始化大模型客户端失败：%s", exc)
        return None

    logger.info("大模型客户端已就绪：base_url=%s, model=%s, timeout=%ss",
                settings.llm_base_url, settings.llm_model, settings.llm_timeout_seconds)
    return client


# 模块级单例：OpenAI 客户端内部维护连接池，
# 每次请求都 new 一个会白白浪费连接。
_llm_client = _build_client()
_parse_service = ParseService(_llm_client, settings)
_analyze_service = AnalyzeService(_llm_client, settings)
_greeting_service = GreetingService(_llm_client, settings)


@app.get("/health")
def health() -> dict:
    """健康检查。

    ★ llmConfigured 这个字段很有用：Java 侧启动时可以探一下，
      若为 false 就提前把"AI 记账"按钮降级，而不是等用户点了才报错。
    """
    return {
        "status": "ok",
        "llmConfigured": settings.llm_configured,
        "model": settings.llm_model if settings.llm_configured else None,
    }


@app.post("/ai/parse-bill", response_model=ParseBillResponse)
def parse_bill(request: ParseBillRequest) -> ParseBillResponse:
    """把一段自然语言解析成结构化账单草稿。

    ★ 返回的是「待确认草稿」，不是可直接落库的账单：
      - categoryId 已校验在候选集内（幻觉的会被置 null）
      - accountHint 只是账户名提示，**不是 accountId**
      - billTime 缺失时已兜底为 now

    ★ 为什么记这两条日志：
      "输入了什么候选集"和"返回了几笔"是排查 AI 链路最关键的两个信息。
      没有它们，你只能猜是 Prompt 的问题还是 Java 传参的问题。
    """
    logger.info("解析请求：textLen=%d, categories=%d, accounts=%d",
                len(request.text), len(request.categories), len(request.accounts))

    result = _parse_service.parse(request)

    logger.info("解析完成：success=%s, bills=%d, error=%s",
                result.success, len(result.bills), result.error)
    return result


@app.post("/ai/analyze", response_model=AnalyzeResponse)
def analyze(request: AnalyzeRequest) -> AnalyzeResponse:
    """根据 Java 算好的消费数据，生成一段人话点评。

    ★★ **数字全部由 Java 计算，本接口只负责"说人话"**：
      这让"点评里提到的金额"与"页面上的图表"必然一致 ——
      模型只能引用传入的数字，没有机会算错，也没机会编造出图上不存在的分类。

    ★ 点评是「锦上添花」，失败不应该影响主流程：
      超时/解析失败都返回 success=false，Java 侧据此不显示点评即可，
      **不要让统计页因为 AI 挂了而报错**。
    """
    logger.info("点评请求：month=%s, expense=%s, 分类数=%d, 角色=%s",
                request.month, request.expense,
                len(request.top_categories), request.persona_name)

    result = _analyze_service.analyze(request)

    logger.info("点评完成：success=%s, %s",
                result.success,
                f"{len(result.comment)} 字" if result.comment else result.error)
    return result


@app.post("/ai/greeting", response_model=GreetingResponse)
def greeting(request: GreetingRequest) -> GreetingResponse:
    """按时段生成每日问候语。

    ★★ **时段由 Java 算好传入**（DAWN/MORNING/…/NIGHT），
      不让模型自己判断"现在是几点" —— 模型拿不到真实时间，猜必然出错。

    ★★ **本接口【不重试】**：Java 侧只等 3 秒就要渲染首屏。
      在这里重试一次会让最坏耗时翻倍到 6 秒，那时前端早已降级成模板，
      重试纯属浪费一次调用。

    ★ 失败时返回 success=false，Java 用模板问候兜底 ——
      **首页绝不能白屏**。
    """
    logger.info("问候请求：bucket=%s, month=%s, expense=%s, 今天已记=%s, 角色=%s",
                request.time_bucket, request.month, request.expense,
                request.has_bill_today, request.persona_name)

    result = _greeting_service.greeting(request)

    logger.info("问候完成：success=%s, %s",
                result.success,
                f"{len(result.greeting)} 字" if result.greeting else result.error)
    return result
