import test from 'node:test';
import assert from 'node:assert/strict';
import { runRapidSequence } from '../lib/ai-continuity.mjs';

const accept = value => assert.equal(value, 'ok');
test('sequential calls never overlap, retry, or sleep; exact count retained', async () => {
  let active = 0, calls = 0;const evidence = [];
  await runRapidSequence({ count: 6, evidence, accept, generate: async () => {
    assert.equal(++active, 1);calls++;await Promise.resolve();active--;return 'ok';
  } });
  assert.equal(calls, 6);assert.equal(evidence.length, 6);assert.ok(evidence.every(x => x.generated && x.reads.length === 0));
});
test('rejected model result stops sequence without another call', async () => {
  let calls = 0;const evidence = [];
  await assert.rejects(runRapidSequence({ count: 6, evidence, accept, generate: () => ++calls === 1 ? 'ok' : 'RESOURCE_LIMIT' }));
  assert.equal(calls, 2);assert.equal(evidence.length, 2);assert.equal(evidence[1].generated, false);
});
test('bounded readers start during generation and drain before next generation', async () => {
  const evidence = [];let release;let reads = 0;
  await runRapidSequence({ count: 1, evidence, accept, workers: 2, maxReadsPerWorker: 2,
    generate: () => new Promise(resolve => { release = resolve; }),
    read: async () => { if (++reads === 4) release('ok');await Promise.resolve(); },
  });
  assert.equal(reads, 4);assert.equal(evidence[0].reads.length, 4);
  assert.ok(evidence[0].reads.every(x => x.startedWhilePending && x.success));
});
test('business read failure waits for in-flight generation and prevents next one', async () => {
  let calls = 0, completed = false;const evidence = [];
  await assert.rejects(runRapidSequence({ count: 3, evidence, accept, workers: 1,
    generate: async () => { calls++;await new Promise(resolve => setImmediate(resolve));completed = true;return 'ok'; },
    read: async () => { throw new Error('synthetic read failure'); },
  }), /synthetic read failure/);
  assert.equal(calls, 1);assert.equal(completed, true);assert.equal(evidence[0].generated, true);
  assert.equal(evidence[0].reads[0].success, false);
});
test('synchronous generation error is handled and no retry is scheduled', async () => {
  const evidence = [];let calls = 0;
  await assert.rejects(runRapidSequence({ count: 2, evidence, accept, generate: () => { calls++;throw new Error('synthetic'); } }), /synthetic/);
  assert.equal(calls, 1);assert.equal(evidence[0].failureType, 'Error');
});
test('invalid limits rejected before callbacks', async () => {
  for (const override of [{ count: 0 }, { count: 13 }, { workers: 5 }, { workers: -1 }, { maxReadsPerWorker: 0 }, { maxReadsPerWorker: 101 }]) {
    await assert.rejects(runRapidSequence({ count: 1, evidence: [], accept, generate: () => assert.fail('must not start'), ...override }), /Invalid bounded/);
  }
});
