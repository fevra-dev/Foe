## What

<One paragraph: what changed and why.>

## Checks

- [ ] Pre-push gate passes locally (`bash .githooks/lint-gate.sh`)
- [ ] Touches an existing ADR constraint? Named it here, or wrote a new ADR from `adr/TEMPLATE.md`
- [ ] New dependency? Supply-chain justification per ADR-0001 (package + version + source, risk vs. in-house, transitive count)
- [ ] New/changed workflow? Actions SHA-pinned, **top-level** `permissions:` starting at `contents: read`, no `write-all`, no unwaived `pull_request_target` (ADR-0018)
- [ ] Self-built tooling? Dogfooded end-to-end before calling it done (ADR-0010)

## Risk

<What could this break? What is the rollback?>
