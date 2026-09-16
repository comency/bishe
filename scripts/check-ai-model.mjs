// Explicit real-model evaluation. Only synthetic inputs; no DB access, download or cloud calls.
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { freemem } from 'node:os';
import { resolve } from 'node:path';
if (![3, 4].includes(process.argv.length) || process.argv[2] !== '--confirm-local-model-trial' || (process.argv[3] && process.argv[3] !== '--diagnostic-warm')) {
  console.error('Usage: node scripts/check-ai-model.mjs --confirm-local-model-trial [--diagnostic-warm] (requires manually started local Ollama and pre-downloaded qwen3:1.7b).'); process.exit(1);
}
// Diagnostic mode is NOT the application policy: allow 90s initial loading and
// retain the model for warm latency measurements; always unload in finally.
const diagnosticWarm = process.argv[3] === '--diagnostic-warm';
const base = 'http://127.0.0.1:11434', model = 'qwen3:1.7b';
const output = resolve('.local/ai-model-trial', new Date().toISOString().replace(/[:.]/g, '-'));
await mkdir(output, { recursive: true });
async function api(path, body, timeout = 20000) {
  const r = await fetch(base + path, { method: body === undefined ? 'GET' : 'POST', redirect: 'error', headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(timeout) });
  if (!r.ok) throw new Error(`Local provider ${path}: HTTP ${r.status}`);
  return r.json();
}
const version = await api('/api/version');
assert.equal(version.version, '0.34.1', 'Trial runtime version must match the verified portable build');
assert.equal((await api('/api/ps')).models.length, 0, 'An existing loaded model must not be disturbed by this trial');
const tags = await api('/api/tags');
const selected = tags.models.find(m => m.name === model);
assert.ok(selected && selected.details.quantization_level === 'Q4_K_M', 'Expected pre-downloaded Q4_K_M model; never pulls automatically');
assert.equal(selected.digest, '8f68893c685c3ddff2aa3fffce2aa60a30bb2da65ca488b61fff134a4d1730e7', 'Model digest changed; review before evaluating a new build');
const info = await api('/api/show', { model });
assert.match(info.license, /Apache License/);
const contentPolicy = JSON.parse(await readFile('src/main/resources/ai-content-policy.json', 'utf8'));
const cases = [...JSON.parse(await readFile('scripts/fixtures/ai-evaluation-cases.json', 'utf8')),
  ...JSON.parse(await readFile('scripts/fixtures/ai-evaluation-unseen-v2.json', 'utf8')),
  ...JSON.parse(await readFile('scripts/fixtures/ai-evaluation-holdout-v2.json', 'utf8'))];
const guidanceFormat = { type: 'object', properties: { statements: { type: 'array', minItems: 1, maxItems: 3,
  items: { type: 'string', enum: contentPolicy.guideStatements } } }, required: ['statements'], additionalProperties: false };
const labeledStatements = contentPolicy.guideStatements.map((statement, i) => `【${contentPolicy.guideTopics[i]}】${statement}`);
// os.freemem avoids starting a PowerShell process for each admission decision.
const samples = []; let sampleErrors = '', sampling = null;
async function sampleResources() {
  if (sampling) return sampling;
  sampling = (async () => {
    try {
      const gpu = await promisify(execFile)('nvidia-smi.exe', ['--query-gpu=memory.used,memory.free', '--format=csv,noheader,nounits'], { windowsHide: true, timeout: 5000 });
      const [used, free] = gpu.stdout.trim().split(/\r?\n/)[0].split(',').map(Number);
      if (![used, free].every(Number.isFinite)) throw new Error('GPU sample invalid');
      samples.push({ at: new Date().toISOString(), freeMemoryMiB: +(freemem() / 1024 ** 2).toFixed(1), gpuUsedMiB: used, gpuFreeMiB: free });
    } catch (e) { sampleErrors += e.message; }
  })();
  try { await sampling; } finally { sampling = null; }
}
await sampleResources();
const sampleTimer = setInterval(() => { void sampleResources(); }, 1000);
const results = []; let failure, attemptedGenerations = 0;
try {
  for (const test of cases) {
    const freeBytes = freemem();
    if (!Number.isFinite(freeBytes) || freeBytes < 4 * 1024 ** 3) {
      results.push({ id: test.id, error: 'RESOURCE_LIMIT: below 4 GiB free memory; inference not attempted', freeBytes });
      console.error(results.at(-1).error);
      failure = true; break;
    }
    const polish = test.kind === 'polish';
    const system = polish ? contentPolicy.polishPrompt : contentPolicy.chatPrompt + '\n' + labeledStatements.join('\n');
    const start = performance.now();
    try {
      attemptedGenerations++;
      const response = await api('/api/chat', { model, messages: [{ role: 'system', content: system }, { role: 'user', content: test.input }], stream: false, think: false, keep_alive: diagnosticWarm ? 60 : 0, ...(!polish ? {format: guidanceFormat} : {}), options: { temperature: polish ? 0.2 : 0, num_ctx: 4096, num_predict: 512 } }, diagnosticWarm && attemptedGenerations === 1 ? 90000 : 20000);
      const rawContent = response.message?.content ?? '';
      let content = rawContent, structuredParseError = false;
      // Extraction for diagnostics only, NOT duplicate-key/enum/relevance validation or the Java guard.
      if (!polish) {
        try {
          const decoded = JSON.parse(rawContent);
          if (!decoded || Object.keys(decoded).length !== 1 || !Array.isArray(decoded.statements) ||
              !decoded.statements.every(x => typeof x === 'string')) throw new Error('Invalid shape');
          content = decoded.statements.join('\n');
        } catch { structuredParseError = true; }
      }
      const flags = [...test.required.filter(s => !content.includes(s)).map(s => `missing:${s}`), ...(test.forbidden ?? []).filter(s => content.includes(s)).map(s => `forbidden:${s}`)];
      if (structuredParseError) flags.push('invalid-structured-json');
      if (!response.done || response.done_reason !== 'stop' || !content.trim() || response.message?.tool_calls?.length || /<think>|<\/think>/.test(content)) flags.push('invalid-completion');
      if (flags.length) failure = true;
      results.push({ ...test, elapsedMs: Math.round(performance.now() - start), rawContent, content, doneReason: response.done_reason, loadMs: Math.round(response.load_duration / 1e6), promptTokens: response.prompt_eval_count, promptMs: Math.round(response.prompt_eval_duration / 1e6), tokens: response.eval_count, tokensPerSecond: response.eval_duration ? +(response.eval_count * 1e9 / response.eval_duration).toFixed(1) : null, flags, loaded: diagnosticWarm ? await api('/api/ps') : undefined });
      console.log(`${test.id}: ${results.at(-1).elapsedMs}ms; screeningFlags=${flags.length}`);
    } catch (e) { results.push({ ...test, elapsedMs: Math.round(performance.now() - start), error: e.message }); failure = true; break; }
  }
} finally {
  // Unload only the selected trial model; never stop an unrelated server or model.
  if (attemptedGenerations) {
    try { await api('/api/generate', { model, keep_alive: 0 }, 10000); } catch (e) { failure = true; console.error(`Unload unconfirmed: ${e.message}`); }
  }
  const loaded = await api('/api/ps').catch(() => null);
  if (!loaded || loaded.models.some(m => m.name === model)) failure = true;
  clearInterval(sampleTimer); if (sampling) await sampling; await sampleResources();
  const summary = { at: new Date().toISOString(), model, version, digest: selected.digest, details: selected.details, sizeBytes: selected.size, source: 'https://ollama.com/library/qwen3:1.7b', diagnosticWarm, parameters: { context: 4096, output: 512, temperature: {polish: 0.2, chat: 0}, structuredChat: true, think: false, keepAlive: diagnosticWarm ? 60 : 0, firstRequestTimeoutMs: diagnosticWarm ? 90000 : 20000 }, modelCalled: attemptedGenerations > 0, attemptedGenerations, humanReviewRequired: true, automaticScreeningOnly: true, results, loadedAfter: loaded, samplerErrors: sampleErrors, resources: samples.length ? { count: samples.length, minimumFreeMemoryMiB: Math.min(...samples.map(s => s.freeMemoryMiB)), peakGpuUsedMiB: Math.max(...samples.map(s => s.gpuUsedMiB)) } : null };
  summary.contentPolicyVersion = contentPolicy.version;
  summary.scope = 'Raw provider diagnostics only; does not execute the Java content guard. Use RUN_AI_MODEL_TESTS for actual adapter acceptance.';
  await writeFile(resolve(output, 'result.json'), JSON.stringify(summary, null, 2));
  await writeFile(resolve(output, 'resources.jsonl'), samples.map(s => JSON.stringify(s)).join('\n') + '\n');
  console.log(`Evidence: ${output}. Synthetic examples only; human review still required.`);
  if (!samples.length || sampleErrors || failure) process.exitCode = 1;
}
