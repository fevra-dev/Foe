# ADR-0006 — Sanitize Untrusted Bytes Before Any Sink

**Status:** Accepted · **Date:** 2026-06-07
**Supersedes:** — · **Superseded-by:** —
*(Threat-model review: `grill-with-threat-model` pass below.)*

## Context
Security/forensics/detection tooling ingests attacker-controlled bytes (HTTP headers, log lines, packet payloads, file contents, tool output). Writing those bytes unsanitized to a sink — disk, log, stdout/terminal, SIEM, or a parser's memory — enables log injection, telemetry forgery, and terminal-escape attacks. (From the operator's own ADR-context-layer note: "all raw byte streams must pass through `sanitize_payload()` before being written to disk or memory.")

## Decision
All raw/untrusted byte streams MUST pass a sanitizer (`sanitize_payload()` or equivalent) **at the trust boundary**, before being written to any sink: disk, log, stdout/stderr/terminal, SIEM/telemetry, or parsed into trusted memory. Sanitization happens once, at the boundary — not at each use site.

## Consequences
One mandatory choke-point per ingestion path; prevents log/telemetry forgery and terminal-injection from poisoning the tool's own output.

## Security Considerations & Mitigations (grill-with-threat-model)
- **Terminal/ANSI-escape injection** — untrusted bytes with escape sequences reach stdout / a log viewer → terminal spoofing, hidden text, log forgery. → strip/escape control + ANSI sequences in the sanitizer.
- **Log-line / newline forgery** — untrusted input with `\n`/`\r` injects fake log entries (spoofs the SIEM baseline). → escape newlines/CR before logging.
- **Sanitize-at-use desync (TOCTOU)** — sanitized for one sink, but another code path writes the *raw* value to a different sink. → sanitize at the boundary, single choke-point (pair with `spec-isolation-boundaries`).
- **Encoding bypass / double-decode** — the sanitizer validates UTF-8 but the sink re-decodes differently, or input is double-decoded past the check. → canonicalize encoding *before* sanitizing; the sink consumes only the canonical form.

## Enforcement
- Semgrep rule: flag any write/log/print of a value that did not pass the sanitizer (direct sink of untrusted input).
- `spec-isolation-boundaries` to map the per-tool entry points + choke-points that feed this rule.

## Enforcement status — F2 addendum (2026-06-25, append-only)
The Semgrep rule above is **specified but unbuilt** (F2 audit confirmed: only the `llm-surface` ruleset
exists). Per ADR-0000 ("an ADR without enforcement is a comment, not a control"), the honest status:
- **Built apps that ingest untrusted bytes** — the Semgrep taint rule is a **deferred opt-in seed**,
  built when an app actually needs it (ADR-0008: no harness on a hypothesis; the same posture that kept
  `llm-surface`/ADR-0012 an opt-in seed until a real LLM-app need).
- **The audit agent's own untrusted-byte handling** (reading malware / ABIs / hostile bytes) has **no
  static codebase to taint-scan** — the agent *is* the runtime. Its deterministic boundary is therefore
  **Seatbelt containment** (blast-radius bound, network egress denied) + **`redact.py`** on the output
  side. ADR-0006 is classified **inherent-inferential** in the HARNESS §1b guardrail map with those two
  as the named boundaries.
