const UA = 'cartel-cinematic-addon/1.0 (open metadata build)';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export const youtubeId = (url) =>
  url?.match(/[?&]v=([\w-]{11})/)?.[1] ?? url?.match(/youtu\.be\/([\w-]{11})/)?.[1] ?? undefined;

/** GET JSON; 404 -> null; 429 honours Retry-After; any other failure throws. */
async function getJson(url, headers) {
  for (let attempt = 0; attempt < 4; attempt++) {
    const res = await fetch(url, { headers: { 'user-agent': UA, ...headers } });
    if (res.status === 404) return null;
    if (res.status === 429) {
      await sleep((Number(res.headers.get('retry-after')) || 5 * (attempt + 1)) * 1000);
      continue;
    }
    if (!res.ok) throw new Error(`${new URL(url).hostname} -> ${res.status}`);
    return res.json();
  }
  throw new Error(`${new URL(url).hostname} rate limited`);
}

export function traktClient(clientId) {
  const headers = { 'trakt-api-version': '2', 'trakt-api-key': clientId, 'content-type': 'application/json' };
  const get = (path) => getJson(`https://api.trakt.tv${path}`, headers);
  const kind = (type) => (type === 'movie' ? 'movies' : 'shows');

  return {
    async summary(type, imdb) {
      const d = await get(`/${kind(type)}/${imdb}?extended=full`);
      await sleep(350);
      if (!d) return null;
      return {
        overview: d.overview || undefined, tagline: d.tagline || undefined,
        certification: d.certification || undefined,
        released: type === 'movie' ? d.released : d.first_aired?.slice(0, 10),
        runtime: d.runtime || undefined, country: d.country, language: d.language,
        homepage: d.homepage || undefined, status: d.status,
        rating: d.rating ? Number(d.rating.toFixed(1)) : undefined, votes: d.votes,
        trailer: youtubeId(d.trailer), slug: d.ids?.slug, tmdb: d.ids?.tmdb,
      };
    },

    /** Ordered title stubs from a Trakt listing ('movies/trending', 'users/u/lists/slug/items/movies', ...). */
    async list(path, type, maxPages = 5) {
      const out = [];
      for (let page = 1; page <= maxPages; page++) {
        const sep = path.includes('?') ? '&' : '?';
        const rows = await get(`${path}${sep}extended=full&page=${page}&limit=100`);
        await sleep(350);
        if (!rows?.length) break;
        for (const row of rows) {
          const t = row.movie ?? row.show ?? row;
          if (!t?.ids?.imdb || t.title == null) continue;
          out.push({
            id: t.ids.imdb, type, name: t.title, year: t.year ?? null, endYear: null,
            genres: (t.genres ?? []).map((g) => g.replace(/(^|-)(\w)/g, (_, s, c) => (s ? '-' : '') + c.toUpperCase())),
            fromTrakt: true, rating: t.rating ?? 0, votes: t.votes ?? 0, runtime: t.runtime ? `${t.runtime} min` : undefined,
          });
        }
        if (rows.length < 100) break;
      }
      return out;
    },

    kind,
  };
}

export async function omdbLookup(apiKey, imdb) {
  const d = await getJson(`https://www.omdbapi.com/?i=${imdb}&tomatoes=true&apikey=${apiKey}`, {});
  if (!d || d.Response === 'False') return null;
  const na = (v) => (v && v !== 'N/A' ? v : undefined);
  const rt = d.Ratings?.find((r) => r.Source === 'Rotten Tomatoes')?.Value;
  return {
    rated: na(d.Rated), awards: na(d.Awards), metascore: na(d.Metascore),
    rottenTomatoes: rt, boxOffice: na(d.BoxOffice), imdbVotes: na(d.imdbVotes),
  };
}

/** Fills `cache[id]` for ids that are missing or past ttl; `fetchOne` returning undefined means "try again next run". */
export async function fillCache(cache, ids, ttlMs, fetchOne, { cap = Infinity } = {}) {
  let calls = 0;
  for (const id of ids) {
    const hit = cache[id];
    if (hit && Date.now() - hit.at < ttlMs) continue;
    if (calls >= cap) break;
    calls++;
    try {
      cache[id] = { at: Date.now(), data: await fetchOne(id) };
    } catch (err) {
      console.warn(`lookup ${id} failed: ${err.message}`);
    }
  }
  return calls;
}
