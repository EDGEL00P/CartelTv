# On Demand — Nuvio feature parity

*Status: current (P1–P3 of the original plan shipped) · Owner: repo maintainer · Last verified: 2026-11-27*

Source: Nuvio wiki (`nuvio.wiki/features`, `/settings/player`, `/settings/badges`,
`/integrations/*`, `/settings/ui-and-customization`, `/settings/profiles`,
`/settings/collections`), retrieved 2026-11-26, and the NuvioTV source tree.

CartelTV's On Demand surface is the Movies and Shows sections plus everything a catalog card can
open. "Parity" means the same user-facing capability on this app's own design system and provider
model — not a port of Nuvio's code. The original dated plan is archived in
[archive/nuvio-parity-plan-2026-11.md](archive/nuvio-parity-plan-2026-11.md).

## Capability matrix

| Nuvio capability | State | Notes |
|---|---|---|
| Discover rows (trending, popular, top rated, upcoming) | Done | Discover screen with mixed movie/series rows, genre chips, TMDB feeds |
| Rich title detail page | Done | Art, logo, plot, genres, cert, cast, crew, trailer, seasons/episodes, recommendations, collection |
| Playable search results | Done | Search results open the detail page |
| Library / watchlist | Done | Profile-scoped entries with `favs` migration; add/remove on detail and long-press |
| Continue watching / up next | Kept | Present; now profile-scoped, with dismissed Watch Next keys; Trakt markers reused |
| Trailer playback | Done | Trailer button on the detail page |
| Watched markers | Done | Movies and episodes markable from detail |
| Stream list: sort and filters | Done | Sort by best/quality/size/seeders/name; filter resolution, HDR/DV, cached, source; junk hidden by ordering |
| Stream auto-play modes | Done | Manual, first, name-match; reuse-last-link |
| Provider/source grouping | Done | Source-group filter over a flat list |
| Custom badge sets | Kept, extended | One imported set; parsed badges now render on add-on streams too |
| Debrid cache filtering | Kept, extended | Cached hashes surfaced as a filter and sort signal |
| Collections / custom catalogs | Done | Local shelves from TMDB discover/genre/company/collection, with management |
| Profiles with own history and lists | Done | Library and continue-watching scoped by active profile |
| Trakt / Simkl / MDBList sync rows | Partial | Trakt and Simkl scrobble + watch status; watchlist rows and MDBList still open (below) |
| Mobile downloads | Out of scope | TV app; Nuvio ships downloads on mobile only |
| In-app badge set-up | Kept | Badges in Settings |
| Offline/cloud library | Kept | Debrid downloads screen exists |

## Still open

- MDBList integration, and Trakt/Simkl watchlist rows in the Library.
- New-episode notifications cover provider favourites only; add-on titles are not watched by the
  scheduler yet.
- Party/co-watch, plugins, media servers, usenet.
- Profile cloning and per-profile Trakt linking.
- Nuvio-style home layouts, card size presets, catalog hide/lock/pin beyond the existing Home prefs.

## Where it lives

- `app/src/main/java/com/carteltv/tv/data/discovery/MetaDetails.kt` — normalized meta and the
  TMDB/Stremio repository.
- `app/src/main/java/com/carteltv/tv/ui/catalog/MetaDetailScreen.kt` — the title page.
- `app/src/main/java/com/carteltv/tv/data/preferences/LibraryPreferences.kt`,
  `app/src/main/java/com/carteltv/tv/ui/catalog/LibraryScreen.kt` — the library.
- `app/src/main/java/com/carteltv/tv/data/preferences/StreamPreferences.kt`,
  `app/src/main/java/com/carteltv/tv/data/stremio/StreamOrdering.kt` — picker rules.
- `app/src/main/java/com/carteltv/tv/data/preferences/ShelfPreferences.kt`,
  `app/src/main/java/com/carteltv/tv/ui/catalog/ShelvesScreen.kt` — shelves.
- `app/src/main/java/com/carteltv/tv/ui/catalog/DiscoverScreen.kt` — discovery rows.
- `app/src/main/java/com/carteltv/tv/ui/settings/AddonHubScreen.kt` — picker UI;
  `app/src/main/java/com/carteltv/tv/ui/settings/SettingsPlayback.kt` — its settings.

## Non-goals

- Party mode, plugins, media servers, usenet, mobile-only download UI.
- A pixel copy of Nuvio's layouts.
