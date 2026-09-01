# watchlauncher

A launcher for the rooted **pt880** tracker (Spreadtrum SL8521E, Android 4.4.4,
API 19) with the apps it was missing built into it.

240×240, no touchscreen, two hardware buttons. One APK, one screen stack, and
the clock and battery across the top of every screen in it.

<img src="docs/pt880.png" width="360" alt="pt880 tracker: rugged wrist unit with a small square screen, a camera above it, two buttons on the right edge and a perforated locking strap">

Rooting the hardware this runs on, and the tooling to dump, patch and reflash
it, is in [pt880-root](https://github.com/no-body-in-particular/pt880-root).

```
 10:42                      84% [|||]
 ─────────────────────────────────────
  Tue 25 Aug

  ♪  Music
  ☂  Rain
  ᛒ  Bluetooth
  ▣  Camera
  ☎  Call
  >_ Terminal

        A:open  hold:menu  B:down
```

## Why one application

The stock launcher is a clock face with no app list and no key handling, so
every app on this watch is a dead end: close it and there is nothing to go back
to but `adb shell am start`. A launcher that starts other APKs would inherit
that problem — each one draws its own status bar, and each one's *back* lands
on the clock face.

So the five apps are five screens of one activity, on a stack. Backing out
always has somewhere to go, the status bar never leaves, and the music keeps
playing while you take a photo or answer a call.

[`../watchplayer`](../watchplayer) is still there and still builds; this
supersedes it. Do not run both — they would fight over the headphones' media
buttons.

## The apps

| | |
|---|---|
| **Music** | The player from `apps/watchplayer`, moved in whole: same `MusicService`, same filesystem walk, same transport gestures. |
| **Rain** | The next 24 hours of rain over the Netherlands, played as an animation. Only appears where there is data for it — see below. |
| **Bluetooth** | Scan, pair, connect, forget. Pairs headphones over A2DP and keyboards over HID. Names devices that will not name themselves — see below. |
| **Camera** | Viewfinder, shutter, self-timer, review. Saves to `/sdcard/DCIM/Camera`. |
| **Call** | Dials from `contacts.txt`, answers incoming calls, and reads the system call log. |
| **Terminal** | A root shell, typed on a Bluetooth keyboard. |

## Controls

Button **A** is the main key; button **B** is the former power key (see
[`../watchplayer/apply-keylayout.sh`](../watchplayer/apply-keylayout.sh), which
this app needs too — it is the same remap).

In any list — the launcher, every menu, the device list, contacts:

| Gesture | Action |
|---|---|
| A tap | select |
| A hold | back out |
| B tap | move down |
| B hold | move up |

On the screens that are not lists:

| | A tap | A hold | B tap | B hold |
|---|---|---|---|---|
| Music | play / pause | menu | next track | previous track |
| Camera | shutter | menu | cycle self-timer | show last photo |
| In a call | answer / hang up | back | reject / audio route | — |
| Terminal | command list | back | scroll down | scroll up |

**A hold is any hold.** Length is deliberately not overloaded: people hold a
watch button for five or six seconds, and an earlier build of the player that
treated a longer hold as a separate gesture just meant the menu could never be
reached in practice.

The app also works with **button A alone** — tap moves, hold picks — and
switches to the two-button scheme the first time it sees a B press, so it
degrades gracefully if the keylayout remap is reverted. Every list also carries
a `Back` row, so backing out never *requires* a hold.

### Why BACK is the whole gesture model

The firmware does not hand the main key to apps normally: a tap is swallowed
and re-emitted as a synthetic `BACK` (`deviceId=-1`), and a hold leaks
`DPAD_CENTER` auto-repeats first and then *still* ends in that same synthetic
`BACK`. So `BACK` is the only reliable "key released" signal, and the
auto-repeat count seen beforehand is the only measure of how long the key was
held. `BACK` is swallowed so it can never quietly close the app. Two rapid taps
are collapsed into one `BACK` by the firmware, which is why there is no
double-tap gesture anywhere.

## Bluetooth: naming devices that will not name themselves

A scan on this watch used to be a column of MAC addresses. Earbuds only answer
a name request while they are in pairing mode, and plenty of devices never
answer at all.

`assets/oui.db` is **57,858 vendor prefixes** built from four IEEE registries:

| Registry | Prefix | Entries |
|---|---|---|
| MA-L | 24-bit | 39,573 |
| MA-M | 28-bit | 6,560 |
| MA-S | 36-bit | 7,151 |
| IAB (retired) | 36-bit | 4,574 |

MA-M, MA-S and IAB are blocks sold out of MA-L entries that IEEE holds itself,
so a 24-bit hit alone is often just "IEEE Registration Authority". Lookup tries
36 bits, then 28, then 24.

That, plus the class-of-device bits every device sets, turns

```
AC:9B:0A:11:22:33
00:16:94:AA:BB:CC
40:ED:98:11:22:33
```

into

```
Sony                Headset
Sennheiser          Headset
GuangZhou FiiO      Headphones
```

Rebuild the database with:

```bash
python3 tools/build_oui_db.py          # downloads from IEEE
python3 tools/build_oui_db.py --from DIR   # from CSVs you already have
```

Vendor names are trimmed at build time — `Sony Corporation` → `Sony`,
`Samsung Electronics Co.,Ltd` → `Samsung` — because 240px has room for the
brand and not for the corporate form. The trim knows to stop: `Texas
Instruments` and `General Electric` keep both words, since the half that would
be stripped is the half that identifies them.

Records are fixed width and sorted, and the asset is packaged **uncompressed**
(`aapt -0 db`), so the watch binary-searches it in place through the APK's own
descriptor — about 16 seeks per lookup, no parsing, no heap, and no second copy
on a device with 2.3 GB free.

## Pairing a keyboard

Headphones and keyboards need opposite handling at the one moment that matters.
A cheap headset uses a fixed `0000` PIN or Just Works, so the request is
answered automatically and you never see it. A keyboard has no display and
expects **you** to type a code **on it** — so the code goes on the watch's
screen and stays there, and answering the request automatically would break the
pairing rather than complete it. The app tells them apart by their
class-of-device and does the right one.

Once a keyboard is connected it drives the whole UI, not only the terminal:
arrows move, Enter picks, Escape backs out.

### Low Energy will not pair, and the app says so

Android 4.4 can scan and connect LE GATT, but it has **no HOGP host and no
working LE bonding**. `createBond()` on an LE-only device fails in about
thirty milliseconds -- before any radio exchange -- and the stack returns a
status AOSP has no mapping for, so `getUnbondReasonFromHALCode()` falls through
to `UNBOND_REASON_REMOVED`. That surfaces as "removed" and reads exactly like
something deleted the pairing. Nothing did.

The scan list marks those devices `LE` and refuses to try, because retrying
cannot work. Dual-mode devices are fine: they bond over their classic half.

Telling them apart is `BluetoothDevice.getType()`, which is API 18 and
authoritative when it answers. When it returns UNKNOWN there is a fallback on
the address -- the top two bits of the first octet say an LE address is random
rather than IEEE-assigned -- but only *together with* the vendor lookup
failing. On its own that test flags Sennheiser's `00:16:94` and Huawei's
`F0:61:C0`, both perfectly ordinary public addresses. A real trackball at
`F1:65:26:9B:A8:00` matches the bits **and** appears in no registry, which is
what makes it conclusive.

So: a Bluetooth **Classic** mouse or keyboard works here. Anything sold as
"Bluetooth 4.0 LE" does not, and no app-side change can alter that.

## Contacts

```bash
adb push contacts.txt /sdcard/Documents/
```

One entry per line, name first, number after a colon:

```
Arno Phone:+31619036989
Home:0031619036989
# lines starting with a hash are ignored
```

Both dialling forms reach the same place. Spaces, dashes and brackets inside a
number are ignored, so a number pasted from anywhere will dial. Incoming
numbers are matched back to a name on their **last nine digits**, so `+31 6…`,
`0031 6…` and `06…` all find the same person.

Also searched, in order: `/sdcard/documents/`, `/sdcard/`, `/sdcard/Download/`,
`/storage/sdcard1/Documents/`. If there is no file at all, the Call screen
offers to write a commented example at `/sdcard/Documents/contacts.txt`.

There is no keypad, because there is nothing to press it with. A number that is
not in the file and not in the call log cannot be dialled from the watch.

### Answering

Placing a call is public API. Answering and hanging up are not — both live
behind the hidden `ITelephony`, guarded by `MODIFY_PHONE_STATE`, which is a
signature permission no ordinary app can hold. Each has three attempts,
cheapest first:

1. reflection into `ITelephony`, in case the guard does not bite on this
   vendor build;
2. a media-button broadcast, which the phone app answers exactly as it answers
   a headset hook press;
3. `input keyevent` through the root shell, which nothing can refuse.

The third is why the root helper below is worth installing even if you never
open the terminal.

## Terminal, and getting root inside an app

The adbd patch in this repo makes `adb shell` uid 0. That does nothing for an
app — apps are forked from zygote, not from adbd, and nothing an app can call
gets those privileges back.

What does work is two properties of this build:

* Android 4.4's zygote sets an app's capabilities with `capset()` and never
  calls `PR_CAPBSET_DROP`, so an app process still holds the full capability
  **bounding** set;
* SELinux is Disabled and `/system` is mounted without `nosuid`.

Together those mean a setuid-root binary exec'd from an app regains everything.
That binary is `native/wsu.c`, 40 lines:

```bash
bash install-root-helper.sh
```

It needs a boot image from `tools/build_boot_capbnd.py` — the earlier
`build_boot_root.py` leaves `CapBnd=0xc0`, which cannot remount `/system`, and
the script has to write there. See [NOTES.md](../../NOTES.md) section 6.

`wsu` is an unconditional root escalation for anything on the device that can
exec it. That is the point, and it is why it belongs on a tracker you have
taken ownership of and nowhere else.

Without it the terminal still opens, as the app's own uid, and says so on its
first line. `RootShell` tries `/system/xbin/wsu`, then `su`, then plain `sh`.

The shell is long-lived, so `cd` and exported variables stick between commands.
There is no pty, so each command is followed by an echoed sentinel carrying the
exit status — that is how the reader knows a command has finished.

With a keyboard attached: `Enter` runs, `Up`/`Down` walk history, `Ctrl-L`
clears, `Ctrl-C` abandons the line, `Ctrl-D` leaves. Without one, button A
opens a short list of built-in commands — enough to check an IP address or
reboot with nothing else attached. There is deliberately no on-screen character
wheel: two buttons spelling out a command is a novelty, not an input method.

## Becoming the home screen

```bash
bash set-as-home.sh            # take over
bash set-as-home.sh --revert   # give it back
```

The `HOME` intent filter lives on an **activity-alias that ships disabled**, and
this script is the only thing that enables it. That is not tidiness: two enabled
HOME activities with no default chosen means the next boot puts up the "which
launcher?" chooser, and that dialog needs a touchscreen this device does not
have. The script disables the stock launcher and enables the alias together, and
disables the others *first* — if it dies in between, the watch has no home
activity at all, which is recoverable over adb, rather than an unanswerable
dialog, which is much worse.

## Installing from the web

The built APK and the pieces around it are hosted alongside the rest of the
pt880 tooling:

```bash
curl -fsSL https://coredump.ws/pt880/install-launcher.sh | bash -s -- --all
```

| Flag | Adds |
|---|---|
| *(none)* | the APK, and starts it |
| `--root` | the setuid helper the Terminal needs |
| `--vitals` | the daemon that measures the pulse, pressure, saturation and temperature |
| `--home` | makes it the watch's home screen |
| `--call` | takes the in-call screen off the stock dialler |
| `--all` | all four |

Root and home are opt-in on purpose: one installs a binary that hands root to
anything on the device that can exec it, and the other changes what the watch
boots into. Neither belongs in the default path of a one-liner.

`--vitals` needs root and so runs after `--root`, because the daemon goes in
the init slot the vendor's used to occupy — init supervises it there and
restarts it if it dies, which is supervision the app cannot give it. Without
that daemon the watch reports no vitals at all: the launcher measures nothing
itself, and the fallback to the OEM's own sensor service was removed when ours
became the only source worth having. Build the three binaries with
`./build-vitals.sh` (needs an NDK) before `./publish.sh` pins them.

The installer verifies the SHA-256 of everything it downloads before touching
the device — a 404 page downloads perfectly well and installs not at all. The
constants live at the top of each script and have to be updated when the APK or
`wsu` is rebuilt; both scripts print the expected and actual hash on a
mismatch.

Or by hand:

```bash
curl -fsSLO https://coredump.ws/pt880/watchlauncher.apk
adb install -r watchlauncher.apk
adb shell am start -n org.watchlauncher/.ShellActivity
```

### Turning the vendor's health stack off

Once the launcher measures its own vitals, the OEM's service is still running,
still holding the same chip, and still taking turns on a work queue this
firmware cannot recover if it jams. Nothing needs it:

```bash
curl -fsSL https://coredump.ws/pt880/disable-vendor-health.sh | bash
```

It disables `com.ic.work` — the sensor service, which also carries an AMap
location SDK and holds `INTERNET`, `WAKE_LOCK` and `BODY_SENSORS` — along with
the two factory test apps that can hold the thermometer, and the vendor's own
tracker client if it is somehow still there. `pm disable` is persistent and
survives a reboot; `--undo` puts them all back.

Not part of `--all`, for the same reason root and home are not: it changes what
the device runs, and that belongs in a decision rather than in the default path
of a one-liner.

It leaves `/system/lib/libICJniUtils.so` alone, which costs nothing: it is a
library rather than a package, and nothing of ours calls it any more. The
launcher read the thermometer through it until `vitalsd` learned to answer a
`temp` request, and borrowed its wrist-to-body conversion until that was
disassembled and reimplemented as `BodyTemp`.

Hosted files: `watchlauncher.apk`, `wsu`, `install-launcher.sh`,
`install-root-helper.sh`, `set-as-home.sh`, `disable-vendor-health.sh` and
`vitals/{vitalsd,ppgd,adtwear}`, all under `https://coredump.ws/pt880/`.

## Building

No Gradle — the modern AGP stack fights an API 19 target, so the build calls the
SDK tools directly (`aapt` → `javac` → `d8` → `zipalign` → `apksigner`):

```bash
python3 tools/build_oui_db.py      # once; writes assets/oui.db
bash build.sh
adb install -r watchlauncher.apk
bash install-root-helper.sh        # optional, for the terminal
bash set-as-home.sh                # optional, to boot into it
```

Requires the Android SDK build-tools and a JDK; set `ANDROID_SDK_ROOT` if the
SDK is not in one of the usual places. The root helper additionally needs the
NDK (r21e is known good); set `ANDROID_NDK_ROOT` — or let
`install-root-helper.sh` fetch the prebuilt `wsu` from the web instead, which
is what it does when there is no NDK to build with.

After rebuilding, republish and update the two embedded checksums:

```bash
sha256sum watchlauncher.apk    # -> APK_SHA256 in install-launcher.sh
sha256sum native/wsu           # -> WSU_SHA256 in install-root-helper.sh
cp watchlauncher.apk native/wsu install-launcher.sh \
   install-root-helper.sh set-as-home.sh /var/www/hiawatha/pt880/
```

The APK is ~1.9 MB, and nearly all of that is the vendor database.

## Layout

    src/org/watchlauncher/
      ShellActivity      the window, the screen stack, the key decoding
      Screen             what a screen is
      ListScreen         the one interaction this device has
      StatusBar          clock and battery, on every screen
      BatteryIcon        the stock launcher's glyph, redrawn
      AppIcons           the row glyphs, drawn rather than shipped
      Ui                 palette and metrics
      LauncherScreen  SystemMenuScreen  AboutScreen
      MusicScreen  MusicService  Library  MediaButtonReceiver
      BtScreen  BtHelper  BtNames  OuiDb
      CameraScreen
      CallScreen  CallLogScreen  InCallScreen  Contacts  Telephony
                  PhoneStateReceiver
      TermScreen  RootShell
    assets/oui.db        57,858 IEEE vendor prefixes
    native/wsu.c         the setuid helper
    tools/               the database builder

## Rain

A **Rain** row on the launcher: Buienradar's next twenty-four hours, drawn over
a map of the country, an hour a frame.

```
 10:42                      84% [|||]
 ─────────────────────────────────────

       [ the animation, scaled
         to fit, centred      ]

  A:play   B:+1h   hold B:-1h
```

Buienradar's radar page extrapolates the last few frames and is honest for
about two hours. This uses their `rain48hour` product instead — a weather
model, run hourly — because the question a watch gets asked is not "is it
raining" but "will it be raining when I set off".

### The watch does none of it

The server fetches the run, reads the intensity out of the source's colour
ramp, draws it over a map of the Netherlands and returns one animated GIF. The
watch downloads that and plays it. That is the whole of this side: about 200
lines, most of them about fitting a picture on a screen.

It began the other way round — the server packed a grid of intensities and the
watch drew them over its own cached tiles, which meant a wire format, an
encoder, a parser and a palette that had to agree across two languages. That
version could say **"rain here in 3 h"** for the exact spot the watch was
standing on, which this one cannot: it can only show the country and let you
find yourself on it. It was dropped anyway. The picture very nearly answers the
question by being looked at, and a tenth of the code answers it.

At 200 pixels wide the file is about 62 kB for a whole day, which is one
request every quarter of an hour at most and nothing at all when the forecast
on the card is still fresh. It is kept at `/sdcard/maps/rain.gif` and the tile
cleanup spares it, for the same reason it spares the last route: re-fetching it
needs a network that may well be the reason the cleanup ran.

Drawn with `android.graphics.Movie`, which is the only GIF decoder API 19 has.
It needs a software canvas, which this app already is — `hardwareAccelerated`
is off in the manifest and has been since long before this screen.

### Only where it works

The source covers the Netherlands and no other country, so the row is only on
the launcher when the watch is in it. A row that is always present but only
ever works in one place reads as broken everywhere else rather than as absent.

The test is the last country the map settled on, kept in preferences when it
resolves — not a bounding box against the last fix, which would be a second
definition of the Netherlands to keep in step with the server's, and would be
consulting a fix that may be hours old or missing. A watch that has never had a
fix counts as yes: the map is blank there too, and the first thing a new device
does should not be to offer less than it has.

## Map and navigation

A **Map** row on the launcher: where you are, drawn on an offline map, with
turn-by-turn to a destination read out loud.

### What lives where

The watch holds no OSM data and does no routing. The server holds a country's
roads and renders tiles on demand; the watch caches what it is given and then
needs no network at all.

```
server/mapd/       the daemon that serves all of this; see its own README
coredump.ws/map/
  import.php    a Geofabrik roads shapefile -> a country's road store
  tile.php      z/x/y -> a 4-bit greyscale PNG, rendered and cached
  roads.php     z/x/y -> the same roads as vectors, binary
  route.php     from/to -> geometry plus turns, binary
  country.php   lat/lon -> which country's map covers it
```

The Netherlands is 1,990,294 ways in a 119 MB SQLite store. A tile renders in
a few milliseconds and a lookup around a point takes about 16 ms.

### Four bits

The tiles are 4-bit greyscale PNGs, a few kilobytes each. Sixteen greys is
more than enough to separate a motorway from a footpath on a 240x240 screen,
and the storage saved is spent on covering more ground instead. GD writes a
palette PNG at the smallest bit depth that fits, so a sixteen-colour palette
becomes a 4-bit file without being asked.

### How big a country actually is

Measured rather than assumed — 576 tiles rendered on a lattice across the whole
country:

| | |
|---|---|
| Tiles at z15 | 149,760 |
| **Mean tile** | **448 bytes** |
| Median | 192 B — 72% of the bbox is sea or foreign land |
| p90 | 1,045 B |
| Amsterdam centre, mean | 3,068 B |

**The whole Netherlands at z15 is about 64 MB**, under 3% of the card. So a
country is kept at full navigating detail, not at an overview zoom.

An earlier version stored countries at z13 on an assumed 4 kB a tile. That was
nine times too pessimistic and bought nothing but a worse map: 4-bit greyscale
line art on black is almost entirely one repeated value, and PNG's filtering
eats it.

A continent is another matter — Western Europe is roughly 3–4 GB — so the
**route corridor** stays, and it is what makes travelling abroad possible
without carrying Europe. A 50 km route at z15 is a few hundred tiles.

Tiles arrive in **blocks of 256** from `pack.php`, not one request each. A
country one tile at a time is 149,760 round trips: at even 50 ms apiece that is
two hours, nearly all of it waiting rather than transferring, for files
averaging under half a kilobyte. In blocks it is about 600 requests and the
transfer is bounded by the data instead.

Bulk downloads **refuse to start without wifi**, and stop if it goes away
mid-job: the cellular link belongs to the tracker's own reporting. A single
tile needed right now goes over anything, because half a kilobyte against a
blank screen is not a trade worth making.

### Countries

The country comes from your position, asked once and again only when a fix
falls outside the bounds the last answer gave. Tiles live under
`/sdcard/maps/<country>/`, so crossing a border fetches a new map and crossing
back finds the old one already there. With no network, whatever is on the card
is what gets drawn.

### Destination

```bash
adb push destination.txt /sdcard/Documents/
```

```
Home:51.4667000,4.5000000
Work:51.4667000,4.5000000
```

Same shape as `contacts.txt`, for the same reason: nothing can be typed on this
device. A bare `lat,lon` line works and names itself after its coordinates.

With no destination it is simply a map with a dot on it, which is the common
case and gets no ceremony.

### Turns, spoken

`com.svox.pico` ships on the watch, so nothing needs installing. Instructions
go out on `STREAM_MUSIC`, which means they follow the headphones when those are
connected and fall back to the case speaker when they are not — the same
routing the player uses.

Each turn is announced twice: once at 250 m to change lane, once at 40 m.
Distances are rounded to what a person would say — "in two hundred metres,
turn left", never "in one hundred and eighty-seven metres".

**No street names.** At 240x240, spoken through a wrist, the direction and the
distance are the whole of what is useful, and the name is exactly what makes
the sentence too long to finish before the junction arrives.

### The cold start

A GNSS receiver takes a minute or two from cold and this watch keeps its own
off most of the time, so opening the map usually means nothing to draw and no
way to know which country to fetch. The first position comes from the tracker
server instead — the same resolved wifi-and-cell fix the sports screen reads.

It is drawn as a grey dot inside an uncertainty ring, labelled `approx`, and
**turn instructions are suppressed while it is in use**. Hundreds of metres is
fine for choosing a country and centring a map, and useless for deciding which
junction you are at. The first real fix replaces it silently.

### Position

Every 10 s, and only while the map is open — continuous GNSS is a few hours of
battery against days on the tracker's own ten-minute cycle.

The map is drawn **north-up**. There is no magnetometer on this watch, so
heading can only come from course over ground: it knows which way you are
moving and cannot know which way you are facing. A map that rotated on that
guess would point confidently wrong every time you stopped, so it does not.
