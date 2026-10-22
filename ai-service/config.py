"""配置：全部来自环境变量 / .env，代码里不出现任何密钥。

★ 为什么用 pydantic-settings，而不是直接 os.getenv：
  它在**启动时**就把配置校验并定型，缺字段或类型不对会立刻报错（fail-fast），
  而不是等到第一次调用大模型时才炸在深处。联调时这一点很省时间。

★ 字段名 → 环境变量名是自动的（大写），例如 llm_api_key → LLM_API_KEY。
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    llm_api_key: str = ""
    llm_base_url: str = "https://api.deepseek.com"
    llm_model: str = "deepseek-flash"

    llm_timeout_seconds: float = 15.0

    llm_max_tokens: int = 2000
    llm_temperature: float = 0.0  # 抽取结构化数据要确定性，不要创造性

    max_text_length: int = 500 #用户输入字符上限
    max_bills:int = 20 #返回的最大笔数
    max_candidates:int = 500


    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )

    @property
    def llm_configured(self) -> bool:
        return bool(self.llm_api_key.strip())


# ----------------------------------------------------------------------
# 模块级单例。其他模块直接 `from config import settings` 使用。
# ----------------------------------------------------------------------
settings = Settings()