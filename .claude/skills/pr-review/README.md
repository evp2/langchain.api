# pr-review

A multi-agent resiliency review for a single GitHub pull request, run entirely inside Claude Code.
Three specialists review the change in parallel — **change-risk (CHR)**, **configuration (CFG)**,
and **observability (ORA)** — then a **synthesizer** consolidates their findings into one
**GO / CONDITIONAL / NO_GO** verdict. The specialists don't just read the diff: the setup step gives
them repo context so they can **verify** a finding (e.g. confirm a removed symbol truly has no
remaining callers) before reporting it.

## Usage

From anywhere in this repo, in Claude Code:

```
/pr-review https://github.com/{owner}/{repo}/pull/{n} [model-label]
```

or just ask: *"review this PR for production risk: <PR url>"*.

## Prerequisites

- **Node.js ≥ 18** (uses only built-ins; no `npm install`).
- **git** — for the shallow checkout that gives the specialists repo context.
- **GitHub CLI** (`gh`) authenticated (`gh auth login`), or `GITHUB_TOKEN` / `GH_TOKEN` set — used to
  fetch PR metadata, the full diff, and to clone the repo at the PR head.

## How it works

1. `fetch-pr.mjs` fetches the PR's metadata, description/commits (`pr-context.md`), and full diff,
   then gathers **review context** (see below) and writes a context-aware prompt for each specialist.
2. The three specialists run as **parallel subagents**, each emitting `{summary, findings[]}` JSON.
   They may `rg`/Read the checkout to verify findings, following a per-dimension protocol and a small
   tool budget.
3. `build-synth-prompt.mjs` feeds those findings to a **synthesizer** subagent, which filters,
   de-duplicates, prioritizes, and decides the verdict.
4. `assemble-report.mjs` combines everything into `review.json` and a rendered `report.md`.

## Context (how findings get verified)

Diff-only review produces false positives whenever a change's safety depends on code *outside* the
diff (e.g. a removed duplicative symbol flagged as a breaking change). To avoid that, `fetch-pr.mjs`
gathers the richest context it can and records the achieved **`contextLevel`** (also shown in the
report header). Every tier past the diff is fail-open — a failure only lowers the level:

- **`repo`** — a shallow checkout (`--depth 1`) of the repo at the PR head (`repo/`), plus a
  deterministic `git grep` scan of removed/renamed symbols (`callers.md`). Specialists Read/`rg` it to
  verify findings. Fork-safe (fetches `refs/pull/<n>/head`).
- **`files`** — if the checkout fails, the full contents of the changed files at the PR head
  (`files/`). Whole function bodies, but no repo-wide grep.
- **`diff`** — if both fail, diff only. Specialists hedge findings that depend on outside context.

Cost knobs (all fail-open): shallow `--depth 1` checkout; caps on symbols scanned (25), changed files
fetched (40), and per-file size (200 KB); a ~15–20 tool-call budget in each specialist prompt.

## Layout

```
pr-review/
├── SKILL.md                # orchestration: fetch → 3 parallel specialists → synthesizer → assemble
├── agents/                 # the four review prompts (CHR / CFG / ORA / synthesizer)
│   ├── risk.md · configuration.md · observability.md · synthesizer.md
└── scripts/
    ├── lib.mjs             # helpers: prompt render, JSON tolerance, severity sort, context block
    ├── fetch-pr.mjs        # gh fetch (meta + diff) + context gathering + pre-render prompts
    ├── build-synth-prompt.mjs  # inject the 3 specialist JSONs into the synthesizer prompt
    └── assemble-report.mjs # specialist + synth JSON → review.json + report.md
```

Artifacts are written to `./.pr-review/output/{owner}-{repo}-pr-{n}/`:
`meta.json`, `pr-context.md`, `diff.txt`, `repo/` + `callers.md` (repo level) or `files/` (files
level), `prompts/*.txt`, `{risk,configuration,observability}.json`, `synthesizer.json`, `review.json`,
`report.md`.

## Findings & verdict

Each finding carries a stable id (`CHR-`/`CFG-`/`ORA-` prefix), a severity
(`CRITICAL` > `HIGH` > `MEDIUM` > `LOW`), a title, description, file, and recommendation. The
verdict follows the severity of the surviving findings: any CRITICAL → **NO_GO**; one or more
HIGH/MEDIUM → **CONDITIONAL**; otherwise **GO**. The full diff is analyzed (no truncation). If the
synthesizer output is unavailable, the verdict is derived directly from the specialist findings.
