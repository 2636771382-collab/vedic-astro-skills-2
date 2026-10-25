# 印度占星原生计算验收记录（2026-10-25）

## 验收范围
这是对**数值计算程序**的验收，不是对占星预测有效性的科学验证。个人出生参数不应写进公开 GitHub Actions 的工作流、手动触发字段或构建日志。

### 已通过
- GitHub Actions: Ubuntu 24.04 + Python 3.12 + PyJHora 4.8.6 + Swiss Ephemeris。
- 完整三份 .se1 星历；上游 engine v1.0.1。
- 仓库原生 `skills/vedic-calculator/tests/test_engine_precision.py`：11/11 通过。
- 2004-09-12 11:15:27 上海 JHora 桌面金标准：Lagna、七曜与岁差共9项均误差≤0.5角秒。
- 最严格误差值：最大约0.006402角秒（七曜加Lagna和岁差）。
- 原生 `calculate_full_chart` 输出：SAV 12星座总计337、Shadbala七曜、MD 9段、AD 81段、PD 729段。
- `dasha_query.py --check`：729/729段，时间区间连续性通过。

## 复现
打开 GitHub Actions → **Vedic native engine validation** → **Run workflow**。
实现文件：`.github/workflows/vedic-native-validation.yml`、`tools/vedic_native_audit.py`。
测试数据属于公共金标准，不包含私人出生信息。下载工作流产物 `vedic-native-gold-reference`，其中含 JSON 误差表和原生 structured_data.md。

## 限制
1. 金标准通过，不保证任何出生时间输入精度都足够；D9/D10 出生时刻扰动需要逐盘审计。
2. 此处原生计算只生成 MD/AD/PD（三级）。不得未经实际计算而宣称 L4/L5 得到原生验证。
3. DeepSeek 可以审核原始文件，但与 GPT 解读相似，不构成计算引擎的独立第二验证。
4. 本仓库为公开仓库。私人本命盘产物应保存在私有环境或聊天附件中，不应提交到 main。

## 验收链接
- [GitHub Actions 成功运行](https://github.com/2636771382-collab/vedic-astro-skills-2/actions/runs/40188933667)
- [原生计算器](../skills/vedic-calculator/SKILL.md)
