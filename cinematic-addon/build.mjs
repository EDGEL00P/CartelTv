import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { createInterface } from 'node:readline';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream';
import { createGunzip } from 'node:zlib';
import {
  HOLIDAYS, applyExternal, applyFacts, catalogFiles, currentHoliday, imageUrls, pages, stripHtml, weightedRating,
} from './lib.mjs';
import { fillCache, omdbLookup, traktClient } from './external.mjs';
import { HOLIDAY_LABELS, holidayImdbIds, movieFacts } from './wikidata.mjs';

const WIKI_CACHE_FILE = process.env.WIKIDATA_CACHE ?? '.cache/wikidata.json';
const ENRICH_MOVIES = Number(process.env.ENRICH_MOVIES ?? 1500);
const TRAKT_CLIENT_ID = process.env.TRAKT_CLIENT_ID;
const OMDB_API_KEY = process.env.OMDB_API_KEY;
const TRAKT_LISTS = process.env.TRAKT_LISTS ?? '';
const OMDB_DAILY_CAP = Number(process.env.OMDB_DAILY_CAP ?? 900);
const EXTERNAL_CACHE_FILE = process.env.EXTERNAL_CACHE ?? '.cache/external.json';
const EXTERNAL_TTL_MS = 14 * 86400000;
const HOLIDAY_TTL_MS = 3 * 86400000;
const MOVIE_TTL_MS = 14 * 86400000;

const OUT = process.env.OUT_DIR ?? 'dist';
const CACHE_FILE = process.env.TVMAZE_CACHE ?? '.cache/tvmaze.json';
const ENRICH_LIMIT = Number(process.env.ENRICH_LIMIT ?? 300);
const CACHE_TTL_MS = 3 * 86400000;
const MIN_VOTES = { movie: 2000, series: 1000 };
const IMDB = 'https://datasets.imdbws.com';
const UA = 'cartel-cinematic-addon/1.0 (open metadata build)';
const TYPE_MAP = { movie: 'movie', tvSeries: 'series', tvMiniSeries: 'series' };

async function fetchRetry(url, attempts = 3) {
  let lastError;
  for (let i = 1; i <= attempts; i++) {
    try {
      const res = await fetch(url, { headers: { 'user-agent': UA } });
      if (res.ok && res.body) return res;
      lastError = new Error(`GET ${url} -> ${res.status}`);
    } catch (err) {
      lastError = err;
    }
    await new Promise((r) => setTimeout(r, 3000 * i));
  }
  throw lastError;
}

async function* tsvRows(url) {
  const res = await fetchRetry(url);
  const gz = pipeline(Readable.fromWeb(res.body), createGunzip(), (err) => {
    if (err) console.error(`stream error ${url}: ${err.message}`);
  });
  let header = true;
  for await (const line of createInterface({ input: gz, crlfDelay: Infinity })) {
    if (header) { header = false; continue; }
    yield line.split('\t');
  }
}

const nul = (v) => (v === '\\N' || v === undefined ? null : v);

async function loadImdb() {
  const ratings = new Map();
  const floor = Math.min(...Object.values(MIN_VOTES));
  for await (const [id, rating, votes] of tsvRows(`${IMDB}/title.ratings.tsv.gz`)) {
    if (Number(votes) >= floor) ratings.set(id, { rating: Number(rating), votes: Number(votes) });
  }
  const items = [];
  for await (const r of tsvRows(`${IMDB}/title.basics.tsv.gz`)) {
    const type = TYPE_MAP[r[1]];
    const rt = ratings.get(r[0]);
    if (!type || !rt || r[4] === '1' || rt.votes < MIN_VOTES[type]) continue;
    const year = nul(r[5]) ? Number(r[5]) : null;
    const endYear = nul(r[6]) ? Number(r[6]) : null;
    const runtime = nul(r[7]);
    items.push({
      id: r[0], type, name: r[2], year, endYear,
      runtime: runtime ? `${runtime} min` : undefined,
      genres: nul(r[8]) ? r[8].split(',') : [],
      rating: rt.rating, votes: rt.votes,
    });
  }
  return items;
}

function toMeta(it) {
  const img = imageUrls(it.id);
  const releaseInfo = it.year
    ? it.type === 'series' ? `${it.year}\u2013${it.endYear ?? ''}` : String(it.year)
    : undefined;
  return {
    id: it.id, type: it.type, name: it.name,
    ...img, posterShape: 'poster',
    releaseInfo, year: it.year ? String(it.year) : undefined,
    imdbRating: it.fromTrakt ? undefined : it.rating.toFixed(1), imdb_id: it.id,
    genres: it.genres, runtime: it.runtime,
  };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function tvmazeGet(path) {
  for (let attempt = 0; attempt < 4; attempt++) {
    const res = await fetch(`https://api.tvmaze.com${path}`, { headers: { 'user-agent': UA } });
    if (res.status === 404) return null;
    if (res.status === 429) { await sleep(2000 * (attempt + 1)); continue; }
    if (!res.ok) throw new Error(`tvmaze ${path} -> ${res.status}`);
    return res.json();
  }
  throw new Error(`tvmaze ${path} rate limited`);
}

async function loadCache() {
  try { return JSON.parse(await readFile(CACHE_FILE, 'utf8')); } catch { return {}; }
}

async function enrichSeries(id, cache) {
  const hit = cache[id];
  if (hit && Date.now() - hit.at < CACHE_TTL_MS) return hit.data;
  let data = null;
  try {
    const show = await tvmazeGet(`/lookup/shows?imdb=${id}`);
    await sleep(550);
    if (show) {
      const full = await tvmazeGet(`/shows/${show.id}?embed[]=episodes&embed[]=cast`);
      await sleep(550);
      data = full && {
        description: stripHtml(full.summary),
        status: full.status,
        network: full.network?.name ?? full.webChannel?.name,
        country: full.network?.country?.name,
        language: full.language,
        runtime: full.runtime ?? full.averageRuntime,
        poster: full.image?.original,
        cast: (full._embedded?.cast ?? []).slice(0, 12).map((c) => c.person?.name).filter(Boolean),
        videos: (full._embedded?.episodes ?? [])
          .filter((e) => e.season != null && e.number != null)
          .map((e) => ({
            id: `${id}:${e.season}:${e.number}`,
            title: e.name, season: e.season, episode: e.number,
            released: e.airstamp ?? (e.airdate ? `${e.airdate}T00:00:00.000Z` : undefined),
            overview: stripHtml(e.summary), thumbnail: e.image?.medium,
          })),
      };
    }
  } catch (err) {
    console.warn(`enrich ${id} failed: ${err.message}`);
    return hit?.data ?? null;
  }
  cache[id] = { at: Date.now(), data };
  return data;
}

async function loadWikiCache() {
  try { return JSON.parse(await readFile(WIKI_CACHE_FILE, 'utf8')); } catch { return {}; }
}

async function wikidataHolidaySets(wiki) {
  wiki.holidays ??= {};
  const sets = {};
  for (const [id, labels] of Object.entries(HOLIDAY_LABELS)) {
    const hit = wiki.holidays[id];
    if (!hit || Date.now() - hit.at > HOLIDAY_TTL_MS) {
      try {
        wiki.holidays[id] = { at: Date.now(), ids: await holidayImdbIds(labels) };
        await sleep(1500);
      } catch (err) {
        console.warn(`wikidata holiday ${id} failed: ${err.message}`);
      }
    }
    sets[id] = new Set(wiki.holidays[id]?.ids ?? []);
  }
  return sets;
}

const FACTS_SCHEMA = 2;

async function enrichFacts(titles, wiki) {
  if (wiki.factsSchema !== FACTS_SCHEMA) { wiki.titles = {}; wiki.factsSchema = FACTS_SCHEMA; }
  wiki.titles ??= {};
  const stale = titles.filter((m) => !wiki.titles[m.id] || Date.now() - wiki.titles[m.id].at > MOVIE_TTL_MS);
  for (let i = 0; i < stale.length; i += 60) {
    const batch = stale.slice(i, i + 60).map((m) => m.id);
    try {
      const facts = await movieFacts(batch);
      for (const id of batch) wiki.titles[id] = { at: Date.now(), data: facts[id] ?? null };
      await sleep(1500);
    } catch (err) {
      console.warn(`wikidata facts batch ${i} failed: ${err.message}`);
    }
  }
  return (id) => wiki.titles[id]?.data ?? null;
}

async function loadJson(file) {
  try { return JSON.parse(await readFile(file, 'utf8')); } catch { return {}; }
}

/** Trakt's public charts plus any lists named in TRAKT_LISTS ("movie:user/slug=Name,series:user/slug=Name"). */
async function loadTraktCatalogs(trakt) {
  const defs = [];
  for (const type of ['movie', 'series']) {
    for (const chart of ['trending', 'popular', 'anticipated']) {
      defs.push({ type, id: `trakt-${chart}`, name: `Trakt ${chart[0].toUpperCase()}${chart.slice(1)}`, path: `${trakt.kind(type)}/${chart}` });
    }
  }
  TRAKT_LISTS.split(',').map((s) => s.trim()).filter(Boolean).forEach((spec, n) => {
    const m = spec.match(/^(movie|series):([\w.-]+)\/([\w.-]+)=(.+)$/);
    if (!m) { console.warn(`ignoring malformed TRAKT_LISTS entry: ${spec}`); return; }
    const [, type, user, slug, name] = m;
    defs.push({ type, id: `trakt-list-${n}`, name, path: `users/${user}/lists/${slug}/items/${trakt.kind(type)}` });
  });
  const out = [];
  for (const d of defs) {
    try {
      out.push({ ...d, stubs: await trakt.list(`/${d.path}`, d.type) });
    } catch (err) {
      console.warn(`trakt ${d.path} failed: ${err.message}`);
    }
  }
  return out;
}

async function enrichExternal(titles, trakt, cacheRoot) {
  cacheRoot.trakt ??= {};
  cacheRoot.omdb ??= {};
  const type = new Map(titles.map((t) => [t.id, t.type]));
  const ids = titles.map((t) => t.id);
  if (trakt) await fillCache(cacheRoot.trakt, ids, EXTERNAL_TTL_MS, (id) => trakt.summary(type.get(id), id));
  if (OMDB_API_KEY) {
    const calls = await fillCache(cacheRoot.omdb, ids, EXTERNAL_TTL_MS, (id) => omdbLookup(OMDB_API_KEY, id), { cap: OMDB_DAILY_CAP });
    console.log(`OMDb: ${calls} lookups this run`);
  }
  return (id) => ({ trakt: cacheRoot.trakt[id]?.data ?? undefined, omdb: cacheRoot.omdb[id]?.data ?? undefined });
}

async function put(file, json) {
  const target = join(OUT, file);
  await mkdir(dirname(target), { recursive: true });
  await writeFile(target, JSON.stringify(json));
}

async function main() {
  const started = new Date();
  console.log('Loading IMDb datasets...');
  const items = await loadImdb();
  const mean = items.reduce((s, i) => s + i.rating, 0) / items.length;
  for (const it of items) it.score = weightedRating(it.rating, it.votes, 25000, mean);
  const byType = (t) => items.filter((i) => i.type === t);
  console.log(`${items.length} titles kept`);

  const thisYear = started.getUTCFullYear();
  const season = currentHoliday(started);
  const byId = new Map(items.map((i) => [i.id, i]));
  const trakt = TRAKT_CLIENT_ID ? traktClient(TRAKT_CLIENT_ID) : null;
  const traktCatalogs = trakt ? await loadTraktCatalogs(trakt) : [];
  if (!trakt) console.log('TRAKT_CLIENT_ID not set: skipping Trakt');
  if (!OMDB_API_KEY) console.log('OMDB_API_KEY not set: skipping OMDb');
  const wiki = await loadWikiCache();
  const tagged = await wikidataHolidaySets(wiki);
  const isHoliday = (h) => (i) => h.re.test(i.name) || (tagged[h.id]?.has(i.id) ?? false);
  const catalogDefs = [
    { id: 'top', name: 'Top Rated', genre: true, sort: (a, b) => b.score - a.score, filter: (i) => i.votes >= 25000 },
    { id: 'popular', name: 'Most Popular', genre: true, sort: (a, b) => b.votes - a.votes },
    { id: 'new', name: 'New & Notable', genre: true, sort: (a, b) => b.votes - a.votes, filter: (i) => i.year >= thisYear - 1 },
    { id: 'seasonal', name: `In Season: ${season.name}`, sort: (a, b) => b.votes - a.votes, filter: isHoliday(season) },
    ...HOLIDAYS.map((h) => ({
      id: `holiday-${h.id}`, name: `Holiday: ${h.name}`, sort: (a, b) => b.votes - a.votes, filter: isHoliday(h),
    })),
  ];

  await rm(OUT, { recursive: true, force: true });
  const genres = [...new Set(items.flatMap((i) => i.genres))].sort();
  const manifestCatalogs = [];
  const cinematic = { catalogs: [], items: {} };
  const used = new Map();

  for (const type of ['movie', 'series']) {
    for (const def of catalogDefs) {
      const base = byType(type).filter(def.filter ?? (() => true)).sort(def.sort);
      if (base.length === 0) continue;
      const genreOptions = def.genre ? genres.filter((g) => base.some((i) => i.genres.includes(g))) : [];
      manifestCatalogs.push({
        type, id: def.id, name: def.name,
        extra: def.genre
          ? [{ name: 'genre', options: genreOptions, isRequired: false }, { name: 'skip' }]
          : [{ name: 'skip' }],
      });
      const variants = [null, ...genreOptions];
      for (const g of variants) {
        const list = (g ? base.filter((i) => i.genres.includes(g)) : base).slice(0, 500);
        for (const it of list) used.set(it.id, it);
        for (const p of pages(list)) {
          for (const file of catalogFiles(type, def.id, g, p.skip)) {
            await put(file, { metas: p.metas.map(toMeta) });
          }
        }
        if (!g) cinematic.catalogs.push({ type, id: def.id, name: def.name, ids: list.map((i) => i.id) });
      }
    }
  }

  for (const def of traktCatalogs) {
    const list = def.stubs.map((s) => byId.get(s.id) ?? s).filter((i) => i.type === def.type);
    if (list.length === 0) continue;
    manifestCatalogs.push({ type: def.type, id: def.id, name: def.name, extra: [{ name: 'skip' }] });
    for (const it of list.slice(0, 500)) used.set(it.id, used.get(it.id) ?? it);
    for (const p of pages(list)) {
      for (const file of catalogFiles(def.type, def.id, null, p.skip)) await put(file, { metas: p.metas.map(toMeta) });
    }
    cinematic.catalogs.push({ type: def.type, id: def.id, name: def.name, ids: list.slice(0, 500).map((i) => i.id) });
  }

  const cache = await loadCache();
  const seriesIds = [...used.values()].filter((i) => i.type === 'series')
    .sort((a, b) => b.votes - a.votes).slice(0, ENRICH_LIMIT);
  const topMovies = [...used.values()].filter((i) => i.type === 'movie')
    .sort((a, b) => b.votes - a.votes).slice(0, ENRICH_MOVIES);
  const enrichTargets = [...topMovies, ...seriesIds];
  const factsFor = await enrichFacts(enrichTargets, wiki);
  const external = await loadJson(EXTERNAL_CACHE_FILE);
  const extFor = await enrichExternal(enrichTargets, trakt, external);
  let enriched = 0;
  for (const it of seriesIds) {
    const extra = await enrichSeries(it.id, cache);
    const meta = toMeta(it);
    if (extra) {
      Object.assign(meta, {
        description: extra.description || undefined,
        runtime: extra.runtime ? `${extra.runtime} min` : meta.runtime,
        status: extra.status, network: extra.network, country: extra.country, language: extra.language,
        cast: extra.cast, videos: extra.videos,
      });
      applyExternal(applyFacts(meta, factsFor(it.id)), extFor(it.id));
      await put(`meta/series/${it.id}.json`, { meta });
      enriched++;
    }
    cinematic.items[it.id] = meta;
  }
  let enrichedMovies = 0;
  for (const it of topMovies) {
    const meta = applyExternal(applyFacts(toMeta(it), factsFor(it.id)), extFor(it.id));
    if (!meta.description) continue;
    await put(`meta/movie/${it.id}.json`, { meta });
    cinematic.items[it.id] = meta;
    enrichedMovies++;
  }
  for (const it of used.values()) cinematic.items[it.id] ??= toMeta(it);
  await mkdir(dirname(CACHE_FILE), { recursive: true });
  await writeFile(CACHE_FILE, JSON.stringify(cache));
  await mkdir(dirname(WIKI_CACHE_FILE), { recursive: true });
  await writeFile(WIKI_CACHE_FILE, JSON.stringify(wiki));
  await writeFile(EXTERNAL_CACHE_FILE, JSON.stringify(external));

  await writeFile(join(OUT, 'index.html'), await readFile(new URL('./site/index.html', import.meta.url), 'utf8'));
  const stamp = `${thisYear}.${started.getUTCMonth() + 1}.${started.getUTCDate()}`;
  await put('manifest.json', {
    id: 'tv.cartel.cinematic',
    version: stamp,
    name: 'Cartel Cinematic',
    description: 'Genre, holiday and seasonal catalogs built nightly from IMDb datasets, Wikidata, Wikipedia and TVmaze. Plot, cast and episodes for top titles.',
    resources: ['catalog', { name: 'meta', types: ['movie', 'series'], idPrefixes: ['tt'] }],
    types: ['movie', 'series'],
    idPrefixes: ['tt'],
    catalogs: manifestCatalogs,
    behaviorHints: { configurable: false, adult: false },
  });
  await put('cinematic.json', {
    generatedAt: started.toISOString(),
    sources: ['IMDb non-commercial datasets', 'TVmaze API (CC BY-SA)', 'Wikidata (CC0)', 'Wikipedia intro text (CC BY-SA)'],
    genres, holidays: HOLIDAYS.map((h) => ({ id: h.id, name: h.name, window: h.window })),
    ...cinematic,
  });
  console.log(`Done: ${manifestCatalogs.length} catalogs, ${used.size} titles, ${enriched} series and ${enrichedMovies} movies enriched -> ${OUT}`);
}

main().catch((err) => { console.error(err); process.exit(1); });
