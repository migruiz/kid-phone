# Installs or updates KidLock on the child's PC. Run as an admin (piscos), e.g. over SSH, from a
# folder holding this repo's pc\agent files and, optionally, KidLauncher.exe:
#
#   powershell -ExecutionPolicy Bypass -File install.ps1 [-Hub http://192.168.0.11:8774]
#
# Safe to run again for updates: the hub token, the parents' switches (grants.json), the mode and
# the log are kept. See pc\README.md.

param([string]$Hub = '')

$ErrorActionPreference = 'Stop'
$src = $PSScriptRoot
$dir = 'C:\ProgramData\kidlock'
$bin = 'C:\Program Files\KidLock'
$pub = 'C:\ProgramData\KidLockLayout'

$config = Get-Content (Join-Path $src 'apps.json') -Raw | ConvertFrom-Json
$user = $config.user
Get-LocalUser $user | Out-Null   # stops here if the child's account doesn't exist

# 1. Stop the agent while its files change.
if (Get-ScheduledTask -TaskName 'KidLock agent' -ErrorAction SilentlyContinue) { Stop-ScheduledTask -TaskName 'KidLock agent' }
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object CommandLine -like '*kidlock-agent.ps1*' | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

# 2. The agent's folder: admins and SYSTEM only (it holds the hub token).
New-Item -ItemType Directory $dir -Force | Out-Null
icacls $dir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
foreach ($f in 'kidlock-rules.ps1', 'kidlock-agent.ps1', 'kidlock-policy.ps1', 'kidlock-tidy.ps1', 'apps.json') {
    Copy-Item (Join-Path $src $f) $dir -Force
}
if ($Hub) {
    $c = Get-Content "$dir\apps.json" -Raw | ConvertFrom-Json
    $c.hub = $Hub
    [IO.File]::WriteAllText("$dir\apps.json", ($c | ConvertTo-Json -Depth 5))
}
Remove-Item "$dir\kidlock-boot.ps1" -ErrorAction SilentlyContinue   # replaced by the agent

# 3. The folder he can read (taskbar layout, now.json).
New-Item -ItemType Directory $pub -Force | Out-Null
icacls $pub /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-32-545:(OI)(CI)RX' | Out-Null

# 4. The launcher (Program Files: only admins can change it, so his rules can allow it).
# Replaced only when it changed: that closes his home screen, which its task starts again within a minute.
$launcher = Join-Path $src 'KidLauncher.exe'
$installed = Join-Path $bin 'KidLauncher.exe'
if ((Test-Path $launcher) -and (-not (Test-Path $installed) -or (Get-FileHash $launcher).Hash -ne (Get-FileHash $installed).Hash)) {
    New-Item -ItemType Directory $bin -Force | Out-Null
    $running = @(Get-Process KidLauncher -ErrorAction SilentlyContinue)
    $running | Stop-Process -Force
    $running | Wait-Process -Timeout 15 -ErrorAction SilentlyContinue
    for ($i = 0; ; $i++) {
        try { Copy-Item $launcher $bin -Force -ErrorAction Stop; break }
        catch { if ($i -ge 10) { throw }; Start-Sleep 1 }
    }
    "launcher updated"
}

# 5. Tasks. Both must run on battery (the laptop often is), and never time out.
$settings = { New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew }
$every = { param($minutes) (New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes $minutes)).Repetition }

Unregister-ScheduledTask -TaskName 'KidLock startup check' -Confirm:$false -ErrorAction SilentlyContinue

$atStart = New-ScheduledTaskTrigger -AtStartup
$keepAlive = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 5)
Register-ScheduledTask -TaskName 'KidLock agent' -Force `
    -Description "Keeps $user's apps in step with the Kid Phone hub (C:\ProgramData\kidlock). Started at boot and every 5 minutes if not running." `
    -Principal (New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest) `
    -Action (New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$dir\kidlock-agent.ps1`"") `
    -Trigger @($atStart, $keepAlive) -Settings (& $settings) | Out-Null

if (Test-Path "$bin\KidLauncher.exe") {
    $atLogOn = New-ScheduledTaskTrigger -AtLogOn -User "$env:COMPUTERNAME\$user"
    $atLogOn.Repetition = & $every 1   # back within a minute if it ever stops
    Register-ScheduledTask -TaskName 'KidLock launcher' -Force `
        -Description "$user's full-screen home screen and time warnings, started when he signs in." `
        -Principal (New-ScheduledTaskPrincipal -UserId "$env:COMPUTERNAME\$user" -LogonType Interactive -RunLevel Limited) `
        -Action (New-ScheduledTaskAction -Execute "$bin\KidLauncher.exe") `
        -Trigger $atLogOn -Settings (& $settings) | Out-Null
}

# 6. Start the agent and show what it logs in its first moments.
$before = (Get-Content "$dir\kidlock.log" -ErrorAction SilentlyContinue).Count
Start-ScheduledTask -TaskName 'KidLock agent'
$deadline = (Get-Date).AddSeconds(60)
do {
    Start-Sleep 2
    $new = @(Get-Content "$dir\kidlock.log" -ErrorAction SilentlyContinue | Select-Object -Skip $before)
} until (($new -match 'rules applied|mode is Off|FAILED|error:') -or (Get-Date) -gt $deadline)
$new
"--- tasks"
Get-ScheduledTask -TaskName 'KidLock*' | ForEach-Object { "$($_.TaskName): $($_.State)" }
