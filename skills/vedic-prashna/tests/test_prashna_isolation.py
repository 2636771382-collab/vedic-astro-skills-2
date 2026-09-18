"""
Prashna 隔离回归测：确保沙箱化硬约束不被打破。
三条断言，任一红即禁止上线。每次 sync 前必跑。

对应 SKILL.md §沙箱化硬约束 的四条自律与三条回归断言。
"""
import os
import re
import sys
from pathlib import Path

# 从本文件位置推导 skills 根，不再硬编码机器路径（Mac 也要能跑）。
#   仓布局   <repo>/skills/vedic-prashna/tests/本文件
#   安装布局 ~/.claude/skills/vedic-prashna/tests/本文件
# 两种布局下 parents[2] 都是 skills 根。
# VEDIC_SKILLS_ROOT 可覆盖：consistency_lint 靠它对开源仓/Pro 仓各跑一遍——
# Pro 的 core 在仓里同样叫 vedic-core（不叫 vedic-core-pro），只跑开源仓根的话
# 断言2的 GUARDED_SKILLS 会 exists()==False 静默跳过 Pro，那是漏检不是通过。
SKILLS_ROOT = Path(os.environ.get("VEDIC_SKILLS_ROOT") or Path(__file__).resolve().parents[2])
PRASHNA_ROOT = SKILLS_ROOT / "vedic-prashna"


# ---------------------------------------------------------------------------
# 断言 1：共享 engine.py / formatter.py 输出字段无异体系 key
# ---------------------------------------------------------------------------
FORBIDDEN_ENGINE_KEYS = [
    "tajika",
    "ithasala",
    "kp_",
    "sub_lord",
    "sublord",
    "void_of_course",
    "kambula",
    "kamboola",
    "ishraga",
    "muthashila",
    "manau",
]


def test_engine_no_alien_fields() -> None:
    hits = []
    for fname in ("engine.py", "formatter.py"):
        path = SKILLS_ROOT / "vedic-calculator" / "scripts" / fname
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8", errors="ignore").lower()
        for key in FORBIDDEN_ENGINE_KEYS:
            if key in text:
                hits.append(f"{fname}: {key}")
    assert not hits, (
        "红灯：共享 engine 出现异体系 key —— 违反沙箱化硬约束 #1。\n"
        "engine.py/formatter.py 是所有 skill 共享的核心，异体系字段一旦进入即污染主系统。\n"
        "修复：把这些字段全部移回 vedic-prashna/scripts/calc_optional_*.py。\n"
        f"命中: {hits}"
    )


# ---------------------------------------------------------------------------
# 断言 2：主系统 skill 规则文件无 Prashna 异体系术语
# ---------------------------------------------------------------------------
GUARDED_SKILLS = [
    "vedic-core",
    "vedic-core-pro",
    "vedic-love",
    "vedic-career",
    "vedic-synastry",
    "vedic-rectifier",
    "vedic-reader",
]

FORBIDDEN_TERMS = [
    "prashna",
    "tajika",
    "ithasala",
    "sub-lord",
    "sub_lord",
    "sublord",
    "chandra kriya",
]


def test_shared_rules_no_prashna_terms() -> None:
    offenders = []
    for skill in GUARDED_SKILLS:
        skill_root = SKILLS_ROOT / skill
        if not skill_root.exists():
            continue
        for path in skill_root.rglob("*.md"):
            text = path.read_text(encoding="utf-8", errors="ignore").lower()
            for term in FORBIDDEN_TERMS:
                if term in text:
                    rel = path.relative_to(SKILLS_ROOT)
                    offenders.append(f"{rel}: '{term}'")
    assert not offenders, (
        "红灯：主系统 skill 规则文件出现 Prashna/异体系术语 —— 违反沙箱化硬约束 #2。\n"
        "主系统 skill 应对 Prashna 零感知。这些术语必须完全存在于 vedic-prashna/ 内。\n"
        f"命中:\n  " + "\n  ".join(offenders)
    )


# ---------------------------------------------------------------------------
# 断言 3：Prashna 产物物理隔离
# ---------------------------------------------------------------------------
def test_prashna_products_in_isolated_dir() -> None:
    build_script = PRASHNA_ROOT / "scripts" / "build_prashna_data.py"
    if not build_script.exists():
        # 尚未实现，跳过（Task #2 才落地）
        return

    text = build_script.read_text(encoding="utf-8", errors="ignore")

    # 剥掉 # 注释与三引号 docstring/字符串块，只检查真实代码体。
    # 允许注释/docstring 自由说明"与本命 structured_data.md 严格区分"这类关系。
    code = re.sub(r'"""[\s\S]*?"""', '', text)
    code = re.sub(r"'''[\s\S]*?'''", '', code)
    code = re.sub(r'#[^\n]*', '', code)

    assert "prashna_" in code, (
        "红灯：build_prashna_data.py 代码体未见 'prashna_' 独立目录前缀 —— 违反沙箱化硬约束 #3。\n"
        "所有产物必须写 prashna_<yyyymmdd_HHMM>_<label>/ 独立子目录。"
    )

    assert "structured_prashna.md" in code, (
        "红灯：build_prashna_data.py 代码体未见产物名 'structured_prashna.md' —— 违反沙箱化硬约束 #3。\n"
        "Prashna 产物必须命名为 structured_prashna.md，与本命根 structured_data.md 严格区分。"
    )

    forbidden_writes = re.findall(r"structured_data\.md", code)
    assert not forbidden_writes, (
        "红灯：build_prashna_data.py 代码体(排除注释/docstring)中出现 structured_data.md —— "
        "违反沙箱化硬约束 #3。Prashna 产物名必须为 structured_prashna.md，"
        "代码路径不得写入或引用本命根 structured_data.md。"
    )


# ---------------------------------------------------------------------------
# CLI 入口
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# 断言 4：主系统 skill 的 scripts 不得反向 import vedic-prashna 沙箱模块
# ---------------------------------------------------------------------------
# 防呆：SAV / graha drishti 越权都是"未预见路径泄漏"栽的；
# 主 skill 会话 2026-07-12 复核建议加此断言。
PRASHNA_SANDBOX_MODULES = [
    "calc_moon_vedic",
    "calc_optional_tajika",
    "calc_optional_kp",
    "build_prashna_data",
    "vedic_prashna",   # 目录名换 py 合法标识符的可能拼法
]


def test_no_reverse_import_from_prashna() -> None:
    guarded_skills = [
        "vedic-core", "vedic-core-pro", "vedic-love", "vedic-career",
        "vedic-synastry", "vedic-rectifier", "vedic-reader", "vedic-calculator",
    ]
    offenders = []
    for skill in guarded_skills:
        skill_root = SKILLS_ROOT / skill
        if not skill_root.exists():
            continue
        for path in skill_root.rglob("*.py"):
            if "__pycache__" in str(path):
                continue
            text = path.read_text(encoding="utf-8", errors="ignore")
            for mod in PRASHNA_SANDBOX_MODULES:
                # 匹配 `import <mod>` 或 `from <mod> import ...`(行首)
                pattern = rf"^\s*(?:from\s+{re.escape(mod)}\b|import\s+{re.escape(mod)}\b)"
                if re.search(pattern, text, re.MULTILINE):
                    rel = path.relative_to(SKILLS_ROOT)
                    offenders.append(f"{rel}: import '{mod}'")
            # 也扫路径字符串 `vedic-prashna/scripts` 出现在 sys.path 操作里
            if "vedic-prashna" in text and re.search(r"sys\.path|import", text):
                # 只在真的 import 上下文里报（避免注释误伤）
                for line in text.split("\n"):
                    line_lower = line.lower()
                    if "vedic-prashna" in line_lower and (
                        "sys.path" in line_lower or "importlib" in line_lower
                    ):
                        rel = path.relative_to(SKILLS_ROOT)
                        offenders.append(f"{rel}: sys.path/importlib refers 'vedic-prashna'")
                        break
    assert not offenders, (
        "红灯：主系统 skill 的 scripts 反向 import Prashna 沙箱模块 —— 违反沙箱化(防呆断言 #4)。\n"
        "主系统不应依赖 Prashna 沙箱代码；即便当前用途看似合理，也会让沙箱变成'半共享'、"
        "泄漏路径破防。修复：把该逻辑挪回 vedic-prashna/scripts/ 内、或改由主系统独立实现。\n"
        "命中:\n  " + "\n  ".join(offenders)
    )


def _run(name, fn):
    try:
        fn()
        print(f"[PASS] {name}")
        return True
    except AssertionError as e:
        print(f"[FAIL] {name}\n{e}\n")
        return False


if __name__ == "__main__":
    results = [
        _run("engine_no_alien_fields", test_engine_no_alien_fields),
        _run("shared_rules_no_prashna_terms", test_shared_rules_no_prashna_terms),
        _run("prashna_products_in_isolated_dir", test_prashna_products_in_isolated_dir),
        _run("no_reverse_import_from_prashna", test_no_reverse_import_from_prashna),
    ]
    if all(results):
        print("\n[GREEN] 四条隔离断言全绿。")
        sys.exit(0)
    else:
        print("\n[RED] 隔离断言未全绿，禁止上线。")
        sys.exit(1)
