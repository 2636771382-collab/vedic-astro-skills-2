#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
仓 → 平台副本 / 本机安装点 的同步器（「仓即源」改造，取代 sync_all.ps1）。

方向是反过来的：旧脚本 源(.gemini/config/skills) → 仓，本脚本 仓 → 本机。
理由（SKILL_LOG「仓即源」2026-08-28 定案）：现状本来就是仓在当源——Mac 一个月的
工作全部直接进仓、GitHub 一直是最新的，腐坏的是那个从不被读的 Windows 源目录。
旧方向留着就是回退炸弹：源一旦落后，跑一次就用旧覆盖新，还自动 push 出去。

真源 = <repo>/skills/    （build_anchored.py 的 SKILL_REL 也指这里）

子命令：
  platforms  仓内 skills/ → claude-code + codex
  install    仓 → 本机 ~/.claude/skills
  all        先 platforms 后 install

⚠️ 本脚本不做 git add/commit/push。旧脚本那段用 `2>$null` 吞掉所有错误、
   再无条件打印「✅ Pro仓库 pushed」，是假成功的来源。提交推送一律手工，
   push 前须创始人确认。

跑法：
  python skill_sync.py all
  python skill_sync.py all --dry-run
  python skill_sync.py install --pro /path/to/vedic-astro-skills-pro
"""
import argparse
import hashlib
import shutil
import sys
from pathlib import Path

# 2026-09-04：真源目录 antigravity/skills/ → skills/。
# ⚠️ 这不只是改名，是修一个概念错误：真源本来就不是「Antigravity 这个平台的发行面」，
#    它是**所有发行面的来源**。旧结构把它塞进 PLATFORMS 列表里当成员之一，再靠
#    `if plat == CANON_PLATFORM: continue` 把自己跳过去——这是代码在绕开自己的分类错误。
#    现在 CANON_DIR 与 PLATFORMS 彻底分开，循环里那条 continue 也随之删掉。
CANON_DIR = 'skills'                    # 真源，不是平台
PLATFORMS = ['claude-code', 'codex']    # 由真源派生的发行面

# 只同步这些。codex 的 agents/openai.yaml 与各平台 README 是手工层，不在此列
# = 不会被覆盖（它们由 consistency_lint 第8组守门）。
# tests 是 2026-09-03 补入的：consistency_lint 第7组依赖
# vedic-prashna/tests/test_prashna_isolation.py，而旧 SYNC_ITEMS 漏了 tests，
# 导致该文件只存在于本机源目录、从未进过仓——正是"不在同步范围所以悄悄漂移"
# 这类缺口的成因，故一并纳入。
SYNC_ITEMS = ['SKILL.md', 'resources', 'scripts', 'requirements.txt', 'tests']

# 2026-09-04：把 ephe/ 与 .se1 移出排除名单。它们是 setup_env.fix_ephemeris_data
# 的优先级 1 数据源（PyJHora 4.8.6 的 pip 包缺这三个 .se1，缺了就退到 Moshier）。
# 排除的后果是潜伏型：现有端点的 ephe 是 sync_all.ps1 时代铺下的历史遗留，一直在，
# 所以无症状；只有**全新端点首次 install 到空目录**才会缺，届时 setup_env 退到网络
# 下载分支而该 URL 已 404。三处 ephe 内容本就相同，解除排除后 copy_one 比对即跳过，
# 实际文件零变动。（find_orphans 对目标侧也调 iter_files，故此前 --prune 不会误删。）
EXCLUDE_DIRS = {'__pycache__', 'venv', '.git'}
EXCLUDE_EXTS = {'.pyc', '.pyo'}

# (仓内目录名, 本机安装名, 归属仓)
# ⚠️ 最后一行是唯一需要改名的：Pro 仓里 core 同样叫 vedic-core，
#    只有装到本机时才区分成 vedic-core-pro。手工安装最容易在这里
#    把 Pro 版覆盖到开源版头上，且不报错。
SKILLS = [
    ('vedic-reader',     'vedic-reader',     'shared'),
    ('vedic-career',     'vedic-career',     'shared'),
    ('vedic-love',       'vedic-love',       'shared'),
    ('vedic-synastry',   'vedic-synastry',   'shared'),
    ('vedic-rectifier',  'vedic-rectifier',  'shared'),
    ('vedic-prashna',    'vedic-prashna',    'shared'),
    ('vedic-calculator', 'vedic-calculator', 'shared'),
    ('vedic-core',       'vedic-core',       'open'),
    ('vedic-core',       'vedic-core-pro',   'pro'),
]

# 本机安装点。⚠️ 这里全是**消费端**，一个都不是源——这正是本次改造的要点。
# 旧世界里 ~/.gemini/config/skills 身兼两职（既当真源、又当安装点），角色一混，
# 「同步」的方向就取决于谁记得当时是哪个身份，于是同一个脚本既可能分发最新、
# 也可能用旧覆盖新。现在真源只有 <repo>/skills/ 一处，下面这些只收不发。
#
# 2026-09-04 当日反复：先去掉 Antigravity（以为已弃用），当日又加回——创始人仍偶尔开
# 该端，且那边另有 92 个非 vedic 的 skill 在用，目录并未废弃。**加回的是安装端点身份，
# 不是真源身份**：它现在和 ~/.claude/skills 平级，都是从仓里铺下去的副本。
INSTALL_ROOTS = [
    ('claude', Path.home() / '.claude' / 'skills'),
    ('antigravity', Path.home() / '.gemini' / 'config' / 'skills'),
]


def sha(p: Path) -> str:
    return hashlib.sha1(p.read_bytes()).hexdigest()


def iter_files(root: Path):
    """列 root 下应同步的文件（相对路径），套排除规则。"""
    if not root.exists():
        return
    for p in root.rglob('*'):
        if not p.is_file():
            continue
        if any(part in EXCLUDE_DIRS for part in p.parts):
            continue
        if p.suffix in EXCLUDE_EXTS:
            continue
        yield p.relative_to(root)


def copy_one(s: Path, d: Path, label: str, dry: bool) -> int:
    if d.exists() and sha(s) == sha(d):
        return 0
    print(f'  [{"DIFF" if dry else "SYNC"}] {label}')
    if not dry:
        d.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(s, d)
    return 1


def sync_skill(src: Path, dst: Path, label: str, dry: bool) -> int:
    """按 SYNC_ITEMS 把 src 同步到 dst，返回改动文件数。"""
    n = 0
    for item in SYNC_ITEMS:
        s = src / item
        if not s.exists():
            continue
        if s.is_dir():
            for rel in iter_files(s):
                n += copy_one(s / rel, dst / item / rel, f'{label}/{item}/{rel}', dry)
        else:
            n += copy_one(s, dst / item, f'{label}/{item}', dry)
    return n


def find_orphans(src: Path, dst: Path, label: str):
    """目标有、源没有的文件。只报不删——仓里删掉的文件不会自己从本机消失，
    静默留着旧版比多打几行提示危险得多。真要删用 --prune。"""
    out = []
    for item in SYNC_ITEMS:
        s, d = src / item, dst / item
        if not d.exists() or not d.is_dir():
            continue
        have = set(iter_files(s)) if s.exists() else set()
        for rel in iter_files(d):
            if rel not in have:
                out.append((f'{label}/{item}/{rel}', d / rel))
    return out


def cmd_platforms(repos, dry: bool) -> int:
    total = 0
    for label, repo in repos:
        canon = repo / CANON_DIR
        if not canon.exists():
            print(f'  [SKIP] {label}: 无 {CANON_DIR}/')
            continue
        for name in sorted(d.name for d in canon.iterdir() if d.is_dir()):
            for plat in PLATFORMS:
                # 目标不存在也建：新增 skill 才不会漏掉某个平台。
                # codex 会因此缺 agents/openai.yaml，由 lint 第8组报出来提醒补手工层。
                total += sync_skill(canon / name, repo / plat / 'skills' / name,
                                    f'{label}/{plat}/{name}', dry)
    return total


def cmd_install(repo_open: Path, repo_pro: Path, dry: bool, prune: bool) -> int:
    total = 0
    orphans = []
    for src_name, dst_name, kind in SKILLS:
        repo = repo_pro if kind == 'pro' else repo_open
        if repo is None or not repo.exists():
            print(f'  [SKIP] {dst_name}: 归属仓不可用'
                  f'{"（Pro 私有仓未克隆）" if kind == "pro" else ""}')
            continue
        src = repo / CANON_DIR / src_name
        if not src.exists():
            print(f'  [SKIP] {dst_name}: {src} 不存在')
            continue
        for root_label, root in INSTALL_ROOTS:
            dst = root / dst_name
            total += sync_skill(src, dst, f'{root_label}/{dst_name}', dry)
            orphans += find_orphans(src, dst, f'{root_label}/{dst_name}')

    if orphans:
        print(f'\n  ── 本机多出、仓里没有的文件（{len(orphans)} 个）──')
        for label, path in orphans:
            print(f'  [ORPHAN] {label}')
            if prune and not dry:
                path.unlink()
                print('           ^ 已删除 (--prune)')
        if not prune:
            print('  （只报不删；确认无用后加 --prune 清理）')
    return total


def check_shared_parity(repo_open: Path, repo_pro: Path):
    """shared skill 在两仓各有一份、应逐字一致；install 取开源仓那份
    （Mac 可能只克隆了开源仓）。不一致是 consistency_lint 第3组的活儿，
    这里出 WARN，不让它静默。"""
    if repo_pro is None or not repo_pro.exists():
        return
    for src_name, _, kind in SKILLS:
        if kind != 'shared':
            continue
        a = repo_open / CANON_DIR / src_name
        b = repo_pro / CANON_DIR / src_name
        if not a.exists() or not b.exists():
            continue
        for rel in iter_files(a):
            fb = b / rel
            if not fb.exists() or sha(a / rel) != sha(fb):
                print(f'  [WARN] 两仓 shared 不一致: {src_name}/{rel} '
                      f'—— install 用的是开源仓那份，请跑 consistency_lint 查第3组')


def main():
    ap = argparse.ArgumentParser(description='仓 → 平台副本 / 本机安装点 同步器')
    ap.add_argument('cmd', choices=['platforms', 'install', 'all'])
    ap.add_argument('--pro', help='Pro 仓路径（默认 <开源仓>/../vedic-astro-skills-pro）')
    ap.add_argument('--dry-run', action='store_true', help='只报差异，不写文件')
    ap.add_argument('--prune', action='store_true', help='install 时删除本机多出的文件')
    args = ap.parse_args()

    repo_open = Path(__file__).resolve().parent
    repo_pro = Path(args.pro) if args.pro else repo_open.parent / 'vedic-astro-skills-pro'
    if not repo_pro.exists():
        print(f'[NOTE] Pro 仓不存在，跳过其相关同步: {repo_pro}')
        repo_pro = None

    print(f'\n{"=" * 46}')
    print(f'  skill_sync · {args.cmd}{"  [DRY RUN]" if args.dry_run else ""}')
    print(f'  开源仓 {repo_open}')
    print(f'  Pro仓  {repo_pro if repo_pro else "(无)"}')
    print(f'{"=" * 46}\n')

    repos = [('OS', repo_open)] + ([('Pro', repo_pro)] if repo_pro else [])
    total = 0

    if args.cmd in ('platforms', 'all'):
        print('── 仓内三平台拉平 ──')
        total += cmd_platforms(repos, args.dry_run)

    if args.cmd in ('install', 'all'):
        print('\n── 装到本机 ──')
        check_shared_parity(repo_open, repo_pro)
        total += cmd_install(repo_open, repo_pro, args.dry_run, args.prune)

    if total == 0:
        print('\n  所有端点已一致，无需同步')
    print(f'\n完成（{"待同步" if args.dry_run else "已同步"} {total} 个文件）')
    print('提醒：本脚本不碰 git。commit / push 手工做，push 前须创始人确认。\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
