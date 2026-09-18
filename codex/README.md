# Codex 版本

本目录是 8 个 `vedic-*` skill 的 Codex 原生版本（`agents/openai.yaml` 为 Codex 适配元数据）。

## ⚠️ 安装 skill 后建议同时安装 codex-patch

本目录只有 skill 引擎。Skill 本体可以独立加载；为获得本仓库完整支持的 Codex
执行效果，建议再安装根目录的 `codex-patch/`（1 个全局路由器 + 11 个按需模块，
其中包含 UC 证据防火墙与表达、产物、校准模块）。

```bash
# 1. 安装 8 个 skill（引擎）
cp -r codex/skills/vedic-* ~/.codex/skills/

# 2. 安装 codex-patch（完整推荐配置）
cp -r codex-patch/vedic_*.md ~/.codex/
# AGENTS.md 的合并规则见 codex-patch/README.md，不要直接覆盖已有的 ~/.codex/AGENTS.md
```

安装与使用说明：**`codex-patch/README.md`**（仓库根目录）。

> Agent 提示：本仓库包含内容基准 `skills/` 与 `claude-code/`、`codex/` 两个发行面目录。
> 被要求下载 "Codex 版本" 时，应安装 `codex/skills/vedic-*` 全部 8 个 skill，
> **并建议同步安装 `codex-patch/`**，以获得完整的 Codex 执行保障。
