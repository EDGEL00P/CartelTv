import test from 'node:test';
import assert from 'node:assert/strict';
import { youtubeId } from './external.mjs';
import { HOLIDAYS, applyExternal, applyFacts, catalogFiles, currentHoliday, daysUntilWindow, pages } from './lib.mjs';
import { titleFromArticle } from './wikidata.mjs';

const holiday = (id) => HOLIDAYS.find((h) => h.id === id);

test('holiday windows open, wrap the new year, and look ahead', () => {
  assert.equal(daysUntilWindow(holiday('halloween'), new Date('2026-10-15T00:00:00Z')), 0);
  assert.equal(daysUntilWindow(holiday('new-year'), new Date('2026-01-02T00:00:00Z')), 0);
  assert.equal(currentHoliday(new Date('2026-12-05T00:00:00Z')).id, 'christmas');
  assert.ok(daysUntilWindow(holiday('halloween'), new Date('2026-09-01T00:00:00Z')) > 0);
});

test('holiday patterns match titles and ignore near misses', () => {
  assert.ok(holiday('christmas').re.test('The Grinch'));
  assert.ok(!holiday('christmas').re.test('Elfin Lied'));
  assert.ok(holiday('valentines').re.test("My Bloody Valentine"));
});

test('wikipedia article urls become page titles', () => {
  assert.equal(titleFromArticle('https://en.wikipedia.org/wiki/Home_Alone_(film)'), 'Home Alone (film)');
  assert.equal(titleFromArticle('https://en.wikipedia.org/wiki/Am%C3%A9lie'), 'Amélie');
});

test('applyFacts fills Stremio fields and keeps existing richer ones', () => {
  const meta = applyFacts({ name: 'X', description: 'kept', cast: ['A'], links: [] }, {
    description: 'wiki', director: ['D'], writer: ['W'], cast: ['B'], awards: ['a', 'b', 'c', 'd'],
    trailers: ['yt1'], released: ['1999-01-01T00:00:00Z'], rottenTomatoes: ['m/x'],
  });
  assert.equal(meta.description, 'kept');
  assert.deepEqual(meta.cast, ['A']);
  assert.deepEqual(meta.director, ['D']);
  assert.equal(meta.awards, 'a; b; c +1 more');
  assert.deepEqual(meta.trailers, [{ source: 'yt1', type: 'Trailer' }]);
  assert.ok(meta.links.some((l) => l.url === 'https://www.rottentomatoes.com/m/x'));
  assert.equal(applyFacts({ a: 1 }, null).a, 1);
});

test('applyExternal prefers Trakt text and OMDb awards, and adds a ratings map', () => {
  const meta = applyExternal({ type: 'movie', name: 'X', description: 'wiki', imdbRating: '8.0', links: [] }, {
    trakt: { overview: 'trakt', certification: 'PG-13', trailer: 'abcdefghijk', rating: 7.9, slug: 'x-1999', released: '1999-03-31' },
    omdb: { awards: 'Won 4 Oscars', metascore: '73', rottenTomatoes: '88%', boxOffice: '$100' },
  });
  assert.equal(meta.description, 'trakt');
  assert.equal(meta.awards, 'Won 4 Oscars');
  assert.equal(meta.certification, 'PG-13');
  assert.deepEqual(meta.ratings, { imdb: '8.0', trakt: 7.9, metacritic: '73', rottenTomatoes: '88%' });
  assert.equal(meta.trailers[0].source, 'abcdefghijk');
  assert.ok(meta.links.some((l) => l.url === 'https://trakt.tv/movies/x-1999'));
  assert.equal(meta.details.boxOffice, '$100');
  assert.equal(applyExternal({ name: 'Y', description: 'keep' }, {}).description, 'keep');
});

test('youtube ids parse from watch and short urls', () => {
  assert.equal(youtubeId('https://youtube.com/watch?v=ZWXKyDK8eZE'), 'ZWXKyDK8eZE');
  assert.equal(youtubeId('https://youtu.be/ZWXKyDK8eZE'), 'ZWXKyDK8eZE');
  assert.equal(youtubeId(undefined), undefined);
});

test('catalog files cover both extra orders and pagination', () => {
  assert.deepEqual(catalogFiles('movie', 'top', null, 0), ['catalog/movie/top.json']);
  assert.deepEqual(catalogFiles('movie', 'top', 'Action', 100), [
    'catalog/movie/top/genre=Action&skip=100.json',
    'catalog/movie/top/skip=100&genre=Action.json',
  ]);
  assert.equal(pages(Array.from({ length: 950 }, (_, i) => i)).length, 5);
  assert.equal(pages([]).length, 1);
});
