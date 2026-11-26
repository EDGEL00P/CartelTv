# Cartel Cinematic

A Stremio-format addon that builds genre, holiday, seasonal and awards-style catalogs from open data, plus one `cinematic.json` for custom clients. It needs no API keys; keys only add detail.

## Run it (pick one)

**Local or home server**

    npm start

Builds the catalog on first start, serves it on port 7000, and rebuilds every 24 hours (`REBUILD_HOURS`). Open `http://localhost:7000/` for the install page.

**GitHub Pages (hosted, no server)**

Enable Pages (source: GitHub Actions). `.github/workflows/cinematic-addon.yml` rebuilds and deploys nightly. The install page is at your Pages URL.

## Install

- Stremio: open the install page and press Install, or paste the manifest URL into Addons.
- Nuvio and other Stremio-compatible apps: add an addon and paste `<your-url>/manifest.json`.
- Custom clients: read `<your-url>/cinematic.json`.

## Optional keys (environment variables)

| Variable | Adds |
| --- | --- |
| `TRAKT_CLIENT_ID` | plot, trailer, age rating, Trakt charts |
| `TRAKT_LISTS` | your public Trakt lists as catalogs, `movie:user/slug=Name,series:user/slug=Name` |
| `OMDB_API_KEY` | awards, Rotten Tomatoes, Metacritic, box office (1,000 lookups per day) |

Tuning: `ENRICH_MOVIES` (default 1500), `ENRICH_LIMIT` (series, default 300), `OUT_DIR`, `PORT`.

## Sources and licences

IMDb non-commercial datasets, TVmaze (CC BY-SA), Wikidata (CC0), Wikipedia intro text (CC BY-SA), optionally Trakt and OMDb (CC BY-NC). Personal and non-commercial use only unless you license the sources.

## Test

    npm test
