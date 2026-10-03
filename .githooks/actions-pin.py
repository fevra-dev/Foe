#!/usr/bin/env python3
# harness-version: 177d0413e15c
# harness-base: 177d0413e15c
"""actions-pin.py — enforce ADR-0018 on GitHub Actions definitions.

Scans `.github/workflows/*.{yml,yaml}` and `.github/actions/**/action.yml`.

  PIN001  `uses:` reference not pinned to a full 40-hex commit SHA
  PIN002  workflow has no TOP-LEVEL `permissions:` block (column 0)
  PIN003  `permissions: write-all`
  PIN004  `pull_request_target` trigger without an explicit waiver comment
  PIN006  a job or step whose failure is swallowed (`continue-on-error: true`, `|| true`)

PIN002/3/4 apply to workflows only; PIN001 applies to both workflows and
composite actions (a local `uses: ./...` is exempt from being pinned itself,
but its contents are not exempt from pinning what they call).

Modes:
  --check          exit 1 on any violation (default; offline)
  --json           machine-readable findings on stdout
  --fix            rewrite unpinned refs to resolved SHAs (requires `gh`, network)
  --verify         re-resolve each `# tag` comment and confirm it matches the
                   pinned SHA (requires `gh`, network). Catches a lying comment
                   and a post-hoc retag. Deliberately NOT part of the pre-push
                   gate, which must work offline.

Line-based by design: the stdlib has no YAML parser, and adding one would need
an ADR-0001 justification for a check this shallow. The parser tracks block
scalars (`run: |`) so a `uses:` string inside a shell heredoc is not a false
positive — unfixable FPs on a pre-push gate are what train `--no-verify`.

stdlib only (ADR-0001).
"""
import argparse
import json
import os
import re
import subprocess
import sys

USES_RE = re.compile(
    r'^(?P<prefix>\s*(?:-\s+)?uses:\s*)(?P<quote>["\']?)(?P<ref>[^"\'\s#]+)(?P=quote)(?P<rest>.*)$'
)
BLOCK_RE = re.compile(r'^(?P<indent>\s*)(?:-\s+)?[A-Za-z_-]+:\s*[|>][-+0-9]*\s*$')
TOP_PERMS_RE = re.compile(r'^permissions:')
WRITE_ALL_RE = re.compile(r'^\s*permissions:\s*write-all\s*$')
PR_TARGET_RE = re.compile(r'^\s*pull_request_target\s*:')
WAIVER = "adr-0018: allow-pull-request-target"
SHA_RE = re.compile(r'^[0-9a-f]{40}$')

# PIN006 — a job or step whose failure is swallowed. `continue-on-error: true` and a trailing
# `|| true` both turn a red check permanently green while the log still fills with plausible
# output. This is the same one-token shape that defeated the branch guard's first self-assertion
# (`elif [ -t 0 ]` → `elif true`), relocated into YAML, and it is most dangerous on exactly the
# jobs worth having: a security gate whose failures no longer fail.
#
# Waivable, because there are legitimate uses (an advisory matrix leg, a flaky third-party
# upload). The waiver is the review, same as PIN004.
CONTINUE_ON_ERROR_RE = re.compile(r'^\s*continue-on-error\s*:\s*true\s*(#.*)?$')
SWALLOWED_RUN_RE = re.compile(r'\|\|\s*(true|:)\s*(#.*)?$')
CI_WAIVER = "adr-0018: allow-swallowed-failure"
# PIN007 — a pull_request trigger whose base-branch allowlist silently excludes stacked PRs.
PR_TRIGGER_RE = re.compile(r"^(\s*)pull_request:\s*(#.*)?$")
PR_BRANCHES_RE = re.compile(r"^\s*branches:")
PR_WAIVER = "adr-0019: allow-pull-request-base-filter"
TAG_COMMENT_RE = re.compile(r'#\s*(?P<tag>[A-Za-z0-9][\w.\-/+]*)')


def target_files(root):
    """Workflow files and composite-action definitions, as (path, kind) pairs."""
    files = []
    wf_dir = os.path.join(root, ".github", "workflows")
    if os.path.isdir(wf_dir):
        for name in sorted(os.listdir(wf_dir)):
            if name.endswith((".yml", ".yaml")):
                files.append((os.path.join(wf_dir, name), "workflow"))
    # Composite actions live anywhere, not only under .github/actions/. A local action is
    # referenced by PATH (`uses: ./tools/deploy`), so the path is the author's choice — and
    # scanning only the conventional directory left every other location unscanned while
    # ADR-0018 rule 5 claimed composite actions were in scope. An `action.yml` at
    # `tools/deploy/` calling `evil/exfil@main` passed the checker clean.
    # (Adversarial self-audit, 2026-08-10.)
    seen = set()
    skip_dirs = {".git", "node_modules", "vendor", ".venv", "venv", "dist", "build", "__pycache__"}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in skip_dirs]
        for name in sorted(filenames):
            if name in ("action.yml", "action.yaml"):
                path = os.path.join(dirpath, name)
                if path not in seen:
                    seen.add(path)
                    files.append((path, "action"))
    return files


def split_ref(ref):
    """'github/codeql-action/init@v3' -> ('github/codeql-action', 'init', 'v3')."""
    if "@" not in ref:
        return None, None, None
    path, _, version = ref.rpartition("@")
    parts = path.split("/")
    if len(parts) < 2:
        return None, None, None
    return "/".join(parts[:2]), "/".join(parts[2:]), version


def code_lines(lines):
    """Yield (index, line) for lines that are YAML structure, skipping the
    contents of block scalars such as `run: |`."""
    block_indent = None
    for idx, line in enumerate(lines):
        if block_indent is not None:
            if not line.strip():
                continue
            if len(line) - len(line.lstrip()) > block_indent:
                continue
            block_indent = None
        m = BLOCK_RE.match(line)
        if m:
            block_indent = len(m.group("indent"))
            continue
        yield idx, line


def scan(path, kind):
    """Return (violations, lines) for one file."""
    violations = []
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    code = list(code_lines(lines))

    if kind == "workflow":
        if not any(TOP_PERMS_RE.match(line) for _, line in code):
            violations.append({
                "rule": "PIN002", "file": path, "line": 1, "ref": None,
                "message": "no top-level `permissions:` block — jobs without their own "
                           "block run at the repository default token scope",
            })
        for idx, line in code:
            if WRITE_ALL_RE.match(line):
                violations.append({
                    "rule": "PIN003", "file": path, "line": idx + 1, "ref": None,
                    "message": "`permissions: write-all` — start from `contents: read` "
                               "and widen per job",
                })
            if PR_TARGET_RE.match(line):
                prev = lines[idx - 1] if idx else ""
                if WAIVER not in line and WAIVER not in prev:
                    violations.append({
                        "rule": "PIN004", "file": path, "line": idx + 1, "ref": None,
                        "message": "`pull_request_target` runs with the base repo's secrets "
                                   f"against PR-authored code — add `# {WAIVER} — <reason>` "
                                   "if this is reviewed and intended",
                    })
            if CONTINUE_ON_ERROR_RE.match(line):
                prev = lines[idx - 1] if idx else ""
                if CI_WAIVER not in lines[idx] and CI_WAIVER not in prev:
                    violations.append({
                        "rule": "PIN006", "file": path, "line": idx + 1, "ref": None,
                        "message": "`continue-on-error: true` — this job cannot fail, so the "
                                   f"check it renders is decorative. Add `# {CI_WAIVER} — "
                                   "<reason>` if the result is genuinely advisory",
                    })

        # PIN006, swallowed shell failure. Scanned over RAW lines, deliberately NOT `code`:
        # code_lines() skips block scalars, and `run: |` is where shell actually lives. Written
        # against `code` first, this rule caught `run: pytest || true` on one line and missed
        # `pytest || true` inside a run block — the common form. A rule that covers the rare
        # shape and not the ordinary one reads as coverage and is not.
        for idx, raw in enumerate(lines):
            stripped = raw.lstrip()
            if stripped.startswith("#") or not SWALLOWED_RUN_RE.search(raw):
                continue
            prev = lines[idx - 1] if idx else ""
            if CI_WAIVER in raw or CI_WAIVER in prev:
                continue
            violations.append({
                "rule": "PIN006", "file": path, "line": idx + 1, "ref": None,
                "message": "trailing `|| true` swallows this command's failure — the step "
                           f"reports success whatever happens. Add `# {CI_WAIVER} — <reason>` "
                           "if that is intended",
            })

        # PIN007, a pull_request trigger filtered by BASE branch.
        #
        # THE BUG THIS EXISTS FOR, measured 2026-09-05. `pull_request: branches: [main]`
        # filters on the base, so a stacked PR (base = another feature branch) matches
        # nothing and runs NO workflow at all. `gh pr view` then reports
        # mergeable=MERGEABLE, state=CLEAN with an EMPTY check list, which reads as
        # verified and means nothing was checked. PR #92 was merged on that signal.
        # ADR-0019 verbatim, one layer up: a check that could not run must never
        # report success.
        #
        # `branches:` only, NOT `branches-ignore:`. An allowlist excludes every base
        # nobody thought to list -- that is the hole. A denylist excludes only what it
        # names, so `branches-ignore: [gh-pages]` is a narrow, deliberate exclusion and
        # is left alone. Flagging both would make the rule cry wolf on the safe form.
        pr_indent = None
        for idx, raw in enumerate(lines):
            stripped = raw.strip()
            if stripped.startswith("#"):
                continue
            m = PR_TRIGGER_RE.match(raw)
            if m:
                pr_indent = len(m.group(1))
                continue
            if pr_indent is None:
                continue
            if not stripped:
                continue
            indent = len(raw) - len(raw.lstrip())
            if indent <= pr_indent:
                pr_indent = None          # left the pull_request block
                continue
            if PR_BRANCHES_RE.match(raw):
                prev = lines[idx - 1] if idx else ""
                if PR_WAIVER in raw or PR_WAIVER in prev:
                    pr_indent = None
                    continue
                violations.append({
                    "rule": "PIN007", "file": path, "line": idx + 1, "ref": None,
                    "message": "`pull_request` is filtered by BASE branch, so a PR based on "
                               "anything else runs no CI and still reports as clean. Remove the "
                               f"`branches:` filter, or add `# {PR_WAIVER} — <reason>`",
                })
                pr_indent = None

    for idx, line in code:
        m = USES_RE.match(line)
        if not m:
            continue
        ref = m.group("ref")
        if ref.startswith("./") or ref.startswith("docker://"):
            continue
        _, _, version = split_ref(ref)
        if version is None:
            violations.append({
                "rule": "PIN001", "file": path, "line": idx + 1, "ref": ref,
                "message": "action reference has no version at all",
            })
        elif not SHA_RE.match(version):
            violations.append({
                "rule": "PIN001", "file": path, "line": idx + 1, "ref": ref,
                "message": f"{ref} — `{version}` is a mutable ref, pin to a 40-hex commit SHA",
            })
    return violations, lines


def resolve_sha(repo, version):
    """Resolve a tag or branch to a commit SHA via the authenticated gh API."""
    for endpoint in (f"repos/{repo}/git/ref/tags/{version}",
                     f"repos/{repo}/git/ref/heads/{version}"):
        proc = subprocess.run(
            ["gh", "api", endpoint, "--jq", '.object.sha + " " + .object.type'],
            capture_output=True, text=True)
        if proc.returncode != 0:
            continue
        sha, _, obj_type = proc.stdout.strip().partition(" ")
        if obj_type == "tag":
            # Annotated tag: dereference, or the pin lands on the tag object.
            deref = subprocess.run(
                ["gh", "api", f"repos/{repo}/git/tags/{sha}", "--jq", ".object.sha"],
                capture_output=True, text=True)
            if deref.returncode != 0:
                return None
            sha = deref.stdout.strip()
        return sha if SHA_RE.match(sha) else None
    return None


def fix_file(path, violations, lines):
    """Rewrite PIN001 lines in place. Returns the number of refs pinned."""
    pinned = 0
    for v in (x for x in violations if x["rule"] == "PIN001"):
        idx = v["line"] - 1
        m = USES_RE.match(lines[idx])
        if not m:
            continue
        repo, subpath, version = split_ref(m.group("ref"))
        if repo is None:
            print(f"  ! {os.path.basename(path)}:{v['line']} unparsable ref — left as-is",
                  file=sys.stderr)
            continue
        sha = resolve_sha(repo, version)
        if sha is None:
            print(f"  ! could not resolve {repo}@{version} — left as-is", file=sys.stderr)
            continue
        new_ref = f"{repo}/{subpath}@{sha}" if subpath else f"{repo}@{sha}"
        lines[idx] = f"{m.group('prefix')}{new_ref}  # {version}"
        pinned += 1
    if pinned:
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
    return pinned


def verify_file(path):
    """Re-resolve each `# tag` comment and confirm it matches the pinned SHA."""
    mismatches = []
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    for idx, line in code_lines(lines):
        m = USES_RE.match(line)
        if not m:
            continue
        ref = m.group("ref")
        if ref.startswith("./") or ref.startswith("docker://"):
            continue
        repo, _, version = split_ref(ref)
        if repo is None or not SHA_RE.match(version or ""):
            continue
        tag_m = TAG_COMMENT_RE.search(m.group("rest"))
        if not tag_m:
            continue
        tag = tag_m.group("tag")
        actual = resolve_sha(repo, tag)
        if actual is None:
            print(f"  ? {os.path.basename(path)}:{idx + 1} {repo}@{tag} unresolvable",
                  file=sys.stderr)
            continue
        if actual != version:
            mismatches.append({
                "rule": "PIN005", "file": path, "line": idx + 1, "ref": ref,
                "message": f"comment claims `{tag}` but that tag resolves to {actual[:12]}… "
                           f"while the pin is {version[:12]}… — lying comment or a retag",
            })
    return mismatches


def main():
    ap = argparse.ArgumentParser(description="Enforce ADR-0018 on GitHub Actions definitions")
    ap.add_argument("root", nargs="?", default=".", help="repo root (default: cwd)")
    ap.add_argument("--check", action="store_true", help="exit 1 on violations (default)")
    ap.add_argument("--fix", action="store_true", help="rewrite unpinned refs to resolved SHAs")
    ap.add_argument("--verify", action="store_true",
                    help="re-resolve tag comments against pinned SHAs (network)")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args()

    files = target_files(args.root)
    if not files:
        if args.json:
            print(json.dumps({"violations": [], "files_scanned": 0}))
        return 0

    all_violations = []
    for path, kind in files:
        violations, lines = scan(path, kind)
        if args.fix and violations:
            n = fix_file(path, violations, lines)
            if n:
                print(f"  + pinned {n} ref(s) in {os.path.relpath(path, args.root)}")
            violations, _ = scan(path, kind)
        if args.verify:
            violations = violations + verify_file(path)
        all_violations.extend(violations)

    if args.json:
        print(json.dumps({"violations": all_violations, "files_scanned": len(files)}, indent=2))
    else:
        for v in all_violations:
            rel = os.path.relpath(v["file"], args.root)
            print(f"{rel}:{v['line']}: {v['rule']} {v['message']}")
        if all_violations:
            print(f"\n{len(all_violations)} ADR-0018 violation(s). "
                  f"Pin refs with: actions-pin.py --fix {args.root}")

    return 1 if all_violations else 0


if __name__ == "__main__":
    sys.exit(main())
