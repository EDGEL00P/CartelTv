export const PAGE_SIZE = 100;
export const MAX_PER_CATALOG = 500;

export const HOLIDAYS = [
  { id: 'christmas', name: 'Christmas', window: [11, 15, 12, 31], re: /\b(christmas|xmas|santa claus|scrooge|nutcracker|grinch|noel|rudolph|north pole|yule)\b/i },
  { id: 'halloween', name: 'Halloween', window: [10, 1, 10, 31], re: /\b(halloween|trick.?r.?treat|hocus pocus|pumpkin|samhain|all hallows)\b/i },
  { id: 'thanksgiving', name: 'Thanksgiving', window: [11, 1, 11, 30], re: /\bthanksgiving\b/i },
  { id: 'valentines', name: "Valentine's Day", window: [2, 1, 2, 14], re: /\b(valentine'?s?|cupid|sweetest day)\b/i },
  { id: 'easter', name: 'Easter', window: [3, 15, 4, 25], re: /\b(easter|passion of the christ|ten commandments|ben-hur|king of kings)\b/i },
  { id: 'new-year', name: "New Year's", window: [12, 26, 1, 5], re: /\b(new year'?s?|auld lang|nye)\b/i },
  { id: 'independence-day', name: 'Fourth of July', window: [6, 25, 7, 5], re: /\b(independence day|fourth of july|4th of july|july 4)\b/i },
  { id: 'hanukkah', name: 'Hanukkah', window: [12, 1, 12, 31], re: /\b(hanukkah|chanukah|menorah|dreidel)\b/i },
  { id: 'st-patricks', name: "St. Patrick's Day", window: [3, 10, 3, 17], re: /\b(st\.? patrick'?s?|saint patrick'?s?|leprechaun)\b/i },
  { id: 'diwali', name: 'Diwali', window: [10, 10, 11, 15], re: /\b(diwali|deepavali)\b/i },
  { id: 'lunar-new-year', name: 'Lunar New Year', window: [1, 15, 2, 20], re: /\b(lunar new year|chinese new year|spring festival)\b/i },
  { id: 'mothers-day', name: "Mother's Day", window: [5, 1, 5, 14], re: /\bmother'?s day\b/i },
  { id: 'fathers-day', name: "Father's Day", window: [6, 1, 6, 21], re: /\bfather'?s day\b/i },
];

const dayOfYear = (m, d, year) => Math.floor((Date.UTC(year, m - 1, d) - Date.UTC(year, 0, 0)) / 86400000);

/** Days until the window opens (0 when open now), wrapping over the year boundary. */
export function daysUntilWindow(holiday, now) {
  const [sm, sd, em, ed] = holiday.window;
  const y = now.getUTCFullYear();
  const today = dayOfYear(now.getUTCMonth() + 1, now.getUTCDate(), y);
  const start = dayOfYear(sm, sd, y);
  const end = dayOfYear(em, ed, y);
  const open = start <= end ? today >= start && today <= end : today >= start || today <= end;
  if (open) return 0;
  return start > today ? start - today : start + 365 - today;
}

export function currentHoliday(now = new Date()) {
  return [...HOLIDAYS].sort((a, b) => daysUntilWindow(a, now) - daysUntilWindow(b, now))[0];
}

/** IMDb's Bayesian weighted rating. */
export function weightedRating(rating, votes, minVotes, meanRating) {
  return (votes / (votes + minVotes)) * rating + (minVotes / (votes + minVotes)) * meanRating;
}

export function catalogFiles(type, id, genre, skip) {
  const base = `catalog/${type}/${id}`;
  const parts = [];
  if (genre) parts.push(`genre=${genre}`);
  if (skip > 0) parts.push(`skip=${skip}`);
  if (parts.length === 0) return [`${base}.json`];
  const names = [`${base}/${parts.join('&')}.json`];
  if (parts.length === 2) names.push(`${base}/${parts.reverse().join('&')}.json`);
  return names;
}

export function pages(list) {
  const capped = list.slice(0, MAX_PER_CATALOG);
  const out = [];
  for (let skip = 0; skip < Math.max(capped.length, 1); skip += PAGE_SIZE) {
    out.push({ skip, metas: capped.slice(skip, skip + PAGE_SIZE) });
  }
  return out;
}

export const stripHtml = (s) => (s ?? '').replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();

const searchUrl = (name) => `stremio:///search?search=${encodeURIComponent(name)}`;
const orUndef = (list) => (list?.length ? list : undefined);

/** Merges Wikidata/Wikipedia facts into a Stremio meta object without overriding richer existing fields. */
export function applyFacts(meta, f) {
  if (!f) return meta;
  const awards = f.awards ?? [];
  const person = (category, names) => (names ?? []).map((name) => ({ name, category, url: searchUrl(name) }));
  const site = (name, category, url) => (url ? [{ name, category, url }] : []);
  meta.description ||= f.description;
  meta.director ??= orUndef(f.director);
  meta.writer = orUndef(f.writer);
  meta.cast ??= orUndef(f.cast);
  meta.country = f.country?.join(', ') || meta.country;
  meta.language = f.language?.join(', ') || meta.language;
  meta.released = f.released?.[0] ?? meta.released;
  meta.website = f.website?.[0];
  meta.awards = awards.length
    ? `${awards.slice(0, 3).join('; ')}${awards.length > 3 ? ` +${awards.length - 3} more` : ''}`
    : undefined;
  meta.trailers = orUndef((f.trailers ?? []).map((source) => ({ source, type: 'Trailer' })));
  meta.trailerStreams = orUndef((f.trailers ?? []).map((ytId) => ({ title: meta.name, ytId })));
  meta.links = [
    ...(meta.links ?? []),
    ...person('actor', meta.cast), ...person('director', meta.director), ...person('writer', f.writer),
    ...site('Wikipedia', 'wikipedia', f.wikipedia),
    ...site('Rotten Tomatoes', 'rottentomatoes', f.rottenTomatoes?.[0] && `https://www.rottentomatoes.com/${f.rottenTomatoes[0]}`),
    ...site('Metacritic', 'metacritic', f.metacritic?.[0] && `https://www.metacritic.com/${f.metacritic[0]}`),
    ...site('Letterboxd', 'letterboxd', f.letterboxd?.[0] && `https://letterboxd.com/film/${f.letterboxd[0]}/`),
  ];
  meta.details = {
    composers: orUndef(f.composer), producers: orUndef(f.producer), productionCompanies: orUndef(f.company),
    filmingLocations: orUndef(f.locations), awards: orUndef(awards), tmdbId: f.tmdb?.[0],
  };
  return meta;
}

/** Layers Trakt and OMDb data over a meta: Trakt wins for text, OMDb for awards and review scores. */
export function applyExternal(meta, { trakt, omdb } = {}) {
  if (trakt) {
    meta.description = trakt.overview || meta.description;
    meta.tagline = trakt.tagline;
    meta.certification = trakt.certification;
    meta.released = trakt.released ? `${trakt.released.slice(0, 10)}T00:00:00.000Z` : meta.released;
    meta.website = trakt.homepage || meta.website;
    meta.status = trakt.status || meta.status;
    if (trakt.runtime && !meta.runtime) meta.runtime = `${trakt.runtime} min`;
    if (trakt.trailer && !meta.trailers?.some((t) => t.source === trakt.trailer)) {
      meta.trailers = [{ source: trakt.trailer, type: 'Trailer' }, ...(meta.trailers ?? [])];
      meta.trailerStreams = [{ title: meta.name, ytId: trakt.trailer }, ...(meta.trailerStreams ?? [])];
    }
    if (trakt.slug) {
      const kind = meta.type === 'movie' ? 'movies' : 'shows';
      meta.links = [...(meta.links ?? []), { name: 'Trakt', category: 'trakt', url: `https://trakt.tv/${kind}/${trakt.slug}` }];
    }
  }
  if (omdb) {
    meta.certification ||= omdb.rated;
    meta.awards = omdb.awards || meta.awards;
  }
  const ratings = {
    imdb: meta.imdbRating, trakt: trakt?.rating, metacritic: omdb?.metascore, rottenTomatoes: omdb?.rottenTomatoes,
  };
  const known = Object.fromEntries(Object.entries(ratings).filter(([, v]) => v != null));
  if (Object.keys(known).length > 1) meta.ratings = known;
  if (omdb?.boxOffice) meta.details = { ...meta.details, boxOffice: omdb.boxOffice };
  if (trakt?.tmdb) meta.details = { ...meta.details, tmdbId: String(trakt.tmdb) };
  return meta;
}

export function imageUrls(id) {
  const root = 'https://images.metahub.space';
  return {
    poster: `${root}/poster/medium/${id}/img`,
    background: `${root}/background/medium/${id}/img`,
    logo: `${root}/logo/medium/${id}/img`,
  };
}
