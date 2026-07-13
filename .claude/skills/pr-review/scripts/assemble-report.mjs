#!/usr/bin/env node
// Step 3: assemble the specialist + synthesizer JSON into review.json and render report.md with a
// verdict banner. Findings are ordered by severity (CRITICAL first) and counted; when the
// synthesizer output is missing/unparseable, a fallback verdict is derived from the specialist
// findings (CRITICAL->NO_GO, HIGH/MEDIUM->CONDITIONAL, else GO). Fail-loud: a render failure exits
// non-zero.
//
// Usage: assemble-report.mjs <outputDir>

import fs from 'node:fs';
import path from 'node:path';
import { parseJsonLoose, prioritize, severityCounts, SEV_ORDER } from './lib.mjs';

const outputDir = process.argv[2];
if (!outputDir) {
  console.error('ERROR: Usage: assemble-report.mjs <outputDir>');
  process.exit(1);
}

const meta = JSON.parse(fs.readFileSync(path.join(outputDir, 'meta.json'), 'utf8'));

/** Read + tolerantly parse a specialist JSON; on any failure return an "unavailable" analysis. */
function readAnalysis(name, label) {
  const p = path.join(outputDir, `${name}.json`);
  try {
    const obj = parseJsonLoose(fs.readFileSync(p, 'utf8'));
    const findings = Array.isArray(obj.findings) ? obj.findings : [];
    return { summary: obj.summary || '', findings };
  } catch {
    return { summary: `${label} analysis unavailable (subagent produced no parseable output).`, findings: [] };
  }
}

const chr = readAnalysis('risk', 'Change-risk');
const cfg = readAnalysis('configuration', 'Configuration');
const ora = readAnalysis('observability', 'Observability');

// Synthesizer output, or a defensible fallback derived from the raw specialist findings.
let synthesis;
try {
  const s = parseJsonLoose(fs.readFileSync(path.join(outputDir, 'synthesizer.json'), 'utf8'));
  synthesis = {
    verdict: String(s.verdict || '').toUpperCase(),
    executiveSummary: s.executiveSummary || '',
    prioritizedFindings: Array.isArray(s.prioritizedFindings) ? s.prioritizedFindings : [],
  };
  if (!['GO', 'CONDITIONAL', 'NO_GO'].includes(synthesis.verdict)) throw new Error('bad verdict');
} catch {
  synthesis = fallbackSynthesis(chr, cfg, ora);
}

/** Derive a verdict from the raw findings when the synthesizer output is unavailable. */
function fallbackSynthesis(a, b, c) {
  const all = prioritize([...a.findings, ...b.findings, ...c.findings]);
  const counts = severityCounts(all);
  let verdict;
  if (counts.CRITICAL > 0 || counts.HIGH > 1) verdict = 'NO_GO';
  else if (counts.HIGH === 1) verdict = 'CONDITIONAL';
  else verdict = 'GO';
  return {
    verdict,
    executiveSummary: `Synthesizer unavailable; verdict derived directly from specialist findings (${all.length} total).`,
    prioritizedFindings: all,
  };
}

const prioritized = prioritize(synthesis.prioritizedFindings);
const counts = severityCounts(prioritized);
const durationMillis = Math.max(0, Date.now() - new Date(meta.fetchedAt).getTime());
// Model label recorded in the report. Defaults to "claude-code"; pass a precise label
// (e.g. "claude-opus-4-8") as the skill's 2nd arg to record it.
const modelId = meta.modelLabel;

const review = {
  prUrl: meta.prUrl,
  repository: meta.repository,
  prNumber: meta.number,
  prTitle: meta.title,
  verdict: synthesis.verdict,
  executiveSummary: synthesis.executiveSummary,
  totalFindings: prioritized.length,
  severityCounts: counts,
  dimensions: [
    { dimension: 'CHANGE_RISK', summary: chr.summary, findings: chr.findings },
    { dimension: 'CONFIGURATION', summary: cfg.summary, findings: cfg.findings },
    { dimension: 'OBSERVABILITY', summary: ora.summary, findings: ora.findings },
  ],
  prioritizedFindings: prioritized,
  meta: {
    agentModels: {
      risk: modelId, configuration: modelId, observability: modelId, synthesizer: modelId,
    },
    changedFiles: meta.changedFiles,
    durationMillis,
    diffChars: meta.diffChars,
    contextLevel: meta.contextLevel || 'diff',
    headSha: meta.headSha || null,
    baseSha: meta.baseSha || null,
    headRef: meta.headRef || null,
    baseRef: meta.baseRef || null,
    author: meta.author || null,
  },
  generatedAt: new Date().toISOString(),
};

fs.writeFileSync(path.join(outputDir, 'review.json'), JSON.stringify(review, null, 2));

// ---------------- report.md ----------------
const DIM_LABEL = { CHANGE_RISK: 'Change-Risk (CHR)', CONFIGURATION: 'Configuration (CFG)', OBSERVABILITY: 'Observability (ORA)' };

/** Human-readable grounding level for the report header. */
function contextDesc(mt) {
  const sha = (mt.headSha || '').slice(0, 12);
  if (mt.contextLevel === 'repo') return `full repo checkout${sha ? ` at ${sha}` : ''}`;
  if (mt.contextLevel === 'files') return 'changed-file contents at head';
  return 'diff only (no repository checkout)';
}

const cell = (v) => String(v == null ? '' : v).replace(/\|/g, '\\|').replace(/\n+/g, ' ').trim();
const sha12 = (s) => (s ? String(s).slice(0, 12) : '(unknown)');

/** The prioritized summary table: every finding from every dimension, most-severe first. */
function findingsTable(findings) {
  if (!findings.length) return '_No findings._\n';
  const rows = findings.map((f) => `| ${cell(f.id)} | ${cell(f.severity)} | ${cell(f.title)} | ${cell(f.file)} |`);
  return ['| ID | Severity | Title | File |', '|----|----------|-------|------|', ...rows].join('\n') + '\n';
}

const sevLine = SEV_ORDER.map((s) => `${s}: ${counts[s]}`).join('  ·  ');
const mt = review.meta;
const lines = [];

// 1. Header section
lines.push(`# PR Resiliency Review — ${review.verdict}`);
lines.push('');
lines.push(`## Header`);
lines.push('');
lines.push('| Field | Value |');
lines.push('|-------|-------|');
lines.push(`| PR | [${review.repository} #${review.prNumber}](${review.prUrl}) |`);
lines.push(`| Title | ${cell(review.prTitle)} |`);
lines.push(`| Author | ${cell(mt.author || '(unknown)')} |`);
lines.push(`| Branch | \`${cell(mt.headRef || '?')}\` → \`${cell(mt.baseRef || '?')}\` |`);
lines.push(`| Head commit | \`${sha12(mt.headSha)}\` |`);
lines.push(`| Baseline commit | \`${sha12(mt.baseSha)}\` |`);
lines.push(`| Verdict | **${review.verdict}** |`);
lines.push(`| Findings | ${review.totalFindings} — ${sevLine} |`);
lines.push(`| Files changed | ${mt.changedFiles}  ·  Diff: ${mt.diffChars} chars |`);
lines.push(`| Context | ${contextDesc(mt)} |`);
lines.push(`| LLM model | ${cell(mt.agentModels?.synthesizer || meta.modelLabel)} |`);
lines.push('');
lines.push('### Executive Summary');
lines.push('');
lines.push(review.executiveSummary || '_(none)_');
lines.push('');

// 2. Prioritized findings — all findings, all dimensions, most-severe first
lines.push('## Prioritized Findings');
lines.push('');
lines.push(findingsTable(prioritized));

// 3. Detailed findings — full description + recommendation per finding
lines.push('## Detailed Findings');
lines.push('');
if (!prioritized.length) {
  lines.push('_No findings._');
  lines.push('');
} else {
  for (const f of prioritized) {
    lines.push(`### ${cell(f.id)} · ${cell(f.severity)} — ${cell(f.title)}`);
    lines.push('');
    lines.push(`- **File:** \`${cell(f.file)}\``);
    lines.push(`- **Severity:** ${cell(f.severity)}`);
    lines.push('');
    lines.push('**What & why**');
    lines.push('');
    lines.push(f.description ? String(f.description).trim() : '_(no description provided)_');
    lines.push('');
    lines.push('**Recommendation**');
    lines.push('');
    lines.push(f.recommendation ? String(f.recommendation).trim() : '_(no recommendation provided)_');
    lines.push('');
  }
}

// 4. End of report
lines.push('## End of Report');
lines.push('');
lines.push(`- **Verdict:** ${review.verdict}`);
lines.push(`- **Total findings:** ${review.totalFindings} (${sevLine})`);
lines.push(`- **Dimensions:** ${review.dimensions.map((d) => `${DIM_LABEL[d.dimension]} — ${d.findings.length}`).join('  ·  ')}`);
lines.push(`- **Model:** ${cell(meta.modelLabel)}  ·  **Duration:** ${durationMillis} ms  ·  **Generated:** ${review.generatedAt}`);
lines.push('');
lines.push('---');
lines.push(`_Multi-agent PR resiliency review._`);
lines.push('');
fs.writeFileSync(path.join(outputDir, 'report.md'), lines.join('\n'));

console.log(`RESULT: verdict=${review.verdict} findings=${review.totalFindings} (${sevLine})`);
console.log(`REVIEW_JSON: ${path.join(outputDir, 'review.json')}`);
console.log(`REPORT_MD: ${path.join(outputDir, 'report.md')}`);

// The repo/ checkout is only needed by the specialists (Step 1) to verify findings; once the report
// is assembled it is dead weight (~40x the size of all other artifacts). Remove it now that the run
// is complete. Best-effort: a cleanup failure must not fail the report.
const repoDir = path.join(outputDir, 'repo');
try {
  if (fs.existsSync(repoDir)) {
    fs.rmSync(repoDir, { recursive: true, force: true });
    console.log(`CLEANUP: removed repo checkout ${repoDir}`);
  }
} catch (e) {
  console.log(`CLEANUP: could not remove ${repoDir} (${e.message})`);
}
