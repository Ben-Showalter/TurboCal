# AGENTS.md — instructions for AI agents working on TurboCal

TurboCal is a **calendar for keypad flip phones**. It subscribes to iCalendar (`.ics`) feed
URLs (read-only), has one editable **"On this phone"** calendar, stores everything in a local
database, and shows it in Month, Week, Day and Agenda views that work with the keypad alone.

- Package: `com.turbocal.app` · Kotlin · Android Views · Gradle (Kotlin DSL)
- Reference device: **Kyocera DuraXV Extreme E4810**. It has no touchscreen, no Google account
  and no Play services. Input is a D-pad, OK, left/right soft keys, 0–9, `*` and `#`.

Read this file completely before your first edit.

## 0. The house guide comes first

**Follow the flip-phone guide:
[Ben-Showalter/Flip-DumbPhoneGuide `AGENTS.md`](https://github.com/Ben-Showalter/Flip-DumbPhoneGuide/blob/main/AGENTS.md)**
and its `docs/`. Read it before this file. It covers keys, focus, text input, layout, platform,
network and distribution. Its templates (`FlipBaseActivity.kt`, `SystemBars.kt`,
`list_row_selector.xml`, `AndroidManifest-snippets.xml`) are the starting point for this
project's copies; do not write your own versions.

This file adds only what is specific to TurboCal. When the two disagree:

1. A direct instruction from the human in the task wins. Then update this file in the same change.
2. Otherwise **this file wins over the guide** for TurboCal, but only where it says so explicitly
   (see §11).
3. Otherwise the guide wins over your instincts.

When you learn a new device quirk while working here, add it to the guide as well (its
"Keeping this guide alive" section), not just to this repo.

---

## 1. The rules that matter most

1. **Never hand-roll iCalendar or RRULE parsing.** Parsing, recurrence expansion, time-zone
   resolution and VTIMEZONE handling all come from **ical4j**. The phone calendar too: its
   events are built as ical4j `VEvent`s (`core/local/LocalCalendar`) and expanded by the same
   `Expander`. If you are splitting lines on
   `:`, unfolding lines yourself, or writing a `when (freq)` over `DAILY`/`WEEKLY`, you have taken
   a wrong turn. Our code only maps ical4j's output into database rows.
2. **Subscribed feeds are read-only; only "On this phone" is writable** (owner decision, §11).
   Never add editing, RSVP or write-back to a feed.
3. **The UI reads only from the Room database.** No screen ever fetches the network or parses
   ICS. Sync writes to the database; screens observe it. Offline is the normal case.
4. **Feed URLs are secrets.** A Google "secret address" URL *is* the password to that calendar.
   - Never hardcode one anywhere: source, tests, fixtures, README or commit messages.
   - Never log one in full. Log `FeedUrl.redacted()` (scheme + host + `…`) or the feed's
     database id.
   - Store them only in app-private storage (Room). Set `android:allowBackup="false"`, because
     `adb backup` would otherwise export them.
   - Test fixtures use `https://example.invalid/...` only.
5. **A failed fetch never destroys data.** Keep the last good events for that feed and record
   the error in the feed's status. Replace a feed's events only after a full, successful parse,
   in a single database transaction.
6. **No Google, Play or Firebase anything.** No `com.google.android.gms.*`, no Firebase, no Play
   Core (that includes Play in-app updates; see §9). AndroidX libraries (Room, WorkManager) are
   fine: they do not need Play services.
7. **No touch, ever.** Every screen and dialog must be fully usable with the keypad (see guide §1–2).

## 2. Platform settings

- `minSdk 24`. This covers the E4610 sibling on Android 7. **Confirm the connected phone with
  `adb shell getprop ro.build.version.sdk` and record the result in the README.** Never raise
  `minSdk` above the oldest phone in use without asking. `targetSdk` 34. JVM target 1.8.
  `minifyEnabled false`.
- Enable **core library desugaring** (`coreLibraryDesugaring`) so `java.time` works on API 24–25.
  Use `java.time` for all date maths in our code. Never use `java.util.Date`/`Calendar` outside
  the ical4j mapping layer.
- Manifest: `touchscreen required="false"`, the flip `configChanges` from the guide, portrait
  lock, `INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES` (§9),
  `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"` (feed import, §7), `allowBackup="false"`.
- **No DI framework.** Use one hand-written `AppGraph` created in `TurboCalApp`. The app is too
  small to justify Hilt.
- **Dependencies are on an allow-list.** Adding anything else needs a sentence of
  justification in the PR:

  | Purpose | Library |
  |---|---|
  | ICS parsing + recurrence | `org.mnode.ical4j:ical4j` **3.2.x** (the line DAVx⁵ ships on Android) |
  | Database | `androidx.room` (KSP) |
  | Background sync | `androidx.work:work-runtime-ktx` |
  | Coroutines | `kotlinx-coroutines-android` (each screen has a `MainScope` cancelled in `onDestroy`) |
  | UI | **framework** `Activity`, `ListView`, `AlertDialog` (no AppCompat, no RecyclerView) |
  | FileProvider, receiver flags | `androidx.core` |
  | HTTP | **none**: `HttpURLConnection` (as in MatChat's updater) |
  | JSON | **none**: `org.json` (built into Android) |
  | Tests | JUnit 4, `org.json` (JVM copy for `:core`), Robolectric, `room-testing`, `kotlinx-coroutines-test` |

## 3. Repository map

Two modules. All logic that can be pure Kotlin lives in `:core`, so it tests on any JDK in
seconds, even without the Android SDK.

```
core/                      PURE KOTLIN/JVM — no android.* (it can't compile against Android)
  model/                   EventTime (AllDay | Timed | Floating), EventInstance
  ics/                     Ical4jConfig, IcsParser, Expander, ExpansionWindow, WindowsZones, FeedReader
  feeds/                   FeedUrl (normalise, redact), FeedImport (JSON format + merge plan), FeedColors
  sync/                    HttpTransport + UrlConnectionTransport, FeedFetcher (ETag/304/errors), ManualSyncThrottle
  update/                  ReleaseCheck (GitHub releases/latest, asset pick), VersionCompare
  local/                   LocalCalendar (phone calendar ↔ ical4j: expand, export, import), RepeatChoice
  display/                 AgendaBuilder, WeekBuilder, DayBuilder, MonthGrid, Formats — every string a screen shows
  src/test/resources/ics/  the fixture .ics files (§6)
app/                       Android: storage, background work, screens
  TurboCalApp, AppGraph    application + hand-written wiring
  data/                    Room entities/DAO/database, Prefs, CalendarRepository
  sync/                    SyncRunner (fetch → store), LocalCalendarRunner (phone calendar), SyncWorker, SyncScheduler, SyncState
  update/                  UpdateManager (download, verify, install), UpdateStatus
  feeds/                   FeedFileImporter (finds turbocal_feeds.json)
  ui/LaunchActivity        launcher trampoline: opens the last-used view
  ui/base/                 FlipBaseActivity (keys, theme), KeyMap, SystemBars, CalendarActivity, RowAdapter
  ui/month|week|day|agenda|detail|eventedit|settings|feededit|update/
```

- `-Pturbocal.jvmOnly=true` drops `:app` from the build, so `:core` needs no Google Maven or
  SDK: `./gradlew -Pturbocal.jvmOnly=true :core:test`.
- Gradle plugins go on the root `buildscript` classpath (so the Kotlin and Android plugins share
  one classloader), not per-module `plugins { version }`.

## 4. Parsing and time — the correctness rules

All of these have a fixture test (§6). Treat every feed the same way, whether it comes from
Google, Outlook.com, Teamup or anywhere else. **Add no per-provider code paths** except the
named compatibility quirks below.

- **Configure ical4j for Android before the first parse**, in `Ical4jConfig`:
  - `net.fortuna.ical4j.timezone.cache.impl=net.fortuna.ical4j.util.MapTimeZoneCache`
    (the default JCache isn't on Android);
  - `net.fortuna.ical4j.timezone.update.enabled=false` (no network fetch of zones);
  - relaxed unfolding, parsing and validation, plus `ical4j.compatibility.outlook=true`.

  Exclude ical4j's optional Groovy/bnd transitive dependencies if they get pulled in.
- **URLs:** convert `webcal://` to `https://` (and `webcals://` likewise). Trim whitespace.
  Reject any scheme other than http(s).
- **Recurrence:** expand with ical4j (`calculateRecurrenceSet` or equivalent) over the rolling
  window **60 days back and 365 days forward**, from "today" in the device zone at sync time.
  The window sizes are constants in one place.
- **EXDATE** removes instances; **RECURRENCE-ID** components replace the matching instance
  (same UID). They may move it in time, change its title, or cancel it with
  `STATUS:CANCELLED`. Match overrides by UID + original start instant, not by string.
- **STATUS:CANCELLED** on a master event hides the whole series; on an override it hides just
  that instance.
- **TZID / VTIMEZONE:** a zoned wall time is resolved, in this order:
  1. with `java.time` when the TZID is an IANA zone the phone knows;
  2. with `java.time` after mapping a **Windows zone name** (Outlook sends
     `TZID:Pacific Standard Time`) through `WindowsZones`;
  3. with ical4j from the feed's own VTIMEZONE (custom or unknown TZIDs).

  java.time comes first because it follows RFC 5545 for the ambiguous fall-back hour (the
  *first* 1:30 AM) and the skipped spring-forward hour. ical4j's VTIMEZONE zones pick the second
  1:30, which a test caught. `DURATION` is applied to our resolved start, never to ical4j's
  derived DTEND.
- **UTC times** (`...Z`) are instants.
- **Floating times** (no `Z`, no TZID) are wall-clock times in **whatever the device zone is
  when they are displayed**. Store them as local date-time, not as an instant.
- **All-day events** (`VALUE=DATE`) are dates, not instants. Store them as epoch days.
  **DTEND is exclusive**: a one-day event on the 5th has DTEND the 6th and must appear only on
  the 5th. A missing DTEND means one day.
- **Timed events crossing midnight** appear on each day they touch. Day headers show
  "continued" rather than repeating the start time.
- **Display** everything in `ZoneId.systemDefault()`. Re-read it on every render, and on
  `ACTION_TIMEZONE_CHANGED` re-expand all feeds (instants don't move, but the window does).
- **DST:** never add hours to wall times or minutes to instants by hand to cross a day. Use
  `ZonedDateTime`/`LocalDate` arithmetic. On the US fall-back day (Sun **Nov 1, 2026**),
  `DTSTART 01:30` + `DURATION:PT1H` is 05:30Z–06:30Z (one real hour), and 22:00 Oct 31 → 03:00
  Nov 1 is six real hours.
- **Big feeds:** parse from a streamed file in the worker, never from a `String` holding the whole
  body. These phones have 2 GB of RAM.

### Storage model (the contract between sync and UI)

- `feeds`: id, name, url (secret; `""` for the phone calendar), color, enabled, etag,
  lastModified, lastParsedAt, lastSuccessAt, lastAttemptAt, lastError (plain-language text,
  never containing the URL), `source` (0 = ICS link, 1 = "On this phone").
- `local_events` (phone calendar, what the user typed): uid, title, location, description,
  allDay, `start`/`end` (ISO local date-times; **floating**; all-day end exclusive), `rrule`
  (any RRULE ical4j accepts; the editor offers None/Daily/Weekly/Monthly/Yearly and keeps
  others as "Custom"), `exdates`. After every change `LocalCalendarRunner.reexpand` rebuilds
  that feed's `events` rows; sync re-expands it daily to move the window.
- **Schema changes need a hand-written `Migration`** (see `MIGRATION_1_2`) whose SQL matches
  Room's generated SQL exactly, plus a `MigrationTest`. Never use destructive migration:
  phone-calendar events exist nowhere else.
- `events`: feedId (cascade delete), uid, recurrenceKey, title, location, description, `kind`
  (all-day / timed / floating), `startKey`, `endKey`.
- **Keys** (`EventTime.startKey/endKey`) are epoch millis. A timed key is the real instant. An
  all-day or floating key reads its wall time as if it were UTC, so it is within ±14 h of the
  real instant in any zone. Range queries widen by `EventTime.KEY_SLACK_MILLIS` and then filter
  exactly with `EventTime.overlaps`, so **rows never need rewriting when the phone's zone
  changes**. Only the window edges move, which the re-sync on `ACTION_TIMEZONE_CHANGED` covers.
- The DAO has one range query; `CalendarRepository.events(from, to, zone)` is the only way
  screens read events. Do not load everything and filter by date in Kotlin.

## 5. Sync rules

- `SyncWorker` (WorkManager, `CONSTRAINT: NetworkType.CONNECTED`). The default period is 30 min,
  configurable in Settings: 15 min (WorkManager's floor), 30 min, 1 h, 3 h, 6 h or manual only.
  Use `enqueueUniquePeriodicWork` with `UPDATE` on change.
- Manual sync (`0`, or Settings > Sync now) enqueues a unique one-time work.
  **Throttle it** as the guide says: within 2 min of a success, show
  "Already up to date - try again in about N min" instead of fetching.
- **Conditional requests:** send `If-None-Match` (ETag) and `If-Modified-Since`. On **304**,
  update `lastSuccessAt` only; leave events alone. Store new validators only after a successful
  parse.
- Follow up to 5 redirects yourself (`HttpURLConnection` won't follow https→http or cross-protocol
  redirects), https only. Use 20 s connect/read timeouts and a `User-Agent: TurboCal/<version>`.
- A 304 keeps events only while the last full parse is less than a day old. After that the
  validators are dropped, so the rolling window's far end gets expanded.
- Each feed fails independently. Map errors to short plain text for the status line:
  "No connection", "Feed not found (404) - check the link", "Not an iCalendar feed",
  "Server error (503)". Never put the URL in the error.
- **Hidden calendars still sync** (owner, round 6): `enabled` only means "shown". Hidden
  calendars' events are fetched and stored but left out of the views; `#` shows them for a while.

## 6. Tests — what your change must include

| You changed | You must add |
|---|---|
| `:core` `ics/` | A fixture `.ics` in `app/src/test/resources/ics/` and a JVM test asserting the expanded instances |
| Sync / fetch | A test using a fake HTTP layer (200, 304, 404, timeout, garbage body), asserting **last good data is kept** |
| `:core` `display/` (rows, labels) | A JVM test of the rows/strings it produces |
| `SyncRunner` / DAO | A Robolectric test with an in-memory Room database (see `SyncRunnerTest`) |
| Keys, focus or a new screen | A Robolectric key test (focus never `null`, never stuck) and an entry in the key-only traversal test |
| Updater | `VersionCompare` tests and an asset-picking test against a canned `releases/latest` JSON |
| `:core` `local/` | A `LocalCalendarTest` case (expansion, EXDATE, export → import round trip) |
| Room schema | A migration step + `MigrationTest` |

**The required fixture set** (one small file each, with expected instances written out in the test):

1. weekly `RRULE` with one **EXDATE** (deleted instance) and one **RECURRENCE-ID** override
   moving another instance to a different day and time;
2. override with `STATUS:CANCELLED`; master with `STATUS:CANCELLED`;
3. `TZID` + embedded `VTIMEZONE` (Olson name), and an Outlook-style Windows `TZID`;
4. UTC times; floating times (assert they follow the device zone by running the test in two
   zones);
5. one-day and three-day all-day events (assert no spill into the day after DTEND);
6. **US DST end, Sun Nov 1, 2026**: a timed event at 01:30 America/New_York (ambiguous hour), a
   daily recurrence across the change keeping its wall-clock time, and an event from Oct 31 22:00
   to Nov 1 03:00 with the right duration;
7. a timed event crossing midnight;
8. `webcal://` normalisation and URL redaction.

Pin the device zone in tests (`TimeZone.setDefault` in a rule, restored afterwards), never
the machine's zone. Commands:

```bash
./gradlew -Pturbocal.jvmOnly=true :core:test   # parser and logic only; any JDK, no Android SDK
./gradlew test                    # :core + :app (Robolectric) — must be green before any commit
./gradlew lint
./gradlew :app:assembleDebug
./gradlew :app:installDebug       # adb over USB to the E4810
./gradlew :app:connectedAndroidTest   # traversal test on a 240×320 mdpi emulator, touch off
```

## 7. Feeds: adding, importing, secrets

Typing a long URL on a keypad is painful, so **importing from a file is the main way to add
feeds**; typing is the fallback. This follows the guide's rule: never make the user type a secret.

- **Import:** Settings > "Import feeds from file" reads, in this order:
  1. `/sdcard/Android/data/com.turbocal.app/files/turbocal_feeds.json` (no permission needed);
  2. `/sdcard/Download/turbocal_feeds.json`, which needs `READ_EXTERNAL_STORAGE`. Request it only
     when `SDK_INT <= P`, as the guide says, and use `requestLegacyExternalStorage` for API 29.
- Format, documented in the README:

  ```json
  { "version": 1,
    "feeds": [ { "name": "Family", "url": "https://…", "color": "#4DB6AC", "enabled": true } ] }
  ```

  `color` and `enabled` are optional. Merge by normalised URL: an existing feed gets its name
  and color updated, with no duplicates. Report "Imported 3, updated 1, skipped 1 (bad URL)".
- **After a successful import, offer to delete the file** ("Delete the file now? It contains
  your private calendar links."). Default focus goes on **Delete**.
- `.gitignore` must keep `turbocal_feeds*.json`, `keystore.properties` and `*.jks` /
  `*.keystore` out of git. Never commit a real feeds file, not even "for testing".
- **Typed entry:** the feed edit screen uses a plain `EditText` with the system T9 (guide §3,
  option a; don't mix in a custom T9). The T9 can swallow the right soft key, so the screen has
  a focusable **"More options…"** row and **Save** / **Cancel** rows reachable with the D-pad.
- **Color:** pick from a fixed palette of 8 high-contrast colors. LEFT/RIGHT on the Color row
  cycles through them. There's no free-form hex entry.

## 8. Screens and keys

Screens:
- Four interchangeable calendar views, cycled with the View key: **Month** (default on first
  launch) → **Week** → **Day** → **Agenda**.
  - Month: the grid fills all the height the screen has (the E4810 runs at ~0.75 density, so
    never size it in fixed dp); a grid of bordered blocks (every edge snapped to whole pixels so
    no line vanishes at 0.75 density), 16sp day numbers, and one thin full-width line per event
    (2 dp, 2 dp apart) stacked in each block, "+N" when they don't fit. Under it, **every** event of the focused day, one line each:
    the list is as tall as that day needs and the grid shrinks only by that much, down to
    `month_grid_min_height`; "+N more – OK opens the day" appears only past that (owner,
    round 5: no fixed-size band).
  - Week: 7 days, every day with a header, empty days included; the week starts on the
    locale's first day. Title "This week" / "Next week" / "Last week", otherwise the dates.
  - Day: all-day events at the top.
  - Agenda: days with events, opening on today.
- **Event detail**: read-only for feeds. For phone events: Edit / Delete this one / Delete.
- **Event edit** (phone calendar): OK or any digit on a date/time row opens a **keypad picker**
  (`KeypadPicker`, logic in core `input/KeypadEntry`, unit-tested): type the numbers (fields
  advance by themselves; on AM/PM, 2 = AM and 7 = PM), ‹ › move between fields, ˄˅ change one,
  OK sets, Clear deletes a digit or cancels. Left/right on the row still nudge it.
- **Calendars** (Options > Calendars, also Settings > Calendars): every calendar with its
  status. OK shows/hides it; left soft key **Edit** (edit, sync or export, delete); right soft
  key **Add** (import feeds file, type a link, import .ics into the phone calendar).
- **Settings** is a menu: **Sync** (Sync now, interval), **Calendars** (opens the screen
  above), **App** (theme, show hidden calendars, software update, key help).
- **Text fields never trap focus:** `FlipBaseActivity` moves focus out of an `EditText` on D-pad
  down at its last line / up at its first line, before the system T9 sees the key (the T9
  otherwise keeps the D-pad and soft keys, which made Save unreachable). The event editor also
  has a Save row right under Title.
- **Feed edit**, **Software update**.

Navigation rules (owner decisions, §11):
- **Back/Clear returns to the previous screen**, including the previous calendar view; the
  **End** key goes to the home screen (the system handles `KEYCODE_ENDCALL`; never consume it).
  Views open each other with `CalendarActivity.openView` (`FLAG_ACTIVITY_REORDER_TO_FRONT`, the
  date arrives in `onNewIntent`), so there is at most one of each and Back walks back through
  them; Back on the first view leaves the app.
- `LaunchActivity` reopens the last view (`Prefs.lastView`) on today. Detail, Settings pages
  and editors go back normally.

One framework `Activity` per screen, each extending `FlipBaseActivity` (Agenda/Day/Month extend
`CalendarActivity`). **Logic and every displayed string come from `:core` `display/`**
(`AgendaBuilder`, `DayBuilder`, `MonthGrid`, `Formats`), which is unit-tested. An Activity only
collects a repository `Flow`, passes it through a builder, and renders. Lists are `ListView`s:
the list owns focus, the shared selector is drawn on the selected row, and day headers are
disabled items, so the D-pad skips them. Empty states are a selectable "No events" row.

### Key map — the only source of truth (keep the README table identical)

| Key | Everywhere | Notes |
|---|---|---|
| **Left soft key** / `7` | **View**: Month → Week → Day → Agenda → Month | the screen's primary action (guide §1) |
| **Right soft key** / `9` / `MENU` | **Options** menu: New event, Sync now, Go to today, Settings, Calendars, Show/hide hidden | `AlertDialog.setItems`; Software update and Key help live in Settings > App. On the Calendars screen the right soft key is **Add** (owner) |
| OK (`DPAD_CENTER` or `ENTER`) | open the focused item | Month: open selected day |
| Back / Clear | back to the previous screen (including the previous calendar view) | text fields: Clear deletes a character |
| End | home screen | handled by the system |
| D-pad up/down | move focus | no wrap at the ends; leaves a text field at its last/first line |
| D-pad left/right | Week/Day/Agenda: previous/next day · Month: previous/next day, wrapping into the previous/next week · editor rows: nudge (±1 day, ±15 min, next repeat) | skip while an `EditText` has focus |
| `1` / `3` | previous / next day (Day, Agenda), week (Week) or month (Month) | on the editor's date/time rows digits open the picker instead (`wantsDigits()`) |
| `5` | jump to today | |
| `0` | sync now (throttled) | |
| `*` (also `NUMPAD_MULTIPLY`) | Settings | |
| `#` | show/hide disabled calendars | toast says which |

- All keys are handled in `KeyMap` / `FlipBaseActivity`, never in a screen. Fire one-shot actions
  on **UP**, only for a DOWN this window saw, with `repeatCount == 0`. Consume the whole MENU
  press. Unknown keys show `KEY <code>` (keep it in release builds) and log under the tag
  `TurboCalKeys`.
- `7`/`9` are aliases on **every** screen except while a text field has focus, where digits type.
- Soft-key label bar at the bottom: `View` · `Open` · `Options`. The labels are not focusable.
  Hide the system navigation bar (guide §4) except while a text field has focus.
- **Focus:** one shared, strong highlight (`focus_highlight`: tinted fill, 2 dp teal outline,
  6 dp teal bar) for list rows (`list_row_selector`) and for buttons and fields
  (`focus_selector`). The month grid draws a filled teal cell. Every screen sets its starting
  focus (Agenda: today's first event or its "No events" row; Day: first row; Month: the grid on
  today). **The four calendar views use rounded selection boxes** (`list_row_selector_rounded`
  via `CalendarActivity.useRoundedSelector`, and a rounded selected cell in `MonthGridView`);
  Settings, dialogs and editors keep the square shared highlight (owner, round 4). `RowAdapter.submit` keeps the selection on the same row (stable ids) when sync changes
  the data.

## 9. In-app updates (from day one) with manual APK releases

These phones have no Play Store, so TurboCal updates itself from **GitHub Releases** of
`Ben-Showalter/TurboCal`. Model it on MatChat's `UpdateManager`. Releases are **built, signed and
published by hand**: there is **no CI release workflow**. Don't add one unless asked.

### In the app (`update/`)

- `UpdateManager` is one owner with a `StateFlow<UpdateStatus>`
  (Idle, Checking, UpToDate, Available, Downloading(%), Downloaded, Failed(reason)).
- **Check:** `GET https://api.github.com/repos/Ben-Showalter/TurboCal/releases/latest` with
  `HttpURLConnection` + `org.json`. It auto-checks on launch **at most once per 24 h**, in the
  background; Settings > Software update forces a check. A 404 means "No releases yet", not an
  error. The repo (or at least its releases) must be public for this unauthenticated call to work.
- **Compare** the tag (`v1.2.3`, with the `v` stripped) against `versionName` using
  `VersionCompare` (numeric per part; a pre-release suffix sorts lower). Never compare strings.
- **Pick the asset:** the `.apk` whose name contains a `Build.SUPPORTED_ABIS` entry, then
  `universal`, then the only `.apk`. Show its size and the release notes (`body`) before
  downloading.
- **Never download or install without the user pressing OK.** Download to `cacheDir/updates/`
  with progress, then install through a **FileProvider + `ACTION_VIEW`**
  (`application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION`).
- **Unknown sources (API 26+):** if `canRequestPackageInstalls()` is false, open
  `ACTION_MANAGE_UNKNOWN_APP_SOURCES` with the package URI. If that fails, open it without one
  (OEM per-app deep links are unreliable; see guide §5). Also show a toast with the exact D-pad
  steps. When the user comes back, re-check and continue.
- Show a small "Update available: v1.2.0" line on the Settings row. No nags or pop-ups.
- The updater talks only to `api.github.com` / GitHub's asset host. It never sends feed data.
- An update must be signed with the **same key** as the installed app. A debug build can't
  update to a release build. The user switches from debug to release once (uninstall, install
  the release APK, re-import feeds).

### Signing (in `app/build.gradle.kts`)

- Read release signing from an **untracked `keystore.properties`** (`storeFile`, `storePassword`,
  `keyAlias`, `keyPassword`). If the file is missing, build unsigned and print a warning; never
  fail a debug build over it.
- The one release keystore is kept outside the repo and backed up by the owner. **Losing it means
  existing installs can never update in place.** Never generate a new one without asking.
- Outputs: `TurboCal-<versionName>.apk` (release) and `TurboCal-<versionName>-debug.apk`.
  There are no ABI splits: TurboCal has no native code, so every split would be the same file.
  The updater still prefers an ABI-named asset, then `universal`, then the only `.apk`.
- Before installing, `UpdateManager` checks that the downloaded file is complete, is
  `com.turbocal.app`, and is newer than the installed version.

### Manual release checklist (document the same steps in the README)

1. Bump `versionCode` (+1, always increases) and `versionName` (semver) in `app/build.gradle.kts`.
2. `./gradlew test lint :app:assembleRelease` with `keystore.properties` present.
3. `apksigner verify --print-certs app/build/outputs/apk/release/TurboCal-<version>.apk`. The certificate must match
   the previous release's.
4. Install it on the phone over the previous release with `adb install -r`. It must update
   without uninstalling and keep the feeds.
5. Commit, tag `v<versionName>`, push the tag.
6. On GitHub, create a Release from that tag, attach the APKs (never commit APKs to the repo),
   write the notes in plain language (they are shown in the app), and publish it. Don't mark it
   as a pre-release: `releases/latest` ignores pre-releases.
7. On the phone: Settings > Software update > Check. It must offer the new version.

## 10. Look and feel

- **Two themes**, picked in Settings > App > Theme (**Light default**, owner round 6; no
  "follow system": Android 9 has none). Owner's other apps use purple and orange; never reuse those as accents.
  - Dark: background `#000000`, surface `#121212`, text `#FFFFFF` / `#B3B3B3`, teal `#4DB6AC`.
  - Light: background `#FFFFFF`, surface `#F2F2F2`, text `#000000` / `#424242`, teal `#00796B`
    for bars and fills, `#00574B` for teal text.
- Colours are theme attributes (`res/values/attrs.xml`: `tcBackground`, `tcAccent`, …).
  Layouts and drawables use `?attr/tc…`; code uses `FlipBaseActivity.themeColor(R.attr.tc…)`.
  Never a raw hex or `@color/` in a layout, and never `R.color` in code. Calendar colours go
  through `FlipBaseActivity.calendarColor()` (darkened on Light so pastels stay visible).
  The light theme keeps a **dark teal status bar**: the phone's status icons are always white.
  Dialog themes carry the same roles and `list_row_selector`, so dialog lists get the strong
  focus highlight too.
  `FlipBaseActivity` applies the theme before `super.onCreate` and recreates on resume after a
  change. Contrast ≥ 7:1 for body
  text, ≥ 4.5:1 for metadata (guide §4).
- Type: body 16 sp, headers 18 sp, labels 14 sp, metadata ≥ 11 sp. One line per label,
  ellipsized; rows use `minHeight`. Calendar color shows as a 4 dp bar on the row's leading edge.
  It is never the only signal: the detail screen names the calendar.
- No animations, no ripples. Design for 240×320 mdpi; check 320×240 landscape doesn't break.

## 11. Conventions decided here

- **Soft keys follow the guide, not the original chat brief** (owner confirmed). The brief said "left = Back/menu,
  right = view switcher". The guide says left = primary action and right = Options, with Back on
  the Back/Clear key. TurboCal therefore uses **Left = View switcher, Right = Options**, with
  `7`/`9` as aliases. Change this only on the owner's explicit instruction, and update §8 and the
  README together.
- **Back goes back, End goes home** (owner, round 6, replacing round 2's "Back exits from every
  calendar view"). The launcher reopens the view you were last on.
- **Calendars screen soft keys** (owner, round 6): left = Edit, right = **Add**, instead of the
  guide's right = Options.
- **One writable calendar, "On this phone"** (owner, round 2). Subscribed feeds stay read-only:
  no editing, RSVP, CalDAV or account sign-in. Timed phone events are floating (they follow the
  phone's clock). Editing a repeating event edits the whole series; "Delete this one" adds an
  EXDATE. Because `allowBackup=false`, Settings > Calendars has Export/Import of
  `turbocal_local.ics`, and the README tells the user to export before reinstalling.
- No outer-display (sub-LCD) cards and no notifications/reminders in v1. Ask first: a sub-LCD
  card covers the phone's clock (guide checklist).
- No search box over events in v1.

## 12. Build stages (do them in order; each ends green)

Status: stages 1–4 done (ICS feeds confirmed working on the E4810). Round 2 is written: themes,
four views, the month grid redesign, Settings pages and the phone calendar. `:core` tests pass on
the JVM; `:app` is type-checked but its Robolectric tests (`SyncRunnerTest`, `MigrationTest`,
`LocalCalendarRunnerTest`) have only run where the Android SDK is available. The key-only
traversal test (§6) is still to be written.


1. **Project + data + parser.** Gradle setup and `.gitignore`, ical4j on Android (§4),
   `ics/` + fixtures + every §6 parser test, Room schema + DAO range queries, `FeedUrl`,
   `FeedImport`. Stage gate: `./gradlew test` green, and the `minSdk` confirmed via adb.
2. **Screens.** `FlipBaseActivity`/`KeyMap`/soft-key bar/focus drawables from the guide's
   templates, then Agenda, Day, Month, Event detail with the full key map, plus Robolectric
   focus/key tests. Seed the database from fixtures in debug only.
3. **Sync, settings and updates.** `FeedFetcher` (ETag, 304, redirects), `SyncWorker`/scheduler,
   per-feed status, Settings + Feed edit + Import, the Software update screen + `UpdateManager`
   + signing config, README (ICS links from Google / Outlook.com / Teamup, JSON format, `adb push`
   command, build/install, key map, release steps).
4. **Device pass.** `assembleDebug`, `adb install -r`, and a key-only walkthrough on the E4810.
   Record the Android version and any new keycodes in the README and the guide.

## 13. Definition of done

- [ ] `./gradlew test lint` green; new behaviour has the tests §6 asks for.
- [ ] Works with D-pad + soft keys + digits only; focus is never lost or trapped.
- [ ] No feed URL in source, logs, errors, tests or git history.
- [ ] No Play/Google/Firebase dependency; no new dependency outside §2 without a reason in the PR.
- [ ] README key map and §8 match; README updated for any user-visible change.
- [ ] Plain-language error on screen for every failure, with a retry.

## 14. When you are unsure

Stop and ask, with a specific question and a proposed default. Don't guess about scope, new
screens, key bindings, dependencies, signing keys, or anything that would show or send a feed URL.
