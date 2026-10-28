# TurboCal

A **calendar for keypad flip phones**. It subscribes to iCalendar (`.ics`) links from Google
Calendar, Outlook.com, Teamup or any other service (read-only). It also has its own **"On this
phone"** calendar you can add events to with the keypad. Everything is kept in a local database
so it works offline, in a dark or light theme. No touchscreen, Google account or Play services
needed.

- **Reference phone:** Kyocera DuraXV Extreme **E4810** (2.6″, 240×320, D-pad + soft keys).
  Android version: *to be confirmed with `adb shell getprop ro.build.version.release`*.
- **Status:** pre-release. ICS feeds tested on the E4810; round 2 (themes, Week view, phone
  calendar) not yet tested on the phone.
- **Data sources:** only the `.ics` links you add, plus GitHub Releases for app updates.

Built to the [flip-phone guide](https://github.com/Ben-Showalter/Flip-DumbPhoneGuide). Contributors
and AI agents: read [AGENTS.md](AGENTS.md) first.

## Views

Four views, switched with the **View** key in this order: **Month → Week → Day → Agenda**.
The first launch opens Month. After that TurboCal opens the view you left, on today.

- **Month:** a grid of blocks. Each event is a full-width stripe in its calendar's colour,
  stacked inside the day's block, with "+N" when they don't all fit. Under the grid you see the
  selected day's events, one per line. If there are too many, the last line says
  "+N more – OK opens the day".
- **Week:** the seven days of the week (starting on your locale's first weekday), each with a
  header, including empty days.
- **Day:** one day, all-day events first.
- **Agenda:** every day that has events, opening on today.

## Keys

| Key | Does |
|---|---|
| **Left soft key** (or `7`) | **View**: Month → Week → Day → Agenda → Month |
| **Right soft key** (or `9`, or MENU) | **Options**: New event, Sync now, Go to today, Settings, show/hide disabled |
| OK | Open the selected event or day |
| D-pad up/down | Move |
| D-pad left/right | Month: move the selected day · Week/Day/Agenda: previous/next day |
| `1` / `3` | Previous / next month (Month), week (Week) or day (Day, Agenda) |
| `5` | Jump to today |
| `0` | Sync now |
| `*` | Settings |
| `#` | Show / hide disabled calendars |
| Back / Clear | On a calendar view: **exit** (it reopens there next time) · elsewhere: back |

In Settings the left soft key is **Sync** and the right one shows this key list. In a text
field the keys type, and Clear deletes a character.

## Settings

`*` opens a menu of three pages, each its own screen:

- **Sync:** Sync now, and how often to sync in the background.
- **Calendars:**
  - import feeds from a file, or add a calendar by typing a link;
  - import/export the phone calendar;
  - every calendar with its status. OK on one gives Edit, Turn on/off, Sync, Delete.
- **App:** Theme (Dark or Light, OK to switch), Show disabled calendars, Software update,
  Key help.

## The "On this phone" calendar

Options → **New event** adds an event on the selected day. Every field works with the keypad:

| Row | Keys |
|---|---|
| Title, Location, Notes | type with the phone's keyboard |
| All day | OK switches it on/off |
| Starts / Ends (dates) | OK or type a number: opens the date picker · left/right: one day |
| At / Until (times) | OK or type a number: opens the time picker · left/right: 15 minutes |
| Repeat | left/right: doesn't repeat, every day, week, month, year |

In the pickers, type the numbers: for example `1105` then `2026` for Nov 5, 2026, or `930`
then `7` for 9:30 PM (`2` = AM, `7` = PM, like the letters on those keys). Left/right move
between fields, up/down change one, OK sets it, and Clear deletes a digit or closes the picker.
Moving the start moves the end with it. Press **Save** (or the left soft key).

Open a phone event to change it: the left soft key is **Edit**. Options gives **Delete this
one** (for repeating events) or **Delete**. Editing a repeating event changes every repeat.
Times follow the phone's clock, so 9:00 stays 9:00 if you change time zone.

**Back it up:** phone events live only in TurboCal. Uninstalling it (for example, to switch
from a debug build to a release build) deletes them. Before that, use Settings → Calendars →
**Export phone calendar**, then:

```bash
adb pull /sdcard/Android/data/com.turbocal.app/files/turbocal_local.ics
# later, after reinstalling and opening TurboCal once:
adb push turbocal_local.ics /sdcard/Android/data/com.turbocal.app/files/turbocal_local.ics
```

Then Settings → Calendars → **Import .ics into phone calendar**. This works with any `.ics`
file named `turbocal_local.ics` (also from `Download/`). Changed single occurrences in an
imported file are skipped and counted.

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

On the phone: `*` (Settings) → **Calendars** → **Import feeds from file** → OK. If it asks for storage access,
move to **Allow** and press OK. When it says "Imported N…", leave the focus on **Delete** and
press OK to remove the file: it contains your private links. `turbocal_feeds*.json` is in
`.gitignore`; never commit it.

### Type a link

Settings → Calendars → **Add calendar by typing a link**. Type the name and link with the phone's keyboard.
Move down to the **Color** row and use left/right to change it. **Enabled** toggles with OK.
Then move to **Save** and press OK.

## Sync

- Background sync every 30 minutes by default (Settings → Sync → *Sync every*: 15 min to 6 h,
  or manual only). It only runs with a connection, and it sends `ETag`/`If-Modified-Since` so unchanged
  feeds aren't downloaded again.
- `0` syncs now. Right after a clean sync it answers "Already up to date - try again in about
  N min" instead.
- Events are expanded from 60 days back to a year ahead. A feed that fails keeps its last good
  events. Its row in Settings → Calendars shows the error in plain words, e.g. "Error: No connection · last good
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

The app updates itself from this repo's **GitHub Releases**: Settings → App → **Software update**
(or Options → *Check for updates*). It also checks quietly once a day at launch, and shows
"Update available" on the Settings row. Nothing downloads or installs until you press OK.

- The first time, Android asks to allow installs from TurboCal. A toast gives the steps:
  select TurboCal, press OK to turn it on, then press Back, and press **Install** again.
- The check reads `releases/latest` without signing in, so it only works once the repository
  (or its releases) is public. Until then it says "No releases published yet."
- An update must be signed with the same key as the installed app. **A debug build can't update
  to a release build.** To switch: export the phone calendar (see above), uninstall,
  `adb install` the release APK, then re-import your feeds file and the phone calendar.

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
7. On the phone: Settings → App → Software update → it should offer the new version.

## Privacy

Feed links and phone events are stored only in TurboCal's private database. They are never logged (logs show only
`https://host/…`) and never sent anywhere except to that calendar's own server. App backup is
off, so `adb backup` can't export them. The update check talks only to GitHub.
