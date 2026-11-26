# Naming changelog

*Owner: repo maintainer · One dated table per rename pass; paths are repo-relative.*

Rules live in [naming-conventions.md](../naming-conventions.md). Entries below record what moved;
wire names (`tvgId`, `group_cfg`, `@SerialName("interval")`) deliberately stay as the wire spells
them — keys are snake_case, code is camelCase.

## 2026-11-26

| Before | After |
|---|---|
| `app/src/main/java/com/carteltv/tv/data/preferences/Prefs.kt`, `*Prefs.kt` | `AppPreferences.kt`, `*Preferences.kt` |
| `app/src/main/java/com/carteltv/tv/util/` | `core/` |
| `data/network/Http.kt` (`Http`) | `HttpSupport.kt` (`HttpSupport`) |
| `components/StatusHost.kt` (`Status`, `StatusHost`) | `StatusMessageHost.kt` (`TransientStatus`, `StatusMessageHost`) |
| `stability/Watchdog.kt` | `CrashWatchdog.kt` |
| `stability/Authority.kt` | `HomeAuthority.kt` |
| `update/Updater.kt` | `ReleaseUpdater.kt` |
| `update/SelfUpdater.kt` | `ApkInstaller.kt` |
| `recording/Scheduler.kt` | `RecordingScheduler.kt` |
| `components/Dpad.kt` | `DpadKeys.kt` |
| `remap/RemapSink.kt` | `KeyRemapService.kt` |
| `apps/AppsCache.kt` | `InstalledAppsCache.kt` |
| `provider/ChannelsRepo.kt` | `ChannelRepository.kt` |
| `components/Focus.kt` (`FocusMemory`, `OverlayState`) | `TvFocus.kt` (`FocusMemoryStore`, `OverlayCoordinator`) |
| `core/SafeIntents.kt` | `core/ProviderIntentSanitizer.kt` |
| `components/GlassSurfaces.kt` (`CartelGlass`) | `GlassGeometry.kt` (`GlassGeometry`) |
| `nav/RemoteIdle.kt` (`ShellIdle`) | `RemoteIdleTimer.kt` (`ShellIdleTimer`) |
| `player/PlayerDeck.kt` (`Deck*`) | `PlayerGlyphs.kt` (`Player*`) |
| `player/PlaybackWatchdog.kt` | `PlaybackStallGuard.kt` |
| `player/PlayerBottomBar.kt`, `ConceptBottomBar` | `PlayerControlBar.kt`, `PlayerControlBar` |
| `player/AutoTracks.kt` | `TrackSelection.kt` |
| `player/TrackLabels.kt`, `player/StreamCodecs.kt` | `TrackFormatting.kt`, `CodecCatalog.kt` |
| `components/Rail.kt` | `RailBehavior.kt` |
| `components/Shimmer.kt` | `ShimmerEffect.kt` |
| `components/ImagePrefetch.kt` | `ImagePrefetcher.kt` |
| `components/PageStates.kt` | `PageStateViews.kt` |
| `components/FitText.kt` (`FitText`) | `AutoSizeText.kt` (`AutoSizeText`) |
| `components/AppLifecycle.kt` | `AppStartupState.kt` |
| `components/PhoneTypeAssist.kt` | `PhoneTextInput.kt` |
| `nav/ScreenActions.kt` | `ui/nav/MastheadActions.kt` (composables PascalCased) |
| `ui/home/HomeActions.kt` | merged into `ui/nav/MastheadActions.kt` (`MastheadActions`) — the masthead composables share one file (an earlier draft of this row named a `HomeMastheadActions.kt` that never existed) |
| `watch/WatchItem.kt` | `WatchProgress.kt` |
| `watch/WatchHistory.kt` | `WatchHistoryStore.kt` |
| `watch/WatchRecorder.kt` | `WatchProgressRecorder.kt` |
| `chapters/IntroDatabase.kt` | `IntroSkipDatabase.kt` |
| `network/PrivateDns.kt` | `PrivateDnsController.kt` |
| `stability/PresenceGuard.kt` | `ViewerPresenceGuard.kt` |
| `stability/BackgroundWork.kt` | `BackgroundWorkScheduler.kt` |
| `stability/BufferPolicy.kt` | `PlaybackBufferPolicy.kt` |
| `stability/ReconnectPolicy.kt` | `StreamReconnectPolicy.kt` |
| `playback/SourceFallback.kt` | `AlternateSourceSelector.kt` |
| `playback/SeekThumbs.kt` | `SeekThumbnails.kt` |
| `playback/LinkCheck.kt` | `StreamLinkCheck.kt` |
| `playback/HlsRepair.kt` | `HlsPlaylistRepair.kt` |
| `playback/StartupTrace.kt` | `StartupLatencyTrace.kt` |
| `playback/MeasuredThroughput.kt` | `ThroughputSampler.kt` |
| `search/OnlineSearchRepository.kt` | `OnlineSearchSource.kt` |
| `finance/ExchangeRatesService` | `ExchangeRateService` |
| `xtream/ChannelCopies.kt` | `ChannelDuplicates.kt` |
| `xtream/CopyFailover.kt` | `StreamCopyFailover.kt` |
| `xtream/VodCache.kt` | `VodCatalogCache.kt` |
| `xtream/InfoChannels.kt` | `NoticeChannels.kt` |
| `apps/AppIcons.kt`, `apps/IconPacks.kt`, `apps/AppHistory.kt` | `AppIconLoader.kt`, `IconPackRegistry.kt`, `AppLaunchHistory.kt` |
| `espn/VenueTime.kt` (`VenueTimeModel`) | `VenueClock.kt` (`VenueClock`) |
| `espn/MatchConditionsModel` | `MatchConditionsSource` |
| `theme/Tones.kt` | `GenreTones.kt` |
| `network/HttpTransport.kt` (`HttpStream`, `HttpMetricsListener`) | `HttpResponseStream`, `HttpEventListener` |
| `settings/SettingsGlyphs.kt` (`Ico`) | `SettingsIcons.kt` (`SettingsIcon`) |
| `search/SearchScreen.kt` (`SFilter`) | `SearchFilter` |
| `xtream/ChannelGroups.kt` (`GroupCfg`) | `ChannelGroupConfig` |
| `playback/RefreshSync.kt` | `RefreshRateSync.kt` |
| `stability/TuneRecoveryPolicy.kt` | `ChannelTuneRecoveryPolicy.kt` |
| `voice/AiAnswers.kt` | `AiAnswerFormatter.kt` |
| `espn/ScoreDiff.kt` | `ScoreDifferential.kt` |

## 2026-11-27

| Before | After |
|---|---|
| `EpgMatcher.Match.guideId` | `Match.epgId` (one vocabulary with `Channel.epgId`; the wire name `tvgId` stays) |
| `DebridAuth`/`TorboxAuth`/`TraktClient` device-code `interval` | `intervalSec` (`TraktClient` keeps the wire key via `@SerialName("interval")`), matching `SimklClient` |
| `VoiceEngine.timeout` (a Runnable, not a duration) | `VoiceEngine.onListenTimeout` |
| `LiveGuide` local composable `act()` | `DialogAction` (rule 4: PascalCase noun) |
| `AppPreferences.groupCfg()` / `setGroupCfg` / `cfg` locals / `LiveChannels.viewCfg` | `channelGroupConfig()` / `setChannelGroupConfig()` / `config` locals / `viewConfig` — completes the `GroupCfg` → `ChannelGroupConfig` entry above (the storage key `group_cfg` stays, keys are snake_case) |
| `nav/SectionNav.kt` (`BarClusters`) | deleted — dead code: nothing encoded or decoded it; the live preference is the raw `bar_clusters` bit string |

## Considered and deliberately kept

- `XtreamClient.LiveStream` — renaming to `LiveChannel` would cross the Xtream wire model into
  every UI call site for no behavior gain.
- The `_ms`-suffixed persisted preference keys — keys are snake_case, code is camelCase; a
  consistent split, not a defect.
- Single-letter helpers in the weather/xtream parsers — a nice-to-have, left out of the 2026-11-27
  pass to keep it high-impact only.
