#!/usr/bin/env node
// Step 2 helper: build the synthesizer's self-contained prompt by injecting the three specialist
// JSON blobs into agents/synthesizer.md. Run AFTER the three specialist subagents have written
// their {risk,configuration,observability}.json files.
//
// Usage: build-synth-prompt.mjs <outputDir>

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderAgentPrompt, outputInstruction } from './lib.mjs';

const SKILL_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const AGENTS_DIR = path.join(SKILL_DIR, 'agents');

const outputDir = process.argv[2];
if (!outputDir) {
  console.error('⚠ Usage: build-synth-prompt.mjs <outputDir>');
  process.exit(1);
}

const meta = JSON.parse(fs.readFileSync(path.join(outputDir, 'meta.json'), 'utf8'));

// Pass each specialist's raw JSON text straight through (the synthesizer prompt asks for the raw
// analysis JSON). If a specialist file is missing, substitute an empty analysis so a single failed
// specialist doesn't sink the synthesis.
const EMPTY = '{"summary":"(not provided)","findings":[]}';
function specialistJson(name) {
  const p = path.join(outputDir, `${name}.json`);
  try {
    const raw = fs.readFileSync(p, 'utf8').trim();
    return raw || EMPTY;
  } catch {
    return EMPTY;
  }
}

const vars = {
  repo: meta.repository,
  number: meta.number,
  title: meta.title,
  chr: specialistJson('risk'),
  cfg: specialistJson('configuration'),
  ora: specialistJson('observability'),
};

const md = fs.readFileSync(path.join(AGENTS_DIR, 'synthesizer.md'), 'utf8');
const outPath = path.join(outputDir, 'synthesizer.json');
const prompt = renderAgentPrompt(md, vars) + outputInstruction(outPath);

const promptsDir = path.join(outputDir, 'prompts');
fs.mkdirSync(promptsDir, { recursive: true });
const synthPromptPath = path.join(promptsDir, 'synthesizer.txt');
fs.writeFileSync(synthPromptPath, prompt);

console.log(`→ Synthesizer prompt written to ${synthPromptPath}`);
console.log(`SYNTH_PROMPT: ${synthPromptPath}`);
