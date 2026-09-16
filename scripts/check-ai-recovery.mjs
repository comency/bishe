// Explicit runtime-only recovery diagnostic. No DB, application enabling or hidden retries.
import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { freemem } from 'node:os';
import { resolve } from 'node:path';

if (process.argv.length !== 3 || process.argv[2] !== '--confirm-local-model-trial') {
  throw new Error('Pass --confirm-local-model-trial; verified local runtime must already be running.');
}
const base = 'http://127.0.0.1:11434', model = 'qwen3:1.7b';
const output = resolve('.local/ai-recovery-profile', new Date().toISOString().replace(/[:.]/g, '-'));
await mkdir(output, { recursive: true });
const policy = JSON.parse(await readFile('src/main/resources/ai-content-policy.json', 'utf8'));
const input = '图书馆 捡到蓝色水杯，杯底有划痕';
const results = [], memorySamples = [];
let ownsTrial = false, failure, timer;
const freeMiB = () => Math.round(freemem() / 1024 ** 2);
async function api(path, body, timeout = 5000) {
  const response = await fetch(base + path, { method: body ? 'POST' : 'GET', redirect: 'error',
    headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(timeout) });
  assert.equal(response.status, 200, `Local runtime ${path} status`);
  return response.json();
}
try {
  assert.equal((await api('/api/version')).version, '0.34.1');
  assert.equal((await api('/api/ps')).models.length, 0, 'Do not disturb preexisting model');
  assert.ok((await api('/api/tags')).models.some(item => item.name === model && item.digest === '8f68893c685c3ddff2aa3fffce2aa60a30bb2da65ca488b61fff134a4d1730e7'));
  ownsTrial = true;
  timer = setInterval(() => memorySamples.push({ at: new Date().toISOString(), freeMiB: freeMiB() }), 50);
  for (let round = 1; round <= 4; round++) {
    const beforeMiB = freeMiB();
    assert.ok(beforeMiB >= 4096, '4 GiB gate before every diagnostic generation');
    const start = performance.now();
    const response = await api('/api/chat', { model, messages: [{ role: 'system', content: policy.polishPrompt }, { role: 'user', content: input }],
      stream: false, think: false, keep_alive: 0, options: { temperature: 0.2, num_ctx: 4096, num_predict: 512 } }, 20000);
    const ended = performance.now();
    const result = { round, beforeMiB, afterMiB: freeMiB(), elapsedMs: Math.round(ended - start),
      loadMs: Math.round(response.load_duration / 1e6), doneReason: response.done_reason,
      content: response.message?.content, samples: [] };
    results.push(result);
    assert.ok(response.done && response.done_reason === 'stop' && response.message?.role === 'assistant');
    assert.equal(result.content?.replace(/[，。\s]/g, ''), input.replace(/[，。\s]/g, ''), 'Synthetic description facts preserved; not Java policy acceptance');
    for (const offset of [0, 50, 100, 200, 400, 800, 1600]) {
      const remaining = offset - (performance.now() - ended);
      if (remaining > 0) await new Promise(resolve => setTimeout(resolve, remaining));
      const memoryBeforeProbeMiB = freeMiB();
      const loaded = (await api('/api/ps')).models.some(item => item.name === model);
      result.samples.push({ elapsedMs: Math.round(performance.now() - ended), freeBeforeProbeMiB: memoryBeforeProbeMiB, freeAfterProbeMiB: freeMiB(), loaded });
    }
    console.log(`Recovery sample ${round}: generation=${result.elapsedMs}ms memory=${beforeMiB}->${result.afterMiB}MiB; raw diagnostic only`);
  }
} catch (error) {
  failure = error.message; process.exitCode = 1; console.error(failure);
} finally {
  clearInterval(timer);
  if (ownsTrial) {
    try { await api('/api/generate', { model, keep_alive: 0 }); assert.equal((await api('/api/ps')).models.length, 0); }
    catch { failure ??= 'Selected model unload unconfirmed'; process.exitCode = 1; }
  }
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ at: new Date().toISOString(), completed: !failure, failure: failure ?? null,
    mode: 'runtime-only-recovery-profile', results, memorySamples,
    limitations: ['No application HTTP or Java content guard', 'Fixed 1600ms observation between requests, not rapid-use success proof', 'Host memory changes are not uniquely attributable to Ollama'] }, null, 2));
  console.log(`${failure ? 'FAIL' : 'PASS'} runtime recovery diagnostic: ${output}`);
}
