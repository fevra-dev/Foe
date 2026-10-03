# ADR-NNNN — <Title>

**Status:** Proposed | Accepted | Superseded · **Date:** YYYY-MM-DD
**Supersedes:** — · **Superseded-by:** —

## Context
<What forces / threat model are at play? Why is a decision needed?>

## Decision
<The rule. Imperative and testable. e.g. "All raw byte streams MUST pass through sanitize_payload() before any disk/memory write.">

## Considered Options
<!-- Optional but recommended. The alternatives weighed and why each lost — so a future reader/agent
     does NOT re-litigate a settled call. One line each; the chosen option is listed first. Omit only
     for trivial/forced decisions. -->
- **<Option A — chosen>** — why it won.
- **<Option B>** — rejected: <why.>
- **<Option C>** — rejected: <why.>

## Consequences
<Trade-offs; what gets easier/harder.>

## Security Considerations & Mitigations
<Output of grill-with-threat-model: attack vectors and how this decision closes them.>

## Enforcement
<The Semgrep/CodeQL/lint rule (or path to it) that fails CI on violation. An ADR without enforcement is a comment, not a control.>

<!-- ── Optional: only when this ADR changes a tool's OUTPUT FORMAT. Drives the
     schema-contract system (~/.claude/templates/schema-contract/, STACK.md §14). ── -->

## Output Schema Impact
**Schema Change Type:** none   <!-- none | additive | breaking -->
<!-- If additive/breaking: -->
**Schema File:** schema/<tool>-output.v<N>.json · **New Version:** v<N+1>
**Fields Added/Modified/Removed:** <field: change>
**Consumer Audit Required:** no   <!-- if yes: run scripts/schema_audit.py and list broken consumers from schema_consumers.md -->

## Semantic Drift Assessment
<!-- The failure JSON Schema CANNOT catch: type+name unchanged, MEANING changed. -->
- **Type/Format stability:** does this keep the JSON type but change scale/unit/precision? (e.g. risk_score 0-100→0-10, u64-string→float)
- **Enum extensibility:** if expanding an enum, do consumers have a fallback for unknown values?
- **Mathematical invariants:** min/max/pattern changed? downstream impact?
- **Verification plan:** unit tests asserting the field's `x-semantic-constraints` block.
