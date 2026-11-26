import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { access, readFile, rename, rm } from 'node:fs/promises';
import { extname, join, normalize, resolve, sep } from 'node:path';

const ROOT = resolve(process.env.OUT_DIR ?? 'dist');
const NEXT = `${ROOT}.next`;
const PORT = Number(process.env.PORT ?? 7000);
const REBUILD_EVERY_MS = Number(process.env.REBUILD_HOURS ?? 24) * 3600000;
const TYPES = { '.json': 'application/json; charset=utf-8', '.html': 'text/html; charset=utf-8' };

let building = false;

function rebuild() {
  if (building) return;
  building = true;
  console.log('Building catalog...');
  const child = spawn(process.execPath, ['build.mjs'], { stdio: 'inherit', env: { ...process.env, OUT_DIR: NEXT } });
  child.on('exit', async (code) => {
    try {
      if (code === 0) {
        await rm(ROOT, { recursive: true, force: true });
        await rename(NEXT, ROOT);
        console.log('Catalog updated.');
      } else {
        console.error(`Build failed (exit ${code}); keeping the previous catalog.`);
      }
    } catch (err) {
      console.error(`Could not publish the new catalog: ${err.message}`);
    } finally {
      building = false;
    }
  });
}

const exists = (p) => access(p).then(() => true, () => false);

createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', '*');
  if (req.method === 'OPTIONS') { res.writeHead(204).end(); return; }
  if (req.method !== 'GET') { res.writeHead(405).end(); return; }
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(ROOT, path === '/' ? 'index.html' : path));
    if (file !== ROOT && !file.startsWith(ROOT + sep)) { res.writeHead(403).end(); return; }
    const body = await readFile(file);
    res.writeHead(200, {
      'content-type': TYPES[extname(file)] ?? 'application/octet-stream',
      'cache-control': 'public, max-age=3600',
    });
    res.end(body);
  } catch {
    if (building && !(await exists(join(ROOT, 'manifest.json')))) {
      res.writeHead(503, { 'content-type': 'text/plain', 'retry-after': '300' }).end('Catalog is building, retry in a few minutes.');
      return;
    }
    res.writeHead(404, { 'content-type': 'application/json' }).end('{"metas":[]}');
  }
}).listen(PORT, async () => {
  console.log(`Cartel Cinematic on http://localhost:${PORT}/  (manifest: /manifest.json)`);
  if (!(await exists(join(ROOT, 'manifest.json')))) rebuild();
  setInterval(rebuild, REBUILD_EVERY_MS).unref();
});
