# Kid Launcher (the PC's home screen)

The child's home screen on the Windows PC, in Kid Phone's style. It runs in his Windows account,
starts when he signs in and fills the main screen:

- **"Hi Alejandro 👋"**, the time and date, and the laptop's battery (orange at 50% or less, red
  at 20% or less, a yellow bolt while the charger is in; hidden on a PC without a battery).
- **A big button for each thing he may open**: first the apps the parents switched on in Kid
  Phone Parent (Roblox…), with the minutes left on the corner (green, amber in the last five),
  then the everyday tools (Calculator, Notepad, Paint, Photos, Camera, Snipping Tool). One click
  opens it. An app whose program isn't on the PC shows "Not found on this PC".
- **Time warnings, the same as on his phone**: "⏰ 10 minutes left" and "⏰ 5 minutes left" for 7
  seconds each with Windows' notification sound, then for the last 30 seconds a countdown (red for
  the last ten), then "Time's up!" for 3 seconds. They float at the top of the screen over whatever
  is open, a game included, and clicks go straight through them. A reminder more than a minute late
  (the PC was locked) is skipped, and so is one that would come as the time starts. The KidLock
  agent closes the app when the time is up; the launcher only warns.
- It stays behind the apps he opens, so it is there again when they close. If he gets to the bare
  desktop, it comes back. Alt+F4 does nothing; only signing out or shutting down closes it. It runs
  once per signed-in user.

It is not the lock: what can run is decided by the KidLock rules (AppLocker) and the agent. This
only shows what is allowed and warns about time.

## Building

Needs the .NET SDK (any recent one; it builds for .NET Framework 4.8, which every Windows 11 has):

```
cd pc\launcher
dotnet build -c Release
```

The result is `pc\launcher\bin\Release\KidLauncher.exe`. That one file is all the PC needs (the
Nunito font is inside it); it goes in `C:\Program Files\KidLock\`, where his AppLocker rules let
it run. Ignore the `.config` and `.pdb` next to it.

## Trying it

- `KidLauncher.exe --snapshot <folder> [--state <now.json>]` draws the home screen and the warning
  cards into PNG files without opening any window: `home.png` (a made-up state with Roblox on for
  45 more minutes, unless `--state` is given), `home-standin.png` (Edge standing in for Roblox, to
  see a real program icon), `home-stale.png` (the agent not running), and `warn-10min.png`,
  `warn-5min.png`, `countdown-30.png`, `countdown-8.png`, `times-up.png`.
- `KidLauncher.exe --selftest` plays made-up hours through the warning rules and prints PASS or FAIL
  for each case (an hour, ten minutes, locked over a reminder, time changed or stopped during the
  countdown, two apps…).
- `KidLauncher.exe --window [--state <now.json>]` opens it as an ordinary 1280×800 window that
  closes normally.

It logs to `%LOCALAPPDATA%\KidLock\launcher.log` (kept small).

## now.json

What to show comes from `C:\ProgramData\KidLockLayout\now.json`, written by the KidLock agent
(running as SYSTEM) whenever something changes and at least every minute. He can read it but not
change it. The launcher reads it every second and keeps the last good copy if it is ever unreadable.

```json
{
  "writtenAt": 1761400000000,
  "hubOk": true,
  "tools": [{"label": "Calculator", "aumid": "Microsoft.WindowsCalculator_8wekyb3d8bbwe!App"}],
  "apps": [{"id": "roblox", "label": "Roblox",
            "launch": "%LOCALAPPDATA%\\Roblox\\Versions\\*\\RobloxPlayerBeta.exe",
            "allowed": true, "endsAt": 1761403600000, "minutes": 60}]
}
```

- `writtenAt`: when the agent wrote it (milliseconds since 1970, UTC). Older than 2 minutes means
  the agent isn't running: only the tools are offered, with a quiet note.
- `hubOk`: false shows a quiet "Not connected to home right now".
- `tools`: always offered, by Windows app ID (if the list is missing, the six tools above).
- `apps`: shown while `allowed` is true and `endsAt` (when its time is up, or `null` for no time
  limit) hasn't passed. `launch` is the program, with `%VARIABLES%` (expanded as the child) and `*`
  for "the newest folder that has it" (Roblox makes a new folder with each update).
- Any change of `endsAt` is a new time, as on the phone (the agent smooths out tiny differences).
