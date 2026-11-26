# On Demand — Nuvio parity plan (archived)

*Status: archived — superseded by [../nuvio-on-demand-parity.md](../nuvio-on-demand-parity.md) ·
Plan written 2026-11-26; P1–P3 shipped in the 2026-11 pass · Kept as the record of what was
planned and in what order.*

The original gap matrix and phases, verbatim. The "CartelTV today" column described the app
*before* the pass; the current state lives in the status doc.

## Gap matrix (pre-pass snapshot)

| Nuvio capability | CartelTV then | Plan |
| --- | --- | --- |
| Discover rows (trending, popular, top rated, upcoming) | Home has one TMDB "In Cinemas" row | Discover screen with mixed movie/series feeds, genre and sort controls (P2) |
| Rich title detail page | Provider movie/series pages only; Stremio/TMDB cards skip straight to a stream picker or do nothing | Unified meta detail page: art, logo, plot, genres, cert, cast, crew, trailer, seasons/episodes, recommendations, collection (P1) |
| Playable search results | TMDB and Stremio results are inert | Results open the meta detail page (P1) |
| Library / watchlist | `favs` holds bare ids; no screen and no metadata | Profile-scoped library entries, Library screen, add/remove on detail and long-press (P1) |
| Continue watching / up next | Present on Home and On Demand | Keep; reuse watched markers from Trakt (done) |
| Trailer playback | Auto hero previews only | Trailer button and optional delayed auto-play on detail (P1) |
| Watched markers | Trakt markers on provider pages | Mark movies and episodes watched from detail (P1) |
| Stream list: sort and filters | Insertion order only | Sort by best/quality/size/seeders; filter resolution, HDR/DV, cached, provider; hide junk (P2) |
| Stream auto-play modes (manual / first / regex) | Always manual | Setting and picker behaviour; reuse-last-link (P2) |
| Provider/source grouping | One flat list | Group by add-on/provider with a source filter (P2) |
| Custom badge sets (up to 3 URLs) | One imported set | Keep; render on add-on streams too, not only built-ins (P2) |
| Debrid cache filtering | Present (TorBox/RD cached hashes) | Keep; show "cached" as filter and sort signal (P2) |
| Collections / custom catalogs | None | Local shelves from TMDB discover/genre/company/collection with artwork and management (P3) |
| Profiles with own history and lists | Profiles exist; history is global | Library and continue-watching scoped by active profile (P1) |
| Trakt / Simkl / MDBList sync | Trakt and Simkl scrobble + watch status | Add Trakt/Simkl watchlist rows to Library when linked (P3) |
| Mobile downloads | TV app; no downloads | Out of scope (Nuvio ships downloads on mobile only) |
| In-app trailer and stream badges set-up | Badges in Settings | Keep |
| Offline/cloud library | Debrid downloads screen exists | Keep |

## Phase 1 — detail and library

1. `MetaDetails` / `MetaRepository`: TMDB details (videos, credits, recommendations, collection,
   external ids, seasons) merged with Stremio add-on metadata (Cinemeta is the fallback and the
   clone source for IMDb ids).
2. `Route.Meta` and `MetaDetailScreen`, reachable from On Demand poster tiles, Home cards and
   search results.
3. `LibraryPreferences` (JSON, keyed by active profile) with migration from `favs`.
4. Library screen, nav entry, detail button and poster long-press.
5. Trailer button, watched marking and per-episode actions.

## Phase 2 — stream control

1. Sort and filters over every stream, built-in and add-on, from release-name parsing already in
   `ReleaseQuality`.
2. Auto-play modes, reuse-last-link and a source group filter; all settings persist and are
   exposed in Playback settings.
3. Badges parsed for add-on streams as well as built-ins.

## Phase 3 — discovery and shelves

1. Discover screen from the On Demand toolbar.
2. Shelves: user-named TMDB rows (discover, genre, company, collection, list) pinned into On
   Demand, with edit/delete.
3. Trakt and Simkl watchlists as Library rows.
