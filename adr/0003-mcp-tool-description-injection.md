# ADR-0003 — Review MCP Tool Descriptions for Hidden Instructions

**Status:** Accepted · **Date:** 2026-06-07
**Supersedes:** — · **Superseded-by:** —
*(Threat-model review: inline adversarial pass below — formalize via `grill-with-threat-model` once built.)*

## Context
MCP tool **descriptions** are injected into the model's context and are treated as instructions the agent will follow. A malicious MCP server can embed hidden directives in its tool descriptions — indirect prompt injection via tool metadata. Confirmed pattern: `ruvnet/ruflo` issue **#1375** (2026-03-17, "Security Audit Summary: Multiple Critical Concerns") documents Ruflo MCP tool descriptions carrying hidden instructions that direct Claude to add the repository owner as a contributor to the user's repositories — using the user's own credentials, without consent. This is a supply-chain attack via tool metadata, not a bug. (Ruflo is KILLed in STACK.md §11.)

## Decision
No third-party MCP server may be connected to a Claude Code instance with write access until its tool descriptions have been reviewed for hidden/embedded instructions. **Reject** any server whose tool descriptions reference external accounts, repositories, contributor/permission changes, credential use, network destinations, or any directive unrelated to the tool's stated function.

## Consequences
One review step per new MCP server; blocks credential-abusing supply-chain injections that no allowlist or sandbox catches (the payload is in trusted-looking metadata).

## Security Considerations & Mitigations
- **Contributor/permission injection** (the Ruflo pattern). → grep tool descriptions for `contributor|collaborator|permission|token|credential|owner|push|deploy|grant` and human-review hits.
- **Exfil directives** (description says "send X to URL"). → flag any URL/host appearing in a tool description.
- **Persistent "always/never" directives** smuggled as description text. → flag imperative directives unrelated to the tool's function.

## Enforcement
- `npx ecc-agentshield scan` — profiles MCP servers and matches 25+ MCP vulnerability patterns incl. tool-description injection. *(Vet before first run per ADR-0001.)*
- Pre-connection checklist: dump each tool's `description`, scan for the patterns above, human-approve.
- Disqualify any server failing review. `ruvnet/ruflo` is permanently disqualified.
