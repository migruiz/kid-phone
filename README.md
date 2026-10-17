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

## Building

```
gradlew.bat assembleRelease      # app\build\outputs\apk\release\app-release.apk (small, for the phone)
gradlew.bat assembleDebug        # app\build\outputs\apk\debug\app-debug.apk
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
- From a computer (the app is marked test-only for this reason):
  `adb shell dpm remove-active-admin ovh.tenjo.kidphone/.kiosk.AdminReceiver`
- Last resort: factory reset from the recovery menu (power + volume buttons while the phone starts).

## Not included yet

- Emergency number button (112 / 999).
- Real photos on the call buttons (only emoji pictures for now).
- Location of the phone (Family Link did this before; it had to be removed).
- Call waiting: a second caller during a call gets the busy signal.
- On Android 12 and later, the phone's own "vibrate for calls" setting can override Kid Phone's
  vibration. Not an issue on the Mi A2 Lite (Android 10).

The Nunito font is under the SIL Open Font License, see `FONT-LICENSE-Nunito.txt`.
