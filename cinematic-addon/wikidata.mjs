const UA = 'cartel-cinematic-addon/1.0 (open metadata build)';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const lit = (s) => `"${s.replace(/["\\]/g, '')}"@en`;

export const HOLIDAY_LABELS = {
  christmas: ['Christmas film', 'Christmas television film', 'Christmas'],
  halloween: ['Halloween film', 'Halloween'],
  thanksgiving: ['Thanksgiving'],
  valentines: ["Valentine's Day"],
  easter: ['Easter'],
  'new-year': ["New Year's Eve", "New Year's Day"],
  hanukkah: ['Hanukkah'],
  'st-patricks': ["Saint Patrick's Day"],
  diwali: ['Diwali'],
  'lunar-new-year': ['Chinese New Year'],
  'mothers-day': ["Mother's Day"],
  'fathers-day': ["Father's Day"],
};

export const titleFromArticle = (url) => decodeURIComponent(url.split('/').pop()).replace(/_/g, ' ');

export async function sparql(query) {
  for (let attempt = 0; attempt < 4; attempt++) {
    const res = await fetch('https://query.wikidata.org/sparql', {
      method: 'POST',
      headers: { 'user-agent': UA, accept: 'application/sparql-results+json', 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ query }),
    });
    if (res.status === 429 || res.status >= 500) { await sleep(3000 * (attempt + 1)); continue; }
    if (!res.ok) throw new Error(`wikidata -> ${res.status}`);
    return (await res.json()).results.bindings;
  }
  throw new Error('wikidata unavailable');
}

/** IMDb ids of films/series Wikidata tags with a holiday as genre or main subject. */
export async function holidayImdbIds(labels) {
  const rows = await sparql(`SELECT DISTINCT ?imdb WHERE {
    VALUES ?l { ${labels.map(lit).join(' ')} } ?g rdfs:label ?l .
    ?f (wdt:P136|wdt:P921)/wdt:P279* ?g ; wdt:P345 ?imdb . }`);
  return rows.map((r) => r.imdb.value).filter((v) => /^tt\d+$/.test(v));
}

/** The API caps intro extracts at 20 pages per request. */
async function extracts(allTitles) {
  const lookups = [];
  for (let i = 0; i < allTitles.length; i += 20) { lookups.push(await extractsChunk(allTitles.slice(i, i + 20))); await sleep(500); }
  return (title) => lookups.map((l) => l(title)).find(Boolean);
}

async function extractsChunk(titles) {
  const url = new URL('https://en.wikipedia.org/w/api.php');
  url.search = new URLSearchParams({
    action: 'query', format: 'json', prop: 'extracts', exintro: '1', explaintext: '1',
    exlimit: String(titles.length), redirects: '1', titles: titles.join('|'),
  });
  let res;
  for (let attempt = 0; attempt < 5; attempt++) {
    res = await fetch(url, { headers: { 'user-agent': UA } });
    if (res.status !== 429) break;
    await sleep(Number(res.headers.get('retry-after') ?? 0) * 1000 || 2000 * (attempt + 1));
  }
  if (!res.ok) throw new Error(`wikipedia -> ${res.status}`);
  const q = (await res.json()).query ?? {};
  const alias = new Map();
  for (const n of [...(q.normalized ?? []), ...(q.redirects ?? [])]) alias.set(n.from, n.to);
  const byTitle = new Map(Object.values(q.pages ?? {}).map((p) => [p.title, p.extract]));
  return (title) => {
    let t = title;
    for (let i = 0; i < 3 && alias.has(t); i++) t = alias.get(t);
    return byTitle.get(t);
  };
}

/** [key, Wikidata property, entity-valued?, max kept]. Entity values resolve to English labels. */
export const FACT_PROPS = [
  ['director', 'P57', true, 4], ['writer', 'P58', true, 6], ['cast', 'P161', true, 15],
  ['composer', 'P86', true, 3], ['producer', 'P162', true, 6], ['country', 'P495', true, 4],
  ['language', 'P364', true, 3], ['company', 'P272', true, 6], ['awards', 'P166', true, 15],
  ['locations', 'P915', true, 5], ['released', 'P577', false, 1], ['trailers', 'P1651', false, 3],
  ['website', 'P856', false, 1], ['rottenTomatoes', 'P1258', false, 1],
  ['metacritic', 'P1712', false, 1], ['letterboxd', 'P6127', false, 1], ['tmdb', 'P4947', false, 1],
];

async function propValues(ids, pid, isEntity) {
  const values = ids.map((i) => `"${i}"`).join(' ');
  const body = isEntity
    ? `?f wdt:${pid} ?e . ?e rdfs:label ?v . FILTER(LANG(?v) = "en")`
    : `?f wdt:${pid} ?v .`;
  const rows = await sparql(`SELECT ?imdb (GROUP_CONCAT(DISTINCT STR(?v); separator="|") AS ?vals) WHERE {
    VALUES ?imdb { ${values} } ?f wdt:P345 ?imdb . ${body} } GROUP BY ?imdb`);
  return new Map(rows.map((r) => [r.imdb.value, r.vals.value.split('|').filter(Boolean)]));
}

async function articles(ids) {
  const rows = await sparql(`SELECT ?imdb ?article WHERE {
    VALUES ?imdb { ${ids.map((i) => `"${i}"`).join(' ')} } ?f wdt:P345 ?imdb .
    ?article schema:about ?f ; schema:isPartOf <https://en.wikipedia.org/> . }`);
  return new Map(rows.map((r) => [r.imdb.value, r.article.value]));
}

/** Plot summary and credits, awards, release, trailers and review-site ids for a batch of IMDb ids (<= 100). */
export async function movieFacts(ids) {
  const perProp = {};
  for (const [key, pid, isEntity] of FACT_PROPS) {
    perProp[key] = await propValues(ids, pid, isEntity);
    await sleep(700);
  }
  const wiki = await articles(ids);
  const lookup = wiki.size ? await extracts([...wiki.values()].map(titleFromArticle)) : () => undefined;
  const out = {};
  for (const id of ids) {
    const facts = { wikipedia: wiki.get(id) };
    const text = facts.wikipedia ? lookup(titleFromArticle(facts.wikipedia)) : undefined;
    facts.description = text ? text.split('\n')[0].trim() : undefined;
    for (const [key, , , max] of FACT_PROPS) {
      const v = perProp[key].get(id) ?? [];
      facts[key] = key === 'released' ? [v.sort()[0]].filter(Boolean) : v.slice(0, max);
    }
    out[id] = facts;
  }
  return out;
}
