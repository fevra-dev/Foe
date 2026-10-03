#!/usr/bin/env bash
# lint-gate.sh — pre-push quality + security gate (fevra-dev template, 2026-08-09)
# harness-version: 8fdd67be0cd0
# harness-base: 8fdd67be0cd0
#
# Drop into a project (TS/JS and/or Python) and wire as a git pre-push hook (see
# README). ANY stage failure aborts the push.
#
# Stages skip for two DIFFERENT reasons, and the gate keeps them apart:
#   "n/a"       the stage does not apply here (no JS files, no .semgrep/ rules). Nothing missed.
#   "UNCHECKED" the stage applies but its tool/script is missing, so nothing was verified.
# Locally, UNCHECKED is a warning — the gate stays usable on a machine without every tool.
# In CI, set STRICT=1: UNCHECKED becomes a failure. A check that could not run must never
# report success. Without this, a runner missing every tool produced ten skips and a green ✅.
#
# Stack (validated STACK.md §11/§16/§17/§20/§25): branch guard (ADR-0018) + Oxlint (lint)
# + Biome (format) + ruff (Python lint/format) + Trivy (deps/secrets — ADR-0001) + Semgrep
# (ADR-derived security rules) + bandit (Python SAST) + actions-pin (CI supply chain —
# ADR-0018) + dependency-cruiser (module-graph / architecture rules) + Sigma (detection
# rules) + redact --check (scrub agent-generated .audit reports — ADR-0011).
# STRICT=1 (set it in CI) turns "I could not check this" into a failure. See skip_tool below.
set -uo pipefail
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT" || exit 1
fail=0
ran=0

# STRICT: fail on anything that applies but could not be checked.
#
# Defaults to 1 under CI. Deriving it rather than requiring it is the point (ADR-0019 r3): a
# workflow that simply forgets `STRICT: "1"` must not silently inherit the permissive path —
# a fail-open reachable by omission is the failure this exists to close.
#
# A global STRICT=0 is refused under CI. Escaping one broken stage by disabling all of them is
# the `--no-verify` dynamic ADR-0017 documents. There is deliberately no per-stage escape
# either: waivers were removed (ADR-0019) because five of eight grill vectors existed only
# because they carried a date, and a date enforces recurrence rather than review.
if [ "${CI:-}" = "true" ]; then
  if [ "${STRICT:-1}" = "0" ]; then
    echo "❌ global STRICT=0 is not available under CI."
    echo "   If a stage genuinely does not apply here, declare it 'exempt: <stage> <reason>' in"
    echo "   .githooks/gate-baseline — that is verified, not asserted, and holds only while the"
    echo "   stage reports n/a. If it does apply and its tool is missing, install the tool."
    exit 1
  fi
  STRICT=1
else
  STRICT="${STRICT:-0}"
fi

# Outcome ledger. bash 3.2 (macOS system bash) has no associative arrays, so membership is
# tracked as colon-delimited strings and tested with a glob. Every stage lands in exactly one.
STAGES_RAN=":"; STAGES_NA=":"; STAGES_UNCHECKED=":"

# The stages this gate defines — single source of truth for the completeness check in the
# verdict block. A stage missing from this list would be silently exempt from having to be
# declared at all, which is the same silent-omission failure the declaration file exists to
# prevent, one level up. `branch-guard` is deliberately absent: it reports n/a under CI and
# lands in no ledger on a local feature branch, so its membership is context-dependent and
# cannot be verified consistently. It is a push-time control, not a coverage claim.
GATE_STAGES="oxlint biome ruff trivy trivy-register gitleaks semgrep bandit actions-pin seed-drift dep-cruiser sigma redact"
in_set() { case "$2" in *":$1:"*) return 0 ;; *) return 1 ;; esac; }

# Declared here rather than beside the ratchet below, because the trivy-register stage reads it
# too: ADR-0017's opt-in signal is `required: trivy-register` in this file, deliberately OUTSIDE
# the register being inspected (F5), so deleting the register cannot also delete the reason to
# check for it. One definition, so the two readers cannot drift onto different paths.
BASELINE=".githooks/gate-baseline"

# step <stage-id> <human label>
step() { ran=$((ran+1)); STAGES_RAN="${STAGES_RAN}${1}:"; printf '\n\033[1m▶ %s [%s]\033[0m\n' "$2" "$1"; }

# Two kinds of decline, deliberately distinguished — ADR-0005 (fail closed) applied to the gate
# itself. Conflating them is what let a CI run report ✅ with all ten stages skipped:
#   skip_na    the stage does not apply to this repo. Nothing was missed. Always a pass.
#   skip_tool  the stage APPLIES but its tool/script/data is unavailable, so nothing was
#              checked. Under STRICT=1 that is a failure — a check that could not run must
#              never report success. (ADR-0019 rules 1 and 2.)
skip_na()   { STAGES_NA="${STAGES_NA}${1}:";        echo "· skip [$1] (n/a: $2)"; }
skip_tool() {
  STAGES_UNCHECKED="${STAGES_UNCHECKED}${1}:"
  if [ "$STRICT" = "1" ]; then
    echo "❌ [$1] applies here but could not run: $2 [STRICT]"; fail=1
  else
    echo "· skip [$1] (UNCHECKED: $2 — install to enable)"
  fi
}
# True if any path matching the given git pathspecs is tracked, or untracked but
# not gitignored — i.e. something the tool would actually lint. Lets JS-only
# stages self-skip on a Python repo instead of erroring on "no files found".
have_files() { [ -n "$( { git ls-files -- "$@"; git ls-files --others --exclude-standard -- "$@"; } 2>/dev/null )" ]; }
# Harness checkers: canonical copy first (ADR-0019 r8). The vendored copy under .githooks/
# exists so a CI runner — which has no ~/.claude — can run the checker at all. Preferring it on
# a workstation would put a repo-writable artifact ahead of the machine-controlled one for no
# gain, since the workstation always has the canonical copy.
harness_script() {
  if   [ -f "$HOME/.claude/scripts/$1" ]; then printf '%s' "$HOME/.claude/scripts/$1"
  elif [ -f ".githooks/$1" ];            then printf '%s' ".githooks/$1"
  else printf '%s' ""; fi
}

# 0. Branch guard — ADR-0018 companion, corrected 2026-08-14.
#    Spec: docs/specs/2026-08-14-branch-guard-destination.md
#
#    Server-side branch protection is unavailable on private repos on this plan (re-verified
#    2026-08-14: rulesets and classic protection both 403 "Upgrade to GitHub Pro"), so this
#    guard is the ONLY control preventing a direct push to main here.
#
#    It decides on the PUSH DESTINATION — git's pre-push refspecs, on stdin — not on HEAD.
#    HEAD is correlated with the destination; it is not the destination. Reading it produced
#    BOTH failure directions:
#      false negative: pushing HEAD:main from a feature branch presents a non-main HEAD, was
#                      allowed, and main took a direct push.
#      false positive: pushing a feature branch while standing on main was blocked. Not
#                      cosmetic — routine false blocks are what train --no-verify, which
#                      disables this entire gate.
#    ADR-0019's threat model named the correct input on 2026-08-10 and scoped it out; the
#    guard shipped reading the wrong one for four days after that was written down.
#
#    ORDERING IS LOAD-BEARING. Every world where stdin is not git's hook pipe is excluded
#    BEFORE the drain, which is why the drain needs no timeout: CI first (ci.yml runs this
#    gate as a plain step, and a read there is the only way to hang it), then a terminal,
#    then the drain. Moving the drain ahead of either check turns CI into a hang.
#
#    A bounded read was built and REJECTED on measurement, not reasoning: bash 3.2 returns
#    rc=1 for BOTH eof and timeout, so it cannot tell "nothing was pushed" from "I failed to
#    read", and a ( cat ) & watchdog reported "complete, zero refs" — i.e. allow — on a
#    stream it never read, because bash redirects a background job's stdin from /dev/null
#    when job control is off. The fix for a fail-open was itself a fail-open. An unbounded
#    drain can hang; that is a loud failure a developer sees, not a silent pass.
BRANCH_GUARD_OUTCOME=""     # must be non-empty by the verdict block, or the gate fails
GATE_PUSH_REFS=""           # capture contract for later consumers (ADR-0017 checker B)
TRIVY_JSON=""               # trivy's JSON report, consumed by ADR-0017 checker A

# ONE EXIT trap for every temp file this gate creates. `trap ... EXIT` REPLACES any previous
# EXIT trap rather than adding to it, so a second one installed later would silently stop the
# first file from being cleaned up. New temp files join this line; they do not add their own.
trap 'rm -f "$GATE_PUSH_REFS" "$TRIVY_JSON" "$_GL_CONF" "$_GL_OUT"' EXIT

if [ "${CI:-}" = "true" ]; then
  BRANCH_GUARD_OUTCOME="na-ci"
  skip_na "branch-guard" "CI runner — pre-push control, nothing is being pushed"
elif [ -t 0 ]; then
  BRANCH_GUARD_OUTCOME="na-not-a-push"
  echo "· skip [branch-guard] (n/a: not a push — stdin is a terminal)"
else
  # mktemp failing must not read as "nothing to push". With an unwritable TMPDIR the capture
  # path is empty, `cat > ""` errors, and the emptiness test below then reports n/a — allow.
  # That is this control's own silent-pass path, introduced by the fix that created it: the
  # guard gained a dependency, and the new dependency needed the same question asked of it.
  GATE_PUSH_REFS="$(mktemp 2>/dev/null || printf '')"
  if [ -z "$GATE_PUSH_REFS" ] || [ ! -f "$GATE_PUSH_REFS" ]; then
    BRANCH_GUARD_OUTCOME="could-not-capture"
    echo "❌ [branch-guard] could not create a capture file (TMPDIR unwritable?) — failing closed."
    echo "   The push destination could not be read, so it cannot be cleared."
    fail=1
    GATE_PUSH_REFS=""
  else
   cat > "$GATE_PUSH_REFS"
   if [ ! -s "$GATE_PUSH_REFS" ]; then
    # git runs the hook with zero lines when there is nothing to push. That is genuinely
    # not-applicable, not could-not-check: failing closed here would block every routine
    # no-op push, and that friction is what manufactures --no-verify pressure.
    BRANCH_GUARD_OUTCOME="na-nothing-to-push"
    echo "· skip [branch-guard] (n/a: hook ran with zero refs — nothing to push)"
   else
    # Protected set is ADDITIVE-ONLY. .githooks/protected-refs may add to it and can never
    # remove from it: a protected set the checked repo can shrink is a control grading its
    # own exam. Deriving the default branch from refs/remotes/origin/HEAD was specified and
    # then falsified — that ref does not exist in this repo (rc=128) and remote.origin.HEAD
    # is unset, so it would have silently fallen back 100% of the time while looking dynamic.
    _bg_protected="refs/heads/main refs/heads/master"
    _bg_bad=0
    if [ -f .githooks/protected-refs ]; then
      while IFS= read -r _bg_line || [ -n "$_bg_line" ]; do
        case "$_bg_line" in
          ''|'#'*)       continue ;;
          refs/heads/?*) _bg_protected="$_bg_protected $_bg_line" ;;
          *) echo "❌ [branch-guard] .githooks/protected-refs: not a fully-qualified ref: $_bg_line"
             _bg_bad=1 ;;
        esac
      done < .githooks/protected-refs
    fi
    printf '\n\033[1m▶ branch guard [branch-guard]\033[0m\n'
    echo "  protecting: $_bg_protected"

    _bg_violation=0
    _bg_n=0
    # IFS=' ' — a single space, never IFS=. Blanking IFS puts the whole line in the first
    # variable and leaves remote_ref EMPTY, so the comparison below never matches and the
    # guard silently passes everything. Measured; the ADR-0017 spec specified exactly that.
    # `|| [ -n "$_lr" ]` is load-bearing: without it an unterminated final line is silently
    # DROPPED, and for this guard that is a fail-open — the refspec naming the protected ref is
    # never examined and the push is allowed. git always writes a trailing newline, so it is not
    # reachable from a real push, but a control must not take its verdict from its caller's
    # formatting. Two other read loops in this file already carry the guard; these two did not.
    while IFS=' ' read -r _lr _ls _rr _rs || [ -n "$_lr" ]; do
      _bg_n=$((_bg_n + 1))
      _bg_shape=1
      # A 5th field lands in $_rs, so the 40-hex test is what enforces "exactly four fields".
      [ ${#_ls} -eq 40 ] || _bg_shape=0
      [ ${#_rs} -eq 40 ] || _bg_shape=0
      [ -n "$_lr" ] && [ -n "$_rr" ] || _bg_shape=0
      case "$_ls$_rs" in *[!0-9a-f]*|"") _bg_shape=0 ;; esac
      if [ "$_bg_shape" -ne 1 ]; then
        echo "❌ [branch-guard] refspec line $_bg_n is malformed — failing closed"
        _bg_violation=1
        continue
      fi
      # Compared, never expanded. check-ref-format permits dollar, parens, backtick and
      # semicolon in ref names, so eval or an unquoted expansion here would be command
      # execution inside a pre-push hook, running as the developer.
      case " $_bg_protected " in
        *" $_rr "*)
          echo "❌ [branch-guard] this push targets the protected ref $_rr (local ref: $_lr)"
          _bg_violation=1 ;;
        *) echo "  ok: $_rr" ;;
      esac
    done < "$GATE_PUSH_REFS"

    [ "$_bg_bad" -eq 1 ] && _bg_violation=1
    if [ "$_bg_violation" -eq 0 ]; then
      BRANCH_GUARD_OUTCOME="checked"
    elif [ "${ALLOW_MAIN_PUSH:-0}" = "1" ]; then
      BRANCH_GUARD_OUTCOME="overridden"
      echo "‼️  ALLOW_MAIN_PUSH=1 — branch guard SUPPRESSED for this push."
      echo "   This repo has no server-side branch protection, so nothing else is stopping"
      echo "   it. Unset the variable if you did not mean this — it is inheritable, and a"
      echo "   value left in a shell profile disables this guard on every push."
    else
      BRANCH_GUARD_OUTCOME="blocked"
      echo "   open a PR instead:"
      echo "     git checkout -b feat/<name> && git push -u origin feat/<name> && gh pr create --fill"
      echo "   override (solo/scratch repos): ALLOW_MAIN_PUSH=1 git push"
      fail=1
    fi
   fi
  fi
fi

# 0b. security-review-trailer — ADR-0017 §6 (checker B).
#
#     DELIBERATELY OUTSIDE GATE_STAGES, for the reason branch-guard is: its ledger state
#     depends on which world it runs in. It reports n/a under CI (no push range exists) and
#     runs on a workstation push, so `required:` would fail in CI and `exempt:` would fail
#     locally — no single declaration can be true in both. A stage that cannot be declared
#     truthfully must not be declarable at all, or the baseline starts carrying a line that is
#     wrong half the time and gets waived to make the noise stop.
#
#     It therefore prints directly and touches NO ledger. Using step/skip_na here would put it
#     in STAGES_RAN or STAGES_NA and trip the gate's own drift check ("reported a result but is
#     not in GATE_STAGES") on the very next run.
#
#     --push-refs is passed EXPLICITLY. §5.2.1 of the spec says the wrapper exports
#     GATE_PUSH_REFS; it does not, and no `export` should be added — a subprocess would then
#     silently inherit nothing and report COULD-NOT-CHECK on every push. The explicit argument
#     is the contract.
SRT=""
if [ -f .githooks/security-review-trailer.py ]; then SRT=".githooks/security-review-trailer.py"
elif [ -f "$HOME/.claude/scripts/security-review-trailer.py" ]; then
  SRT="$HOME/.claude/scripts/security-review-trailer.py"
fi
SRT_OUTCOME=""
if [ "${CI:-}" = "true" ]; then
  SRT_OUTCOME="na-ci"
  echo "· skip [security-review-trailer] (n/a: CI runner — no push range to attribute)"
elif [ -z "$GATE_PUSH_REFS" ] || [ ! -s "$GATE_PUSH_REFS" ]; then
  SRT_OUTCOME="na-not-a-push"
  echo "· skip [security-review-trailer] (n/a: not a push — nothing is being attributed)"
elif [ -z "$SRT" ]; then
  # Absence has to be split, or it becomes checker B's version of the F5 deletion hole: a repo
  # that adopted §6 and then lost the script looks exactly like one that never adopted it.
  # `.githooks/sensitive-paths` is the opt-in signal — it lives outside the checker, so removing
  # the control means deleting a tracked file rather than silently dropping one.
  if [ -f .githooks/sensitive-paths ]; then
    SRT_OUTCOME="unchecked-missing"
    echo "❌ [security-review-trailer] .githooks/sensitive-paths exists but the checker does not."
    echo "   This repo declared a sensitive-path set and then lost the control that reads it —"
    echo "   restore .githooks/security-review-trailer.py or delete the config deliberately."
    fail=1
  else
    SRT_OUTCOME="unchecked-missing"
    echo "· skip [security-review-trailer] (UNCHECKED: checker not found and no"
    echo "  .githooks/sensitive-paths — §6 not adopted here)"
    [ "$STRICT" = "1" ] && { echo "❌ [security-review-trailer] UNCHECKED [STRICT]"; fail=1; }
  fi
elif ! command -v uv >/dev/null 2>&1; then
  SRT_OUTCOME="unchecked-nouv"
  echo "❌ [security-review-trailer] applies here but could not run: uv not installed."
  [ "$STRICT" = "1" ] && fail=1
else
  printf '\n\033[1m▶ security-review-trailer [security-review-trailer]\033[0m\n'
  uv run --no-project "$SRT" --check --push-refs "$GATE_PUSH_REFS"
  case $? in
    0) SRT_OUTCOME="checked" ;;
    1) SRT_OUTCOME="blocked"; fail=1 ;;
    2) # COULD-NOT-CHECK follows the ADR-0019 rule rather than failing outright: a
       # failure in CI, a warning on a workstation. Failing it locally blocks ordinary
       # pushes whenever a range is underivable (a fresh clone, a rewritten remote),
       # and routine false blocks are what train --no-verify — which disables the whole
       # gate, this control included.
       SRT_OUTCOME="unchecked"
       if [ "$STRICT" = "1" ]; then
         echo "❌ [security-review-trailer] could not check this push [STRICT]"; fail=1
       else
         echo "·  [security-review-trailer] UNCHECKED — see above (fails in CI)"
       fi ;;
    3) SRT_OUTCOME="na" ;;
    *) SRT_OUTCOME="unexpected"
       echo "❌ [security-review-trailer] unexpected exit status — failing closed"; fail=1 ;;
  esac
fi

# 1. Oxlint — fast lint (~12x ESLint). Prefers the pinned local devDep; falls
#    back to download. Set OXLINT_TYPE_AWARE=1 in CI to enable tsgolint (alpha).
#    Self-skips when the repo has no JS/TS — oxlint exits non-zero on "no files
#    found", which would otherwise abort the push on a Python-only project.
#    `:!machine/**` excludes the ADR-0020 machine-layer mirror, and it is an APPLICABILITY
#    exclusion, not a suppression: machine/ is a byte-exact copy of ~/.claude, so a lint error
#    there is not this repo's to fix — editing it breaks the mirror against the live layer by
#    construction. Its TypeScript is illustrative example code vendored from upstream skills,
#    governed by its upstream. Measured on adoption: 12 tracked .ts files, all under machine/,
#    and none of this repo's own. A no-op pathspec in any repo without a mirror.
#    machine/ is NOT unscanned — trivy covers it, and ADR-0017 §6 requires a SECURITY-REVIEW
#    trailer on machine/scripts, hooks, agents and settings.json. A linter was never that control.
JS_GLOBS=('*.js' '*.jsx' '*.ts' '*.tsx' '*.mjs' '*.cjs' '*.cts' '*.mts' ':!machine/**')
if [ ! -f .oxlintrc.json ]; then skip_na "oxlint" "no .oxlintrc.json"
elif ! have_files "${JS_GLOBS[@]}"; then skip_na "oxlint" "no JS/TS files"
elif ! command -v npx >/dev/null 2>&1; then skip_tool "oxlint" "npx/node not installed"
else
  step oxlint "oxlint"
  npx --yes oxlint --config .oxlintrc.json ${OXLINT_TYPE_AWARE:+--type-aware} || fail=1
fi

# 2. Format check — Biome (stable). Swap to oxfmt once it hits GA.
#    Guarded on JS/TS presence for the same reason as stage 1, learned the hard way:
#    biome formats JSON and JSONC too, so a config seeded into a docs/Python repo turned
#    the stage on over incidental JSON (test fixtures, findings files) and failed the push
#    on a repo with no JavaScript in it at all. Config-present was never the right
#    predicate — files-present is. (Dogfood finding, 2026-08-09.)
if [ ! -f biome.json ]; then skip_na "biome" "no biome.json"
elif ! have_files "${JS_GLOBS[@]}"; then skip_na "biome" "no JS/TS files"
elif ! command -v npx >/dev/null 2>&1; then skip_tool "biome" "npx/node not installed"
else
  step biome "biome format --check"
  npx --yes @biomejs/biome format --error-on-warnings . || fail=1
fi

# 2b. ruff — Python lint + format check (replaces flake8/isort/black). Uses [tool.ruff]
#     if present, else ruff defaults. uv-tool-isolated; self-skips if ruff or a Python
#     project marker (pyproject.toml / ruff.toml) is absent.
if ! { [ -f pyproject.toml ] || [ -f ruff.toml ] || [ -f .ruff.toml ]; }; then
  skip_na "ruff" "no pyproject.toml/ruff.toml"
elif ! command -v ruff >/dev/null 2>&1; then skip_tool "ruff" "ruff not installed"
else
  step ruff "ruff check + format --check"
  ruff check . || fail=1
  ruff format --check . || fail=1
fi

# 3. Dependency / secret / license scan — ADR-0001 zero-trust-deps.
#    Always applicable (ADR-0019 r4): every repo can commit a credential regardless of language,
#    so an absent trivy is never n/a. Two further checks before trusting a green:
#      r11  no database at all -> the scan RAN but verified nothing. UNCHECKED, not passing.
#      r5   database older than 7 days -> "no findings known as of last month". UNCHECKED.
#    Both read `trivy version --format json`; an absent DB omits the VulnerabilityDB key.
#
#    The UpdatedAt extraction is scoped to the VulnerabilityDB object BEFORE reading the field,
#    and that ordering is load-bearing. `trivy version` also emits JavaDB (and CheckBundle)
#    blocks, each carrying its own UpdatedAt, and each `omitempty` — so they are invisible here
#    until the day someone scans a JAR. A single greedy `.*"UpdatedAt":"` over the whole line
#    binds to the LAST match, i.e. JavaDB, and a freshly-pulled JavaDB would then vouch for a
#    year-old vulnerability DB. That is the wrong fail direction for the rule being enforced:
#    it converts a stale-DB failure into a silent pass. Verified against real 0.71.0 output.
#    Suppression is surfaced, never silent. `trivy fs` reads `.trivyignore` from the working
#    directory by default, so committing one quietly removes findings from a green report and
#    nothing in the output says so. ADR-0017 makes suppression legitimate but *disciplined* —
#    dated, justified, expiring — and repos that have not adopted that register get no such
#    discipline. Listing the entries every run keeps a green honest about what it excluded; it
#    does not fail, because suppression is a decision the register is designed to hold.
#    (Adversarial self-audit, 2026-08-10.)
#    Ignorefile resolution. ADR-0017's register is `.trivyignore.yaml`, and trivy does NOT
#    read that name by default. Measured against 0.71.0 with a control in both directions:
#    a plain `.trivyignore` present-but-unpassed suppresses (13 findings -> 12), while
#    `.trivyignore.yaml` present-but-unpassed does not (13 -> 13); passing it explicitly does.
#    So every repo seeded from this template had a register that gated NOTHING, while this
#    stage announced that it was suppressing findings. Validating that file (the trivy-register
#    stage below) without also APPLYING it would make the hygiene verdict describe a file state
#    that never gated anything — which is what grill #13 was actually about.
TRIVY_IGN=""; _ign_n=0
for _ti in .trivyignore .trivyignore.yaml .trivyignore.yml; do
  if [ -f "$_ti" ]; then _ign_n=$((_ign_n+1)); TRIVY_IGN="$_ti"; fi
done
if [ "$_ign_n" -gt 1 ]; then
  # --ignorefile REPLACES the default rather than adding to it, so honouring one of these
  # silently stops the other from applying: findings would move with no diff explaining it.
  echo "❌ [trivy] more than one ignorefile is present:"
  for _ti in .trivyignore .trivyignore.yaml .trivyignore.yml; do
    [ -f "$_ti" ] && echo "     $_ti"
  done
  echo "   trivy takes ONE --ignorefile and it replaces the default, so which findings get"
  echo "   suppressed would depend on this script's loop order rather than on a decision."
  echo "   Keep the ADR-0017 register (.trivyignore.yaml) and remove the others."
  fail=1
  TRIVY_IGN="/dev/null"        # honour neither; report everything
elif [ -n "$TRIVY_IGN" ]; then
  # grep -c exits 1 when the count is zero while still printing "0", so the old
  # `|| echo 0` appended a SECOND zero and printed "0\n0" into the message.
  _n=$(grep -cvE '^[[:space:]]*(#|$)' "$TRIVY_IGN" 2>/dev/null)
  [ -n "$_n" ] || _n=0
  echo "⚠  $TRIVY_IGN is in force (${_n} non-comment line(s)) — findings it matches are"
  echo "   excluded from the trivy stage below."
  #    Measured 2026-08-20: checker A validates `.trivyignore.yaml` BY THAT EXACT NAME
  #    (`trivy-register.py --check .trivyignore.yaml`), and the stage guard below tests for the
  #    same name. So a plain `.trivyignore` — or a `.trivyignore.yml` — is APPLIED here and
  #    validated by nothing, while this message used to promise trivy-register was holding it
  #    to dated entries. Two honest stages pointing at each other with zero coverage between
  #    them. Message-only fix; the coverage gap itself is a control change and is queued.
  if [ "$TRIVY_IGN" = ".trivyignore.yaml" ]; then
    echo "   ADR-0017 makes that suppression legitimate but disciplined; the trivy-register"
    echo "   stage is what holds it to dated, justified entries."
  else
    echo "   $TRIVY_IGN is NOT the ADR-0017 register: trivy-register validates .trivyignore.yaml"
    echo "   by that exact name, so these entries are applied but NEVER date-checked. The"
    echo "   trivy-register stage below reports UNCHECKED for exactly this reason, which fails"
    echo "   the gate under STRICT. Rename it to .trivyignore.yaml to put these entries under"
    echo "   ADR-0017 expiry discipline, or remove it."
  fi
fi

#    The scanned tree must not configure the scanner checking it (silent-pass Q6). trivy reads
#    `trivy.yaml` from the working directory by default, and the CLI does NOT universally win:
#    measured, `vulnerability.ignore-status: [fixed]` takes 13 findings to 0 with
#    `--severity HIGH,CRITICAL` still on the command line, and `scan.skip-dirs: ['**']` does the
#    same. Three committed lines would silence the one stage every baseline declares required.
#    `--config /dev/null` restores all 13. Reported rather than discarded in silence, because a
#    developer who wrote that file is entitled to know it had no effect.
for _tc in trivy.yaml trivy.yml; do
  if [ -f "$_tc" ]; then
    echo "⚠  $_tc is present and NOT used — this gate passes --config /dev/null so the tree"
    echo "   under inspection cannot configure its own scanner."
  fi
done
if ! command -v trivy >/dev/null 2>&1; then
  skip_tool "trivy" "trivy not installed"
else
  _tv="$(trivy version --format json 2>/dev/null)"
  _vdb="$(printf '%s' "$_tv" | sed -n 's/.*"VulnerabilityDB":{\([^}]*\)}.*/\1/p')"
  _db_updated="$(printf '%s' "$_vdb" | sed -n 's/.*"UpdatedAt":"\([^"]*\)".*/\1/p')"
  if [ -z "$_db_updated" ]; then
    skip_tool "trivy" "no vulnerability database — a scan would verify nothing"
  else
    _db_day="${_db_updated%%T*}"
    # BSD needs -j -f; GNU needs -d. 0 is the "neither form parsed" sentinel. tr keeps that
    # sentinel an integer: a date(1) that printed a partial result *and* failed would otherwise
    # leave a non-numeric string here, and $(( )) on it fails, leaving _age_days unset for
    # `set -u` to kill the gate on with an unbound-variable error rather than a verdict.
    _db_epoch="$(date -j -f '%Y-%m-%d' "$_db_day" '+%s' 2>/dev/null \
              || date -d "$_db_day" '+%s' 2>/dev/null || echo 0)"
    _db_epoch="$(printf '%s' "$_db_epoch" | tr -cd '0-9')"
    [ -n "$_db_epoch" ] || _db_epoch=0
    # Computed only on a successful parse, so the sentinel never reaches arithmetic:
    # (now - 0)/86400 is ~20675 "days", which is not a number any message should print.
    #
    # An unparseable date is UNCHECKED, not a pass. The first draft let it through on the
    # grounds that the DB provably exists, but "I cannot tell you whether this is fresh" is
    # not "this is fresh" — and a fail-open sitting inside the rule whose whole thesis is
    # fail-closed is exactly the shape ADR-0019 was written to remove. The ADR-0017 worry
    # (a trivy release breaking every repo's CI) does not apply: a real format change loses
    # the VulnerabilityDB scoping too, so it already lands on the r11 branch above. What
    # remains reachable here is narrow — UpdatedAt present but not a date — and genuinely
    # means freshness is unverifiable.
    # `_db_parsed` is a FLAG, not a sentinel value in _age_days. It used to be the latter
    # (`_age_days=-1`), and arithmetic produces that same -1 on its own, so the two collapsed:
    #
    #   UpdatedAt is a UTC timestamp, but its date part is parsed as LOCAL midnight. Within a
    #   few hours either side of a day boundary that lands in the future, `now - epoch` goes
    #   negative, and the `-lt 0` arm below then ran NO scan and recorded NO ledger entry —
    #   the stage simply vanished. Measured on this machine at 2026-08-16 22:42 EDT with a DB
    #   dated 2026-08-17T00:56Z: WORKFLOW's own gate reported "[trivy] declared required but
    #   reported nothing at all", and in the five repos that carry no baseline the required
    #   vulnerability scan silently did not happen and the gate passed.
    #
    # A DB dated today or later is FRESH, not broken, so the age clamps to 0. Only a genuinely
    # unparseable date is UNCHECKED, and that is now decided by the flag alone.
    _db_parsed=1
    if [ "$_db_epoch" -le 0 ]; then
      skip_tool "trivy" "vulnerability database date unparseable ('$_db_updated') — freshness unverifiable"
      _db_parsed=0
      _age_days=0
    else
      _age_days=$(( ( $(date '+%s') - _db_epoch ) / 86400 ))
      [ "$_age_days" -lt 0 ] && _age_days=0
    fi
    if [ "$_db_parsed" -eq 0 ]; then
      : # already reported UNCHECKED above; must not also run the scan
    elif [ "$_age_days" -gt 7 ]; then
      skip_tool "trivy" "vulnerability database is ${_age_days} days old (max 7)"
    else
      # A failed mktemp must not read as "no findings". The branch guard shipped exactly that
      # fail-open on 2026-08-14 — a temp file the control depends on, whose absence looked like
      # a clean result. Same shape here, so the same answer: UNCHECKED, never a pass.
      TRIVY_JSON="$(mktemp 2>/dev/null || printf '')"
      if [ -z "$TRIVY_JSON" ] || [ ! -f "$TRIVY_JSON" ]; then
        skip_tool "trivy" "could not create a report file (TMPDIR unwritable?) — a scan whose output cannot be kept verifies nothing"
      else
        step trivy "trivy fs (vuln,secret,license)"
        # JSON is not cosmetic: checker A derives direct/transitive from the report (F3) and
        # matches every register entry against a live finding (F2). --show-suppressed is what
        # keeps a correctly-registered entry visible after --ignorefile removes it from the
        # live results; without it every such entry reads as an orphan (42 of 45 on kiln).
        _targs=(fs --config /dev/null --scanners vuln,secret,license --exit-code 1
                --severity HIGH,CRITICAL --quiet --show-suppressed
                --format json -o "$TRIVY_JSON")
        if [ -n "$TRIVY_IGN" ]; then _targs+=(--ignorefile "$TRIVY_IGN"); fi
        _targs+=(.)
        if ! trivy "${_targs[@]}"; then
          fail=1
          # `--format json -o FILE` writes NOTHING to stdout — verified. Without rendering, a
          # blocked push reports no reason at all, which is precisely the friction that trains
          # --no-verify and disables this whole gate.
          #
          # `convert` RE-APPLIES the working directory's ignorefile while rendering, so the
          # table is not a pure function of the report it is handed. Measured with a control:
          # the report held 13 findings and a bare `convert` printed "Total: 12" — the gate
          # would have blocked on 13 while showing the developer 12, with the difference
          # silently dropped. `--config /dev/null` alone does NOT fix it (still 12); the
          # ignorefile has to be neutralised too. Rendering must describe what gated.
          if ! trivy convert --config /dev/null --ignorefile /dev/null \
                             --format table "$TRIVY_JSON" 2>/dev/null; then
            echo "   (could not render the report; raw finding ids follow)"
            grep -o '"VulnerabilityID":"[^"]*"' "$TRIVY_JSON" 2>/dev/null \
              | sed 's/.*:"/     /; s/"$//' | sort -u
          fi
        fi
      fi
    fi
  fi
fi

# 3b. trivy-register — ADR-0017 §3/§4 risk-register hygiene (checker A).
#
#     ORDER. The spec pinned this stage BEFORE trivy, so the register would be validated
#     before being consumed as an ignorefile (grill #13). That ordering cannot hold: checker A
#     derives direct/transitive from the findings (F3) and matches every entry against a live
#     finding (F2), and those findings only exist once trivy has run. The two requirements are
#     in direct conflict as written.
#
#     The ordering was a PROXY for the real property — *the register that was validated is the
#     register that gated*. Order cannot establish that: `fail` is initialised once and only
#     ever set, never reset, so within a run the verdict is identical either way; only the
#     printed order changes. What establishes the property is passing --ignorefile (so the
#     register actually gates) and reporting it, both of which the stage above now does. Recorded
#     rather than silently reordered, because a rejected constraint that leaves no trace gets
#     reinvented.
#
#     Applicability is decided HERE rather than inside the checker, so that a repo which never
#     adopted ADR-0017 is n/a without needing the script present. The alternative — always
#     invoking it — would have forced the checkers into ~/.claude/scripts/ for distribution,
#     where harness_script() resolves them FIRST and would silently shadow a repo's own copy.
#     ADR-0017 authorises repos to adapt these checkers, and macdaddy --refresh destroying
#     kiln's customisation once is why that matters.
TRIVY_REGISTER=""
if [ -f .githooks/trivy-register.py ]; then TRIVY_REGISTER=".githooks/trivy-register.py"
elif [ -f "$HOME/.claude/scripts/trivy-register.py" ]; then
  TRIVY_REGISTER="$HOME/.claude/scripts/trivy-register.py"
fi
_reg_required=0
if [ -f "$BASELINE" ] && grep -qE '^[[:space:]]*required:[[:space:]]+trivy-register([[:space:]]|$)' "$BASELINE" 2>/dev/null; then
  _reg_required=1
fi
#    An ungoverned ignorefile is COULD-NOT-CHECK, not NOT-APPLICABLE (ADR-0019 r1).
#    Until 2026-08-23 this branch reported n/a in a message that said, in the same breath,
#    that something WAS in force and nothing was date-checking it. The message was honest and
#    the classification was wrong, and `skip_na` passes unconditionally — so a repo could
#    suppress findings through an ungoverned file and take a green CI run. Measured before the
#    fix, `STRICT=1 CI=true bash .githooks/lint-gate.sh` with a one-line `.trivyignore`
#    present: exit 0.
#
#    Which names actually matter, measured against trivy 0.71.0 with a non-zero baseline (2
#    misconfig findings) and a positive control (`--ignorefile` -> 0, proving the apparatus
#    live), each present-but-unpassed:
#        .trivyignore        2 -> 1   AUTO-READ by trivy itself
#        .trivyignore.yaml   2 -> 2   not auto-read
#        .trivyignore.yml    2 -> 2   not auto-read
#    So `.trivyignore` applies whether or not anyone decided it should, and `.trivyignore.yml`
#    applies because THIS SCRIPT passes it as $TRIVY_IGN. Both end up suppressing findings that
#    trivy-register never sees, which is the coverage gap this branch closes.
#
#    trivy-register.py:539 already returns UNCHECKED for a sibling alongside the register; the
#    old guard short-circuited to n/a before the checker was ever invoked, so its correct
#    verdict was unreachable. Pinned by test_ungoverned_ignorefile_is_unchecked_not_na.
if [ ! -f .trivyignore.yaml ] && [ "$_reg_required" -eq 0 ] \
   && { [ -z "$TRIVY_IGN" ] || [ "$TRIVY_IGN" = "/dev/null" ]; }; then
  skip_na "trivy-register" "no .trivyignore.yaml register exists here and the baseline does not require this stage"
elif [ ! -f .trivyignore.yaml ] && [ "$_reg_required" -eq 0 ]; then
  skip_tool "trivy-register" "$TRIVY_IGN is in force and suppressing findings, but it is not the ADR-0017 register (.trivyignore.yaml) — nothing date-checks its entries. Rename it to .trivyignore.yaml, or remove it; the tool is present and this is not a missing-tool failure"
elif [ -z "$TRIVY_REGISTER" ]; then
  skip_tool "trivy-register" "trivy-register.py not found in .githooks/ or ~/.claude/scripts/"
elif ! command -v uv >/dev/null 2>&1; then
  skip_tool "trivy-register" "uv not installed"
elif ! in_set "trivy" "$STAGES_RAN"; then
  # H4. Gated on whether the SCAN RAN, not on whether the report file exists. Every trivy
  # decline path leaves that file empty, and an empty file produces two different wrong
  # answers depending on which path fired: unreadable reads as could-not-check, while a
  # valid-but-empty `{}` makes every register entry an orphan. Neither would be a decision.
  skip_tool "trivy-register" "the trivy scan did not run, so there are no findings to check the register against"
else
  step trivy-register "trivy-register (ADR-0017 §3/§4)"
  # --no-findings is deliberately absent and must stay absent: it disables BOTH expiry caps
  # and the orphan check, leaving a stage that prints a pass line having verified almost
  # nothing. Pinned by test_the_gate_never_disables_the_register_checks.
  uv run --no-project "$TRIVY_REGISTER" --check .trivyignore.yaml \
      --findings "$TRIVY_JSON" --baseline "$BASELINE"
  case $? in
    0) : ;;
    1) fail=1 ;;
    2) skip_tool "trivy-register" "the register could not be checked (see above)" ;;
    3) skip_na  "trivy-register" "the checker reports nothing to check here" ;;
    *) echo "❌ [trivy-register] unexpected exit status — failing closed"; fail=1 ;;
  esac
fi

# 3c. gitleaks — secret scan over the COMMITS BEING PUSHED. Closes what trivy cannot see.
#
#     WHY, measured 2026-09-14 (STACK §39). `trivy fs --scanners secret` scans the filesystem,
#     so a secret committed and later deleted exits 0 here and ships inside the pushed history.
#     Three-arm fixture, control fired for both tools:
#         working tree      trivy FOUND   gitleaks FOUND
#         history only      trivy CLEAN   gitleaks FOUND    <- the hole this closes
#         low entropy       trivy FOUND   gitleaks CLEAN    <- why trivy is not replaced
#     Neither subsumes the other, so both run. This stage is additive.
#
#     SCOPE IS THE PUSHED RANGE, NEVER FULL HISTORY. A full scan of this repo returns 24
#     findings, every one an example `curl -H "Authorization: ..."` line or a sample key inside
#     an attack-documentation skill: a repo that documents attacks contains attack-shaped
#     strings. Permanently-red is how a control gets disabled rather than fixed.
#
#     NEVER REPORTS n/a. The stage applies to every git repo, so it can never legitimately be
#     exempt, and `required` + n/a is a hard fail at the declaration check below.
#
#     WHAT THIS DOES NOT COVER — stated here so the stage is not read as broader than it is.
#     Both were found by an adversarial pass 2026-09-14 and are deliberate ceilings, not bugs:
#       merge commits      `git log -p` does not show merge diffs, so content introduced BY a
#                          merge commit — present in neither parent, i.e. a conflict resolution
#                          where someone pastes a real value — is invisible. Upgrade path:
#                          `--diff-merges=first-parent` in --log-opts, weighed against the
#                          duplicate findings that produces on a merge-heavy history.
#       message bodies     commit messages and annotated-tag messages are not scanned at all.
#                          A token pasted into a commit message is an ordinary accident.
#                          Upgrade path: a separate pass over `git log --format=%B <range>`.
#     The promise is therefore "secrets in the FILE CONTENT of the non-merge commits being
#     pushed", which is narrower than "secrets in the commits being pushed". A control that
#     claims more than it does is the failure this gate exists to avoid.
_GL_ZERO="0000000000000000000000000000000000000000"
# git calls pre-push with the destination remote's NAME as $1 and its LOCATION as $2, and
# documents that "if a named remote is not being used both values will be the same".
_GL_REMOTE="${1:-}"
_GL_REMOTE_LOC="${2:-}"
_gl_ranges=""
_gl_why=""

if ! command -v gitleaks >/dev/null 2>&1; then
  # An ENVIRONMENT problem, not a repo-state one: nothing here is suppressing anything, the
  # machine is just missing a tool. Lenient locally, fatal under STRICT, exactly like trivy.
  skip_tool "gitleaks" "gitleaks not installed"

elif [ -e .gitleaksignore ]; then
  #  G1 + G2, both measured. gitleaks reads `.gitleaksignore` from the working directory and
  #  CANNOT be made to ignore it: `--gitleaks-ignore-path /dev/null` returns 0 findings, and a
  #  nonexistent path also returns 0 findings AND no error, while the control (file moved away)
  #  returns 1. It does not care whether the file is tracked.
  #
  #  Two bypasses existed in the first draft, which merely printed the file's contents:
  #    - an UNTRACKED .gitleaksignore silences the scan while appearing in no commit, no diff
  #      and no review (measured: rc 1 -> 0; `git status` shows only `?? .gitleaksignore`);
  #    - a TRACKED one committed in the SAME push suppresses that push's own finding, at the
  #      cost of one extra commit with an innocuous message (measured: rc 1 -> 0).
  #  Printing was not a control. A line in a hook's scrollback is not a review, and the
  #  suppression list was being read from the very tree under inspection — silent-pass Q6.
  #
  #  A nested copy is NOT a hole: measured, gitleaks does not honour sub/.gitleaksignore, so
  #  testing the repo root is sufficient rather than merely convenient.
  #
  #  gitleaks: blunt on purpose — presence alone refuses, with no declared escape hatch. A repo
  #  that must keep fixtures matching a rule cannot get a green stage today. Upgrade path if
  #  such a repo appears: add an explicit keyword to the gate-baseline grammar, declared
  #  OUTSIDE the file it governs, the way ADR-0017 put `required: trivy-register` in the
  #  baseline rather than inside the register it validates.
  #
  #  G7, and this is why it is a HARD FAIL rather than skip_tool. `skip_tool` sets fail=1 only
  #  under STRICT, and STRICT derives from CI — so on a workstation it warns and the push
  #  PROCEEDS. Routing this through skip_tool would have closed the bypass only in CI, i.e.
  #  only after the secret had already reached the remote, which is the event this stage exists
  #  to prevent. ADR-0019 separated two kinds of decline; this is a third:
  #      tool absent   ENVIRONMENT — nothing is wrong with the repo. Lenient locally is right.
  #      suppressed    REPO STATE  — something present is actively disarming the scanner.
  #                                  No benign reading at an irreversible act. Blocks.
  #  The hard fail is justified by push-time irreversibility, so it applies when a push is
  #  actually in flight. Run by hand there is nothing irreversible about to happen, and the
  #  honest verdict is the same could-not-check every other hand-run path reports — otherwise
  #  `bash .githooks/lint-gate.sh` exits 1 in any repo carrying the file, for a reason the
  #  stage's own rationale does not cover. Under CI, skip_tool is a failure anyway via STRICT.
  if [ -n "$GATE_PUSH_REFS" ] && [ -s "$GATE_PUSH_REFS" ]; then
    STAGES_UNCHECKED="${STAGES_UNCHECKED}gitleaks:"
    echo "❌ [gitleaks] .gitleaksignore exists, and gitleaks honours it unconditionally — it"
    echo "   cannot be disabled by any flag (measured), so this scan cannot be trusted."
    echo "   Remove the file, or fix the findings it hides. This blocks the push rather than"
    echo "   warning, because a warning here would let the secret reach the remote and leave"
    echo "   CI to discover it afterwards."
    fail=1
  else
    skip_tool "gitleaks" ".gitleaksignore exists and gitleaks honours it unconditionally (no flag disables it — measured), so a scan here could not be trusted. Nothing is being pushed, so this is reported rather than blocking"
  fi

elif [ "${CI:-}" = "true" ]; then
  # CI arm. No push here, so the equivalent range is what this ref adds to its base. Both
  # inputs come from the workflow and BOTH are verified to resolve before use: a base that does
  # not resolve must be UNCHECKED, never an empty range that reads as a clean pass. This needs
  # fetch-depth: 0 — at the default depth 1 there is no history and every rev-parse below fails.
  if [ -n "${GITHUB_BASE_REF:-}" ]; then
    if git rev-parse --verify --quiet "origin/${GITHUB_BASE_REF}" >/dev/null 2>&1; then
      _gl_ranges="origin/${GITHUB_BASE_REF}..HEAD"
      _gl_why="PR base origin/${GITHUB_BASE_REF}"
    else
      skip_tool "gitleaks" "CI: origin/${GITHUB_BASE_REF} does not resolve — is the checkout shallow? (needs fetch-depth: 0)"
    fi
  elif [ -n "${GITHUB_EVENT_BEFORE:-}" ] && [ "${GITHUB_EVENT_BEFORE}" != "$_GL_ZERO" ]; then
    if git rev-parse --verify --quiet "${GITHUB_EVENT_BEFORE}^{commit}" >/dev/null 2>&1; then
      _gl_ranges="${GITHUB_EVENT_BEFORE}..HEAD"
      _gl_why="push before ${GITHUB_EVENT_BEFORE}"
    else
      skip_tool "gitleaks" "CI: ${GITHUB_EVENT_BEFORE} does not resolve — is the checkout shallow? (needs fetch-depth: 0)"
    fi
  else
    skip_tool "gitleaks" "CI: no resolvable base — neither GITHUB_BASE_REF nor GITHUB_EVENT_BEFORE is usable"
  fi

elif [ -z "$GATE_PUSH_REFS" ] || [ ! -s "$GATE_PUSH_REFS" ]; then
  # Not a push (run by hand), or a push with zero refs. Nothing was checked, and n/a here would
  # assert that nothing NEEDED checking — false, this stage always applies. Not a hard fail:
  # no push is in flight, so nothing irreversible is about to happen.
  skip_tool "gitleaks" "not a push — no refspecs on stdin, so there is no pushed range to scan (run it as the pre-push hook, or in CI)"

elif [ -z "$_GL_REMOTE" ] || [ "$_GL_REMOTE" = "$_GL_REMOTE_LOC" ]; then
  #  G5. A push that targets a location rather than a named remote has no refs/remotes/<name>/*
  #  to bound the new-branch range against, so it would widen to the entire history — 24
  #  documentation false positives here, which gets the stage removed rather than the secret
  #  fixed. git's own doc is the test: when a named remote is not being used, $1 and $2 are
  #  equal. G7 applies again — this branch is only reachable DURING a push, so warning would
  #  make a direct-to-URL push the bypass. Blocks.
  STAGES_UNCHECKED="${STAGES_UNCHECKED}gitleaks:"
  echo "❌ [gitleaks] this push targets a location rather than a named remote ('$_GL_REMOTE'),"
  echo "   so there are no remote-tracking refs to bound the scan and it would widen to the"
  echo "   whole history. Push to a named remote instead."
  fail=1

else
  #  G4 + G5. The exclusion set is the remote ACTUALLY BEING PUSHED TO, taken from git's own
  #  argument, not a hardcoded 'origin' — a fork or triangular workflow pushing to 'upstream'
  #  would otherwise exclude nothing and widen to full history.
  #
  #  Residual, recorded rather than papered over: refs/remotes/<remote>/* are LOCAL refs, so a
  #  developer can move what gets excluded. That is accepted. It is not a privilege boundary —
  #  anyone who can write local refs can also type --no-verify — and pretending otherwise would
  #  be theatre. This stage stops mistakes, not the author.
  #
  #  A remote name cannot inject extra revision arguments into the range: git refuses to create
  #  a remote whose name contains a space (measured), so the value is constrained before the
  #  gate ever sees it.
  _gl_bad=0
  while IFS=' ' read -r _gl_lr _gl_ls _gl_rr _gl_rs || [ -n "$_gl_lr" ]; do
    # `|| [ -n "$_gl_lr" ]` — see the branch-guard loop above. An unterminated final line is
    # otherwise dropped, and here that would silently remove a refspec from the scan.
    #
    # Same shape validation as branch-guard. A malformed line must not silently contribute an
    # empty range that then reads as "scanned, clean".
    _gl_shape=1
    [ ${#_gl_ls} -eq 40 ] || _gl_shape=0
    [ ${#_gl_rs} -eq 40 ] || _gl_shape=0
    [ -n "$_gl_lr" ] && [ -n "$_gl_rr" ] || _gl_shape=0
    case "$_gl_ls$_gl_rs" in *[!0-9a-f]*|"") _gl_shape=0 ;; esac
    if [ "$_gl_shape" -ne 1 ]; then _gl_bad=1; continue; fi

    #  ONE RANGE PER LINE, and each is scanned in its OWN gitleaks invocation below.
    #
    #  They used to be concatenated into a single --log-opts string, and that was a complete
    #  fail-open. git documents that `--not` "reverses the meaning of the ^ prefix for all
    #  following revision specifiers", so a new-branch refspec — which contributes
    #  `<sha> --not --remotes=<remote>` — INVERTS every range appended after it. Measured:
    #      new branch FIRST, then an update range : count 1, the update commit EXCLUDED
    #      update range FIRST, then the new branch: count 2, present
    #  A push of a new branch alongside an ordinary update therefore left the update unscanned
    #  while printing a plausible commit count and a green "no leaks found".
    #
    #  The first grill tested exactly one ordering — `--not` last, where nothing follows it —
    #  saw no contamination, and recorded the whole class as falsified. A negative result from
    #  one configuration is evidence about that configuration.
    if [ "$_gl_ls" = "$_GL_ZERO" ]; then
      : # ref deletion — git sends (delete) with an all-zero local object name. No new objects
        # are published, so there is nothing to scan.
    elif [ "$_gl_rs" = "$_GL_ZERO" ]; then
      # New branch: everything reachable from the local sha that the remote does not have.
      #
      #  The exclusion set must actually EXIST. `--not --remotes=<remote>` excludes nothing when
      #  refs/remotes/<remote>/* is empty, and the range then silently widens to the whole
      #  history — measured in this repo: `--not --remotes=origin` counts 14, an unfetched
      #  remote counts 324. That is the ordinary state right after `git remote add`, since
      #  remote-tracking refs are only written by a fetch or a successful push. A full scan here
      #  yields 24 documentation false positives and both escape hatches are now closed, so
      #  widening would hard-block the first push to any new remote for a reason the operator
      #  cannot act on. The $1 == $2 guard above only covers pushing to a bare URL; this is the
      #  named-but-unknown case, and it refuses for the same reason: an unbounded scope at an
      #  irreversible act is not a scope.
      if ! git for-each-ref --count=1 "refs/remotes/${_GL_REMOTE}/" 2>/dev/null | grep -q .; then
        _gl_bad=2
        continue
      fi
      _gl_ranges="${_gl_ranges}${_gl_ls} --not --remotes=${_GL_REMOTE}
"
    else
      _gl_ranges="${_gl_ranges}${_gl_rs}..${_gl_ls}
"
    fi
  done < "$GATE_PUSH_REFS"

  if [ "$_gl_bad" -eq 2 ]; then
    STAGES_UNCHECKED="${STAGES_UNCHECKED}gitleaks:"
    echo "❌ [gitleaks] no remote-tracking refs exist for '${_GL_REMOTE}', so a new branch cannot be"
    echo "   bounded against what that remote already has and the scan would widen to the whole"
    echo "   history. Run: git fetch ${_GL_REMOTE}   then push again."
    fail=1
    _gl_ranges=""
  elif [ "$_gl_bad" -eq 1 ]; then
    # A push is in flight and its scope could not be derived. Same reasoning as the two hard
    # fails above: unscannable scope at an irreversible act blocks.
    STAGES_UNCHECKED="${STAGES_UNCHECKED}gitleaks:"
    echo "❌ [gitleaks] a refspec line was malformed — the pushed range could not be derived,"
    echo "   so nothing can be scanned. Failing closed."
    fail=1
    _gl_ranges=""
  elif [ -z "$_gl_ranges" ]; then
    # Every refspec was a ref deletion, so no new objects are being published and there is
    # genuinely nothing to scan. The stage must still REPORT: `required` + reported-nothing is
    # a gate failure ("declared required but reported nothing at all"), and n/a would claim
    # this stage does not apply to the repo, which is false.
    step gitleaks "gitleaks (pushed range)"
    echo "  0 commit(s) in range — every refspec was a ref deletion, so no new objects are being published"
  else
    _gl_why="pushed refs, excluding what ${_GL_REMOTE} already has"
  fi
fi

if [ -n "$_gl_ranges" ]; then
  step gitleaks "gitleaks (pushed range)"
  echo "  scanning each pushed ref separately (${_gl_why})"

  #  A THIRD suppression channel, and it is disarmed rather than blocked. `.gitleaks.toml` is
  #  auto-loaded from the scan root and REPLACES the whole ruleset, so an untracked one —
  #  appearing in no commit, no diff and no review — silences every rule (measured: rc 1 -> 0).
  #  `GITLEAKS_CONFIG` in a shell profile does the same and is less visible still.
  #
  #  Unlike `.gitleaksignore`, this channel CAN be disarmed, and the standing rule in this stage
  #  is that a channel which can be disarmed is (measured: with --config pointing outside the
  #  repo, rc is 1 again). The config is WRITTEN AT RUNTIME to a temp file rather than shipped
  #  and resolved: nothing new to seed into every repo, no resolution order that a repo-local
  #  copy could shadow, and nothing on disk for the checked-out tree to tamper with.
  # Registered in the single EXIT trap at the top, per the convention stated there: a Ctrl-C
  # during a long multi-range scan is the likeliest moment to interrupt, and the per-path `rm -f`
  # below only runs on the normal route.
  _GL_CONF="$(mktemp 2>/dev/null || printf '')"
  if [ -z "$_GL_CONF" ] || [ ! -f "$_GL_CONF" ]; then
    STAGES_UNCHECKED="${STAGES_UNCHECKED}gitleaks:"
    echo "❌ [gitleaks] could not create a config file (TMPDIR unwritable?), so the repo's own"
    echo "   .gitleaks.toml could not be neutralised and the scan cannot be trusted."
    fail=1
  else
    printf '[extend]\nuseDefault = true\n' > "$_GL_CONF"
    unset GITLEAKS_CONFIG

    _gl_n=0        # ranges actually handed to the scanner
    _gl_seen=0     # ranges seen, including those refused before the scan
    while IFS= read -r _gl_r; do
      [ -z "$_gl_r" ] && continue
      _gl_seen=$((_gl_seen + 1))

      #  The denominator, per range (ADR-0019 r6). A count that cannot be computed is a
      #  COULD-NOT-CHECK and blocks: an unresolvable range makes gitleaks log an error and exit
      #  0, which is a clean pass over nothing. Reachable on a force-push to a remote tip that
      #  was never fetched — exactly the operation someone performs after committing a token.
      _gl_count="$(git rev-list --count $_gl_r 2>/dev/null)"
      if [ -z "$_gl_count" ]; then
        STAGES_UNCHECKED="${STAGES_UNCHECKED}gitleaks:"
        echo "❌ [gitleaks] range ${_gl_seen} (${_gl_r}) does not resolve, so its commits cannot be"
        # $_GL_REMOTE is empty in the CI arm (invoked with no arguments there), so the advice
        # names a remote only when there is one — and "push again" is nonsense in CI.
        if [ -n "$_GL_REMOTE" ]; then
          echo "   counted or scanned. Fetch from ${_GL_REMOTE} and push again. Failing closed."
        else
          echo "   counted or scanned — is this checkout shallow? Failing closed."
        fi
        fail=1
        continue
      fi
      _gl_n=$((_gl_n + 1))
      echo "  range ${_gl_seen}: ${_gl_r} — ${_gl_count} commit(s)"

      #  gitleaks exits 0 when git itself fails inside it, so its exit status alone cannot
      #  distinguish "clean" from "never ran". Its stderr is captured and an ERR line is
      #  treated as a failure regardless of exit status.
      _GL_OUT="$(mktemp 2>/dev/null || printf '')"; _gl_out="$_GL_OUT"
      if [ -z "$_gl_out" ] || [ ! -f "$_gl_out" ]; then
        STAGES_UNCHECKED="${STAGES_UNCHECKED}gitleaks:"
        echo "❌ [gitleaks] could not create a report file for range ${_gl_seen} — a scan whose"
        echo "   output cannot be kept verifies nothing. Failing closed."
        fail=1
        continue
      fi
      if ! gitleaks git . --no-banner --redact --ignore-gitleaks-allow \
                          --config "$_GL_CONF" --log-opts="$_gl_r" > "$_gl_out" 2>&1; then
        cat "$_gl_out"
        #  A nonzero exit is not necessarily a leak: a config parse failure, an unreadable
        #  object store or a killed process are also nonzero, and telling the operator to rewrite
        #  history and rotate a credential that does not exist is a misdiagnosis. The scanner
        #  names its own finding, so the message follows the output rather than the exit status.
        if grep -q "leaks found" "$_gl_out"; then
          echo "❌ [gitleaks] a secret was found in the commits being pushed (range ${_gl_seen})."
        else
          echo "❌ [gitleaks] the scanner FAILED on range ${_gl_seen} without reporting a leak —"
          echo "   its output is above. Failing closed: this range was not verified."
        fi
        echo "   These commits are not yet public. Rewrite them rather than adding a follow-up"
        echo "   commit that deletes the file: deleting it leaves the secret in history, which"
        echo "   is the exact hole this stage exists to close. Then rotate the credential."
        fail=1
      elif grep -q 'ERR' "$_gl_out"; then
        cat "$_gl_out"
        echo "❌ [gitleaks] the scanner reported an error on range ${_gl_seen} and still exited 0,"
        echo "   so this range was not actually scanned. Failing closed."
        fail=1
      fi
      rm -f "$_gl_out"
    done <<< "$_gl_ranges"

    rm -f "$_GL_CONF"
    # Counts ranges the scanner actually ran on, not ranges seen. The two differ whenever a
    # range was refused before the scan (unresolvable, or no report file), and in a gate whose
    # own rule is that a control reports a true denominator, this line IS the denominator.
    if [ "$_gl_n" -eq "$_gl_seen" ]; then
      echo "  ${_gl_n} range(s) scanned"
    else
      echo "  ${_gl_n} of ${_gl_seen} range(s) scanned — the rest were refused above"
    fi
  fi
fi

# 4. Semgrep — ADR-derived security rules authored via semgrep-rule-creator.
if [ ! -d .semgrep ]; then skip_na "semgrep" "no .semgrep/ rules"
elif ! command -v semgrep >/dev/null 2>&1; then skip_tool "semgrep" "semgrep not installed"
else
  step semgrep "semgrep (ADR rules)"
  semgrep --config .semgrep --error --quiet . || fail=1
fi

# 4b. bandit — Python SAST (security). Fails only on HIGH severity + HIGH confidence
#     (fail-closed on real issues, low FP noise — sast-triage-criteria lens); tune via
#     [tool.bandit] in pyproject. uv-tool-isolated; self-skips if bandit or pyproject absent.
if [ ! -f pyproject.toml ]; then skip_na "bandit" "no pyproject.toml"
elif ! command -v bandit >/dev/null 2>&1; then skip_tool "bandit" "bandit not installed"
else
  step bandit "bandit (Python SAST, HIGH/HIGH)"
  bandit -r . -q --severity-level high --confidence-level high \
    -x '*/.venv/*,*/venv/*,*/node_modules/*,*/build/*,*/dist/*' || fail=1
fi

# 4c. actions-pin — ADR-0018: Actions pinned to full commit SHAs, top-level `permissions:`,
#     no write-all, no unwaived pull_request_target. Scans .github/workflows/ and
#     .github/actions/**/action.yml. Offline by design (--verify is the network mode and
#     deliberately stays out of the gate). Self-skips when the repo ships no workflows.
#     Invoked via `uv run`: bare `python3` here resolves to modern-python's PATH shim, which
#     exits 1 on every invocation [measured 2026-08-29; the command and its output are in the
#     SHEBANG note of ~/Apps/WORKFLOW/scripts/anchored-edit.py]. This cited STACK §20 until
#     2026-08-29; that section is the ruff/bandit stages and the word "shim" appears nowhere in
#     STACK.md (probe.sh, control "uv"=21, target "shim"=0, rc=1).
ACTIONS_PIN="$(harness_script actions-pin.py)"
if ! { [ -d .github/workflows ] || [ -d .github/actions ]; }; then
  skip_na "actions-pin" "no .github/workflows|actions"
elif [ -z "$ACTIONS_PIN" ]; then skip_tool "actions-pin" "actions-pin.py not found in ~/.claude/scripts/ or .githooks/"
elif ! command -v uv >/dev/null 2>&1; then skip_tool "actions-pin" "uv not installed"
else
  step actions-pin "actions-pin (ADR-0018)"
  uv run --no-project "$ACTIONS_PIN" --check . || fail=1
fi

# 4d. seed-drift — ADR-0020: the ADRs a repo seeds into NEW repos must match its own adr/.
#     macdaddy.sh:101 globs ~/.claude/templates/adr/*.md into every new repo, so that directory
#     is a seed set, not a decorative copy. Measured 2026-08-23: ADR-0017's seed sat 92 lines
#     stale — no §6, no §7, and asserting "reviewed surface" without the addendum correcting it.
#
#     This stage does NOT reimplement the comparison. It invokes the test that already owns it,
#     because two enforcers of one rule in two languages is the drift this repo keeps paying for
#     — the same reason adr/.not-seeded is a file both readers share rather than a literal in
#     each. One implementation, two callers: CI runs the test directly, this runs it pre-push.
#
#     n/a is honest here: a repo with no machine/templates/adr/ seeds nothing, so there is
#     nothing to compare. Deleting the directory to escape the check is NOT a free opt-out in a
#     repo that declares `required: seed-drift` — n/a against a required stage is a hard failure
#     in the verdict block below.
SEED_TEST="harness-eval/tests/test_harness_mirror.py::test_tracked_adr_seed_mirror_matches_repo"
if [ ! -d adr ] || [ ! -d machine/templates/adr ]; then
  skip_na "seed-drift" "no adr/ + machine/templates/adr/ pair — this repo seeds no ADRs to others"
elif [ ! -f adr/.not-seeded ]; then
  skip_tool "seed-drift" "adr/.not-seeded missing — cannot tell a considered exclusion from a forgotten copy"
elif [ ! -f "${SEED_TEST%%::*}" ]; then
  skip_tool "seed-drift" "${SEED_TEST%%::*} not found — the comparison lives there, not here"
elif ! command -v uv >/dev/null 2>&1; then
  skip_tool "seed-drift" "uv not installed (needed to run the seed-set test)"
else
  step seed-drift "ADR seed set matches repo (ADR-0020)"
  # Denominator, so a green run is distinguishable from a glob that matched nothing (ADR-0019 r6).
  _seed_n=$(ls adr/0*.md 2>/dev/null | wc -l | tr -d ' ')
  _seed_x=$(grep -cvE '^[[:space:]]*(#|$)' adr/.not-seeded 2>/dev/null || echo 0)
  if [ "$_seed_n" -eq 0 ]; then
    echo "❌ [seed-drift] adr/ contains no 0*.md — nothing to compare, which cannot be a pass"
    fail=1
  else
    echo "  $_seed_n ADRs in adr/, $_seed_x declared not-seeded in adr/.not-seeded"
    uv run --no-project --with pytest pytest "$SEED_TEST" -q --no-header || fail=1
  fi
fi

# 5. dependency-cruiser — module-graph / architecture rules (structural ADR enforcement, STACK §16).
#    Complements Semgrep (AST patterns): forbidden imports, dependency-direction, circular deps,
#    orphans. Opt-in — runs only if the project ships a dep-cruiser config (i.e. has architectural
#    ADRs). Scaffold one with `npx depcruise --init`. MIT · npx · no global install · no telemetry.
DEPCRUISE_CFG=""; for c in .dependency-cruiser.js .dependency-cruiser.cjs .dependency-cruiser.mjs .dependency-cruiser.json .dependency-cruiser.jsonc; do [ -f "$c" ] && DEPCRUISE_CFG="$c" && break; done
DEPCRUISE_SRC="src"; [ -d "$DEPCRUISE_SRC" ] || DEPCRUISE_SRC="."
if [ -z "$DEPCRUISE_CFG" ]; then skip_na "dep-cruiser" "no .dependency-cruiser config"
elif ! command -v npx >/dev/null 2>&1; then skip_tool "dep-cruiser" "npx/node not installed"
else
  step dep-cruiser "dependency-cruiser ($DEPCRUISE_CFG)"
  npx --yes dependency-cruiser --config "$DEPCRUISE_CFG" --no-progress "$DEPCRUISE_SRC" || fail=1
fi

# 6. Sigma detection-rule validation — Detection-as-Code (STACK.md §14).
#    Validates Sigma rules (from adr-to-sigma-yara-sync) before they reach a SIEM.
#    Local equiv of the SigmaHQ/sigma-rules-validator GitHub Action; sigma-cli via uv.
SIGMA_DIR=""; for d in detections .sigma sigma rules/sigma; do [ -d "$d" ] && SIGMA_DIR="$d" && break; done
if [ -z "$SIGMA_DIR" ]; then skip_na "sigma" "no detections/ rules"
elif ! command -v sigma >/dev/null 2>&1; then skip_tool "sigma" "sigma-cli not installed"
else
  step sigma "sigma check ($SIGMA_DIR)"
  sigma check "$SIGMA_DIR" || fail=1
fi

# 7. Output redaction — ADR-0011: scrub agent-generated audit reports of card/PII/
#    credential/key material before they're pushed. Fail-closed backstop (the
#    write-time scrub happens in the /audit + /attack commands via redact.py).
#    Self-skips when no .audit/ reports exist or redact.py is absent.
REDACT="$(harness_script redact.py)"
# Invoke via `uv run`: bare `python3` here resolves to modern-python's PATH shim, which exits 1 on
# every invocation [measured 2026-08-29; the command and its output are in the SHEBANG note of
# ~/Apps/WORKFLOW/scripts/anchored-edit.py]. This cited STACK §20 until 2026-08-29; that section is
# the ruff/bandit stages and the word "shim" appears nowhere in STACK.md.
# Gate on uv, not python3, so the stage runs where uv is present.
_reports=""
[ -d .audit ] && _reports=$(find .audit -type f \( -name '*.md' -o -name '*.sarif' \) 2>/dev/null)
if [ ! -d .audit ]; then skip_na "redact" "no .audit/"
elif [ -z "$_reports" ]; then skip_na "redact" "no .audit reports"
elif [ -z "$REDACT" ]; then skip_tool "redact" "redact.py not found in ~/.claude/scripts/ or .githooks/"
elif ! command -v uv >/dev/null 2>&1; then skip_tool "redact" "uv not installed"
else
  step redact "redact --check (.audit reports, ADR-0011)"
  printf '%s\n' "$_reports" | tr '\n' '\0' | xargs -0 uv run --no-project "$REDACT" --check || fail=1
fi

# Applicability ratchet — ADR-0019 r9. Without this, the set of active controls is derived
# entirely from the tree under test, so deleting `.semgrep/` or relocating `.github/workflows`
# converts a stage from enforcing to passing and the gate still reports ✅ because, read
# literally, nothing applied. The baseline makes removing a control a reviewable diff.
# ($BASELINE is defined at the top — the trivy-register stage reads it too, and two
#  definitions of the same path are two things that can drift apart.)
# Absence is opt-out, and opt-out must not be reachable by deletion. Locally a missing baseline
# is fine — not every repo has adopted one. Under STRICT (i.e. CI) it is UNCHECKED: the run's
# whole purpose is to demonstrate coverage, and an undeclared coverage set cannot be checked
# against anything. Without this, `rm .githooks/gate-baseline` removes the ratchet in one
# unreviewed line and every later run looks identical to the ones before it.
# (Adversarial self-audit, 2026-08-10.)
DECLARED=":"; DECL_REQUIRED=":"; DECL_EXEMPT=":"
decl_ok=1; _n_req=0; _n_exempt=0
if [ ! -f "$BASELINE" ]; then
  if [ "$STRICT" = "1" ]; then
    skip_tool "gate-baseline" "no $BASELINE — coverage is undeclared, so it cannot be verified"
  fi
else
  while IFS= read -r line || [ -n "$line" ]; do
    # Trim first: a whitespace-only line is not the empty string, so an untrimmed `case` would
    # send it to the unrecognised-line arm and fail the gate on a blank line. Trimming also
    # lets a declaration be indented for readability.
    line="$(printf '%s' "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    case "$line" in ''|'#'*) continue ;; esac
    _kw=""; _rest=""
    case "$line" in
      required:*) _kw="required"; _rest="${line#required:}" ;;
      exempt:*)   _kw="exempt";   _rest="${line#exempt:}" ;;
      waive:*)
        echo "❌ $BASELINE: 'waive:' is no longer supported — waivers were removed (ADR-0019)."
        echo "   Declare the stage 'required:' and fix the tool, or 'exempt: <reason>' if it"
        echo "   genuinely does not apply here. An exemption is verified, not asserted."
        decl_ok=0; fail=1; continue
        ;;
      *)
        echo "❌ $BASELINE: unrecognised line '$line'"
        echo "   Expected 'required: <stage>' or 'exempt: <stage> <reason>'."
        decl_ok=0; fail=1; continue
        ;;
    esac
    _sid="$(printf '%s' "$_rest" | awk '{print $1}')"
    _reason="$(printf '%s' "$_rest" | awk '{$1=""; sub(/^ /,""); print}')"
    if [ -z "$_sid" ]; then
      echo "❌ $BASELINE: '$_kw:' line names no stage"; decl_ok=0; fail=1; continue
    fi
    case " $GATE_STAGES " in
      *" $_sid "*) : ;;
      *) echo "❌ $BASELINE: '$_sid' is not a stage this gate defines"; decl_ok=0; fail=1; continue ;;
    esac
    if in_set "$_sid" "$DECLARED"; then
      echo "❌ $BASELINE: '$_sid' is declared more than once"; decl_ok=0; fail=1; continue
    fi
    DECLARED="${DECLARED}${_sid}:"
    if [ "$_kw" = "exempt" ] && [ -z "$_reason" ]; then
      echo "❌ $BASELINE: 'exempt: $_sid' needs a reason — an exemption without one is an"
      echo "   assertion with no content."
      decl_ok=0; fail=1; continue
    fi
    if [ "$_kw" = "required" ]; then DECL_REQUIRED="${DECL_REQUIRED}${_sid}:"; _n_req=$((_n_req+1))
    else DECL_EXEMPT="${DECL_EXEMPT}${_sid}:"; _n_exempt=$((_n_exempt+1)); fi
  done < "$BASELINE"
fi

# Verify the declaration against what actually happened. Three checks, because the declaration,
# the stage list, and the run can each drift from the others.
if [ -f "$BASELINE" ] && [ "$decl_ok" -eq 1 ]; then
  # 1. Declaration completeness — every known stage accounted for. Absence is not a state:
  #    this is what makes deleting a line a visible error rather than a quiet removal.
  for _s in $GATE_STAGES; do
    if ! in_set "$_s" "$DECLARED"; then
      echo "❌ [$_s] is not declared in $BASELINE — add 'required: $_s' or"
      echo "   'exempt: $_s <reason>'. Every stage must be accounted for."
      fail=1
    fi
  done
  # 2. List completeness — a stage that reported but is not in GATE_STAGES would be silently
  #    exempt from ever needing declaration. The list is a control, so it gets a drift check.
  for _s in $(printf '%s' "${STAGES_RAN}${STAGES_NA}${STAGES_UNCHECKED}" | tr ':' ' '); do
    [ -z "$_s" ] && continue
    [ "$_s" = "branch-guard" ] && continue   # excluded by design: context-dependent membership
    case " $GATE_STAGES " in
      *" $_s "*) : ;;
      *) echo "❌ [$_s] reported a result but is not in GATE_STAGES — the stage list has drifted"; fail=1 ;;
    esac
  done
  # 3. State verification — an exemption is checked against reality, not trusted.
  #
  #    Assert POSITIVELY what each state requires, rather than ruling out the wrong outcomes.
  #    "not n/a and not UNCHECKED" is not the same as "ran": a stage that reports nothing at all
  #    satisfies both negatives and passes — the exact silent-pass class this ADR exists to
  #    close, reintroduced inside its own enforcement.
  #
  #    `required` + UNCHECKED is deliberately NOT re-failed here. skip_tool already owns that
  #    case and applies the STRICT rule to it: a warning on a workstation, a failure in CI.
  #    Failing it again here would ignore STRICT and hard-fail a laptop missing a tool.
  for _s in $GATE_STAGES; do
    if in_set "$_s" "$DECL_REQUIRED"; then
      if in_set "$_s" "$STAGES_NA"; then
        echo "❌ [$_s] declared required but reported n/a — a control was removed from this repo,"
        echo "   or the declaration is stale. Both are decisions; neither is silent."
        fail=1
      elif ! in_set "$_s" "$STAGES_RAN" && ! in_set "$_s" "$STAGES_UNCHECKED"; then
        echo "❌ [$_s] declared required but reported nothing at all — the stage has no reporting"
        echo "   path on this code route, so its result cannot be verified."
        fail=1
      fi
    elif in_set "$_s" "$DECL_EXEMPT"; then
      if ! in_set "$_s" "$STAGES_NA"; then
        if in_set "$_s" "$STAGES_UNCHECKED"; then
          # Two causes reach UNCHECKED, and naming only the first sent operators to install a
          # tool that was already present (found dogfooding the ungoverned-ignorefile fix,
          # 2026-08-23). The stage's own line above says which; this one must not overwrite it.
          echo "❌ [$_s] declared exempt but reported UNCHECKED — it DOES apply here and could not"
          echo "   be checked. The stage's own line above says why: either its tool is missing, or"
          echo "   a condition arose that makes the exemption untrue. Fix that, or correct the"
          echo "   declaration — an exemption is verified against an n/a result, and there is none."
        elif in_set "$_s" "$STAGES_RAN"; then
          echo "❌ [$_s] declared exempt but actually ran — this repo gained what the exemption"
          echo "   said it lacked. Change it to 'required: $_s'."
        else
          echo "❌ [$_s] declared exempt but reported nothing at all — an exemption is verified"
          echo "   against an n/a result, and there is none to verify against."
        fi
        fail=1
      fi
    fi
  done
  # Report positively. These three checks are silent on success, so without this a green run is
  # indistinguishable from one where the declaration was never read — the same silent-pass class
  # this ADR exists to close, in the code that closes it. ADR-0019 r6: report coverage, not just
  # a verdict. (Found by reading a green CI log for evidence and finding none, 2026-08-11.)
  if [ "$fail" -eq 0 ]; then
    _n_stages=0; for _s in $GATE_STAGES; do _n_stages=$((_n_stages+1)); done
    echo "✓ declaration verified: ${_n_req} required + ${_n_exempt} exempt = ${_n_stages} stages, states match this run"
  fi
fi

# The branch guard asserts its own execution. It is deliberately outside GATE_STAGES, the three
# ledgers and `ran`: counting it would mask the "ran 0 stages — nothing was verified" warning
# (ADR-0019 r6) behind a guard that verified only a push destination. The price of that choice
# is that every completeness check above is structurally blind to the guard — deleting it would
# leave the declaration verifying and the stage count unchanged. This is its denominator.
#
# UNCONDITIONAL by design. Placing it inside the `[ -f "$BASELINE" ]` block above would make the
# anti-deletion check itself removable by deleting a file — opt-out reachable by deletion, in
# the check whose entire purpose is making deletion visible.
# The assertion is POSITIVE: it re-derives each outcome's precondition independently rather than
# accepting that *some* outcome was recorded. "Not empty" is not "earned".
#
# Measured, because the weaker version shipped first and this is what got past it: changing
# `elif [ -t 0 ]` to `elif true` leaves the guard present, printing, and recording a plausible
# "n/a — not a push" on every push, while a push to a protected ref sails through and the gate
# prints a pass. One token. The old check saw a non-empty outcome and was satisfied.
#
# `${VAR:-}` throughout: deleting the whole stage leaves these unset, and under `set -u` a bare
# reference aborts with "unbound variable" — fail-closed, but the reason never prints.
_bg_ok=1
case "${BRANCH_GUARD_OUTCOME:-}" in
  "")
    echo "❌ branch-guard recorded no outcome — the control did not run on this code route."
    echo "   Every path through stage 0 must set BRANCH_GUARD_OUTCOME. If you removed that"
    echo "   stage, this repo has no direct-push control at all."
    _bg_ok=0 ;;
  na-ci)
    if [ "${CI:-}" != "true" ]; then
      echo "❌ branch-guard claimed 'CI runner', but CI is not set. A push from a workstation"
      echo "   was declined as if it were a runner, and its destination was never examined."
      _bg_ok=0
    fi ;;
  na-not-a-push)
    if [ "${CI:-}" = "true" ] || [ ! -t 0 ]; then
      echo "❌ branch-guard claimed 'not a push', but stdin is not a terminal. Something was"
      echo "   being pushed and the guard declined to look at where it was going."
      _bg_ok=0
    fi ;;
  na-nothing-to-push)
    if [ -z "${GATE_PUSH_REFS:-}" ] || [ ! -f "${GATE_PUSH_REFS:-}" ] || [ -s "${GATE_PUSH_REFS:-}" ]; then
      echo "❌ branch-guard claimed 'nothing to push', but the capture is missing or non-empty."
      _bg_ok=0
    fi ;;
  checked|blocked|overridden)
    if [ -z "${GATE_PUSH_REFS:-}" ] || [ ! -s "${GATE_PUSH_REFS:-}" ]; then
      echo "❌ branch-guard reported a verdict with no refspecs to judge it against."
      _bg_ok=0
    fi ;;
  could-not-capture)
    : ;;   # already failed the gate at the point it happened
  *)
    echo "❌ branch-guard recorded an unrecognised outcome '${BRANCH_GUARD_OUTCOME:-}'."
    _bg_ok=0 ;;
esac
if [ "$_bg_ok" -eq 0 ]; then
  echo "   branch-guard sits outside GATE_STAGES and the ledgers by design (counting it would"
  echo "   mask the zero-stage warning), so this is the only check that can see it fail."
  fail=1
fi

# The same treatment for checker B, and for the same reason: it is outside GATE_STAGES, so the
# declaration ratchet cannot see it. Without this, deleting stage 0b leaves a gate that still
# prints a pass line and has silently stopped attributing sensitive-path changes. Preconditions
# are re-derived rather than trusting that *some* outcome was recorded.
_srt_ok=1
case "${SRT_OUTCOME:-}" in
  "")
    echo "❌ security-review-trailer recorded no outcome — the control did not run on this route."
    _srt_ok=0 ;;
  na-ci)
    if [ "${CI:-}" != "true" ]; then
      echo "❌ security-review-trailer claimed 'CI runner', but CI is not set — a workstation"
      echo "   push was declined as if it were a runner, and nothing was attributed."
      _srt_ok=0
    fi ;;
  na-not-a-push)
    if [ -n "${GATE_PUSH_REFS:-}" ] && [ -s "${GATE_PUSH_REFS:-}" ]; then
      echo "❌ security-review-trailer claimed 'not a push', but refspecs were captured."
      _srt_ok=0
    fi ;;
  checked|blocked|unchecked|na)
    if [ -z "${GATE_PUSH_REFS:-}" ] || [ ! -s "${GATE_PUSH_REFS:-}" ]; then
      echo "❌ security-review-trailer reported a verdict with no refspecs to judge it against."
      _srt_ok=0
    fi ;;
  unchecked-missing|unchecked-nouv|unexpected)
    : ;;   # already failed the gate at the point it happened
  *)
    echo "❌ security-review-trailer recorded an unrecognised outcome '${SRT_OUTCOME:-}'."
    _srt_ok=0 ;;
esac
if [ "$_srt_ok" -eq 0 ]; then
  echo "   security-review-trailer sits outside GATE_STAGES by design (ADR-0017 §6 is a"
  echo "   push-time control), so this is the only check that can see it fail."
  fail=1
fi

echo
if [ "$fail" -eq 0 ]; then
  # Say how much was actually verified. A pass with zero stages run is not a pass in any
  # meaningful sense, and saying so plainly is cheaper than discovering it from a CI log.
  if [ "$ran" -eq 0 ]; then
    echo "⚠️  lint-gate passed but ran 0 stages — nothing was verified."
    [ "$STRICT" != "1" ] && echo "   Re-run with STRICT=1 to fail on unavailable checks."
  else
    echo "✅ lint-gate passed (${ran} stage(s) ran)"
  fi
else
  echo "❌ lint-gate failed — push aborted"
fi
exit "$fail"
