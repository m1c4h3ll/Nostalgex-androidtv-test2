# Nostalgex for Android TV

**Retro TV for your own movie and show library, on Android TV and Google TV.** A native
Android TV app (Kotlin, Jetpack Compose, Media3 ExoPlayer) based on the open-source
[Nostalgex](https://github.com/chadMueller/Nostalgex) by Chad Mueller (MIT), for Plex,
Jellyfin and Emby.

Nostalgex turns your library into 130+ channels in 14 packages, with a real channel guide.
Every channel plays on a set daily schedule, so tuning in feels like live TV: you join a film
part way through, the guide shows what's on next, and flipping channels lands mid-programme.
No account to make, and it never supplies any media of its own.

## What's in it

- **Connect** to Plex (a code at plex.tv/link, with a QR code drawn on the TV), Jellyfin
  (Quick Connect, or name and password) or Emby (pick your user). Jellyfin and Emby servers
  are found on the home network, or you type the address.
- **The guide**, after the Apple TV app's look: the info panel and the picture at the top, a
  two-hour grid below with the now line, channel colours, VT323 and DM Mono.
- **Full screen** with the channel OSD. Left and Right (or CH+ and CH-) change channel, Up opens
  the channel strip, Down the Now Playing panel (subtitles, RETRO MODE, how it's playing),
  number keys tune a channel by number.
- **RETRO MODE**: the guide's picture cropped to 4:3 under scanlines and a vignette, and static
  snow between channels.
- **Packages** (NOSTALGEX, KIDZ ZONE, CASE FILES, PRIME TIME, MARQUEE, ARTHOUSE,
  ADVENTURELAND, OVERTIME, DECADES, FRANCHISES, NETWORKS, HIGH ROTATION and the seasonal
  SCREAM and TIS THE SEASON) switched on and off in Settings. A seasonal package in season is
  offered above channel one and leads the guide. A first lineup with nothing on switches on
  every package that has content.
- **Playback** joins each programme at the point the clock says, with upstream's fallback
  ladder: direct play when the TV decodes the file, then the server stream (copying HEVC and
  Dolby audio when this TV decodes them), then a 1080p stream. A start that errors or shows no
  picture in time steps down; a stream that starves steps down; a programme that can't play
  shows why for five seconds and the channel moves on; five in a row stop the channel.
- **The saved library**: the guide comes up at once on launch and the library is read again
  behind it; the guide only changes if the library did.

## The same channels and schedule as the Apple TV app

The channel lineup is upstream's `channels.json`, `channels-memberships.json` and
`franchises.json`, unchanged (in `app/src/main/assets`). The channel filter, the daily
scheduler and the seasons are line-for-line ports of upstream's JS engines
(`scripts/nostalgex-channel-filter.cjs`, `scripts/nostalgex-daily-manifest.js`,
`scripts/nostalgex-season.js`), which upstream holds to the Apple TV's Swift. This port is
held to the same golden files, so the same library gives the same channels and the same
programme at the same minute as on an Apple TV:

| Check | What it compares | Result |
|---|---|---|
| `filter-golden.json` (upstream) | 102 titles on and off the shipped channels | 102 pass |
| `rating-golden.json` (upstream) | ratings from other countries as US labels | 296 pass |
| `schedule-parity-golden.json` (made by the Apple TV's own code) | every day, the guide, conflicts, tune and next pick, Plex/Jellyfin/Emby, 23- and 25-hour days | 3 cases byte for byte |
| `schedule-dst-golden.json` (upstream) | local days in ten zones where the clocks change | 320 pass |
| `season-golden.json` (upstream) | seasons, invites, leads, answers, labels | 103 pass |
| `vectors/filter.json` (from upstream's JS) | 182 channels x 612 items x 2 clocks | 364 pass |
| `vectors/schedule.json` (from upstream's JS) | 7 channels x 14 days x 3 zones, guide and picks | 546 pass |
| `vectors/mediabrowser.json` (upstream's own Jellyfin loader) | the library scan, item for item | 223 pass |

The vectors in `engine/src/test/resources/vectors` are generated, never edited, by
`tools/gen-vectors.mjs` from an upstream checkout:

```sh
NOSTALGEX_DIR=/path/to/Nostalgex node tools/gen-vectors.mjs
```

The engine (`engine/`) is plain Kotlin with no dependencies, so the checks also run without
Gradle or the Android SDK: `KOTLINC=/path/to/kotlinc tools/check-engine.sh`.

## Build and install

GitHub builds the APK: push this project to a GitHub repository and the **Build APK** workflow
(`.github/workflows/build.yml`) runs the engine tests and produces
`nostalgex-android-tv.apk` under the run's **Artifacts**. Tag a commit `v0.1.0` and the APK is
attached to a release too.

Locally, with Android Studio or the Android SDK and JDK 17:

```sh
./gradlew :engine:test          # upstream's golden vectors
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk
```

Install on the TV: turn on Developer options and USB or network debugging, then
`adb connect <tv-ip>` and `adb install nostalgex-android-tv.apk`. Or put the APK on a URL and
open it in the Downloader app. The release build is signed with the debug key unless the
`NOSTALGEX_KEYSTORE`, `NOSTALGEX_KEYSTORE_PASSWORD`, `NOSTALGEX_KEY_ALIAS` and
`NOSTALGEX_KEY_PASSWORD` environment variables point at your own.

Android 8.0 (API 26) or later. Works with a TV remote; on a phone or tablet it opens too, for
testing, with a keyboard or a game controller.

## Remote

| Where | Key | Does |
|---|---|---|
| Guide | Up / Down | Move between channels (Down off the last wraps to channel one) |
| Guide | OK | Tune the channel; OK on the one playing goes full screen |
| Guide | Up from channel one | SETTINGS (and the seasonal invite, when there is one) |
| Guide | Menu | Settings |
| Guide | Back, twice | Leave |
| Full screen | Left / Right, CH+ / CH- | Change channel |
| Full screen | Up | Channel strip |
| Full screen | Down | Now Playing panel |
| Full screen | 0-9 | Tune a channel by number |
| Full screen | Back | Back to the guide |

## Not ported yet

- **Franchise channels from CH 300** and **server collections from CH 500** (upstream's
  `nostalgex-franchise-builder.js` and `nostalgex-collections.js`). The stock franchise
  channels (STAR WARS, MARVEL, HARRY POTTER and the rest of the FRANCHISES package) work.
- **TMDB/OMDb enrichment** through upstream's shared cache, which needs upstream's keys. Without
  it, rules that need enrichment behave as on an Apple TV with no record: keyword and network
  matches don't fire, production companies match on the server's studio alone, and the IMDb
  gates leave FOREIGN FILMS and CULT CLASSICS empty.
- **MusicBrainz music-video enrichment**, **Show Now Playing / Mark Watched**, the **sleep
  timer**, **audio and subtitle language** choices and **stream quality** settings.

## Credits and licence

MIT, like upstream (`LICENSE` keeps upstream's copyright line). The channel data, schedule and
filter logic, guide design and the Nostalgex name and wordmark come from
[chadMueller/Nostalgex](https://github.com/chadMueller/Nostalgex). The library generator for the
filter vectors and the Jellyfin fixtures come from upstream's Roku port (`roku/tests`). Fonts:
VT323 and DM Mono under the SIL Open Font License (`licenses/`). This is an independent port,
not an official Nostalgex release.
