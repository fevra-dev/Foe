# <Project Name>

<One-line tagline that is a pitch, not a description.>

[![License](https://img.shields.io/badge/license-BSD--2--Clause-blue.svg)](LICENSE)
[![CI](https://github.com/fevra-dev/<repo>/actions/workflows/ci.yml/badge.svg)](https://github.com/fevra-dev/<repo>/actions/workflows/ci.yml)

<Two to three sentences: what it does, who it is for, and the one thing that is
actually different from the obvious alternative. This paragraph and the three lines
above it carry most of the weight — most visitors decide here.>

<!-- SECURITY TOOLING: keep the authorized-use line directly below, above the fold.
     Delete it for non-security projects. -->
> **Authorized use only.** This tool is for systems you own or have written permission
> to test. See [SECURITY.md](SECURITY.md).

## Highlights

- **<Capability>** — <one-sentence payoff>
- **<Capability>** — <one-sentence payoff>
- **<Capability>** — <one-sentence payoff>

<!-- SECURITY TOOLING: group findings/checks by attack surface or technique, not flat —
     match how a security-literate reader already thinks (OWASP-style taxonomy). -->
<!-- AGENT / SKILLS: replace this section with a stats line (skills / workflows / files)
     + a runtime-compatibility table (Claude Code, Codex, Cursor, Gemini CLI, …). -->

## Install

```bash
<exact copy-paste command — one block per supported platform>
```

<!-- MCP SERVERS: replace the block above with one config JSON block per client
     (Claude Desktop, Claude Code, Cursor, VS Code), and state plainly whether this is a
     reference implementation or production-ready. Document permission-scoping flags
     (read-only mode, tool allow/deny lists) prominently — installing an MCP server
     means granting it access to something. -->

## Quickstart

```bash
<minimal working example — not a full reference>
```

<!-- SECURITY TOOLING: a ready-to-paste CI job does more for adoption than any feature
     list, because the real question is whether it fits what is already running.
     Actions MUST be SHA-pinned with a top-level `permissions:` block (ADR-0018).
     Resolve a tag with:
       gh api repos/<owner>/<repo>/git/ref/tags/<tag> --jq '.object.sha'
     or let the checker do it: actions-pin.py --fix . -->

## Documentation

Full docs: <link>. This README stays short on purpose — see the docs for configuration,
architecture, and the full command reference.

## Security

Disclosure policy, supported versions, and authorized-use terms: [SECURITY.md](SECURITY.md).
<!-- Credit the upstream open-source projects this builds on. In security tooling this is
     a stronger trust signal than any badge. -->

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Work lands through a PR, even solo — the pre-push
gate (branch guard → lint → format → Trivy → Semgrep → bandit → actions-pin → redact)
blocks direct pushes to `main`.

<!-- RESEARCH TOOLING: add a CITATION.cff to the repo root — GitHub renders a
     "Cite this repository" button with APA/BibTeX export, no README changes needed.
     For a research tool this is the difference between being cited and being quietly
     reimplemented uncredited. -->

## Credits

Foe's elemental weakness table (`src/main/resources/com/foe/weakness-table.tsv`) is derived from the
[Old School RuneScape Wiki](https://oldschool.runescape.wiki), whose content is licensed under
[CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/). The table carries its own notice,
[`weakness-table.LICENSE`](src/main/resources/com/foe/weakness-table.LICENSE), and the wiki's editors are credited
through each page's history. The plugin's code is licensed separately (see below).

## License

The plugin code: [BSD 2-Clause](LICENSE). The weakness table: CC BY-NC-SA 3.0, as above.
