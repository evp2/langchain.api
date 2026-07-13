// Shared helpers for the pr-review skill scripts.
// No third-party deps — Node built-ins only.

import { execFileSync } from 'node:child_process';

/** Severity order (CRITICAL < HIGH < MEDIUM < LOW). */
export const SEV_ORDER = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

/** Rank a severity by its position in SEV_ORDER; unknown/blank sorts last. */
export function sevRank(sev) {
  const i = SEV_ORDER.indexOf(String(sev || '').toUpperCase());
  return i === -1 ? Number.MAX_SAFE_INTEGER : i;
}

/** Stable sort a findings array by severity (CRITICAL first). */
export function prioritize(findings) {
  return [...(findings || [])].sort((a, b) => sevRank(a?.severity) - sevRank(b?.severity));
}

/** Count findings per severity, initializing all four buckets to 0. */
export function severityCounts(findings) {
  const counts = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 };
  for (const f of findings || []) {
    const s = String(f?.severity || '').toUpperCase();
    if (s in counts) counts[s] += 1;
  }
  return counts;
}

/**
 * Split an agent .md file into its SYSTEM and USER sections. The markers must each be alone on
 * their own line, so intro prose that *mentions* the markers inline (in backticks) is ignored.
 */
export function parseAgentMd(mdText) {
  const sysM = /^<!-- SYSTEM -->[ \t]*$/m.exec(mdText);
  const usrM = /^<!-- USER -->[ \t]*$/m.exec(mdText);
  if (!sysM) throw new Error('agent file missing a `<!-- SYSTEM -->` line');
  if (!usrM || usrM.index < sysM.index) throw new Error('agent file missing/invalid `<!-- USER -->` line');
  const system = mdText.slice(sysM.index + sysM[0].length, usrM.index).trim();
  const user = mdText.slice(usrM.index + usrM[0].length).trim();
  return { system, user };
}

/** Replace every {{key}} placeholder with vars[key] (missing keys left untouched). */
export function substitute(text, vars) {
  return text.replace(/\{\{(\w+)\}\}/g, (m, k) => (k in vars ? String(vars[k]) : m));
}

/** Render a full self-contained prompt (system + user) with placeholders substituted. */
export function renderAgentPrompt(mdText, vars) {
  const { system, user } = parseAgentMd(mdText);
  return `${substitute(system, vars)}\n\n${substitute(user, vars)}\n`;
}

/** The tail appended to a rendered prompt telling the subagent exactly where to write its JSON. */
export function outputInstruction(absOutPath) {
  return [
    '',
    '------------------------------------------------------------',
    'OUTPUT INSTRUCTION (follow exactly):',
    'Produce ONLY the raw JSON object specified above — no prose, no markdown, no code fences.',
    `Write that JSON, and nothing else, to this exact file path using the Write tool:`,
    `  ${absOutPath}`,
    'Do not print the JSON into the conversation. Do not create any other files.',
    'Reply to the orchestrator with a single short line confirming the file was written.',
    '',
  ].join('\n');
}

/**
 * Strip a surrounding markdown code fence if present, then parse JSON. Falls back to extracting
 * the outermost { ... } object, tolerating stray prose or fences around the JSON.
 */
export function parseJsonLoose(raw) {
  if (raw == null) throw new Error('empty input');
  let s = String(raw).trim();
  const fence = /^```(?:json)?\s*([\s\S]*?)\s*```$/i.exec(s);
  if (fence) s = fence[1].trim();
  try {
    return JSON.parse(s);
  } catch {
    const first = s.indexOf('{');
    const last = s.lastIndexOf('}');
    if (first !== -1 && last > first) return JSON.parse(s.slice(first, last + 1));
    throw new Error('could not parse JSON from model output');
  }
}

/** Repo root via git, falling back to CWD. Keeps artifacts anchored regardless of shell CWD. */
export function repoRoot() {
  try {
    return execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim();
  } catch {
    return process.cwd();
  }
}

/** Filesystem-safe output-dir slug for a PR. */
export function prSlug(owner, repo, number) {
  return `${owner}-${repo}-pr-${number}`;
}

/**
 * Run a command and return { ok, stdout, stderr } instead of throwing. Used for the OPTIONAL context
 * steps (clone, fetch, grep) so a failure only lowers the context level — it never aborts the review.
 */
export function sh(cmd, args, opts = {}) {
  try {
    const stdout = execFileSync(cmd, args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...opts });
    return { ok: true, stdout, stderr: '' };
  } catch (e) {
    return { ok: false, stdout: (e.stdout || '').toString(), stderr: (e.stderr || e.message || '').toString() };
  }
}

// Identifiers that can appear right after a definition keyword but are not symbols worth scanning.
const SYMBOL_STOP = new Set(['if', 'for', 'while', 'switch', 'return', 'new', 'function', 'const',
  'let', 'var', 'await', 'typeof', 'class', 'else', 'catch', 'try']);

/**
 * Best-effort extraction of removed/renamed public symbol names from a unified diff. Scans DELETED
 * lines (leading `-`, excluding the `---` file header) for common definition shapes across languages.
 * Heuristic — it seeds a caller scan (bounded and informational), so occasional misses/extras are fine.
 */
export function extractRemovedSymbols(diff) {
  const patterns = [
    /\bfunction\s+([A-Za-z_$][\w$]*)/,                                   // JS/TS  function foo
    /\bdef\s+([A-Za-z_][\w]*)/,                                          // Python def foo
    /\bfunc\s+(?:\([^)]*\)\s*)?([A-Za-z_][\w]*)\s*\(/,                   // Go     func foo / (r R) Foo
    /\bclass\s+([A-Za-z_][\w]*)/,                                        // class Foo
    /\b(?:interface|enum|trait|struct|type)\s+([A-Za-z_][\w]*)/,         // interface/enum/... Foo
    /\b(?:export\s+)?(?:default\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=/, // const foo =
    /\b(?:public|protected|private|internal)\s+(?:static\s+|final\s+|abstract\s+|synchronized\s+|async\s+)*[\w<>\[\],.\s]+?\s+([A-Za-z_][\w]*)\s*\(/, // Java/C# method
  ];
  const names = new Set();
  for (const raw of String(diff || '').split(/\r?\n/)) {
    if (!raw.startsWith('-') || raw.startsWith('---')) continue;
    const line = raw.slice(1);
    for (const re of patterns) {
      const m = re.exec(line);
      if (m && m[1] && m[1].length > 2 && !SYMBOL_STOP.has(m[1])) names.add(m[1]);
    }
  }
  return [...names];
}

// Per-dimension verification protocol lines, injected into the prompt when a repo checkout exists.
const DIMENSION_PROTOCOL = {
  risk: [
    'Read the full body of each changed file in the checkout (not just the diff hunks) for context.',
    'Before flagging a REMOVED or RENAMED public symbol as a breaking change:',
    '  - Check callers.md for remaining references to it in the repo at the PR head.',
    '  - Only flag it if references remain OR no equivalent replacement survives. If callers.md shows',
    '    no remaining references and the PR description/commits indicate a deliberate removal or',
    '    de-duplication, do NOT flag it (at most a LOW/informational note).',
    '  - For anything callers.md did not cover, rg the checkout yourself before flagging.',
  ],
  configuration: [
    'Read the full changed config files in the checkout, not just the diff hunks.',
    'Before flagging a missing default/override or an environment mismatch, rg the changed key across',
    'the repo to see where it is consumed and whether sibling env configs already define it.',
  ],
  observability: [
    'rg the repo for its existing logging / metrics / tracing conventions before demanding more.',
    'Check whether a new failure path is already covered by logging/metrics up or down the call stack.',
    'Only flag gaps relative to the repo’s actual observability patterns, not an abstract ideal.',
  ],
};

/**
 * Render the "CONTEXT & VERIFICATION" block injected into a specialist prompt (the `{{context}}`
 * placeholder). `level` is 'repo' | 'files' | 'diff'; `dimension` is 'risk'|'configuration'|'observability';
 * `paths` supplies the artifact locations.
 */
export function renderContextBlock(level, dimension, paths) {
  const L = ['## CONTEXT & VERIFICATION', ''];
  L.push(`- PR metadata & author intent (description + commit messages): ${paths.prContextPath}`);
  if (level === 'repo') {
    L.push(`- Full repository checked out at the PR head: ${paths.repoDir}`);
    L.push(`  Use Read and \`rg\` / \`git -C ${paths.repoDir} grep\` inside it to VERIFY findings.`);
    if (dimension === 'risk' && paths.callersPath) {
      L.push(`- Remaining-reference scan for removed/renamed symbols: ${paths.callersPath}`);
    }
  } else if (level === 'files') {
    L.push(`- Full contents of the changed files at the PR head: ${paths.filesDir}`);
    L.push('  No repo-wide grep is available at this level — reason from these files plus the diff.');
  } else {
    L.push('- Only the diff is available (no repository checkout).');
  }
  L.push('');
  if (level === 'diff') {
    L.push('Only the diff is available, so you cannot verify repo-wide invariants. Hedge any finding that');
    L.push('depends on outside context (e.g. mark a removal "potentially breaking (unverified)" rather than');
    L.push('a confirmed breaking change) and lower its severity/confidence accordingly.');
  } else {
    L.push('Verification protocol for this dimension:');
    for (const line of DIMENSION_PROTOCOL[dimension] || []) L.push(`  ${line}`);
  }
  L.push('');
  L.push('Rules:');
  L.push('  - Base every finding on the PR’s CHANGES (the diff). Repo context is for verification and');
  L.push('    calibration only — do NOT review or report on unchanged code.');
  L.push('  - Prefer `rg` over broad file reads; keep to roughly 15-20 tool calls; stay within the changed');
  L.push('    files and their references.');
  L.push('  - In each finding’s description, briefly cite what you checked (e.g. "verified: 0 remaining');
  L.push('    references to parseA in the checkout").');
  return L.join('\n');
}
