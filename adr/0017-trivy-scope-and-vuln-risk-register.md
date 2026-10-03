# ADR-0017 — Trivy scoping & vulnerability risk register (per-repo opt-in)

**Status:** Accepted · **Date:** 2026-07-26
**Supersedes:** — · **Superseded-by:** — · **Refines:** ADR-0001 (Zero-Trust Dependencies)
**Amended-by:** Addendum 2026-08-22 (below) — **§6's "reviewed surface" wording is corrected there.** · Addendum 2026-08-24 (below) — **§Enforcement's "Not built in this session" is corrected there.**
**Threat-model:** carried from kiln ADR-0017 (grilled 8 vectors, 2026-07-23); re-confirmed for the per-repo-opt-in framing (adds no new surface) · **Sign-off:** operator, 2026-07-26

## Context
ADR-0001 wires `trivy fs --scanners vuln,secret,license --severity HIGH,CRITICAL --exit-code 1 .` into the pre-push gate and fails on any finding. On a low-dependency repo that is correct and stays the default. On a **dependency-heavy** repo it breaks down: the first real run against a JS/Solana app (`kiln`) surfaced **65 vuln findings (4 CRITICAL, 61 HIGH)**, ~60 of them **transitive** and not unilaterally fixable (`@solana/*`, `react-native`, `@stellar/*` chains — `axios`, `lodash`, `protobufjs`, `shell-quote`, …). A gate permanently red on unactionable upstream CVEs trains developers to `git push --no-verify`, which disables the **entire** gate — oxlint, biome, secret-scan, license — a strictly worse posture than an honest, scoped gate. **A gate that is always red is a gate that is ignored.** kiln solved this locally (its own ADR-0017); this ADR lifts the *policy* to WORKFLOW as a **per-repo opt-in** a repo adopts when it hits that wall. The shared gate is unchanged (ADR-0008 consolidation — no forced machinery on repos that don't need it; and per-scanner scoping is inherently ecosystem-specific, so it cannot live in one shared gate anyway).

## Decision
A repo MAY opt into the **Trivy risk-register pattern** when its dependency graph makes the ADR-0001 gate permanently red on unactionable transitive CVEs. The pattern is ecosystem-agnostic in policy, ecosystem-specific in wiring:

1. **Per-scanner scope (a principle, not one lockfile).** **Vuln** scanning targets the repo's **dependency lockfile/graph** (`pnpm-lock.yaml` · `uv.lock`/`requirements.txt` · `Cargo.lock` · `go.sum`) — foreign-ecosystem manifests vendored under `node_modules`/`vendor` are out of scope. **License** scanning runs against the **full filesystem** (real `LICENSE` files live inside dep trees; lockfile-only detects zero). **Secret** scanning is filesystem-scoped (minus vendored dirs for FP noise), fail-closed, and keeps **no register** (a leaked secret is always repo-actionable).
2. **Fail condition.** The gate MUST fail on any HIGH/CRITICAL vuln — or `forbidden`/`restricted` license — **not** present in the version-controlled register `.trivyignore.yaml`.
3. **Register discipline.** Every entry carries: the CVE id, a `statement` recording `direct|transitive` + why accepted (no upstream fix · not repo-reachable · breaking-bump-deferred), and an `expired_at` review date. Append-only; entries are removed only when the dependency is fixed, never to silence a live finding.
4. **Expiry is hard-capped and fails (never warns).** `expired_at` MUST be ≤ **30 days** for `direct:` entries, ≤ **90 days** for `transitive:`; the hygiene check *fails* the gate on any entry over its cap or already past `expired_at`. A direct-dep HIGH/CRITICAL is remediated by upgrade, or accepted under the 30-day cap with a tracked follow-up.
5. **Reachability at seed.** Any CVE in a package imported from the repo's source MUST get an **individual** `statement` with a reachability note; only build/dev-only transitive deps may be batch-accepted.
6. **Security-sensitive paths are a reviewed surface** *[corrected — see Addendum 2026-08-22 §1: it is an audit-trail control, not a review control]* — this **implements the lockfile-justification control ADR-0001 named but never built.** A change to the dependency lockfile, `.trivyignore.yaml`, or any patched-dependency set (`patches/`, `pnpm.patchedDependencies`, equivalent) MUST carry a `SECURITY-REVIEW:` commit trailer; the gate fails otherwise.
7. **Shared gate unchanged.** A repo that never hits the wall keeps the plain ADR-0001 `trivy fs .` gate. This ADR ships **no default machinery** and is not seeded as code — only as this documented pattern in `templates/adr/`.

## Considered Options
- **Risk register (`.trivyignore.yaml`) + per-scanner scope, per-repo opt-in — chosen.** Every accepted risk is explicit, justified, dated, and expiring; new/unaccepted findings still fail; the gate is green-able without being disabled; low-dep repos pay nothing. Strengthens ADR-0001: acceptance now requires a written, expiring justification instead of a silent pass or a blanket `--no-verify`.
- **Bake into the shared gate** — rejected: forces register overhead on low-dep repos, and the shared gate cannot hardcode one ecosystem's lockfile.
- **`--ignore-unfixed`** — rejected: removes ~1/62 in practice; hides fixed-upstream-but-transitive risk with no audit trail or expiry.
- **Severity CRITICAL-only** — rejected: transitive CRITICALs still wedge the gate, and it silently drops every HIGH.
- **Drop trivy / tolerate `--no-verify`** — rejected: disables secret + license + new-CVE detection wholesale — the exact failure ADR-0001 exists to prevent.

## Consequences
Easier: a dependency-heavy repo's gate greens on its baseline while every accepted CVE is documented, dated, and expiring; new direct deps, new CVEs, and license changes still fail; ADR-0001 is intact and tightened; low-dep repos are untouched. Harder: an adopting repo maintains the register (re-triage on expiry and on `pnpm up`/`uv lock`); the register discipline must be policed. Explicitly **not** reduced: actual transitive risk today — this makes it explicit, owned, and time-boxed instead of a push-blocker that gets bypassed.

## Security Considerations & Mitigations
Carried from kiln ADR-0017's grill (8 vectors), unchanged by the per-repo-opt-in lift:
- **Known-CVE-only false assurance.** Trivy matches CVE ids; it does **not** defend against the supply-chain threats ADR-0001 names — typosquats, dependency-confusion, maintainer-compromise — which ship with no CVE id. Those stay owned by lockfile-integrity hashes + human dependency review. The register is explicitly **not** a supply-chain-integrity control.
- **Local-advisory enforcement / `--no-verify`.** The pre-push hook is a local, bypassable control (`core.hooksPath` is local git config). Durable enforcement requires the same three scans in **CI branch protection**, failing closed if trivy is absent or its vuln DB is > 7 days stale (`--skip-db-update` forbidden in CI). **Until a repo adds that CI mirror, its register is advisory** — stated honestly, not claimed closed.
- **Smuggled dep behind a same-PR register edit.** Mitigated by §6: the lockfile / `.trivyignore.yaml` / patches are the security-sensitive path set; a change without a `SECURITY-REVIEW:` trailer fails the gate; register edits are reviewed under the ADR-0007 lens.
- **License detection regressed by lockfile scope.** Real: lockfile-only misses `LICENSE` files that live in dep trees; hence §1 keeps **license** on the full filesystem while only **vuln** moves to the lockfile.
- **Blanket seed amnesty hides a reachable HIGH.** Mitigated by §5 (individual reachability statement for any source-imported CVE).
- **Transitive expiry as a permanent silent accept.** Mitigated by §4 (hard caps; the check fails, never warns).
- **Scanner-absent fail-open.** The local dev hook MAY self-skip when trivy is absent (fast-feedback ergonomics); the CI mirror MUST fail closed (ADR-0005).

## Enforcement
- **Canonical mechanism (follow-on build, opt-in template):** a stdlib **Python** register-hygiene checker (`ccost.py`/`loop-contract.py` house style) shipped under `~/.claude/templates/` as an opt-in drop-in — FAILS on any `.trivyignore.yaml` entry lacking `statement`/`expired_at`, past `expired_at`, `direct:` over 30d, or `transitive:` over 90d. Ecosystem-specific parameters (the vuln lockfile target; the security-sensitive path set) are filled per adopting repo. kiln's `check-trivyignore.mjs` is the JS reference implementation; the WORKFLOW canonical is the Python one. **Not built in this session — tracked as the post-map follow-on (→ `writing-plans`).** *[superseded — see Addendum 2026-08-24: checker A was built 2026-08-16 and runs as a gate stage; only the `~/.claude/templates/` drop-in half of this sentence is still true]*
- **Gate wiring (per adopting repo):** the trivy stage splits into three scoped scans (vuln→lockfile, license→full-tree, secret→fs-minus-vendored), each `--exit-code 1 --ignorefile .trivyignore.yaml`, plus a security-sensitive-path change-review check.
- **CI mirror (required for durability):** the same three scans in branch protection, fail-closed on absent/stale trivy.
- **Not enforced on the shared gate / low-dep repos:** by decision §7, this ADR ships no default rule; its "enforcement" is the opt-in template above.

## Output Schema Impact
**Schema Change Type:** none

## Semantic Drift Assessment
- n/a — no tool output schema changes; `.trivyignore.yaml` is a new opt-in config, not a modified tool output.
- **Verification plan:** the follow-on Python checker is unit-tested against a fixture register (valid entry, missing-statement, expired, over-long `direct:` expiry) and dogfooded (ADR-0010) before the template ships.

---

## Addendum — 2026-08-22: §6's audit trail was never produced, and why

*Append-only. Nothing above is edited. This records what shipping §6 revealed.*

### 1. The F7 correction stands, and this ADR's wording still overstates

§6 above calls security-sensitive paths a **reviewed surface**. It is not one. The
`SECURITY-REVIEW:` trailer is a string the committer writes about their own change; the attester,
the committer and the auditor are the same person. Spec F7 called this an overstatement and the
correction is adopted here: **§6 is an audit-trail control, not a review control.** It forces a
deliberate, recorded act at the moment of a change. It does not establish that a review happened,
and `commit.template` would satisfy it permanently and undetectably (verified unset locally and
globally, 2026-08-21 — real, but unarmed).

### 2. The trail was empty for the entire life of the control

Measured 2026-08-21:

```
git log --all --no-merges --format='%H %(trailers:key=SECURITY-REVIEW,valueonly)' | awk 'NF>1'
  → 4 commits carried a parseable trailer
git log origin/main --format='%(trailers:key=SECURITY-REVIEW,valueonly)' | grep -c .
  → 0
git log --all --format='%(trailers:key=Co-authored-by,valueonly)' | grep -c .
  → 163                                                        ← positive control
```

Four attestations were authored; none reached `main`. GitHub's squash merge deletes the author's
`Co-Authored-By:` line and re-appends its own as a **new blank-line-separated paragraph**, which
becomes the trailer block and orphans everything above it. The attestation text stayed visible in
`git log` while being unreadable to every parser — the trail *looked* present and was absent.

### 3. The fix: rebase-merge, measured against a control

Probe PRs #37/#38/#39 into a scratch base branch, identical bodies:

| Method | Trailer parses | Body vs authored | Paths reported |
|---|---|---|---|
| squash | **empty** | differs | 1 |
| **rebase** | **non-empty** | **byte-identical** | 1 |
| merge commit | on parent-2 only | identical | **0** |

Confirmed afterwards on real merges — `bd5690f` (squash) carries GitHub's `Co-authored-by:` casing;
`78f90e3` (rebase) carries the author's `Co-Authored-By:`, untouched. The merge method is pinned in
`scripts/pr-preflight.py`, a tracked file, **not** in repo settings — settings have no diff, no
history and no review, which is how `squash_merge_commit_message` became load-bearing unread.

### 4. Grandfathered history, stated rather than left silent

177 commits predate a working trailer. The post-merge lane is scoped to the push range only and says
so in its own output. History is **not** retroactively compliant and no attempt is made to imply it
is.

### 5. The self-grading limit — the honest boundary

The post-merge lane takes the checker and its config from `github.event.before` so a push cannot
grade its own exam. **This removes same-push circularity and nothing more.** A two-push sequence
defeats it: push 1 weakens the checker, push 2 is judged by the weakened copy. After this
addendum's widening, push 1 needs a trailer — a string the same actor writes.

**So the boundary is: §6 defends against forgetfulness and drift, not against an actor who wants it
gone.** On this plan it cannot do better — `branches/main/protection` and `rulesets` both return
*"Upgrade to GitHub Pro or make this repository public"* (re-verified 2026-08-22, first-hand). Given
the record — 4 silent absences, 0 tamper attempts — drift is the right threat to defend.

Observed live on `9e27ba48..c01be226`: the lane reported `1 sensitive` where the pre-push hook
reported `3`, because the lane was correctly using the *pre-widening* rules from `before`.

### 6. Widened surface

`.githooks/**`, `.github/workflows/**` and `.github/CODEOWNERS` are now **builtin**; `adr/**`,
`scripts/**`, `machine/templates/**` and the two test-floor files are config. Coverage 21 → 109 of
719 tracked files; friction 4 → 15 of the last 40 commits. Converse bounds are tested — a control
that fires on innocent paths is the one that gets bypassed.

`machine/.mirror-secret-exceptions` is retained but **matches nothing** (not tracked, not on disk,
not in `~/.claude`) and is now labelled pre-armed. A line that looks like protection while
protecting nothing is the shape §6 exists to catch.

### 7. What the grill left open, unsolved

- **H4 — the lane has no reader.** A red check on `main` gates nothing; there is no server-side
  enforcement to attach it to. Detection, not prevention.
- **H5 — opt-out is reachable without deletion.** `if: false`, a renamed job, or an edited trigger
  filter removes the lane and reads as an ordinary edit. Making `.github/workflows/**` sensitive
  turns that into a reviewed diff; it does not prevent it.

Both trace to the same 403 as §5. Neither is closable on this plan. Stated, not solved.

## Addendum — 2026-08-24: the canonical checker was built, and this section did not say so

Found by the thread-17 enforcement audit (`/harness-gc` pass, 2026-08-24), which asked of all 21
ADRs carrying an Enforcement section: *does the named enforcer exist, where does it run, and has
anyone deleted it and watched?*

**§Enforcement's first bullet was false.** It says the canonical register-hygiene checker was
*"Not built in this session."* It was built two days later:

```
$ git log --diff-filter=A --format='%ad %h %s' --date=short -- .githooks/trivy-register.py
2026-08-16 bb8b510 feat: ADR-0017 checkers A and B, built TDD against spec §9 (phase 1) (#18)

$ grep -nE '^\s*(NEXT_HOP_CAP|LIFETIME_CAP)' .githooks/trivy-register.py
53:NEXT_HOP_CAP = {"direct": 30, "transitive": 90}
54:LIFETIME_CAP = {"direct": 60, "transitive": 180}
```

`NEXT_HOP_CAP` is the `direct:` 30d / `transitive:` 90d rule this section specifies, and
`LIFETIME_CAP` is *stronger* than what was specified — a total-lifetime cap derived from git history,
which kills perpetual renewal. The checker is a declared stage in `GATE_STAGES`
(`.githooks/lint-gate.sh:60`) and this repo's `gate-baseline` carries it as
`exempt: trivy-register  no .trivyignore.yaml` — a verified n/a, not an omission.

**What is still accurate:** the checker lives in `~/Apps/WORKFLOW/.githooks/`, **not** under
`~/.claude/templates/` as an opt-in drop-in. `find ~/.claude/templates -iname '*trivy*'` returns only
this ADR's own seeded copy. So the *drop-in template* half of the bullet remains unbuilt; the
*checker* half is done. Adopting repos still have nothing to drop in.

**Why this matters beyond the one wrong sentence.** `HARNESS.md` §1b cited exactly this sentence as
its sole **UNMAPPED — twin deferred** entry: a security-exempt control that is "inferential-only with
no deterministic twin … the shape §1b exists to forbid." That paragraph was last touched 2026-08-13
(`git log -1 -S'UNMAPPED — twin deferred' -- HARNESS.md` → `1b340c0`), **three days before the twin
landed**. One stale sentence in an append-only document propagated into the map that reads it, and
both then read as current for eight days.

This is silent-pass Q9 — *a correction stranded in a different section* — in the file that documents
Q9. §6 of this ADR got the treatment right (an `Amended-by:` header **and** an inline
`*[corrected — see Addendum]*` at the claim). §Enforcement got neither until now. The lesson is not
"be careful": it is that **the pointer has to be added at the claim site in the same edit that makes
the claim false**, because nothing routes a reader from a stale paragraph to a later correction.
