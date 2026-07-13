---
name: pr-review
description: >
  Multi-agent resiliency review of a single GitHub pull request. Runs three specialists in parallel
  — change-risk (CHR), configuration (CFG), and observability (ORA) — over the PR's full diff, then a
  synthesizer consolidates their findings into one GO / CONDITIONAL / NO_GO verdict. Use when asked
  to review a PR for production/deployment risk, assess whether a PR is safe to merge, or run a
  change-risk / configuration / observability review of a pull request. Trigger phrases: "review this
  PR", "is this PR safe to merge", "production risk of this PR", "what could break if we ship this PR".
argument-hint: "<pr-url> [model-label]"
---

# PR Resiliency Review

Runs a multi-agent resiliency review of a GitHub pull request. The setup step fetches the full diff
plus review context — the PR description/commits and, when possible, a shallow checkout of the repo at
the PR head. Three specialists (change-risk, configuration, observability) analyze the change in
parallel, using that context to **verify** findings before reporting them, then a synthesizer
consolidates everything into a single GO / CONDITIONAL / NO_GO verdict.

Arguments: `$1` = GitHub PR URL **[required]**; `$2` = optional model label recorded in the report
(default `claude-code`). Substitute the user's actual values into the commands below.

---

## Step 0 — Fetch the PR and prepare prompts

```bash
SKILL_DIR="$(git rev-parse --show-toplevel 2>/dev/null || pwd)/.claude/skills/pr-review"
node "$SKILL_DIR/scripts/fetch-pr.mjs" "<PR_URL>" "<MODEL_LABEL>"
```

If the script exits non-zero (invalid URL, empty diff, `gh` missing/unauthenticated), stop and show
the message — do not proceed. Otherwise parse the `RESULT_JSON` object printed between `RESULT_JSON:`
and `==== END RESULT_JSON ====`, and keep these values for later steps:
`outputDir`, `promptsDir`, `specialists`, `agentOutputs`, `synthesizerOutput`, `reviewJson`,
`reportMd`, and `contextLevel` (`repo` / `files` / `diff` — the grounding the specialists will have).
Besides the diff, the setup writes `pr-context.md` (author intent) and, at `repo` level, a `repo/`
checkout and `callers.md`; the prompt files already point the specialists at whatever was gathered.

## Step 1 — Launch the three specialists IN PARALLEL

In a **single message**, issue **three** `Agent` tool calls (subagent_type `general-purpose`) — one
each for `risk`, `configuration`, `observability`. Do not await one before starting the next; all
three go out together. Use this prompt for each (substitute `{promptsDir}` and `{name}`):

```
You are a specialist code reviewer. Read the file {promptsDir}/{name}.txt in full — it is your
COMPLETE instructions: your role, the PR diff to review, a CONTEXT & VERIFICATION section (with paths
to the PR description and usually a checkout of the repo at the PR head), the JSON output schema, and
the exact file path to write to. Follow it precisely. You MAY use Read, Grep/`rg`, and Bash
(`git grep`) on the referenced checkout to VERIFY a finding before reporting it — follow the
verification protocol and tool budget in the file, and stay within the changed files and their
references. Then use the Write tool to save ONLY the raw JSON object (no prose, no code fences) to the
path the file specifies, and reply with one short line confirming the file was written.
```

When all three subagents have returned, proceed. Do **not** read their JSON files yourself — the
scripts in Steps 2–3 handle that.

## Step 2 — Synthesize

Build the synthesizer prompt (injects the three specialist JSONs), then launch one synthesizer subagent:

```bash
node "$SKILL_DIR/scripts/build-synth-prompt.mjs" "{outputDir}"
```

Then one `Agent` call (subagent_type `general-purpose`):

```
You are the deployment-integrity quality gate. Read the file {promptsDir}/synthesizer.txt in full —
it is your COMPLETE instructions (role, the three specialist analyses, the output schema, and the
exact output path). Follow it precisely and use the Write tool to save ONLY the raw JSON to the path
it specifies. Then reply with one short line confirming the file was written.
```

## Step 3 — Assemble the report (deterministic, fail-loud)

```bash
node "$SKILL_DIR/scripts/assemble-report.mjs" "{outputDir}"
```

This writes `review.json` and `report.md`. If it exits non-zero, report the failure — there is no
valid report. If `synthesizer.json` was missing/unparseable, it derives a fallback verdict directly
from the specialist findings.

## Step 4 — Present

```bash
REPORT_MD="{reportMd}"
VERDICT=$(node -e "process.stdout.write(JSON.parse(require('fs').readFileSync('{reviewJson}','utf8')).verdict)")
CONTEXT=$(node -e "process.stdout.write(String(JSON.parse(require('fs').readFileSync('{reviewJson}','utf8')).meta.contextLevel||'diff'))")
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  PR RESILIENCY REVIEW"
echo "  Verdict: ${VERDICT}"
echo "  Context: ${CONTEXT}"
echo "  PR:      <PR_URL>"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
cat "$REPORT_MD"
echo ""
echo "Artifacts: {outputDir}  (review.json + report.md)"
```

Then give the user a one-paragraph summary of the verdict and the top findings.
