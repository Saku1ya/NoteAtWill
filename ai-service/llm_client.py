"""大模型客户端封装。

★★ 本文件最重要的设计不是"怎么调模型"，而是**为什么要先定义 LlmClient 协议**：

  把"调模型"抽象成一个可替换的接口之后，
  service.py 里的重试逻辑 / 防幻觉 / 归一化 **全部可以在没有 Key 的情况下被测试**
  （用 FakeLlmClient 喂预设字符串即可）。

  否则你必须先有 Key、先联网，才能写第一行可验证的代码 ——
  那会让整个 AI 链路的开发速度慢好几倍，而且出问题时无法区分
  "是我的逻辑错了"还是"是模型返回的东西不对"。
"""

import logging
from typing import Protocol

from openai import OpenAI

from config import Settings

logger = logging.getLogger(__name__)


class LlmError(Exception):
    """大模型调用失败（网络 / 超时 / 鉴权 / 返回空内容）。

    ★ 为什么要把"调用失败"和"结果不合法"分成两种异常：
      它们的处理策略**完全相反** ——
        · 调用失败（本类）→ **不重试**，直接降级（重试只会让用户多等 15 秒）
        · 结果不合法     → 重试一次，并把错误信息回灌给模型让它自己改
      混成一个异常就会导致"超时了还在重试"，延迟成倍放大。
    """


class LlmClient(Protocol):
    """最小接口：给一组 messages，返回模型的文本输出。

    只定义这一个方法是有意的 —— 接口越小，假实现越容易写。
    """

    def complete_json(self, messages: list[dict[str, str]]) -> str:
        ...


class OpenAiLlmClient:
    """基于 OpenAI 兼容接口的真实实现（DeepSeek 走这条）。"""

    def __init__(self, settings: Settings) -> None:
        if not settings.llm_configured:
            # 抛 LlmError 而不是 ValueError：让上层用同一种方式处理"客户端不可用"
            raise LlmError("未配置大模型 Key：请在 ai-service/.env 里填写 LLM_API_KEY")

        self._settings = settings
        self._client = OpenAI(
            api_key=settings.llm_api_key,
            base_url=settings.llm_base_url,
            timeout=settings.llm_timeout_seconds,
            # ★ 关掉 SDK 自带的重试。
            #   我们会在 service 层做"带错误信息的一次重试"，
            #   两套重试叠加会成倍放大延迟（15s × N），反而更容易超时。
            max_retries=0,
        )

    def complete_json(self, messages: list[dict[str, str]]) -> str:
        """调用模型，返回它输出的文本。

        ★ 注意只负责"拿到文本"，不解析 JSON ——
          解析与重试是 service 层的职责。客户端越单纯，越容易替换和测试。
        """
        try:
            resp = self._client.chat.completions.create(
                model=self._settings.llm_model,
                messages=messages,
                temperature=self._settings.llm_temperature,
                max_tokens=self._settings.llm_max_tokens,
                # ★ 强制 JSON 输出模式（DeepSeek 支持）。
                #   注意它只保证"输出是合法 JSON"，**不保证符合我们的 schema** ——
                #   schema 由 Pydantic 在 service 层兜底。这两件事别混。
                response_format={"type": "json_object"},
            )
        except Exception as exc:  # noqa: BLE001 —— 统一收敛成 LlmError 供上层降级
            # 在这里收口：上层只关心"调用有没有失败"，
            # 不想区分是 TimeoutException 还是 AuthenticationError。
            raise LlmError(f"调用大模型失败：{type(exc).__name__}: {exc}") from exc

        if not resp.choices:
            raise LlmError("大模型返回了空 choices")

        content = resp.choices[0].message.content
        # ★ "返回空内容"是真实会发生的情况（尤其被限流时）。
        #   不校验的话后面会拿空字符串去解析 JSON，报一个风马牛不相及的错。
        if not content or not content.strip():
            raise LlmError("大模型返回了空内容")

        return content


# ======================================================================
# 下面这个工具函数是纯预处理，不算核心逻辑
# ======================================================================

def strip_code_fence(text: str) -> str:
    """去掉模型偶尔包上的 markdown 代码块围栏。

    即使 Prompt 里明确要求"只输出 JSON、不要 markdown"，模型仍可能返回：

        ```json
        {"bills": [...]}
        ```

    直接 json.loads 会失败，白白消耗一次重试。这是零成本的预处理。

    三种形式都处理：
        '{"a":1}'            → 原样返回
        '```json\\n{"a":1}\\n```' → '{"a":1}'
        '```\\n{"a":1}\\n```'     → '{"a":1}'
    """
    s = text.strip()
    if not s.startswith("```"):
        return s
    first_newline = s.find("\n")
    if first_newline == -1:
        return s
    body = s[first_newline + 1:]
    if body.rstrip().endswith("```"):
        body = body.rstrip()[:-3]
    return body.strip()
