"""每日问候（GreetingService）测试 —— **不需要大模型 Key**。

覆盖：
  - 时段注入（★ 模型不许自己判断时间）
  - 数字注入（★ 模型不许自己算）
  - 角色口吻 / 中性口吻
  - **不重试**（超时敏感接口的关键差异）
  - 空 greeting 判为失败、超长截断
"""

import json
from datetime import datetime
from decimal import Decimal

from config import Settings
from llm_client import LlmError
from prompt import build_greeting_messages
from schemas import GreetingRequest
from service import GreetingService

NOW = datetime(2026, 10, 19, 8, 30, 0)


def make_request(bucket: str = "MORNING", with_persona: bool = False,
                 has_bill_today: bool = False) -> GreetingRequest:
    return GreetingRequest(
        now=NOW,
        time_bucket=bucket,
        weekday="星期一",
        month="2026-10",
        expense=Decimal("1250.00"),
        daily_average=Decimal("65.79"),
        days=19,
        today_expense=Decimal("88.00") if has_bill_today else Decimal("0"),
        has_bill_today=has_bill_today,
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
    return GreetingService(client, Settings()), client


GOOD = json.dumps({"greeting": "早上好呀！这个月你花了 1250 元，日均 65.79 元，"
                               "节奏挺稳的。今天记得记账哦～"},
                  ensure_ascii=False)


# ==================== Prompt ====================

class TestGreetingPrompt:
    def test_injects_time_bucket(self):
        """★★ 时段必须注入 —— 模型拿不到真实时间。"""
        p = build_greeting_messages(make_request("MORNING"))[0]["content"]
        assert "早上" in p, "MORNING 应描述为「早上」"

    def test_all_buckets_have_description(self):
        """七个时段都要能映射到中文，不能有漏网的。"""
        buckets = ["DAWN", "MORNING", "FORENOON", "NOON",
                   "AFTERNOON", "EVENING", "NIGHT"]
        for b in buckets:
            p = build_greeting_messages(make_request(b))[0]["content"]
            assert "现在是什么时候" in p, f"{b} 的 Prompt 缺少时段段落"
            # 时段描述不能是 None 或空
            assert "None" not in p, f"{b} 的描述缺失"

    def test_no_amount_injected(self):
        """★★ 问候的 Prompt 里【不许】出现任何金额。

        问候一天只生成一次并缓存，而金额一直在变：
        早上说"本月已花 200 元"，用户下午记两笔后首页右侧实时统计变成 500 元，
        两边对不上，用户会怀疑数据出错。所以金额交给统计面板，问候不报数。
        """
        p = build_greeting_messages(make_request())[0]["content"]
        assert "1250.00" not in p, "★ 不能把本月支出塞进 Prompt"
        assert "65.79" not in p, "★ 不能把日均支出塞进 Prompt"
        assert "88.00" not in p, "★ 不能把今日支出塞进 Prompt"

    def test_forbids_numbers(self):
        """★ 必须显式禁止模型报数字，否则它会自己编一个。"""
        p = build_greeting_messages(make_request())[0]["content"]
        assert "不要提到任何金额" in p
        assert "不要自己算" in p

    def test_today_not_booked(self):
        p = build_greeting_messages(make_request(has_bill_today=False))[0]["content"]
        assert "今天还没有记账" in p

    def test_today_already_booked(self):
        p = build_greeting_messages(make_request(has_bill_today=True))[0]["content"]
        assert "今天已经记过账" in p
        # ★ 记过账也只说事实，不报金额
        assert "88.00" not in p

    def test_persona_tone(self):
        p = build_greeting_messages(make_request(with_persona=True))[0]["content"]
        assert "小小鹦鹉" in p
        assert "俏皮" in p
        assert "朋友" in p, "relation 必须进提示词"
        assert "你称呼用户为" in p, "address 必须进提示词"
        assert "女" in p, "gender 必须转成中文进提示词"
        assert "不要因为扮演角色而编造事实" in p

    def test_neutral_without_persona(self):
        p = build_greeting_messages(make_request(with_persona=False))[0]["content"]
        assert "温和" in p
        assert "小小鹦鹉" not in p

    def test_requires_json(self):
        p = build_greeting_messages(make_request())[0]["content"]
        assert "greeting" in p
        assert "只输出 JSON" in p


# ==================== 正常 ====================

class TestGreetingNormal:
    def test_returns_greeting(self):
        service, client = svc([GOOD])
        resp = service.greeting(make_request())

        assert resp.success is True
        assert resp.greeting is not None
        assert "1250" in resp.greeting
        assert len(client.calls) == 1

    def test_trims_whitespace(self):
        service, _ = svc([json.dumps({"greeting": "  你好呀  "}, ensure_ascii=False)])
        assert service.greeting(make_request()).greeting == "你好呀"


# ==================== ★★ 不重试 ====================

class TestGreetingNoRetry:
    def test_invalid_json_does_not_retry(self):
        """★★ 与 parse/analyze 相反 —— 问候【不重试】。

        理由：Java 只等 3 秒，重试会让最坏耗时翻倍，那时前端已降级。
        """
        service, client = svc(["这不是 JSON", GOOD])
        resp = service.greeting(make_request())

        assert resp.success is False, "★ 不重试，直接失败"
        assert len(client.calls) == 1, "★★ 只调了一次，没有重试"
        assert client.calls, "确实调用过"

    def test_empty_greeting_fails_without_retry(self):
        service, client = svc([json.dumps({"greeting": "  "}), GOOD])
        resp = service.greeting(make_request())

        assert resp.success is False
        assert len(client.calls) == 1, "★ 不重试"

    def test_llm_error_does_not_retry(self):
        service, client = svc([LlmError("超时")])
        resp = service.greeting(make_request())

        assert resp.success is False
        assert "超时" in resp.error
        assert len(client.calls) == 1


# ==================== 边界 ====================

class TestGreetingLimits:
    def test_long_greeting_truncated(self):
        long_text = "今天天气不错哦。" * 50   # 约 400 字
        service, _ = svc([json.dumps({"greeting": long_text}, ensure_ascii=False)])
        resp = service.greeting(make_request())

        assert resp.success is True
        assert len(resp.greeting) <= 120
        print(f"\n  原长 {len(long_text)} → 截断到 {len(resp.greeting)}")

    def test_no_client_returns_error(self):
        service = GreetingService(None, Settings())
        resp = service.greeting(make_request())

        assert resp.success is False
        assert "未配置" in resp.error

    def test_missing_field_is_invalid(self):
        service, client = svc([json.dumps({"text": "没有 greeting 字段"})])
        resp = service.greeting(make_request())

        assert resp.success is False
        assert len(client.calls) == 1


# ==================== 契约 ====================

class TestGreetingContract:
    def test_camel_case(self):
        dumped = make_request().model_dump(by_alias=True, mode="json")
        assert "timeBucket" in dumped and "time_bucket" not in dumped
        assert "dailyAverage" in dumped
        assert "hasBillToday" in dumped
        assert "personaName" in dumped
