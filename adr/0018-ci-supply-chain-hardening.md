# ADR-0018 — CI Supply-Chain Hardening (Actions Pinning & Workflow Least-Privilege)

**Status:** Accepted · **Date:** 2026-08-09
**Supersedes:** — · **Superseded-by:** —
**Amended-by:** Addendum 2026-08-14 (below) — **the branch guard's input is corrected there: it decides on the refspec stream, never `HEAD`.**
**Refines:** ADR-0001 (zero-trust dependencies)

## Context
ADR-0001 requires exact-pinned dependencies with a supply-chain justification, but its enforcement
(`trivy fs`, dependency manifests) never reaches `.github/workflows/`. A GitHub Actions step is a
dependency with more privilege than most packages: it executes inside the job, sees `GITHUB_TOKEN`,
and can read whatever the job's `permissions:` grant. A tag reference (`actions/checkout@v4`) is
**mutable** — the upstream owner, or anyone who compromises that account, can repoint the tag at new
code that runs on the next push with no diff in this repo.

`tj-actions/changed-files` (CVE-2025-30066) is the reference case: on 14–15 March 2025 an attacker
retroactively repointed **every tag from v1 through v45.0.7** at a single malicious commit
(`0e58ed8`) that dumped runner-process memory — access keys, PATs, npm tokens, private RSA keys —
into workflow logs, which are publicly readable on public repos. Roughly **23,000 repositories**
consumed it. Consumers pinned to a commit SHA were unaffected, because the retag could not move
their reference. CISA's advisory pairs it with a second compromise the same week,
`reviewdog/action-setup@v1` (CVE-2025-30154), which is the argument against treating any single
publisher as trusted.

Measured at adoption across the fevra-dev portfolio: **0 of ~120** action references were SHA-pinned,
and **9 of 19** workflow files declared a `permissions:` block — the remaining 10 ran with the
repository-default token scope.

## Decision
1. Every `uses:` reference MUST be pinned to a full 40-character commit SHA, with the human-readable
   tag preserved in a trailing comment:

       uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683  # v4.2.2

2. Every workflow MUST declare a **top-level** `permissions:` block (column 0). A job-level block
   alone is insufficient — it leaves every *other* job at the repository default.
3. `permissions: write-all` is forbidden. Start from `contents: read` and widen per-job.
4. `pull_request_target` is forbidden without an explicit, reviewed waiver comment
   (`# adr-0018: allow-pull-request-target — <reason>`) on the trigger line.
5. Composite actions under `.github/actions/**/action.yml` are in scope for rule 1 — a local action
   is exempt from being pinned itself, but its *contents* are not exempt from pinning what they call.
6. Long-lived cloud credentials MUST NOT be stored as Actions secrets where the provider supports
   OIDC federation.

Exempt from rule 1: local refs (`uses: ./...`) and container refs (`uses: docker://...`), which are
pinned by repo contents and image digest respectively.

## Considered Options
- **SHA pinning + top-level least-privilege + trigger control, enforced by a pre-push checker (chosen)** —
  closes the mutable-ref vector deterministically, verifiable offline, reuses the existing gate.
- **Presence-only `permissions:` check** — rejected after adversarial review: satisfied by
  `permissions: write-all`, and by one job out of five declaring a block. It certifies the exact
  configuration it claims to prevent.
- **Tag pinning + Dependabot** — rejected: Dependabot updates the tag *after* a malicious retag has
  already run in CI. A freshness tool, not a supply-chain control.
- **Allowlist of trusted publishers (`actions/*`, `github/*`)** — rejected: `reviewdog` was trusted
  the week it was compromised, and an allowlist is a judgment the gate cannot verify.
- **Full SLSA provenance + cosign signing** — rejected *for now*: correct direction, but it presumes
  the pinned baseline that does not yet exist. Revisit once this ADR holds.

## Consequences
Diffs get noisier and updating an action requires re-resolving the SHA (`actions-pin.py --fix`).
In exchange, a compromised upstream action cannot silently enter a build, and the blast radius of any
action that does run is bounded by an explicit grant. Retrofit is forward-only. Rule 4 will require a
waiver on any repo legitimately using `pull_request_target`; that is intended — the waiver is the
review.

## Security Considerations & Mitigations
Output of `grill-with-threat-model`, 2026-08-09. Ten vectors were raised; five changed the decision.

**Closed by the decision:**
- **Presence-not-privilege bypass** (`permissions: write-all`, or one job of five declaring a block)
  → rules 2 + 3: top-level block required at column 0, `write-all` rejected outright. **PIN002/PIN003.**
- **Local-action indirection** — `uses: ./.github/actions/setup` is exempt, but that composite's
  `action.yml` could call `actions/checkout@main` and never be scanned → rule 5 puts
  `.github/actions/**/action.yml` in scope. **PIN001.**
- **`pull_request_target` privesc** — a fully pinned, `contents: read` workflow is still exploitable
  if it checks out and builds attacker-authored PR head with the base repo's secrets → rule 4
  requires an explicit waiver. **PIN004.**
- **False positives inside `run:` blocks** — a `uses:` string in a heredoc matching as a step key
  would produce unfixable failures on a *pre-push* gate, which is exactly what trains `--no-verify`
  (the ADR-0017 failure mode) → the checker tracks block scalars (`run: |`, `run: >`) and skips
  their contents.
- **Unverified tag comment / `--fix` laundering a pre-compromised tag** — `--fix` faithfully pins
  whatever a tag currently points at, and no human evaluates a 40-hex diff → `--verify` (network,
  opt-in, deliberately **not** in the pre-push gate) re-resolves each `# tag` comment and fails if it
  disagrees with the pinned SHA. This catches both a lying comment and a post-hoc retag.

**Found by adversarial self-audit (2026-08-10) — rule 5 was aspirational, now enforced:**
- **Composite actions outside `.github/actions/`.** Rule 5 put composite actions in scope, but the
  checker globbed `.github/actions/**/action.y*ml` only. A local action is referenced by *path*
  (`uses: ./tools/deploy`), so its location is the author's choice — and `tools/deploy/action.yml`
  calling `uses: evil/exfil@main` passed the checker clean while the ADR claimed coverage. The
  checker now walks the whole tree for `action.yml`/`action.yaml` (skipping `.git`, `node_modules`,
  `vendor`, virtualenvs, build output). Scanning the *convention* rather than the *reality* is the
  general error; the rule's text was right and its enforcement was not.

**Accepted residual risk — documented, not solved:**
- **SHA shape ≠ provenance.** `[0-9a-f]{40}` does not prove the commit exists or belongs to the
  canonical repo; GitHub serves commits from anywhere in a fork network under the parent's path. An
  attacker-fork SHA passes PIN001 while looking maximally rigorous. `--verify` narrows this (it
  resolves the canonical repo's tag) but does not close it offline.
- **Reusable-workflow callees.** `uses: org/repo/.github/workflows/build.yml@<sha>` pins the entry
  point; the called workflow's own steps live in another repo and are unreachable to an offline
  checker.
- **Non-CLI write paths.** The gate fires on `git push`. The GitHub web editor and `gh api` content
  writes never touch it. On public repos the required status check catches this at merge; on private
  repos, where server-side protection is unavailable on this account's plan (verified 2026-08-09:
  rulesets and classic branch protection both return HTTP 403 "Upgrade to GitHub Pro or make this
  repository public"), there is no compensating control. This is the largest open hole in the ADR.
- **`permissions:` does not scope stored PATs.** A job with `contents: read` consuming a
  `secrets.GH_PAT` has whatever scope that PAT was minted with. PIN002 must not be read as assurance
  about anything but `GITHUB_TOKEN`.
- **`--no-verify`.** Same trust model as every other gate stage.

## Enforcement
`~/.claude/scripts/actions-pin.py --check`, wired as lint-gate stage 4c. Scans
`.github/workflows/*.{yml,yaml}` and `.github/actions/**/action.yml`. Fails the push on:

| Rule | Violation |
|---|---|
| **PIN001** | `uses:` reference not pinned to a 40-hex commit SHA |
| **PIN002** | no **top-level** `permissions:` block |
| **PIN003** | `permissions: write-all` |
| **PIN004** | `pull_request_target` trigger without an `# adr-0018: allow-pull-request-target` waiver |

Self-skips when the repo ships neither directory. `--fix` resolves tags to commit SHAs (dereferencing
annotated tags through `git/tags/{sha}`, or the pin lands on the tag object rather than the commit).
`--verify` re-resolves tag comments against pinned SHAs over the network — run it in CI or by hand,
never in the pre-push gate, which must work offline.

On public repos, `repo-protect.sh` additionally requires the gate as a status check before merge.

## Output Schema Impact
**Schema Change Type:** none

## Semantic Drift Assessment
Not applicable — this ADR adds a gate stage and does not alter any tool's output contract.

---

## Addendum 2026-08-14 — the branch guard decides on the push destination

ADRs are append-only; this supersedes nothing in the decision above, and corrects the *input* of the
client-side branch guard this ADR introduced. Spec: `docs/specs/2026-08-14-branch-guard-destination.md`.

### What was wrong

The guard decided on `git symbolic-ref --short HEAD` — where you are standing, not where the push is
going. HEAD is *correlated* with the destination. It is not the destination. Both directions failed:

- **False negative.** From any feature branch, pushing `HEAD:main` presents a non-main HEAD. The guard
  allowed it and `main` took a direct push. Server-side rulesets and classic branch protection both
  403 on private repos on this plan (re-verified 2026-08-14), so **nothing else was stopping it.**
- **False positive.** Standing on `main` and pushing a feature branch was blocked. Not cosmetic:
  routine false blocks are what train `--no-verify`, which disables the entire gate.

ADR-0019's threat model stated the correct input on 2026-08-10 — *"Detecting direct-to-`main` genuinely
requires inspecting the push event, not the checked-out branch"* — and scoped it out. The guard shipped
reading the wrong one for four days after that was written down. Recording that gap is the point: the
analysis was right and did not reach the code.

### Decision

The guard reads git's pre-push refspec stream and blocks when any line's **remote ref** (field 3) is in
a protected set. Measured against git 2.33.0; every world is enumerated in the spec §2.1.

1. Input is the refspec stream, never HEAD.
2. **Every** line is read. git writes one line per ref and the violating ref is not first — in a
   two-ref push it was line 2, in a push of all branches line 3. A single `read` is a fail-open.
3. Protected = field 3. Keying on the destination catches a bare `HEAD`, a raw sha, a URL remote and a
   **deletion** (`(delete) 000… refs/heads/main`) under one rule — the last being a case the HEAD-based
   guard could not express at all.
4. The protected set is **additive-only**: built-in `main`/`master`, extended by `.githooks/protected-refs`,
   never reduced by it. A protected set the checked repo can shrink is a control grading its own exam.
   A malformed entry fails closed rather than silently reverting to the default scope.
5. Ordering excludes every non-git-pipe world *before* the drain — `CI=true`, then a terminal — so the
   drain needs no timeout and the CI invocation cannot hang on it.
6. Zero refspec lines is **`n/a`, not `UNCHECKED`**: git runs the hook with zero lines when there is
   nothing to push. Failing closed there would block every routine no-op push.
7. Lines are shape-validated before use, and captured ref text is **only ever compared, never expanded**.
   `check-ref-format` permits `$`, parentheses, backticks and semicolons in ref names, so an `eval` or
   unquoted expansion here is command execution inside a pre-push hook, running as the developer.
8. The capture is written once to a `mktemp` file exported as `GATE_PUSH_REFS`, holding all four fields
   verbatim. stdin is one-shot; the gate is now its only reader. ADR-0017 checker B consumes this file.
9. The guard records an outcome on every route, and the gate **verifies that outcome against its own
   precondition** — `na-ci` requires `CI=true`, `na-not-a-push` requires stdin to still be a terminal,
   `na-nothing-to-push` requires a capture that exists and is empty, and any verdict requires a capture
   with content. An unrecognised outcome is a failure.

   *This is the second version.* The first only checked that **some** outcome was recorded, and that is
   not the same claim: changing `elif [ -t 0 ]` to `elif true` — one token — leaves the guard present,
   printing, and recording a plausible "n/a — not a push" on every push, while a push to a protected
   ref sails through and the gate prints a pass. "Not empty" is not "earned". The weaker check shipped
   first and was measured to be bypassable before it reached `main`.

### Security considerations

`grill-with-threat-model`, 2026-08-14. Ten break paths; three were structural.

**Closed by the decision:**
- **Timeout conflating "nothing pushed" with "could not read"** — bash 3.2 returns `rc=1` for *both*
  EOF and timeout, so a bounded read cannot tell them apart, and mapping both to `n/a` is a pass. Closed
  by removing the timeout: non-git-pipe worlds are excluded first, so an empty capture is unambiguous.
- **Legal-but-hostile ref names reaching a shell** — closed by shape validation plus compare-never-expand.
- **Guard invisible to the machinery that detects missing controls** — it is outside `GATE_STAGES`, the
  ledgers and `ran` (counting it would mask ADR-0019 r6's zero-stage warning), so with the stage deleted
  the declaration still verified and the stage count was unchanged. Closed by decision 9's assertion,
  placed *outside* the baseline block so it is not itself removable by deleting a file.
- **Self-disabling on argument count** — argc is informational only; the verdict rests on refspec data.
- **Protected set scoped to convention rather than reality** — closed by decision 4.

**A fix that was itself a fail-open, recorded rather than quietly dropped:** the first bounded-read
design used a background `cat` with a watchdog. bash redirects a background job's stdin from `/dev/null`
when job control is off, so it reported *complete, zero refs* — i.e. allow — on a stream it never read.
Measured, not reasoned about. Do not reintroduce it.

**Stated, not closed:**
- `CI=true` disables the guard, so a workflow that pushes would be unguarded. Verified true-today (no
  push actions in this repo's workflows), not an invariant.
- `core.hooksPath` lives in `.git/config`, which is not versioned — a fresh clone has no guard until it
  is wired. Not closable client-side.
- `--no-verify` bypasses everything; `--dry-run` is blocked (the hook runs, and no signal distinguishes
  it); `ALLOW_MAIN_PUSH=1` is inheritable from a shell profile, now loudly announced when it fires.
- **The guard cannot be declared in `gate-baseline`, and this is no longer "deferred".** Measured, by
  adding it to `GATE_STAGES` and trying each declaration:

  | Declaration | World | Result |
  |---|---|---|
  | `required` | CI | ❌ *declared required but reported n/a — a control was removed* |
  | `required` | real push | ❌ *declared required but reported nothing at all* |
  | `exempt` | real push | ❌ *declared exempt but reported nothing at all* |
  | `exempt` | CI | ✅ passes |

  The only declaration that does not break CI is `exempt`, which asserts the control does not apply —
  a written, committed statement that this repo needs no direct-push guard. The cause is structural:
  `required`/`exempt` are both properties of the **repo**, while this guard's applicability is a
  property of the **invocation**. Same repo, different answer depending on whether a push is happening.

  A third declaration state was considered and **rejected**: it costs a portfolio-wide baseline
  regeneration and does not close the actual hole, because a neutered guard reports `n/a` and a
  "conditional" declaration accepts `n/a` by design. Decision 9's precondition check is what detects
  that, and it needs no vocabulary change. Revisit only if some repo genuinely needs to declare that
  it does not run the guard.
- **The 44 gate fixtures do not run in CI.** `ci.yml` runs the gate itself and not the harness-eval
  suite, so no PR is gated on them today. Tracked separately; it protects every gate behaviour, not
  just this control.
