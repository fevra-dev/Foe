#!/usr/bin/env python3
# harness-version: 1cf8009b2951
# harness-base: 1cf8009b2951
# redact.py — write-boundary redaction for agent-generated audit reports.
#
# Masks card/PAN, PII, and credential material so secrets the model quoted from
# source never land in .audit/FINDINGS.md / AUDIT.md (or any report the harness
# writes). Enforces ADR-0011 (output redaction at write boundary) + ADR-0004.
#
# Two modes:
#   filter : redact.py [FILE ...]            → masked text to stdout (stdin if no FILE)
#            redact.py --in-place FILE ...    → rewrite each FILE masked, in place
#   gate   : redact.py --check FILE ...       → exit 1 if any unredacted secret is
#                                               present (modifies nothing); prints a
#                                               per-label tally to stderr. For lint-gate.
#
# Pure stdlib (re). No deps, no network, no telemetry.
#
# ── Provenance ──────────────────────────────────────────────────────────────
# The validators and pattern table are adapted, near-verbatim, from Visa's
# open-source visa-vulnerability-agentic-harness (vvaharness/report/redact.py),
# Copyright 2026 Visa, Inc., licensed under the Apache License, Version 2.0.
# Adaptation (CLI + harness wiring) 2026-06-12. The Apache-2.0 NOTICE is retained
# for the derived portion; see http://www.apache.org/licenses/LICENSE-2.0.
# High precision by design: PANs are Luhn + IIN gated, SSNs area/group/serial
# gated, generic secrets keyword-gated — so ordinary numbers/prose aren't mangled.
"""Sensitive-data redaction for emitted reports (Markdown / SARIF / any text)."""
from __future__ import annotations
import json
import re
import sys
from typing import Callable


# ─────────────────────────────────────────────────────────────────────────────
# Validators
# ─────────────────────────────────────────────────────────────────────────────

def _luhn(digits: str) -> bool:
    total, odd = 0, True
    for ch in reversed(digits):
        n = int(ch)
        if not odd:
            n *= 2
            if n > 9:
                n -= 9
        total += n
        odd = not odd
    return total % 10 == 0


def _cc_network(digits: str) -> bool:
    """IIN/BIN gate so random Luhn-passing 16-digit ids aren't masked."""
    n = len(digits)
    if n < 12 or n > 19:
        return False
    p1, p2 = digits[0], int(digits[:2])
    p3 = int(digits[:3]) if n >= 3 else -1
    p4 = int(digits[:4]) if n >= 4 else -1
    if n == 15 and p2 in (34, 37):                       # Amex
        return True
    if p1 == "4" and 13 <= n <= 19:                      # Visa
        return True
    if n == 16 and (51 <= p2 <= 55 or 2221 <= p4 <= 2720):  # Mastercard
        return True
    if 16 <= n <= 19 and (p4 == 6011 or p2 == 65 or 644 <= p3 <= 649):  # Discover
        return True
    if 16 <= n <= 19 and 3528 <= p4 <= 3589:             # JCB
        return True
    if 16 <= n <= 19 and p2 == 62:                       # UnionPay
        return True
    if 14 <= n <= 19 and p2 == 36:                       # Diners
        return True
    if 12 <= n <= 19 and p4 in (5018, 5020, 5038, 5893,
                                6304, 6759, 6761, 6762, 6763):  # Maestro
        return True
    if n == 16 and (p3 == 508 or p2 in (81, 82)):        # RuPay
        return True
    return False


def _ssn_valid(d: str) -> bool:
    a, g, s = int(d[:3]), int(d[3:5]), int(d[5:9])
    # Reject only structurally-impossible groupings (area 000/666, group 00,
    # serial 0000). Area 900-999 is intentionally ALLOWED: it is the ITIN range
    # (individual taxpayer IDs) — sensitive PII, so mask it rather than let it egress.
    return not (a == 0 or a == 666 or g == 0 or s == 0)


def _bearer_credential(m: re.Match) -> bool:
    """True only when the post-scheme value looks like a real token.

    Without this guard the BEARER rule fires on prose like
    ``HTTP Basic Authentication`` (the value group captures ``Authentication``).
    A genuine bearer/basic credential is base64 or otherwise carries at least one
    non-alphabetic character, so require one. Known false-negative: an
    all-alphabetic 8+ char credential is left intact — acceptable vs mangling prose.
    """
    return any(not c.isalpha() for c in m.group("bv"))


_SECRET_CODE_SHAPE = re.compile(
    r"^\("                 # leading paren / cast:   (sasl_secret_t
    r"|^[A-Za-z_]\w*\("    # function call:          parse(
    r"|^[A-Za-z_]\w*\.\w"  # member access:          obj.field / this.field
)


# ─────────────────────────────────────────────────────────────────────────────
# Patterns  (label, compiled-regex, optional validator(match)->bool)
# ─────────────────────────────────────────────────────────────────────────────

_b64u = r"[A-Za-z0-9_-]"

_PATTERNS: list[tuple[str, re.Pattern, Callable[[re.Match], bool] | None]] = [
    # ── Card / PAN ───────────────────────────────────────────────────────
    ("PAN",
     re.compile(r"(?<![0-9A-Za-z./_-])"
                # \s (Unicode in a str pattern) also matches NBSP/thin/figure
                # space, so a PAN split by those separators isn't bypassed.
                r"(?:\d[\s\-]?){12,18}\d"
                r"(?![0-9A-Za-z./_-])"),
     lambda m: (lambda d: _cc_network(d) and _luhn(d))(re.sub(r"\D", "", m.group(0)))),
    ("CVV",
     re.compile(r"(?i)\b(cvv2?|cvc2?|cid|csc)\b\s*[:=]?\s*\"?(\d{3,4})\"?"),
     None),
    ("TRACK",
     re.compile(r"%B\d{12,19}\^[^?]{2,90}\?"),
     None),

    # ── PII ──────────────────────────────────────────────────────────────
    ("SSN",
     # Separator class includes Unicode spaces (NBSP / thin / narrow-no-break /
     # figure) so an SSN/ITIN split by those isn't bypassed. \n and \r are
     # deliberately excluded to avoid matching three numbers across table rows.
     re.compile(r"(?<!\d)(\d{3})[-.\t \u00a0\u2009\u202f\u2007]"
                r"(\d{2})[-.\t \u00a0\u2009\u202f\u2007](\d{4})(?!\d)"),
     lambda m: _ssn_valid(m.group(1) + m.group(2) + m.group(3))),
    ("SSN-CTX",
     re.compile(
         r"(?i)\b(ssn|social[\s_-]*sec(?:urity)?(?:[\s_-]*(?:no|num|number))?"
         r"|itin|tin|taxpayer[\s_-]*id)\b['\"]?\s*[:=#-]?\s*['\"]?"
         r"(?<!\d)(\d{9})(?!\d)"),
     lambda m: _ssn_valid(m.group(2))),

    # ── Cloud / SaaS credentials ────────────────────────────────────────
    ("AWS-KEY",
     re.compile(r"\b(?:AKIA|ASIA|AGPA|AIDA|AROA|AIPA|ANPA|ANVA)[0-9A-Z]{16}\b"),
     None),
    ("GITHUB-TOKEN",
     re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{36,255}"
                r"|github_pat_[A-Za-z0-9_]{22}_[A-Za-z0-9]{59})\b"),
     None),
    ("SLACK-TOKEN",
     re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,72}\b"),
     None),
    ("STRIPE-KEY",
     re.compile(r"\b(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{24,99}\b"),
     None),
    ("GOOGLE-API-KEY",
     re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
     None),
    ("AZURE-SAS",
     re.compile(r"(?i)\bsig=[0-9A-Za-z%+/=]{20,}\b"),
     None),
    ("TWILIO-KEY",
     re.compile(r"\bSK[0-9a-fA-F]{32}\b"),
     None),
    # ── Crypto-wallet key material (ADR-0011 grill V1) ──────────────────
    # BIP-32 extended PRIVATE key: unambiguous xprv/tprv prefix → zero-FP, safe
    # to mask standalone. Bare base58 Solana secret keys and Bitcoin WIF are
    # DELIBERATELY NOT matched standalone: a 64-byte Solana *signature* and a
    # secret key are both ~88-char base58, so a standalone rule would mask
    # legitimate signatures/addresses and mangle audit evidence (grill V4).
    # Announced crypto keys (`secret_key =`, `private_key:`, `keypair =`) are
    # caught by the keyword-gated SECRET rule below; bare seed phrases / byte
    # arrays are a documented residual handled by ADR-0004 (don't read them in).
    ("XPRV",
     re.compile(r"\b(?:xprv|tprv)[1-9A-HJ-NP-Za-km-z]{107,108}\b"),
     None),

    # ── Bearer / Basic / JWT ────────────────────────────────────────────
    ("JWT",
     re.compile(r"\beyJ" + _b64u + r"{10,}\." + _b64u + r"{10,}\." + _b64u + r"{10,}\b"),
     None),
    ("BEARER",
     re.compile(r"(?i)\b(?:Bearer|Basic)\s+(?P<bv>[A-Za-z0-9+/=._-]{8,})\b"),
     _bearer_credential),

    # ── URL userinfo credential (scheme://user:secret@host) ─────────────
    # Masks ONLY the password component; scheme + username are preserved.
    ("URL-CRED",
     re.compile(r"(?i)\b([a-z][a-z0-9+.\-]*://[^\s:/@]+:)([^\s/@]{1,256})@"),
     None),

    # ── Private-key material ────────────────────────────────────────────
    # Unbounded lazy body between two fixed literal anchors — linear-safe.
    ("PRIVATE-KEY",
     re.compile(r"-{5}BEGIN [A-Z ]*PRIVATE KEY-{5}[\s\S]*?-{5}END [A-Z ]*PRIVATE KEY-{5}"),
     None),

    # ── Keyword-gated generic secret assignment ─────────────────────────
    ("SECRET",
     re.compile(
         # Leading anchor also accepts a camelCase boundary so `superSecret`,
         # `myApiKey`, `dbPassword` are recognised, not just `\b`-delimited names.
         # Order matters: longer crypto-specific forms (secret_key, private_key)
         # precede bare `secret` so `secret_key = …` matches the full keyword
         # (grill V1 — `secret_key`/`private_key` were missed; only api_key/
         # access_key were gated).
         r"(?i)(?:\b|(?<=[a-z]))(pass(?:word|wd)?|pwd|secret[_-]?key|private[_-]?key"
         r"|signing[_-]?key|secret|api[_-]?key|access[_-]?key|client[_-]?secret"
         r"|auth[_-]?token|seed[_-]?phrase|mnemonic|keypair|token|credential)s?\b"
         r"['\"`]?\s*[:=]\s*"
         # \x00 excluded so SECRET never re-captures across a placeholder sentinel
         # left by an earlier pattern.
         r"(?P<q>['\"`]?)(?P<v>[^\s'\"`,;\x00]{6,256})(?P=q)"),
     lambda m: not re.fullmatch(
         # Idempotency: an already-redacted placeholder / env-var / template value
         # is not re-masked.
         r"(?i)\$\{?[A-Z0-9_.]+}?|%[A-Z0-9_]+%|<[^>]+>|\*{3,}|x{3,}"
         r"|\[?redacted]?|\[redacted-[a-z0-9-]+]"
         r"|null|none|true|false|changeme|your[_-]?\w+|placeholder|example",
         m.group("v"))),
]


# ─────────────────────────────────────────────────────────────────────────────
# Core engine
# ─────────────────────────────────────────────────────────────────────────────

def _redact_impl(text: str) -> tuple[str, dict[str, int]]:
    """Core masking pass. Returns (masked_text, counts). Pure / no globals."""
    # NUL is reserved as the in-band placeholder sentinel below.
    if "\x00" in text:
        text = text.replace("\x00", "")
    counts: dict[str, int] = {}
    placeholders: list[str] = []
    sentinel = "\x00{}\x00"

    def _mask(label: str, m: re.Match) -> str:
        counts[label] = counts.get(label, 0) + 1
        placeholders.append(f"[REDACTED-{label}]")
        return sentinel.format(len(placeholders) - 1)

    out = text
    for label, rx, validator in _PATTERNS:
        def _sub(m: re.Match, _label=label, _ok=validator) -> str:
            if _ok is not None and not _ok(m):
                return m.group(0)
            if _label == "SECRET":
                v = m.group("v")
                quoted = bool(m.group("q"))
                v_core = v if quoted else (v.rstrip(").}]!?>") or v)
                if len(v_core) < 6:
                    v_core = v
                keyword = re.sub(r"[^a-z]", "", m.group(1).lower())
                strong = {"password", "passwd", "pwd", "apikey",
                          "accesskey", "clientsecret", "authtoken"}
                generic_unquoted = keyword not in strong and not quoted
                plain_word = (generic_unquoted
                              and v_core.isalpha() and v_core.islower()
                              and len(v_core) < 20)
                code_shape = generic_unquoted and bool(_SECRET_CODE_SHAPE.match(v_core))
                if plain_word or code_shape:
                    return m.group(0)
                head = m.group(0)[: m.start("v") - m.start(0)]
                tail = v[len(v_core):] + m.group(0)[m.end("v") - m.start(0):]
                return head + _mask(_label, m) + tail
            if _label == "CVV":
                return m.group(0)[: m.start(2) - m.start(0)] + _mask(_label, m)
            if _label == "SSN-CTX":
                return m.group(0)[: m.start(2) - m.start(0)] + _mask("SSN", m)
            if _label == "URL-CRED":
                return m.group(1) + _mask(_label, m) + "@"
            return _mask(_label, m)
        out = rx.sub(_sub, out)

    if placeholders:
        def _reinsert(m: re.Match) -> str:
            idx = int(m.group(1))
            return placeholders[idx] if 0 <= idx < len(placeholders) else m.group(0)
        out = re.sub(r"\x00(\d+)\x00", _reinsert, out)
    return out, counts


def redact(text: str) -> str:
    """Return `text` with card data, PII and credential material masked."""
    if not text:
        return text
    out, _ = _redact_impl(text)
    return out


def redact_counts(text: str) -> tuple[str, dict[str, int]]:
    """Return (masked_text, per-label hit counts)."""
    if not text:
        return text, {}
    return _redact_impl(text)


def redact_json_text(text: str) -> str:
    """Parse JSON, redact every string value via redact(), re-serialize. Preserves validity
    by operating on DECODED values (BP-2: no escape-splitting). Idempotent (BP-3 — redact()
    will not re-mask an existing [REDACTED-*] placeholder). For .audit/findings.json (ADR-0016)."""
    data = json.loads(text)

    def walk(node):
        if isinstance(node, str):
            return redact(node)
        if isinstance(node, list):
            return [walk(x) for x in node]
        if isinstance(node, dict):
            return {k: walk(v) for k, v in node.items()}
        return node

    return json.dumps(walk(data), indent=2, ensure_ascii=False) + "\n"


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────

_USAGE = """\
redact.py — mask card/PAN, PII, and credential material in agent reports.

  redact.py [FILE ...]            redact to stdout (reads stdin if no FILE)
  redact.py --in-place FILE ...   rewrite each FILE, masked, in place
  redact.py --check FILE ...      exit 1 if any unredacted secret is present
                                  (modifies nothing; prints a per-label tally)
  redact.py --json FILE           redact string VALUES, emit valid JSON (findings.json)
"""


# Audit reports are small (KBs). An anomalously large .audit file is suspicious
# and the linear-but-O(n) scan over it invites the "gate hangs → --no-verify"
# fail-open (ADR-0011 grill V7). Cap it: oversized files fail-closed in --check
# (manual review required) rather than silently passing or hanging.
_MAX_BYTES = 8_000_000

import os


def _read(path: str) -> str:
    if path == "-":
        return sys.stdin.read()
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


def main(argv: list[str]) -> int:
    args = argv[1:]
    if not args:
        sys.stdout.write(redact(sys.stdin.read()))
        return 0
    if args[0] in ("-h", "--help"):
        sys.stdout.write(_USAGE)
        return 0
    if args[0] == "--json":
        files = args[1:] or ["-"]
        for f in files:
            try:
                sys.stdout.write(redact_json_text(_read(f)))
            except (json.JSONDecodeError, ValueError, RecursionError) as exc:
                # self-audit TP-003: clean error + fail-closed exit, not an uncaught traceback.
                print(f"redact --json: {f}: not valid/parseable JSON ({exc})", file=sys.stderr)
                return 1
        return 0

    mode = "filter"
    if args[0] == "--check":
        mode, files = "check", args[1:]
    elif args[0] == "--in-place":
        mode, files = "in-place", args[1:]
    else:
        files = args
    if not files:
        files = ["-"]

    if mode == "check":
        total: dict[str, int] = {}
        hit_files: list[str] = []
        oversized: list[str] = []
        for f in files:
            if f != "-" and os.path.getsize(f) > _MAX_BYTES:
                oversized.append(f)
                print(f"  ✗ {f}: {os.path.getsize(f)} bytes > {_MAX_BYTES} cap — "
                      f"anomalous for an audit report; review manually.", file=sys.stderr)
                continue
            _, counts = redact_counts(_read(f))
            if counts:
                hit_files.append(f)
                n = sum(counts.values())
                labels = ", ".join(f"{k}:{v}" for k, v in sorted(counts.items()))
                print(f"  ✗ {f}: {n} unredacted ({labels})", file=sys.stderr)
                for k, v in counts.items():
                    total[k] = total.get(k, 0) + v
        if hit_files or oversized:
            grand = sum(total.values())
            if hit_files:
                print(f"❌ redact --check: {grand} sensitive value(s) in "
                      f"{len(hit_files)} file(s) — scrub before commit.", file=sys.stderr)
            if oversized:
                print(f"❌ redact --check: {len(oversized)} file(s) over the "
                      f"{_MAX_BYTES}-byte cap — manual review required (fail-closed).",
                      file=sys.stderr)
            return 1
        print("✓ redact --check: no unredacted card/PII/credential material.",
              file=sys.stderr)
        return 0

    if mode == "in-place":
        for f in files:
            if f == "-":
                sys.stdout.write(redact(sys.stdin.read()))
                continue
            masked = redact(_read(f))
            with open(f, "w", encoding="utf-8") as fh:
                fh.write(masked)
        return 0

    # filter mode → stdout
    for f in files:
        sys.stdout.write(redact(_read(f)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
