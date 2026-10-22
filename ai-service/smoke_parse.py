"""冒烟测试：对着【已运行】的 AI 服务打真实请求。

用法：
    终端 A： .venv\\Scripts\\python.exe -m uvicorn main:app --port 8000
    终端 B： .venv\\Scripts\\python.exe smoke_parse.py

★ 它跑的就是计划里那个「当日必达」用例：
    输入：昨天中午吃饭25，打车18，超市买水果56.5
    期望：解析出 3 笔，分类落在 餐饮 / 交通 / 购物，日期都是昨天
  Key 未配置时会明确提示，不会静默失败。
"""

import sys
from datetime import datetime

import httpx

BASE = "http://127.0.0.1:8000"

# 与数据库预置分类对齐（sql/02_seed.sql 里餐饮/交通/购物的一级分类 id）
CATEGORIES = [
    {"id": 1001, "name": "餐饮", "type": "EXPENSE"},
    {"id": 1011, "name": "餐饮/午餐", "type": "EXPENSE"},
    {"id": 1012, "name": "餐饮/晚餐", "type": "EXPENSE"},
    {"id": 1002, "name": "交通", "type": "EXPENSE"},
    {"id": 1021, "name": "交通/打车", "type": "EXPENSE"},
    {"id": 1003, "name": "购物", "type": "EXPENSE"},
    {"id": 1031, "name": "购物/日用", "type": "EXPENSE"},
    {"id": 2001, "name": "职业收入", "type": "INCOME"},
]
ACCOUNTS = [{"id": 5, "name": "微信"}, {"id": 6, "name": "招行卡"}]

CASES = [
    "昨天中午吃饭25，打车18，超市买水果56.5",
    "今天早上公交2块，午饭食堂15",
    "上周五发工资8000",
    "买菜花了33.8，用微信付的",
    "星巴克38",
]


def main() -> int:
    print("=" * 72)
    try:
        h = httpx.get(f"{BASE}/health", timeout=5).json()
    except Exception as exc:  # noqa: BLE001
        print(f"服务不可达：{exc}")
        print("请先运行：.venv\\Scripts\\python.exe -m uvicorn main:app --port 8000")
        return 1

    print("健康检查：", h)
    if not h.get("llmConfigured"):
        print()
        print("!! 未配置大模型 Key —— 无法做真实解析。")
        print("   请把 .env.example 复制成 .env，填入 LLM_API_KEY 后重启服务。")
        return 2

    now = datetime.now().replace(microsecond=0).isoformat()
    print(f"注入的当前时间：{now}")
    print("=" * 72)

    ok = 0
    for i, text in enumerate(CASES, 1):
        payload = {"text": text, "now": now,
                   "categories": CATEGORIES, "accounts": ACCOUNTS}
        try:
            r = httpx.post(f"{BASE}/ai/parse-bill", json=payload, timeout=40)
        except Exception as exc:  # noqa: BLE001
            print(f"\n[{i}] 请求失败：{exc}")
            continue

        if r.status_code != 200:
            print(f"\n[{i}] HTTP {r.status_code}: {r.text[:300]}")
            continue

        body = r.json()
        print(f"\n[{i}] 输入：{text}")
        if not body.get("success"):
            print(f"    失败：{body.get('error')}")
            continue

        for b in body["bills"]:
            cat = b.get("categoryName") or "(未匹配)"
            hint = b.get("accountHint") or "-"
            print(f"    {b['type']:<8} {b['amount']:>8}  "
                  f"分类={cat:<10} 时间={b['billTime']}  "
                  f"账户提示={hint}  备注={b.get('remark')}  conf={b.get('confidence')}")
        if body.get("unmatched"):
            print(f"    未识别：{body['unmatched']}")
        ok += 1

    print()
    print("=" * 72)
    print(f"{ok}/{len(CASES)} 条返回成功结构")
    return 0 if ok == len(CASES) else 1


if __name__ == "__main__":
    sys.exit(main())
