<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="app/src/main/res/drawable-nodpi/logo_wordmark.png">
    <img alt="Nostalgex" src="app/src/main/res/drawable-xhdpi/banner.png" width="420">
  </picture>
</p>

<h3 align="center">Retro TV for your own movie and show library, on Android TV and Google TV</h3>

<p align="center">
  <a href="https://github.com/m1c4h3ll/Nostalgex-androidtv-test2/releases/latest/download/nostalgex-android-tv.apk"><b>Download the APK</b></a>
  ·
  <a href="https://github.com/m1c4h3ll/Nostalgex-androidtv-test2/releases/latest">Latest release</a>
  ·
  <a href="#using-the-remote">Remote guide</a>
</p>

Nostalgex turns your Plex, Jellyfin or Emby library into 130+ channels in 14 packages, with a
real channel guide. Every channel plays on a set daily schedule, so tuning in feels like live
TV: you join a film part way through, the guide shows what's on next, and flipping channels
lands mid-programme. No account to make, and it never supplies any media of its own.

A native Android TV app (Kotlin, Jetpack Compose, Media3 ExoPlayer) based on the open-source
[Nostalgex](https://github.com/chadMueller/Nostalgex) by Chad Mueller (MIT).

## Install

Works on any Android TV or Google TV device running Android 8.0 or later.

**With the Downloader app** (no computer needed)

1. On the TV, install **Downloader** by AFTVnews from the Play Store.
2. Allow it to install apps: *Settings → Apps → Security & restrictions → Unknown sources*
   (the menu names vary a little between TVs), and switch Downloader on.
3. In Downloader, enter
   `https://github.com/m1c4h3ll/Nostalgex-androidtv-test2/releases/latest/download/nostalgex-android-tv.apk`
   and choose **Install** when it has downloaded.

**With adb** (from a computer on the same network)

1. On the TV, turn on *Developer options* and *Network debugging* (or *USB debugging*).
2. Download `nostalgex-android-tv.apk` from the [latest release](https://github.com/m1c4h3ll/Nostalgex-androidtv-test2/releases/latest).
3. Run `adb connect <tv-ip>` then `adb install nostalgex-android-tv.apk`.

**First launch:** pick your server and sign in.

- **Plex:** go to plex.tv/link and enter the code on screen, or scan the QR code with your
  phone. Then choose which servers to use.
- **Jellyfin:** let the app find your server on the home network, or type its address. Sign in
  with Quick Connect or your username and password.
- **Emby:** let the app find your server, or type its address, then pick your user.

The app reads your library and builds the channels. After that, the guide comes up straight away
each time you open it.

## Using the remote

Everything works with the arrows, **OK** and **Back**. The other buttons are shortcuts, if
your remote has them.

### In the guide

| Button | What it does |
|---|---|
| **▲ / ▼** | Move up and down the channel list. ▼ on the last channel wraps round to the first. |
| **▲** from the first channel | Goes up to the seasonal invite (when there is one), then **SETTINGS** at the top. |
| **OK** | Tune the highlighted channel. It plays in the picture at the top right. |
| **OK** again, on the channel that's playing | Go full screen. |
| **OK** on SETTINGS | Open Settings. |
| **◀ / ▶** | Move the guide's time back or forward by half an hour, up to 24 hours ahead. The info panel shows what's on at that time. **OK** still tunes the channel live. |
| **◀ / ▶** on the seasonal invite | Choose SWITCH IT ON, NOT THIS YEAR or DON'T ASK AGAIN, then press **OK**. |
| **CH+ / CH−** | Tune the next or previous channel. |
| **0–9** | Type a channel number. It tunes a moment after the last digit. |
| **Play/Pause** | Go full screen on the channel that's playing. |
| **Menu** | Open Settings. |
| **Back** | From SETTINGS or the invite, go back to the channels. From later in the guide, go back to now. Otherwise, press it twice to leave the app. |

### Watching full screen

| Button | What it does |
|---|---|
| **◀ / ▶** or **CH− / CH+** | Previous or next channel. |
| **▲** | Open the channel strip to see what's on elsewhere. **◀ / ▶** browse it, **OK** tunes, and **▲**, **▼** or **Back** close it. |
| **▼** (or **Info**) | Open the Now Playing panel: the programme's details, **SUBTITLES**, **RETRO MODE**, **SLEEP TIMER** and how it's playing. The arrows choose an option and **OK** switches it. **▲** from the first option, or **Back**, closes the panel. |
| **OK** or **Play/Pause** | Show the channel banner again. If a channel has stopped, **OK** tries it again. |
| **CC** (captions) | Subtitles on or off. |
| **0–9** | Tune a channel by number. |
| **Menu** | Open Settings. |
| **Back** | Back to the guide. |

### In Settings

| Button | What it does |
|---|---|
| **▲ / ▼** | Move through the packages and options. |
| **OK** | Switch a package, RETRO MODE or SUBTITLES on or off, or step through the choices of SUBTITLE LANGUAGE, AUDIO LANGUAGE, LANGUAGE and ON STARTUP. RESCAN LIBRARY reads your server again, and SIGN OUT signs you out. |
| **Back** or **Menu** | Save and go back to the guide. |

At least one package always stays on.

### Signing in, loading and errors

- **Signing in:** the arrows and **OK** pick an option. **OK** on a text field opens the TV's
  keyboard. Press *Done* on the keyboard to confirm, or **Back** to cancel. **Back** also goes
  back a step.
- **Loading:** if your server hasn't answered for a minute, **OK** means STOP WAITING.
- **Error screen:** **OK** tries again, and **Menu** signs out.
- **After the sleep timer:** any button turns the TV back on, on the same channel.

**Game controller or keyboard:** **A** or **Enter** is OK, **B** or **Esc** is Back, and
**Page Up / Page Down** work as CH+ / CH−.

## What's in it

- **The guide**, after the Apple TV app's look: the info panel and the picture at the top, a
  two-hour grid below with the now line, channel colours, VT323 and DM Mono.
- **Full screen** with the channel banner, the channel strip and the Now Playing panel.
- **Browse ahead in the guide.** **◀ / ▶** scroll the grid by half hours across the day the
  schedule already holds, with TOMORROW in the corner once it passes midnight.
- **Sleep timer** in the Now Playing panel: 30, 60, 90 or 120 minutes. A minute before, a
  message warns you; then the picture switches off like an old set, the screen is free to
  sleep, and any button turns it back on.
- **Audio and subtitle languages.** AUDIO LANGUAGE (ORIGINAL, FRANÇAIS or ENGLISH) and SUBTITLE
  LANGUAGE in Settings. Direct play picks the tracks from the file. For a server stream the
  app asks the server for them first: Jellyfin and Emby by stream number, Plex by selecting
  the part's streams on the server, as Plex's own apps do. Only text subtitles are picked,
  so picture subtitles are never burned in.
- **ON STARTUP: LAST CHANNEL** opens straight onto the last channel, full screen, like
  switching on a TV.
- **English and French.** Change it with **LANGUAGE** in Settings. Until you choose, the app
  follows your TV: French on a TV set to French, English otherwise. Channel and package names
  stay as they are in the lineup.
- **RETRO MODE** (on by default): the guide's picture is cropped to 4:3 under scanlines and a
  vignette, with static snow between channels. Switch it off in Settings or the Now Playing
  panel.
- **Packages** you switch on and off in Settings: NOSTALGEX, KIDZ ZONE, CASE FILES, PRIME TIME,
  MARQUEE, ARTHOUSE, ADVENTURELAND, OVERTIME, DECADES, FRANCHISES, NETWORKS, HIGH ROTATION and
  the seasonal SCREAM and TIS THE SEASON. A seasonal package that's in season is offered above
  channel one and leads the guide. If your first lineup has nothing on, every package that has
  content is switched on.
- **Playback** joins each programme at the point the clock says, and falls back in steps:
  - It plays the file directly when the TV can decode it.
  - If not, it uses the server stream, copying HEVC and Dolby audio when the TV decodes them.
  - If that fails too, it uses a 1080p stream.
  - A start that errors, or shows no picture in time, steps down. So does a stream that starves.
  - A programme that can't play shows why for five seconds, then the channel moves on. Five in
    a row stop the channel.
- **The saved library**: the guide comes up at once on launch, and the library is read again
  behind it. The guide only changes if the library did.

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

## Building it yourself

Every push runs the **Build APK** workflow (`.github/workflows/build.yml`). It runs the engine
tests and puts `nostalgex-android-tv.apk` under the run's **Artifacts**. When you push a tag
starting with `v` (like `v0.1.0`), the workflow also attaches the APK to a GitHub release.

Locally, with Android Studio or the Android SDK and JDK 17:

```sh
./gradlew :engine:test          # upstream's golden vectors
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk
```

**Signing.** On GitHub, the workflow signs the release APK with the key in two repository
secrets: `NOSTALGEX_KEYSTORE_B64` (the keystore, base64-encoded) and
`NOSTALGEX_KEYSTORE_PASSWORD` (the key alias is `nostalgex`). With them, every build is signed the
same way, so a new APK installs over the last one and keeps your sign-in and settings. Each run
shows the signer's SHA-256 fingerprint as a notice. Without the secrets, the APK is signed with
that run's throwaway debug key and won't install over another build. Locally, set
`NOSTALGEX_KEYSTORE`, `NOSTALGEX_KEYSTORE_PASSWORD`, `NOSTALGEX_KEY_ALIAS` and
`NOSTALGEX_KEY_PASSWORD` to use your own key. On a phone or tablet the app opens too, for testing, with a
keyboard or a game controller.

## Not ported yet

- **Franchise channels from CH 300** and **server collections from CH 500** (upstream's
  `nostalgex-franchise-builder.js` and `nostalgex-collections.js`). The stock franchise
  channels (STAR WARS, MARVEL, HARRY POTTER and the rest of the FRANCHISES package) work.
- **TMDB/OMDb enrichment** through upstream's shared cache, which needs upstream's keys. Without
  it, rules that need enrichment behave as on an Apple TV with no record:
  - keyword and network matches don't fire;
  - production companies match on the server's studio alone;
  - the IMDb gates leave FOREIGN FILMS and CULT CLASSICS empty.
- **MusicBrainz music-video enrichment**, **Show Now Playing / Mark Watched** and **stream
  quality** settings.

## Credits and licence

MIT, like upstream (`LICENSE` keeps upstream's copyright line). The channel data, schedule and
filter logic, guide design and the Nostalgex name and wordmark come from
[chadMueller/Nostalgex](https://github.com/chadMueller/Nostalgex). The library generator for the
filter vectors and the Jellyfin fixtures come from upstream's Roku port (`roku/tests`). Fonts:
VT323 and DM Mono under the SIL Open Font License (`licenses/`). This is an independent port,
not an official Nostalgex release.
