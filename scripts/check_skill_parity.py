#!/usr/bin/env python3
"""Verify that all published Vedic skill surfaces stay synchronized."""

from __future__ import annotations

import hashlib
from pathlib import Path
import sys


REPO_ROOT = Path(__file__).resolve().parents[1]
# 2026-09-04：真源目录 antigravity/skills/ → skills/。它不是某个平台的发行面，
# 而是所有发行面的来源，故用中立名并去掉多余的一层。
CANONICAL = REPO_ROOT / "skills"
SURFACES = {
    "claude-code": REPO_ROOT / "claude-code" / "skills",
    "codex": REPO_ROOT / "codex" / "skills",
}
IGNORED_NAMES = {".DS_Store", "__pycache__"}
LEGACY_FILES = (
    REPO_ROOT / "claude-code" / ".claude" / "commands" / "vedic-core.md",
    REPO_ROOT / "claude-code" / ".claude" / "commands" / "vedic-career.md",
    REPO_ROOT / "claude-code" / ".claude" / "commands" / "vedic-love.md",
    REPO_ROOT / "scripts" / "report_builder.py",
)


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def skill_names(root: Path) -> set[str]:
    return {
        item.name
        for item in root.iterdir()
        if item.is_dir() and item.name.startswith("vedic-")
    }


def published_files(root: Path) -> dict[Path, str]:
    files: dict[Path, str] = {}
    for item in root.rglob("*"):
        if not item.is_file():
            continue
        relative = item.relative_to(root)
        if any(part in IGNORED_NAMES for part in relative.parts):
            continue
        if "agents" in relative.parts:
            continue
        files[relative] = file_digest(item)
    return files


def agent_files(root: Path) -> set[Path]:
    return {
        item.relative_to(root)
        for item in root.rglob("*")
        if item.is_file()
        and item.name not in IGNORED_NAMES
        and len(item.relative_to(root).parts) >= 3
        and item.relative_to(root).parts[1] == "agents"
    }


def main() -> int:
    errors: list[str] = []
    canonical_skills = skill_names(CANONICAL)
    canonical_files = published_files(CANONICAL)

    for surface_name, surface_root in SURFACES.items():
        surface_skills = skill_names(surface_root)
        if surface_skills != canonical_skills:
            errors.append(
                f"{surface_name}: skill set differs; "
                f"missing={sorted(canonical_skills - surface_skills)}, "
                f"extra={sorted(surface_skills - canonical_skills)}"
            )

        surface_files = published_files(surface_root)
        missing = sorted(canonical_files.keys() - surface_files.keys())
        extra = sorted(surface_files.keys() - canonical_files.keys())
        changed = sorted(
            relative
            for relative in canonical_files.keys() & surface_files.keys()
            if canonical_files[relative] != surface_files[relative]
        )
        if missing:
            errors.append(f"{surface_name}: missing files: {missing}")
        if extra:
            errors.append(f"{surface_name}: extra files: {extra}")
        if changed:
            errors.append(f"{surface_name}: changed files: {changed}")

    codex_root = SURFACES["codex"]
    expected_agents = {
        Path(skill_name) / "agents" / "openai.yaml"
        for skill_name in canonical_skills
    }
    actual_agents = agent_files(codex_root)
    if actual_agents != expected_agents:
        errors.append(
            "codex: agents metadata differs; "
            f"missing={sorted(expected_agents - actual_agents)}, "
            f"extra={sorted(actual_agents - expected_agents)}"
        )
    for relative in sorted(expected_agents & actual_agents):
        skill_name = relative.parts[0]
        metadata = (codex_root / relative).read_text(encoding="utf-8")
        required_fields = ("display_name:", "short_description:", "default_prompt:")
        missing_fields = [
            field for field in required_fields if field not in metadata
        ]
        if missing_fields:
            errors.append(
                f"codex: {relative} missing interface fields: {missing_fields}"
            )
        if f"${skill_name}" not in metadata:
            errors.append(
                f"codex: {relative} default prompt does not invoke ${skill_name}"
            )

    for surface_name in ("skills", "claude-code"):
        surface_root = (
            CANONICAL if surface_name == "skills" else SURFACES[surface_name]
        )
        unexpected_agents = agent_files(surface_root)
        if unexpected_agents:
            errors.append(
                f"{surface_name}: unexpected agents metadata: "
                f"{sorted(unexpected_agents)}"
            )

    for legacy_file in LEGACY_FILES:
        if legacy_file.exists():
            errors.append(
                f"legacy duplicate must stay removed: "
                f"{legacy_file.relative_to(REPO_ROOT)}"
            )

    if errors:
        for error in errors:
            print(f"[FAIL] {error}")
        return 1

    print(
        f"[OK] {len(canonical_skills)} skills match across canonical skills/, "
        "Claude Code, and Codex."
    )
    print("[OK] Codex has exactly one agents/openai.yaml per skill.")
    print("[OK] No legacy Claude commands or root report builder remain.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
