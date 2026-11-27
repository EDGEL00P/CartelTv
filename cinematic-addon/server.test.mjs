import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const FIXTURE_BUILD = `import { mkdir, writeFile } from 'node:fs/promises';
const out = process.env.OUT_DIR;
await mkdir(\`\${out}/catalog/movie\`, { recursive: true });
await writeFile(\`\${out}/manifest.json\`, '{"rebuilt":true}');
await writeFile(\`\${out}/catalog/movie/rebuilt.json\`, '{"metas":[1]}');
`;

async function startServer(outDir, { populate = true } = {}) {
  const root = await mkdtemp(join(tmpdir(), 'cinematic-'));
  const fixture = join(root, 'fixture');
  await mkdir(fixture);
  await writeFile(join(fixture, 'build.mjs'), FIXTURE_BUILD);
  if (outDir && populate) {
    await mkdir(join(outDir, 'catalog/movie'), { recursive: true });
    await writeFile(join(outDir, 'manifest.json'), '{"ok":true}');
    await writeFile(join(outDir, 'catalog/movie/top.json'), '{"metas":[]}');
    await writeFile(join(outDir, 'index.html'), '<!doctype html><title>dist</title>');
  }
  const child = spawn(process.execPath, [resolve(HERE, 'server.mjs')], {
    cwd: fixture,
    env: { ...process.env, OUT_DIR: outDir, PORT: '0' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const base = await new Promise((res, rej) => {
    const onLine = (d) => {
      const m = String(d).match(/http:\/\/localhost:(\d+)\//);
      if (m) { child.stdout.off('data', onLine); res(`http://localhost:${m[1]}`); }
    };
    child.stdout.on('data', onLine);
    child.on('exit', () => rej(new Error('server exited before listening')));
    setTimeout(() => rej(new Error('server did not listen')), 10000);
  });
  return { child, base };
}

const get = async (base, path, init) => {
  const res = await fetch(`${base}${path}`, init);
  return { status: res.status, type: res.headers.get('content-type'), body: await res.text() };
};

const healthy = async (base) => {
  for (let i = 0; i < 100; i++) {
    const { building, catalogReady } = await (await fetch(`${base}/health`)).json();
    if (!building && catalogReady) return true;
    await sleep(100);
  }
  return false;
};

test('serves the catalog, meta 404 shape, HEAD, and rejects bad paths', async () => {
  const out = join(await mkdtemp(join(tmpdir(), 'cinematic-')), 'out');
  const { child, base } = await startServer(out);
  try {
    assert.equal((await get(base, '/catalog/movie/top.json')).body, '{"metas":[]}');
    const home = await get(base, '/');
    assert.match(home.type, /^text\/html/);
    assert.match(home.body, /<title>dist<\/title>/);
    assert.equal((await get(base, '/meta/movie/tt0000001.json')).body, '{}');
    const head = await get(base, '/catalog/movie/top.json', { method: 'HEAD' });
    assert.equal(head.status, 200);
    assert.equal(head.body, '');
    assert.match(head.type, /^application\/json/);
    assert.equal((await get(base, '/%zz')).status, 400);
    assert.equal((await get(base, '/..%2f..%2fbuild.mjs')).status, 403);
    assert.equal((await get(base, '/', { method: 'POST' })).status, 405);
  } finally {
    child.kill();
  }
});

test('POST /rebuild swaps in the fresh catalog; missing index falls back to the packaged page', async () => {
  const out = join(await mkdtemp(join(tmpdir(), 'cinematic-')), 'out');
  const { child, base } = await startServer(out);
  try {
    const accepted = await get(base, '/rebuild', { method: 'POST' });
    assert.equal(accepted.status, 202);
    assert.equal(JSON.parse(accepted.body).accepted, true);
    assert.ok(await healthy(base), 'rebuild did not finish');
    assert.equal((await get(base, '/catalog/movie/rebuilt.json')).body, '{"metas":[1]}');
    assert.equal((await get(base, '/catalog/movie/top.json')).body, '{"metas":[]}');
    const home = await get(base, '/');
    assert.equal(home.status, 200);
    assert.match(home.type, /^text\/html/);
    assert.match(home.body, /Cartel Cinematic/);
  } finally {
    child.kill();
  }
});

test('an empty OUT_DIR triggers a first build and serves the packaged page meanwhile', async () => {
  const out = join(await mkdtemp(join(tmpdir(), 'cinematic-')), 'out');
  await mkdir(out);
  const { child, base } = await startServer(out, { populate: false });
  try {
    const home = await get(base, '/');
    assert.equal(home.status, 200);
    assert.match(home.body, /Cartel Cinematic/);
    assert.ok(await healthy(base), 'first build did not finish');
    assert.equal((await get(base, '/catalog/movie/rebuilt.json')).body, '{"metas":[1]}');
  } finally {
    child.kill();
  }
});
