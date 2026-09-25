import { createDemoServer, parseDemoArguments, DEMO_HOST, DEMO_PORT } from './lib/demo-server.mjs';

try {
  const options = parseDemoArguments(process.argv.slice(2));
  if (options.help) {
    console.log('node scripts/serve-demo.mjs --confirm-local-demo --directory <absolute frontend/dist path>');
    console.log('Read-only local demo: http://127.0.0.1:15174; /api proxies only to http://127.0.0.1:18080. No port overrides.');
  } else {
    const server = await createDemoServer(options);
    await new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(DEMO_PORT, DEMO_HOST, resolve);
    });
    console.log('Local graduation demo ready: http://127.0.0.1:15174 (backend http://127.0.0.1:18080).');
    const stop = () => {
      server.close();
      setTimeout(() => server.closeAllConnections(), 5000).unref();
    };
    process.once('SIGINT', stop);
    process.once('SIGTERM', stop);
  }
} catch {
  console.error('Local demo could not start. Check explicit confirmation, absolute built frontend directory, and port 15174 availability. Use --help for usage.');
  process.exitCode = 1;
}
