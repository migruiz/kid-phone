# Kid Phone on the child's Windows PC

Alejandro has his own Windows account on the family laptop (`mac-windows`, a MacBook Pro running
Windows 11 Pro through Boot Camp; the family's own account is `piscos`). In his account he can only
open what the parents allow, and the parents switch apps on from **Kid Phone Parent**, for a while
or with no time limit, the same way as the apps on his phone.

- **Every app on the PC is a switch in the parents' app.** The agent finds every app installed for
  him — the Store apps automatically, plus the desktop programs listed in `agent/apps.json` (Roblox,
  Chrome, Edge) — and sends them to the hub. In Kid Phone Parent: *Alejandro's PC* → *Apps on
  Alejandro's PC* → switch any of them on for 15 min, 30 min, 1 hour, 2 hours, or with no time limit.
  Within a second or two it opens on his PC. Nothing he can open changes the parts of Windows itself
  he needs.
- **The everyday tools start on:** Calculator, Notepad, Paint, Paint 3D, Photos, Camera and Snipping
  Tool are switched on to begin with, so they're always there until a parent turns one off.
- **Never offered, always blocked** (`blockPackaged`/`blockDesktop` in `apps.json`): the apps that
  would let him leave the locked setup or install anything — Windows Terminal, PowerShell, the
  command prompt, the Microsoft Store, Quick Assist, Dev Home, Power Automate, Git and Node.js — plus
  Task Manager, Control Panel, the registry editor and installers.
- **His screen:** a full-screen home screen like his phone's (`launcher/`), with big buttons for
  what he can open and the minutes left on a timed app. He gets **"10 minutes left"** and **"5
  minutes left"**, then a **30-second countdown** and "Time's up!", floating over the game. Then the
  app closes and is locked again. He can't close the home screen (closing is refused, Task Manager
  is blocked); if it ever stops, the agent starts it again within 15 seconds. The time limit doesn't
  depend on it: the agent closes the app on time either way.
- **His taskbar** shows only the apps he has open, the clock and the volume (and the Start button,
  which Windows won't let go). No pinned apps, search, Task View, Copilot, chat, Wi-Fi, battery,
  Bluetooth or security icons, language indicator, other apps' tray icons (Boot Camp keeps running
  hidden: it drives the brightness and volume keys), notifications panel, and he can't change the
  taskbar's settings. **Start** shows only his tools as pinned apps (Windows' "Configure Start
  pins" setting for his account). His **keyboard** is Irish, the only layout. **Widgets** (the news and
  weather button) are off for the whole laptop, the family account too: Windows only allows that
  laptop-wide, and refuses it from scripts, so the agent sets it through the device-management
  channel at start-up.
- **Away from the hub** (the laptop not at home, or the Pi down): a timed app still runs to the end
  of its time and closes on time, and stays locked after that. An app on with no time limit is
  locked 2 minutes after the hub was last heard from. Nothing can be switched on without the hub.
- Roblox progress lives on Roblox's servers, in his Roblox account; closing the game doesn't lose it.

## The parts

| Part | Runs as | What it does |
|------|---------|--------------|
| `agent/kidlock-agent.ps1` | SYSTEM, task *KidLock agent* (at start-up, and every 5 minutes if it isn't running) | Joins the hub as his PC, sends it the apps, waits for the parents' switches, applies his rules, closes apps whose time is up, writes `now.json` for the launcher, keeps the launcher running while he is signed in, switches widgets off |
| `agent/kidlock-rules.ps1` | (used by the others) | Builds his AppLocker rules and hands them to Windows |
| `agent/kidlock-policy.ps1` | SYSTEM, by hand | `-Mode Enabled` (normal), `AuditOnly` (log only) or `Off` (rules removed: the escape hatch) |
| `agent/kidlock-tidy.ps1` | SYSTEM, task *KidLock tidy at his sign-in* | Keeps his desktop and Start menu to the allowed tools, and sets his taskbar (see above) |
| `agent/apps.json` | | His account name, the hub address, the always-allowed tools, and the apps a parent can switch on |
| `launcher/` | Alejandro, task *KidLock launcher* (at his sign-in) | His full-screen home screen and the time warnings (see `launcher/README.md`) |

On the PC: the agent's files are in `C:\ProgramData\kidlock\` (admins and SYSTEM only: it holds
the hub token), with the log `kidlock.log`. The launcher is `C:\Program Files\KidLock\KidLauncher.exe`.
`C:\ProgramData\KidLockLayout\` is the one folder he can read: `now.json`.

### How the blocking works

Windows' **AppLocker** decides which programs his account may start. On Windows Pro, AppLocker set
the usual way (`Set-AppLockerPolicy`, Local Security Policy) is silently ignored. It is enforced
when the rules come from SYSTEM through the device-management channel (the "AppLocker CSP", reached
through the MDM bridge in WMI: `root\cimv2\mdm\dmmap`). That is why everything that changes the
rules runs as SYSTEM.

The rules: everyone may run anything; his account is denied everything except Windows' own folder
(minus the tools above), Windows' own built-in apps, the allowed tools, the launcher, and the
signer of each app a parent has switched on (Roblox: *O=ROBLOX CORPORATION, L=SAN MATEO,
S=CALIFORNIA, C=US*). Rules only stop programs from starting, so when time is up the agent also
closes the app's programs running in his account. It never touches the parents' account.

Things he can't get round from his account: he can't open Task Manager to stop the launcher, can't
change the rules (they need SYSTEM), can't read the hub token, and the laptop's clock doesn't matter
(times are counted from the hub's "seconds left").

### The hub

The PC talks to the hub's kid port at `http://192.168.0.11:8774` with its own token (see
`hub/API.md`, "PC routes"). It long-polls `GET /pc/state`, so a parent's switch reaches it at once.
The first time, it joins by itself (`POST /pc/register`, allowed while the hub has no PC). A
reinstalled PC needs a parent first: Kid Phone Parent → *Replace Alejandro's PC*, then within 10
minutes restart the agent (or run the installer again).

## Installing or updating

From this repo, with SSH access to the PC as `piscos` (an admin):

```
scp pc/agent/*.ps1 pc/agent/apps.json pc/launcher/bin/Release/KidLauncher.exe mac-windows:C:/Users/piscos/AppData/Local/Temp/kidlock-install/
ssh mac-windows "powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\piscos\AppData\Local\Temp\kidlock-install\install.ps1"
```

(Create the `kidlock-install` folder first if it isn't there.) The installer stops the agent,
copies the files, sets up the tasks, starts the agent and shows its first log lines. The hub token,
the switches, the mode and the log are kept. `-Hub http://...` points it at another hub (for
testing).

Store apps are found automatically. To add a **desktop program** a parent can switch on, add it to
the `desktop` list in `apps.json`: an `id` (lower case, e.g. `minecraft`), a `label`, where its
program is (`launch`, as Alejandro: `%LOCALAPPDATA%`, a `*` folder means the newest one), and its
`processes` (names without `.exe`, closed when its time is up). The agent reads its publisher and
product from the exe; add `"signerWide": true` for an app with several exes (like Roblox) so the
whole signer is allowed. To stop offering something, add it to `blockPackaged` (a Store app's
package name) or `blockDesktop` (a program's exe name). Then run the installer. Give a Store app a
nicer name with the `labels` map.

## Getting out if something goes wrong

- Unblock everything for him at once: run `kidlock-policy.ps1 -Mode Off` as SYSTEM (for example
  from an admin PowerShell: a one-shot scheduled task running
  `powershell -File C:\ProgramData\kidlock\kidlock-policy.ps1 -Mode Off` as SYSTEM). `-Mode Enabled`
  turns them back on. The agent leaves the rules alone while the mode is Off.
- See what happened: `C:\ProgramData\kidlock\kidlock.log`, and Windows' own record of what was
  blocked: Event Viewer → Applications and Services Logs → Microsoft → Windows → AppLocker
  (events 8004 and 8022 are blocks).
- The parents' own account is never affected by any of this.
