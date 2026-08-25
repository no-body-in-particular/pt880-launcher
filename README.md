# watchlauncher

A launcher for the rooted **pt880** tracker (Spreadtrum SL8521E, Android 4.4.4,
API 19) with the apps it was missing built into it.

240×240, no touchscreen, two hardware buttons. One APK, one screen stack, and
the clock and battery across the top of every screen in it.

```
 10:42                      84% [|||]
 ─────────────────────────────────────
  Tue 25 Aug

  ♪  Music
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
| `--home` | makes it the watch's home screen |
| `--all` | both |

Root and home are opt-in on purpose: one installs a binary that hands root to
anything on the device that can exec it, and the other changes what the watch
boots into. Neither belongs in the default path of a one-liner.

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

Hosted files: `watchlauncher.apk`, `wsu`, `install-launcher.sh`,
`install-root-helper.sh`, `set-as-home.sh`, all under
`https://coredump.ws/pt880/`.

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
