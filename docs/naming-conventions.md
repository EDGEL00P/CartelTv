# Naming conventions

*Status: current · Owner: repo maintainer · Last verified: 2026-11-27*

The house naming rules, and the standard they are drawn from. Names say what a thing is for; the
implementation stays free to change.

## Sources

- [Kotlin coding conventions — Naming rules](https://kotlinlang.org/docs/coding-conventions.html#naming-rules):
  camelCase functions, PascalCase types, and *"avoid meaningless words such as `Util` in file names"*.
- [Android Kotlin style guide — Naming](https://developer.android.com/kotlin/style-guide#naming):
  a file named after its single top-level type, otherwise a PascalCase description of its contents.
- [Compose API guidelines](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-api-guidelines.md):
  `@Composable` functions that return `Unit` are PascalCase nouns; app code should follow.
- [Android architecture recommendations — Naming
  conventions](https://developer.android.com/topic/architecture/recommendations):
  repositories are `XRepository`, use cases are `VerbNounUseCase`, UI state is `UiState`; types are
  named for their layer and role.
- [Naming Things (DevIQ)](https://deviq.com/practices/naming-things) and
  [Ubiquitous Language (DDD)](https://milanjovanovic.tech/blog/ubiquitous-language-ddd):
  intent-revealing, pronounceable, searchable names taken from the domain language.

## Rules

1. A type or file name states the role: `ChannelRepository`, not `ChannelsRepo`; `ReleaseUpdater`,
   not `Updater` beside `SelfUpdater`.
2. No abbreviations in type names. `AppPreferences`, not `Prefs`; `PlaybackBufferPolicy`, not
   `BufferPolicy`. Platform acronyms (`HLS`, `HDR`, `DRM`, `ESPN`, `YouTube`) stay as the platform
   spells them.
3. Qualify a generic noun by its domain when the package does not already do so: `CrashWatchdog` in
   `engine.stability`, beside the unrelated `PlaybackStallGuard`.
4. `@Composable` functions that return `Unit` are PascalCase nouns: `MastheadActions`, not
   `mastheadActions`. `remember*` keeps camelCase because it returns a value.
5. Modifier factories stay camelCase (`railEdgeFade`), and extension functions read as a sentence on
   the receiver (`.trapFocus()`).
6. Files are named after their primary declaration. Packages name a layer or feature, not a junk
   drawer — `core`, not `util`.
7. Callbacks are `onX`; booleans are `isX`/`hasX`/`canX`; units live in the name (`timeoutMs`,
   `sizeDp`).
8. Prose follows the same vocabulary as the code: comments say `PlayerControlState`, not "the deck", once
   the type is renamed.

## Rename ledger

The ledger — one dated table per pass, plus the decisions that were considered and deliberately
kept — lives in [changelog/naming.md](changelog/naming.md), so this page stays the stable rules.
