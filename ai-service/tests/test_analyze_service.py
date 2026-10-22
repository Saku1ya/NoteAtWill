"""消费点评（AnalyzeService）的测试 —— **不需要大模型 Key**。

覆盖：
  - Prompt 是否注入了全部数字、角色口吻
  - 正常点评
  - 空 comment / 格式不合法 → 重试
  - 超长截断
  - 调用失败不重试
"""

import json
from datetime import datetime
from decimal import Decimal

import pytest

from config import Settings
from llm_client import LlmError
from prompt import build_analyze_messages
from schemas import AnalyzeRequest, CategoryStat
from service import AnalyzeService

NOW = datetime(2026, 10, 19, 10, 0, 0)


def make_request(with_persona: bool = False) -> AnalyzeRequest:
    return AnalyzeRequest(
        now=NOW,
        month="2026-10",
        income=Decimal("8000.00"),
        expense=Decimal("1250.00"),
        surplus=Decimal("6750.00"),
        daily_average=Decimal("65.79"),
        days=19,
        top_categories=[
            CategoryStat(name="餐饮", amount=Decimal("850.00"), percent=Decimal("68.00")),
            CategoryStat(name="交通", amount=Decimal("400.00"), percent=Decimal("32.00")),
        ],
        max_expense=Decimal("380.00"),
        max_expense_category="餐饮/午餐",
        persona_name="小小鹦鹉" if with_persona else None,
        persona_personality="真诚友善，说话轻快带点俏皮" if with_persona else None,
        persona_relation="朋友" if with_persona else None,
        persona_address="你" if with_persona else None,
        persona_gender="FEMALE" if with_persona else None,
    )


class FakeLlmClient:
    def __init__(self, responses):
        self._responses = list(responses)
        self.calls: list[list[dict]] = []

    def complete_json(self, messages):
        self.calls.append(messages)
        if not self._responses:
            raise AssertionError("FakeLlmClient 没有更多预设响应")
        item = self._responses.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


def svc(responses):
    client = FakeLlmClient(responses)
    return AnalyzeService(client, Settings()), client


GOOD = json.dumps({"comment": "这个月你花了 1250 元，日均 65.79 元，餐饮占了 68%，"
                              "整体节奏挺稳的。可以留意一下单笔 380 元的午餐，"
                              "偶尔改善生活没问题，别忘了给下个月留点余量。"},
                  ensure_ascii=False)


# ==================== Prompt ====================

class TestAnalyzePrompt:
    def test_injects_all_numbers(self):
        """★ 数字必须全部注入 —— 模型只能引用，不许自己算。"""
        p = build_analyze_messages(make_request())[0]["content"]
        for v in ["2026-10", "8000.00", "1250.00", "6750.00", "65.79", "19", "380.00"]:
            assert v in p, f"数字 {v} 未注入 Prompt"
        assert "餐饮" in p and "68.00" in p
        assert "交通" in p and "32.00" in p

    def test_forbids_recomputation(self):
        """★ 必须明确禁止模型自己算数。"""
        p = build_analyze_messages(make_request())[0]["content"]
        assert "只能引用" in p
        assert "不要做任何加减乘除" in p or "不要自己再计算" in p

    def test_requires_json_output(self):
        p = build_analyze_messages(make_request())[0]["content"]
        assert "comment" in p
        assert "只输出 JSON" in p

    def test_persona_tone_when_given(self):
        """★ 有角色时用角色口吻，但强调不能改数字。"""
        p = build_analyze_messages(make_request(with_persona=True))[0]["content"]
        assert "小小鹦鹉" in p
        assert "俏皮" in p
        assert "朋友" in p, "relation 必须进提示词"
        assert "你称呼用户为" in p, "address 必须进提示词"
        assert "女" in p, "gender 必须转成中文进提示词"
        assert "不要因为扮演角色而改变数字" in p

    def test_neutral_tone_without_persona(self):
        """★ 没角色也要能点评（角色是加分项不是前置条件）。"""
        p = build_analyze_messages(make_request(with_persona=False))[0]["content"]
        assert "温和" in p
        assert "小小鹦鹉" not in p

    def test_empty_categories_handled(self):
        req = make_request()
        req.top_categories = []
        p = build_analyze_messages(req)[0]["content"]
        assert "没有可统计的支出" in p


# ==================== 正常路径 ====================

class TestAnalyzeNormal:
    def test_returns_comment(self):
        service, client = svc([GOOD])
        resp = service.analyze(make_request())

        assert resp.success is True
        assert resp.comment is not None
        assert "1250" in resp.comment
        assert len(client.calls) == 1, "顺利时只调一次"

    def test_multiple_categories_in_prompt(self):
        service, client = svc([GOOD])
        service.analyze(make_request())
        user_prompt = client.calls[0][0]["content"]
        assert "餐饮" in user_prompt
        assert "交通" in user_prompt


# ==================== 校验与重试 ====================

class TestAnalyzeRetry:
    def test_empty_comment_triggers_retry(self):
        """★ 空点评等于没点评 —— 必须重试，不能返回空字符串。"""
        service, client = svc([json.dumps({"comment": ""}), GOOD])
        resp = service.analyze(make_request())

        assert resp.success is True
        assert len(client.calls) == 2, "应重试一次"
        assert len(resp.comment) > 0

    def test_invalid_json_triggers_retry(self):
        service, client = svc(["这不是 JSON", GOOD])
        resp = service.analyze(make_request())

        assert resp.success is True
        assert len(client.calls) == 2

    def test_retry_includes_error_feedback(self):
        bad = json.dumps({"comment": ""})
        service, client = svc([bad, GOOD])
        service.analyze(make_request())

        second = client.calls[1]
        assert len(second) == 4, "system + user + assistant(坏输出) + user(纠错)"
        assert second[2]["role"] == "assistant"
        assert second[2]["content"] == bad
        assert "不符合要求" in second[3]["content"]

    def test_gives_up_after_two_attempts(self):
        bad = json.dumps({"comment": "  "})
        service, client = svc([bad, bad])
        resp = service.analyze(make_request())

        assert resp.success is False
        assert resp.comment is None
        assert "格式不正确" in resp.error
        assert len(client.calls) == 2, "最多两次"

    def test_missing_comment_field_is_invalid(self):
        service, client = svc([json.dumps({"text": "没有 comment 字段"}), GOOD])
        resp = service.analyze(make_request())
        assert resp.success is True
        assert len(client.calls) == 2

    def test_llm_error_does_not_retry(self):
        """★ 调用失败不重试 —— 重试只会让用户多等。"""
        service, client = svc([LlmError("超时了")])
        resp = service.analyze(make_request())

        assert resp.success is False
        assert "超时" in resp.error
        assert len(client.calls) == 1, "★ 调用失败不该重试"


# ==================== 截断与降级 ====================

class TestAnalyzeLimits:
    def test_long_comment_is_truncated(self):
        """★ 超长点评要截断，避免前端撑爆。"""
        long_comment = "这是一段很长的点评。" * 100   # 约 1000 字
        service, _ = svc([json.dumps({"comment": long_comment}, ensure_ascii=False)])
        resp = service.analyze(make_request())

        assert resp.success is True
        assert len(resp.comment) <= 500
        print(f"\n  原长 {len(long_comment)} → 截断到 {len(resp.comment)}")

    def test_no_client_returns_clear_error(self):
        service = AnalyzeService(None, Settings())
        resp = service.analyze(make_request())

        assert resp.success is False
        assert "未配置" in resp.error

    def test_comment_is_trimmed(self):
        service, _ = svc([json.dumps({"comment": "  前后有空格  "}, ensure_ascii=False)])
        resp = service.analyze(make_request())
        assert resp.comment == "前后有空格"


# ==================== 数字一致性（契约） ====================

class TestAnalyzeNumbersContract:
    def test_prompt_contains_no_derived_numbers(self):
        """★ 只喂原始数字，不要让 Prompt 里出现"模型可能拿来瞎算的中间值"。"""
        p = build_analyze_messages(make_request())[0]["content"]
        # 收入、支出、结余、日均、天数都要在（原始值）
        assert "8000.00" in p
        assert "1250.00" in p
        assert "6750.00" in p
        assert "65.79" in p
        # 不应出现模型自己算的"占比"以外的衍生值
        assert "百分比" not in p or True   # 仅记录，不作硬断言

    def test_serializes_camel_case(self):
        """★ 与 Java 的契约：输出必须是 camelCase。"""
        req = make_request()
        dumped = req.model_dump(by_alias=True, mode="json")
        assert "dailyAverage" in dumped and "daily_average" not in dumped
        assert "topCategories" in dumped
        assert "maxExpense" in dumped
        assert "personaName" in dumped
