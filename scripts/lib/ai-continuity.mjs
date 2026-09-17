// Bounded sequential model requests, optional closed-loop business reads, no retries or admission waits.
export async function runRapidSequence({ count, generate, accept, read, workers = 0, maxReadsPerWorker = 40, evidence }) {
  if (!Number.isInteger(count) || count < 1 || count > 12 || !Number.isInteger(workers) || workers < 0 || workers > 4 ||
      !Number.isInteger(maxReadsPerWorker) || maxReadsPerWorker < 1 || maxReadsPerWorker > 100 ||
      !Array.isArray(evidence) || typeof generate !== 'function' || typeof accept !== 'function' || (workers && typeof read !== 'function')) {
    throw new Error('Invalid bounded continuity configuration');
  }
  for (let index = 0; index < count; index++) {
    const started = performance.now();
    const sample = { index, at: new Date().toISOString(), workers, reads: [], generated: false, failureType: null };
    evidence.push(sample);
    let settled = false, readFailure;
    // Start generation immediately and attach both handlers before launching reads.
    const generation = (async () => generate(index))().then(value => ({ value }), error => ({ error }))
      .finally(() => { settled = true; });
    const readers = Array.from({ length: workers }, async (_, worker) => {
      for (let n = 0; n < maxReadsPerWorker && !settled && !readFailure; n++) {
        const begin = performance.now();const observation = { worker, startedWhilePending: !settled, success: false };
        sample.reads.push(observation);
        try { await read(index, worker, n); observation.success = true; }
        catch (error) { readFailure ??= error; }
        finally { observation.elapsedMs = performance.now() - begin; observation.finishedWhilePending = !settled; }
      }
    });
    // Drain already-started operations even on a read failure. Never leave inference in the background.
    await Promise.all(readers);
    const outcome = await generation;
    sample.elapsedMs = performance.now() - started;
    try {
      if (outcome.error) throw outcome.error;
      accept(outcome.value, index);sample.generated = true;
      if (readFailure) throw readFailure;
    } catch (error) { sample.failureType = error.name; throw error; }
  }
  return evidence;
}
