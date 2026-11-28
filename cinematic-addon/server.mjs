import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { access, readFile, rename, rm } from 'node:fs/promises';
import { extname, join, normalize, resolve, sep } from 'node:path';

const ROOT = resolve(process.env.OUT_DIR ?? 'dist');
const NEXT = `${ROOT}.next`;
const PORT = Number(process.env.PORT ?? 7000);
const REBUILD_EVERY_MS = Number(process.env.REBUILD_HOURS ?? 24) * 3600000;
const RETRY_FAILED_MS = Number(process.env.RETRY_MINUTES ?? 10) * 60000;
const TYPES = { '.json': 'application/json; charset=utf-8', '.html': 'text/html; charset=utf-8' };
const LOOPBACK = ['127.0.0.1', '::1', '::ffff:127.0.0.1'];

let building = false;
let retryTimer;
const exists = (p) => access(p).then(() => true, () => false);
const manifestReady = () => exists(join(ROOT, 'manifest.json'));

function rebuild() {
  if (building) return;
  building = true;
  clearTimeout(retryTimer);
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
        if (!(await manifestReady())) {
          console.error(`No catalog yet; retrying in ${RETRY_FAILED_MS / 60000} minutes.`);
          retryTimer = setTimeout(rebuild, RETRY_FAILED_MS);
        }
      }
    } catch (err) {
      console.error(`Could not publish the new catalog: ${err.message}`);
    } finally {
      building = false;
    }
  });
}

const app = createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', '*');
  const reply = (code, body, type = 'text/plain; charset=utf-8', extra = {}) => {
    res.writeHead(code, code === 204 ? {} : { 'content-type': type, ...extra });
    res.end(req.method === 'HEAD' || code === 204 ? undefined : body);
  };
  if (req.method === 'OPTIONS') { reply(204); return; }
  if (req.method === 'POST' && req.url === '/rebuild') {
    if (!LOOPBACK.includes(req.socket.remoteAddress)) { reply(403, 'rebuild is local-only'); return; }
    const already = building;
    rebuild();
    reply(202, JSON.stringify({ accepted: true, alreadyBuilding: already }), 'application/json');
    return;
  }
  if (req.method === 'GET' && req.url.split('?')[0] === '/health') {
    reply(200, JSON.stringify({ building, catalogReady: await manifestReady() }), 'application/json');
    return;
  }
  if (req.method !== 'GET' && req.method !== 'HEAD') { reply(405); return; }

  let path;
  try {
    path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  } catch {
    reply(400, 'malformed request path');
    return;
  }
  const file = normalize(join(ROOT, path === '/' ? 'index.html' : path));
  if (file !== ROOT && !file.startsWith(ROOT + sep)) { reply(403); return; }
  try {
    reply(200, await readFile(file), TYPES[extname(file)] ?? 'application/octet-stream', { 'cache-control': 'public, max-age=3600' });
  } catch {
    if (path === '/') {
      try {
        const page = await readFile(new URL('./site/index.html', import.meta.url));
        reply(200, page, TYPES['.html'], { 'cache-control': 'no-cache' });
        return;
      } catch { /* fall through to the build/404 handling below */ }
    }
    if (building && !(await manifestReady())) {
      reply(503, 'Catalog is building, retry in a few minutes.', 'text/plain; charset=utf-8', { 'retry-after': '300' });
      return;
    }
    reply(404, path.startsWith('/catalog/') ? '{"metas":[]}' : '{}', 'application/json');
  }
});

app.listen(PORT, async () => {
  console.log(`Cartel Cinematic on http://localhost:${app.address().port}/  (manifest: /manifest.json)`);
  if (!(await manifestReady())) rebuild();
  setInterval(rebuild, REBUILD_EVERY_MS).unref();
});
