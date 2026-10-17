# Kid Phone

An Android app that turns a phone into a child's phone.

- **Up to four people to call.** Each has a big row with their picture (an emoji such as 👩, or the
  first letter of their name), their name, a green call button, and their phone number in large
  digits so the child learns it. Tapping the row rings that person. That is the only thing the child
  can do.
- **"Hi Alejandro 👋"** and the time at the top, plus the battery level (orange below 50%, red
  below 20%, a bolt while plugged in). The name is set in the parent menu.
- **Only allowed people can ring the phone.** Anyone with a call button, plus an extra list the
  parents set. Every other call, including withheld numbers, is turned away without ringing. Turned-away
  calls still appear in the phone's call history.
- **Calls are never missed.** In kid mode the phone switches off silent mode and Do Not Disturb and
  sets ring and call volume to 100% whenever kid mode starts, whenever the kid screen comes back on,
  and just before an allowed caller rings. The volume buttons do nothing in kid mode. Incoming calls
  also vibrate (for at most a minute), even if vibration is off in the phone's settings.
- **Ringing sound** while a call goes out, until it is answered.
- **Locked in.** No notification shade or quick settings (so no Wi-Fi, Bluetooth or airplane mode),
  no home or recent-apps buttons, no power-off menu, no lock screen, no other app can open. Safe
  mode and factory reset from Settings are blocked.
- **Survives restarts and updates.** If the phone was locked, it comes straight back to the kid
  screen.
- **Parent exit.** The small padlock at the top right asks for the 8-digit parent PIN. From there: unlock the
  phone (it becomes a normal Android phone), edit the call buttons, edit who can ring, change the
  PIN, change the name on screen. Five wrong PINs in a row make it wait a minute, then longer.
- **Opening the app always locks the phone again.**
- Warnings on screen when there is **no SIM card**, or when Kid Phone is no longer the phone app.
- **Home hub and parent app.** Kid Phone connects to a small service on the home Raspberry Pi
  (`hub/`). The parents' phones run **Kid Phone Parent** (`parent/`), which pairs with the hub once
  using a code shown on the child's phone and reaches it from anywhere. While kid mode is on, a
  banner shows when the phone is **not on the home Wi-Fi** (it can't reach the hub).
- **Apps the parents allow.** Kid Phone sends the list of apps on the phone (with icons) to the
  hub; each parent switches apps on or off in Kid Phone Parent, which flags apps that can message
  or call people, open any website, or show any YouTube video. The **Apps** button on the kid screen
  shows only the switched-on apps; the Home button always comes back to the kid screen. Apps only
  open on the home Wi-Fi: about a minute after the phone leaves it, an open app closes. An app
  switched off while open closes within seconds. Settings, the Play Store and the phone's own Phone
  app are never offered. The same list with switches is also on the child's phone itself, behind
  the PIN (padlock → *Apps he can open*); it changes the same switches through the hub, so it needs
  the home Wi-Fi.
- **"Ask first" call buttons.** A call button can be set to *Ask a parent first* (parent menu → Call
  buttons). It shows an amber hand and "Ask first". Tapping it asks the parents: their phones get an
  alert, "Alejandro wants to ring Pablo", with **No / 10 min / 30 min** (Android shows at most three
  buttons; tapping the alert opens the app, which also offers **1 hour**). The first answer counts
  and the other parent's alert changes to say who answered. The child's screen shows the answer at
  once; on yes the button turns green with the minutes left, and he can ring as often as he likes
  until the time runs out. A call already going when it runs out carries on. Nobody answering in 5
  minutes counts as no answer. Asking only works on the home Wi-Fi; a call time already given keeps
  working away from home. That person can still ring the child any time.
  - **A parent who is with him** can answer on his phone: *Parent here? Answer with the PIN* on
    the asking screen, then 10 min / 30 min / 1 hour / No.
  - **A running call time can be changed or stopped**: 10, 30 or 60 minutes counted from now, or
    Stop. In Kid Phone Parent (on the call time card) and on his phone (padlock → PIN, at the top
    of the parent menu).
  - **Everything stays in step**: answers, changes and the apps switches always go through the hub,
    whichever phone they are made on, so every phone shows the same. On his phone that needs the
    home Wi-Fi; a change made from a parent's phone while he is out reaches his phone when it is
    back on the home Wi-Fi.
- **Calls going out only to the call buttons.** In kid mode any outgoing call that isn't to a call
  button (an "ask first" one only during a call time a parent gave) or an emergency number is hung
  up at once, whichever app started it.
- **Updates itself.** Both apps install new versions from this repository's GitHub releases. Kid
  Phone does it silently, only while the screen is off and no call is going on.

## The three parts

| Part | Where it runs | Folder |
|------|---------------|--------|
| Kid Phone | the child's phone | `app/` |
| Kid Phone Parent | each parent's phone | `parent/` |
| Home hub | the home Raspberry Pi, as a Portainer stack | `hub/` (see `hub/README.md` and `hub/API.md`) |

`shared/` holds what both apps use: the look, self-updating and the hub connection helpers.

The child's phone reaches the hub only on the home network (`http://192.168.0.11:8774`), so being
able to reach it is how Kid Phone knows it is at home. The parents' phones use
`https://kidphone.tenjo.ovh`, which the Pi's Cloudflare tunnel forwards to the hub's parent port
(8775). Alerts to the parents' phones go through Firebase Cloud Messaging (Google project
`kidphone-tenjo`).

## How it works

- The app is the phone's **device owner**, which lets it use Android's *lock task* mode: only this
  app can run, and the child can't leave it.
- Kid mode always runs in the app's home screen (`HomeActivity`), which is switched on as the phone's
  home screen only while locked. Opening the app icon (`MainActivity`) locks the phone and hands over
  to it, so the Home button, a restart and the app icon all lead to the same screen.
- The app is the phone's **calling app**. It shows its own simple call screen (the normal one has
  buttons that would let the child dial anyone) and screens incoming calls before they ring.
- On Android 14 and later the app switches the calling app silently: back to the normal Phone app
  when unlocked, and back to itself when locked. On Android 10 to 13, Android asks once during
  setup, and Kid Phone's call screen stays in use while unlocked too.

Needs Android 10 or later. Tested on a Xiaomi Mi A2 Lite (Android 10) and the Android 14 emulator.

## Setting up the phone (once)

1. Remove every account from the phone (Settings → Accounts), including Google, Samsung, Xiaomi
   and app accounts such as Duolingo. Android refuses the next step while any account is present.
   A child account supervised by **Google Family Link** must be removed too (a parent approves it
   with their Google password); Family Link also blocks developer options.
2. Set the screen lock to **None** or **Swipe**.
3. Turn on USB debugging: Settings → About phone → tap *Build number* 7 times, then
   Settings → System → Advanced → Developer options → *USB debugging*.
4. Connect the phone to the computer and run:

   ```
   adb install -t app-release.apk
   adb shell dpm set-device-owner ovh.tenjo.kidphone/.kiosk.AdminReceiver
   ```

   If it says there are accounts on the device even though you removed them all, wait a minute
   (right after a restart Google services are still starting) and run it again.
5. Open **Kid Phone** on the phone and follow the screens: choose the 8-digit parent PIN, enter the call
   buttons, make it the phone app (on Android 10–13 tap *Set as default*), start kid mode.
6. Put the child's SIM card in.

## Releases and updates

Both apps carry the same version (`appVersionName` in `gradle.properties`; 1.2.3 becomes version
code 10203). To publish a new version, from this PC:

```
# set appVersionName=1.2.0 in gradle.properties and commit, then
.\release.ps1
```

It runs the tests, builds both apps, checks they are signed with the Kid Phone key (this PC's
`%USERPROFILE%\.android\debug.keystore`), tags `v1.2.0`, pushes, and uploads `kid-phone.apk` and
`kid-phone-parent.apk` as a GitHub release. The parent app's Firebase config,
`parent/google-services.json`, is needed for the build and is not in git. The apps check
`releases/latest`:

- **Kid Phone** checks every hour on Wi-Fi, downloads a newer version, and installs it the next
  time the screen is off and no call is going on (or at once from *padlock → Version*). As device
  owner it needs no confirmation. Installing restarts it and kid mode comes straight back.
- **Kid Phone Parent** checks every 6 hours and when *Version* is tapped. Android asks "Update?" the
  first time; from Android 12 on, later updates install by themselves.

Release builds are normal apps; debug builds are marked test-only (see "Getting out" below).

## Building

```
gradlew.bat :app:assembleRelease     # app\build\outputs\apk\release\app-release.apk (small, for the phone)
gradlew.bat :parent:assembleRelease  # parent\build\outputs\apk\release\parent-release.apk
gradlew.bat assembleDebug            # debug builds of both
gradlew.bat testDebugUnitTest
```

Both builds are signed with this computer's Android debug key
(`%USERPROFILE%\.android\debug.keystore`). **Keep a copy of that file**: Android only accepts
updates to a device-owner app signed with the same key. Without it, updating means removing Kid
Phone from the phone and setting it up again.

Updating the app on the phone: `adb install -t -r app-release.apk`. Kid mode comes back by itself
after the update.

## Getting out if something goes wrong

- Normal way: padlock → PIN → *Unlock phone*.
- Uninstalling: padlock → PIN → *Remove Kid Phone from this phone…*, then uninstall it like any app.
- From a computer, only while a **debug** build is installed (debug builds are test-only for this
  reason): `adb shell dpm remove-active-admin ovh.tenjo.kidphone/.kiosk.AdminReceiver`. Release
  builds, which is what updates install, can't be removed this way: use the padlock menu.
- Last resort: factory reset from the recovery menu (power + volume buttons while the phone starts).

## Not included yet

- Emergency number button (112 / 999).
- Real photos on the call buttons (only emoji pictures for now).
- Location of the phone (Family Link did this before; it had to be removed).
- Call waiting: a second caller during a call gets the busy signal.
- On Android 12 and later, the phone's own "vibrate for calls" setting can override Kid Phone's
  vibration. Not an issue on the Mi A2 Lite (Android 10).

The Nunito font is under the SIL Open Font License, see `FONT-LICENSE-Nunito.txt`.
