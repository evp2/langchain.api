#!/usr/bin/env node
// Setup step for the pr-review skill.
//
// Fetches the PR's metadata, author intent, and full diff, then gathers as much review CONTEXT as it
// can — a shallow checkout at the PR head (so the specialists can Read/grep the repo to verify
// findings) plus a deterministic caller scan for removed symbols. Everything past the diff is
// fail-open: any context step that fails simply lowers `contextLevel` (repo -> files -> diff); the
// review always runs.
//
// Writes, under ./.pr-review/output/<owner>-<repo>-pr-<n>/:
//   meta.json                    PR metadata + run info (incl. contextLevel, headSha)
//   pr-context.md                title, description, commit messages (author intent)
//   diff.txt                     full unified diff
//   repo/                        shallow checkout at the PR head          (contextLevel "repo")
//   callers.md                   remaining-reference scan for removed syms (contextLevel "repo")
//   files/<path>                 full changed-file contents at head        (contextLevel "files")
//   prompts/{risk,configuration,observability}.txt   specialist prompts (context-aware)
// and echoes a RESULT_JSON block the SKILL.md parses for the later steps.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  renderAgentPrompt, outputInstruction, repoRoot, prSlug,
  sh, extractRemovedSymbols, renderContextBlock,
} from './lib.mjs';

const SKILL_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const AGENTS_DIR = path.join(SKILL_DIR, 'agents');
const PR_URL_RE = /github\.com\/([^/\s]+)\/([^/\s]+)\/pull\/(\d+)/;

// Context caps (all fail-open).
const MAX_CALLER_SYMBOLS = 25;   // removed symbols to caller-scan
const MAX_GREP_LINES = 30;       // reference lines to record per symbol
const MAX_FILES = 40;            // changed files to fetch in the "files" fallback
const MAX_FILE_BYTES = 200_000;  // per-file cap in the "files" fallback

function fail(msg) {
  console.error(`⚠ ${msg}`);
  process.exit(1);
}

/** Hard-fail wrapper for the REQUIRED gh calls (metadata, diff). */
function gh(args, { accept } = {}) {
  const full = accept ? [...args, '--header', `Accept: ${accept}`] : args;
  try {
    return execFileSync('gh', full, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  } catch (e) {
    const stderr = (e.stderr || '').toString().trim();
    if (/not found|command not found|ENOENT/i.test(stderr) || e.code === 'ENOENT') {
      fail('The `gh` CLI is required but was not found. Install GitHub CLI and run `gh auth login`.');
    }
    fail(`gh ${args.join(' ')} failed:\n${stderr || e.message}`);
  }
}

const prUrl = process.argv[2];
const modelLabel = process.argv[3] || 'claude-code';
if (!prUrl) fail('Usage: fetch-pr.mjs <pr-url> [model-label]');

const m = PR_URL_RE.exec(prUrl);
if (!m) fail('Not a valid GitHub PR URL. Expected https://github.com/{owner}/{repo}/pull/{number}');
const [, owner, repo, numberStr] = m;
const number = parseInt(numberStr, 10);
const repository = `${owner}/${repo}`;

// --- rich PR metadata (title, body, commits, files, head SHA, ...) ---
const PR_FIELDS = 'title,body,author,state,baseRefName,headRefName,headRefOid,baseRefOid,'
  + 'additions,deletions,changedFiles,commits,labels,files,isCrossRepository';
let pr;
try {
  pr = JSON.parse(gh(['pr', 'view', prUrl, '--json', PR_FIELDS]));
} catch {
  fail(`Could not read PR metadata for ${repository}#${number}`);
}
const title = pr.title || '(untitled)';
const headSha = pr.headRefOid || '';
const changedFiles = Number.isInteger(pr.changedFiles) ? pr.changedFiles : (pr.files || []).length;

// --- full unified diff via the GitHub diff media type; NO truncation ---
const diff = gh(['api', `repos/${owner}/${repo}/pulls/${number}`], {
  accept: 'application/vnd.github.v3.diff',
});
if (!diff || !diff.trim()) fail('Pull request diff is empty — nothing to review.');

// --- output layout ---
const outputDir = path.join(repoRoot(), '.pr-review', 'output', prSlug(owner, repo, number));
const promptsDir = path.join(outputDir, 'prompts');
fs.mkdirSync(promptsDir, { recursive: true });
const diffPath = path.join(outputDir, 'diff.txt');
fs.writeFileSync(diffPath, diff);

// --- pr-context.md: author intent (description + commit messages) ---
const prContextPath = path.join(outputDir, 'pr-context.md');
fs.writeFileSync(prContextPath, renderPrContext());

function renderPrContext() {
  const labels = (pr.labels || []).map((l) => l.name).join(', ') || '(none)';
  const L = [];
  L.push(`# PR Context — ${repository} #${number}`, '');
  L.push(`- **Title:** ${title}`);
  L.push(`- **Author:** ${pr.author?.login || '(unknown)'}    **State:** ${pr.state || '?'}`);
  L.push(`- **Base:** ${pr.baseRefName || '?'}  <-  **Head:** ${pr.headRefName || '?'} (${headSha.slice(0, 12)})`);
  L.push(`- **Fork (cross-repository):** ${pr.isCrossRepository ? 'yes' : 'no'}`);
  L.push(`- **Labels:** ${labels}`);
  L.push(`- **Changed files:** ${changedFiles}  (+${pr.additions ?? '?'} / -${pr.deletions ?? '?'})`, '');
  L.push('## Description', (pr.body && pr.body.trim()) ? pr.body.trim() : '(no description provided)', '');
  L.push('## Commits');
  const commits = pr.commits || [];
  if (!commits.length) L.push('(none)');
  for (const c of commits) {
    L.push(`- ${c.messageHeadline || '(no message)'}`);
    if (c.messageBody && c.messageBody.trim()) {
      for (const bl of c.messageBody.trim().split(/\r?\n/)) L.push(`  ${bl}`);
    }
  }
  return L.join('\n') + '\n';
}

// --- context tier 1: shallow checkout at the PR head (fork-safe via pull/<n>/head) ---
const repoDir = path.join(outputDir, 'repo');
let contextLevel = 'diff';
fs.rmSync(repoDir, { recursive: true, force: true });
const clone = sh('gh', ['repo', 'clone', repository, repoDir, '--', '--depth', '1', '--no-tags', '--quiet']);
if (clone.ok) {
  const fetched = sh('git', ['-C', repoDir, 'fetch', '--depth', '1', '--quiet', 'origin', `pull/${number}/head`]);
  if (fetched.ok && sh('git', ['-C', repoDir, 'checkout', '--quiet', 'FETCH_HEAD']).ok) {
    contextLevel = 'repo';
  }
}

// --- context tier 1b: deterministic caller scan for removed symbols (repo level only) ---
let callersPath = null;
if (contextLevel === 'repo') {
  const symbols = extractRemovedSymbols(diff).slice(0, MAX_CALLER_SYMBOLS);
  const L = [`# Remaining-reference scan (repo at PR head ${headSha.slice(0, 12)})`, ''];
  if (!symbols.length) {
    L.push('No removed/renamed public symbols were detected in the diff.');
  } else {
    L.push(`Scanned ${symbols.length} removed/renamed symbol(s) with \`git grep -nw\` across the checkout.`);
    L.push('References remaining => a removal may be breaking; none => the removal is likely safe.', '');
    for (const sym of symbols) {
      const g = sh('git', ['-C', repoDir, 'grep', '-nw', '-e', sym]);
      const all = (g.stdout || '').split('\n').filter(Boolean);
      L.push(`## ${sym}`);
      if (all.length) {
        for (const h of all.slice(0, MAX_GREP_LINES)) L.push(`  ${h}`);
        if (all.length > MAX_GREP_LINES) L.push(`  … (${all.length - MAX_GREP_LINES} more)`);
      } else {
        L.push('  no remaining references found');
      }
      L.push('');
    }
  }
  callersPath = path.join(outputDir, 'callers.md');
  fs.writeFileSync(callersPath, L.join('\n') + '\n');
}

// --- context tier 2 (fallback): full changed-file contents at head, if no checkout ---
let filesDir = null;
if (contextLevel !== 'repo') {
  filesDir = path.join(outputDir, 'files');
  let wrote = 0;
  for (const fp of (pr.files || []).map((f) => f.path).slice(0, MAX_FILES)) {
    const r = sh('gh', ['api', `repos/${owner}/${repo}/contents/${encodeURI(fp)}?ref=${headSha}`, '--jq', '.content // ""']);
    if (!r.ok) continue;
    const b64 = (r.stdout || '').replace(/\s/g, '');
    if (!b64) continue;
    let buf;
    try { buf = Buffer.from(b64, 'base64'); } catch { continue; }
    if (buf.length > MAX_FILE_BYTES || buf.includes(0)) continue; // skip oversized / binary
    const dest = path.join(filesDir, fp);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest, buf);
    wrote += 1;
  }
  if (wrote > 0) contextLevel = 'files';
  else filesDir = null;
}

// --- meta.json ---
const meta = {
  prUrl,
  owner,
  repo,
  repository,
  number,
  title,
  changedFiles,
  headSha,
  baseSha: pr.baseRefOid || '',
  headRef: pr.headRefName || '',
  baseRef: pr.baseRefName || '',
  author: pr.author?.login || '',
  contextLevel,
  diffTruncated: false, // the full diff is always analyzed
  diffChars: diff.length,
  modelLabel,
  fetchedAt: new Date().toISOString(),
};
fs.writeFileSync(path.join(outputDir, 'meta.json'), JSON.stringify(meta, null, 2));

// --- pre-render the three specialist prompts, each with a context-aware CONTEXT block ---
const SPECIALISTS = ['risk', 'configuration', 'observability'];
const agentOutputs = {};
for (const name of SPECIALISTS) {
  const md = fs.readFileSync(path.join(AGENTS_DIR, `${name}.md`), 'utf8');
  const outPath = path.join(outputDir, `${name}.json`);
  const context = renderContextBlock(contextLevel, name, {
    prContextPath, diffPath, callersPath, filesDir,
    repoDir: contextLevel === 'repo' ? repoDir : null,
  });
  const prompt = renderAgentPrompt(md, { repo: repository, number, title, diffPath, context })
    + outputInstruction(outPath);
  fs.writeFileSync(path.join(promptsDir, `${name}.txt`), prompt);
  agentOutputs[name] = outPath;
}

const result = {
  status: 'ok',
  repository,
  number,
  prUrl,
  title,
  changedFiles,
  headSha,
  contextLevel,
  outputDir,
  promptsDir,
  diffPath,
  prContextPath,
  callersPath,
  filesDir,
  repoDir: contextLevel === 'repo' ? repoDir : null,
  metaPath: path.join(outputDir, 'meta.json'),
  specialists: SPECIALISTS,
  agentOutputs,
  synthesizerOutput: path.join(outputDir, 'synthesizer.json'),
  reviewJson: path.join(outputDir, 'review.json'),
  reportMd: path.join(outputDir, 'report.md'),
};

console.log(`→ Fetched ${repository}#${number} '${title}' — ${changedFiles} changed files, ${diff.length} diff chars`);
console.log(`→ Context level: ${contextLevel}${contextLevel === 'repo' ? ` (checkout at ${headSha.slice(0, 12)})` : ''}`);
console.log(`→ Prompts written to ${promptsDir}`);
console.log('RESULT_JSON:');
console.log(JSON.stringify(result, null, 2));
console.log('==== END RESULT_JSON ====');
