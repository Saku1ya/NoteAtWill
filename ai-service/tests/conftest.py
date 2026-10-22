"""pytest 配置：把 ai-service 根目录加入 sys.path。

因为各模块（config / schemas / prompt / service）是平铺在根目录的、不是包，
所以测试里需要显式加路径。否则会报 ModuleNotFoundError: No module named 'schemas'。
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
