# TurboCal

A **read-only calendar for keypad flip phones**. It subscribes to iCalendar (`.ics`) links from
Google Calendar, Outlook.com, Teamup or any other service, keeps everything in a local database
so it works offline, and is driven entirely by the keypad. No touchscreen, Google account or
Play services needed.

- **Reference phone:** Kyocera DuraXV Extreme **E4810** (2.6″, 240×320, D-pad + soft keys).
  Android version: *to be confirmed with `adb shell getprop ro.build.version.release`*.
- **Status:** pre-release (`0.1.0`), not yet tested on the phone.
- **Data sources:** only the `.ics` links you add, plus GitHub Releases for app updates.

Built to the [flip-phone guide](https://github.com/Ben-Showalter/Flip-DumbPhoneGuide). Contributors
and AI agents: read [AGENTS.md](AGENTS.md) first.

## Keys

| Key | Does |
|---|---|
| **Left soft key** (or `7`) | **View**: Agenda → Day → Month → Agenda |
| **Right soft key** (or `9`, or MENU) | **Options**: Sync now, Today, Settings, show/hide disabled, updates, key help |
| OK | Open the selected event or day |
| D-pad up/down | Move |
| D-pad left/right | Agenda/Day: previous/next day · Month: move the selected day |
| `1` / `3` | Previous / next day (Agenda, Day) or month (Month) |
| `5` | Jump to today |
| `0` | Sync now |
| `*` | Settings |
| `#` | Show / hide disabled calendars |
| Back / Clear | Back (Day and Month go back to the Agenda) |

In Settings the left soft key is **Sync** and the right one shows this key list. In a text
field the keys type, and Clear deletes a character.

## Getting your calendar's ICS link

Each link is **private**: anyone with it can read that calendar. Treat it like a password.
Menu names change over time; if one has moved, look for "iCal", "ICS" or "publish".

**Google Calendar** (on a computer, at calendar.google.com)
1. Settings (gear) → *Settings for my calendars* → pick the calendar.
2. *Integrate calendar* → **Secret address in iCal format** → copy it (ends in `basic.ics`).

**Outlook.com** (at outlook.live.com)
1. Settings (gear) → *Calendar* → *Shared calendars* → **Publish a calendar**.
2. Pick the calendar and *Can view all details* → *Publish*.
3. Copy the **ICS** link (not the HTML one).

**Teamup**
1. Open the calendar → *Settings* → *Sharing*.
2. Create or pick a link with read-only access → copy its **iCalendar feed** URL
   (`https://ics.teamup.com/feed/…`).

`webcal://` links work too; TurboCal turns them into `https://`.

## Adding calendars

### Import from a file (recommended)

Typing a 100-character link on a keypad is painful, so put the links in a file on your computer:

```json
{
  "version": 1,
  "feeds": [
    { "name": "Family", "url": "https://calendar.google.com/calendar/ical/…/basic.ics", "color": "#64B5F6" },
    { "name": "Work",   "url": "https://outlook.office365.com/owa/calendar/…/calendar.ics" },
    { "name": "Club",   "url": "https://ics.teamup.com/feed/…/0.ics", "enabled": false }
  ]
}
```

- `name` and `url` are needed; `color` (`#RRGGBB`) and `enabled` (default `true`) are optional.
  Without a colour, each calendar gets the next one from the palette (teal, blue, amber, red,
  green, purple, orange, pink).
- Importing again **updates** calendars with the same link (name, colour, on/off) instead of
  adding duplicates. Entries with a bad link are skipped and counted.

Push it to the phone with either command. Use the first one if the phone is on Android 10 or later.

```bash
# no permission needed (open TurboCal once first, so the folder exists)
adb push turbocal_feeds.json /sdcard/Android/data/com.turbocal.app/files/turbocal_feeds.json

# or the Download folder (TurboCal asks for storage access on Android 9 and older)
adb push turbocal_feeds.json /sdcard/Download/turbocal_feeds.json
```

On the phone: `*` (Settings) → **Import feeds from file** → OK. If it asks for storage access,
move to **Allow** and press OK. When it says "Imported N…", leave the focus on **Delete** and
press OK to remove the file: it contains your private links. `turbocal_feeds*.json` is in
`.gitignore`; never commit it.

### Type a link

Settings → **Add calendar by typing a link**. Type the name and link with the phone's keyboard.
Move down to the **Color** row and use left/right to change it. **Enabled** toggles with OK.
Then move to **Save** and press OK.

## Sync

- Background sync every 30 minutes by default (Settings → *Sync every*: 15 min to 6 h, or manual
  only). It only runs with a connection, and it sends `ETag`/`If-Modified-Since` so unchanged
  feeds aren't downloaded again.
- `0` syncs now. Right after a clean sync it answers "Already up to date - try again in about
  N min" instead.
- Events are expanded from 60 days back to a year ahead. A feed that fails keeps its last good
  events. Its Settings row shows the error in plain words, e.g. "Error: No connection · last good
  10:42 AM".
- Google refreshes its secret ICS link on its own schedule (often every few hours). A change you
  make in Google Calendar can take that long to reach any app that subscribes by link.

## Build and install

You need Android Studio (or a JDK 17+ and the Android SDK) and `adb`.

```bash
adb shell getprop ro.build.version.release   # Android version of the connected phone
adb shell getprop ro.build.version.sdk       # API level; minSdk is 24

./gradlew test :app:assembleDebug
adb install -r app/build/outputs/apk/debug/TurboCal-0.1.0-debug.apk
```

On a machine with only a JDK (no Android SDK), the parser and the other pure-Kotlin logic still
build and test:

```bash
./gradlew -Pturbocal.jvmOnly=true :core:test
```

## Updates

The app updates itself from this repo's **GitHub Releases**: Settings → **Software update**
(or Options → *Check for updates*). It also checks quietly once a day at launch, and shows
"Update available" on the Settings row. Nothing downloads or installs until you press OK.

- The first time, Android asks to allow installs from TurboCal. A toast gives the steps:
  select TurboCal, press OK to turn it on, then press Back, and press **Install** again.
- The check reads `releases/latest` without signing in, so it only works once the repository
  (or its releases) is public. Until then it says "No releases published yet."
- An update must be signed with the same key as the installed app. **A debug build can't update
  to a release build.** To switch: uninstall, `adb install` the release APK, then re-import your
  feeds file.

### Making a release (manual)

One-time: create a release key and keep it **outside the repo, backed up**. If it's lost,
installed phones can never update in place again.

```bash
keytool -genkeypair -v -keystore ~/keys/turbocal-release.jks -alias turbocal \
  -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties   # then fill it in (it is gitignored)
```

Each release:
1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
2. `./gradlew test lint :app:assembleRelease`
3. `apksigner verify --print-certs app/build/outputs/apk/release/TurboCal-<version>.apk`.
   The certificate must match the previous release.
4. `adb install -r` it over the previous release. It must update in place and keep the calendars.
5. Commit, then `git tag v<version>` and `git push origin v<version>`.
6. On GitHub: *Releases* → *Draft a new release* → pick the tag → attach the APK → write
   plain-language notes (the app shows them) → **Publish**. Don't tick *pre-release*: the app
   ignores pre-releases.
7. On the phone: Settings → Software update → it should offer the new version.

## Privacy

Feed links are stored only in TurboCal's private database. They are never logged (logs show only
`https://host/…`) and never sent anywhere except to that calendar's own server. App backup is
off, so `adb backup` can't export them. The update check talks only to GitHub.
