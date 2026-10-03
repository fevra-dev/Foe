# Foe — project instructions

Read `/adr` before writing or modifying code; treat each `NNNN-*.md` as a hard constraint that
overrides defaults. A new architectural/security/dependency decision → a new ADR from
`adr/TEMPLATE.md`, grilled with a threat-model, then enforced.

**Pre-push gate:** `.githooks/lint-gate.sh` — branch guard → Oxlint → Biome → ruff → Trivy → Semgrep →
bandit → actions-pin → dependency-cruiser → Sigma → redact; each stage self-skips if its config/tool/files
are absent (e.g. the JS stages self-skip on a Python-only repo). The branch guard blocks direct pushes to
`main`/`master` (override: `ALLOW_MAIN_PUSH=1 git push`); actions-pin enforces ADR-0018 (SHA-pinned
actions + top-level `permissions:`) over `.github/`. Pin JS/TS tools as devDeps (ADR-0001):
`npm i -D oxlint @biomejs/biome eslint-plugin-oxlint` (add `dependency-cruiser` for architecture rules).
Python repos (`pyproject.toml`): `uv tool install ruff bandit` — the ruff (lint/format) + bandit
(SAST, HIGH/HIGH) stages auto-detect and self-skip elsewhere (STACK §20). The redact stage (ADR-0011)
scrubs agent-written `.audit/` reports of secrets/PII via the global `~/.claude/scripts/redact.py`;
it self-skips when there's no `.audit/`.

Operator profile + global conventions: `~/Apps/WORKFLOW/OPERATOR.md`, `STACK.md`, `HARNESS.md`.
