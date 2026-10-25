# KidLock agent: keeps the child's Windows account in step with the home hub. Runs as SYSTEM all
# the time (scheduled task "KidLock agent": at start-up, and every 5 minutes if it isn't running).
#
# - Joins the hub as the child's PC once; the token is kept in hub-token.txt (admin/SYSTEM only).
# - Sends the hub the apps from apps.json that are on the PC, with their icons, for the parents' app.
# - Waits on the hub for the parents' switches (in the background). An app switched on gets its
#   signer allowed in his rules; when it is switched off or its time is up, the rules lock it
#   again and its programs are closed.
# - Keeps the times itself too (grants.json) and checks them twice a second whatever the network
#   does, so an app closes on time also when the hub can't be reached, right after the laptop
#   wakes up, and after a restart. An app on with no time limit needs the hub: it is locked again
#   2 minutes after the hub was last heard from.
# - Writes what his launcher shows to C:\ProgramData\KidLockLayout\now.json (he can read it).
#
# Log: C:\ProgramData\kidlock\kidlock.log (only changes are logged, not every check).

. "$PSScriptRoot\kidlock-rules.ps1"

$AgentVersion = 10900           # sent to the hub as X-App-Version (1.9.0)
$Who          = 'agent'
$TokenFile    = Join-Path $KidLockDir 'hub-token.txt'
$GrantsFile   = Join-Path $KidLockDir 'grants.json'
$UnlockedFile = Join-Path $KidLockDir 'unlocked.txt'
$NowFile      = 'C:\ProgramData\KidLockLayout\now.json'
$HubSilenceMs = 120000          # an app on with no time limit is locked this long after the hub was last heard
$SameEndMs    = 5000            # the same end time heard again a moment later keeps its old value
$ListEveryMs  = 600000          # look for installed apps (and send them if changed) this often
$RulesCheckMs = 300000          # make sure the rules are still in place this often
$CloseCheckMs = 5000            # look for locked apps still running this often
$NowEveryMs   = 20000           # rewrite now.json at least this often (the launcher sees the agent is alive)
$PollWaitSec  = 25              # how long the hub may hold a request open
$LauncherCheckMs = 15000        # make sure his home screen is running this often

Add-Type -AssemblyName System.Net.Http
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
public static class KidIcon {
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern uint PrivateExtractIcons(string file, int index, int cx, int cy, IntPtr[] icons, uint[] ids, uint count, uint flags);
    [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr icon);
    // The program's own icon at the given size, as a base64 PNG (null if it has none).
    public static string Png(string file, int size) {
        var icons = new IntPtr[1]; var ids = new uint[1];
        if (PrivateExtractIcons(file, 0, size, size, icons, ids, 1, 0) == 0 || icons[0] == IntPtr.Zero) return null;
        try {
            using (var icon = Icon.FromHandle(icons[0]))
            using (var bmp = icon.ToBitmap())
            using (var png = new MemoryStream()) { bmp.Save(png, ImageFormat.Png); return Convert.ToBase64String(png.ToArray()); }
        } finally { DestroyIcon(icons[0]); }
    }
}
'@

function NowMs { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }

# Logs a message once, and again only when it changes (for things that repeat, like "hub not reachable").
$script:lastNote = @{}
function Note([string]$Topic, [string]$Message) {
    if ($script:lastNote[$Topic] -ne $Message) { $script:lastNote[$Topic] = $Message; Write-KidLog $Message $Who }
}

# ---------- the hub ----------

$script:http = New-Object System.Net.Http.HttpClient
$script:http.Timeout = [TimeSpan]::FromSeconds(90)   # each request also has its own, shorter limit

# Starts a request to the hub and returns it as a task; the main loop picks up the answer.
function Start-HubRequest([string]$Method, [string]$Path, $Body = $null, [int]$TimeoutSec = 15, [switch]$NoAuth) {
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$($script:config.hub)$Path")
    $req.Headers.Add('X-App-Version', "$AgentVersion")
    if (-not $NoAuth) { $req.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $script:token) }
    if ($null -ne $Body) {
        $req.Content = [System.Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 6 -Compress), [Text.Encoding]::UTF8, 'application/json')
    }
    $cts = [System.Threading.CancellationTokenSource]::new()
    $cts.CancelAfter([TimeSpan]::FromSeconds($TimeoutSec))
    [pscustomobject]@{ Task = $script:http.SendAsync($req, $cts.Token); Request = $req; Cancel = $cts }
}

# The answer of a finished request: Status (0 = the hub couldn't be reached) and Json.
function Read-HubAnswer($call) {
    try {
        if ($call.Task.IsFaulted -or $call.Task.IsCanceled) {
            $why = if ($call.Task.IsCanceled) { 'no answer in time' } else { $call.Task.Exception.GetBaseException().Message }
            return [pscustomobject]@{ Status = 0; Json = $null; Error = $why }
        }
        $res = $call.Task.Result
        $text = $res.Content.ReadAsStringAsync().Result
        $json = if ($text) { try { $text | ConvertFrom-Json } catch { $null } } else { $null }
        $res.Dispose()
        [pscustomobject]@{ Status = [int]$res.StatusCode; Json = $json; Error = $(if ($json.message) { $json.message } else { "HTTP $([int]$res.StatusCode)" }) }
    } finally {
        $call.Request.Dispose(); $call.Cancel.Dispose()
    }
}

# For the rare calls the loop can wait for (joining, sending the apps): at most 15 seconds.
# Returns the answer (Status, Json, Error); Status 0 means the hub couldn't be reached.
function Invoke-Hub([string]$Method, [string]$Path, $Body = $null, [switch]$NoAuth) {
    $call = Start-HubRequest $Method $Path $Body 15 -NoAuth:$NoAuth
    try { [void]$call.Task.Wait([TimeSpan]::FromSeconds(16)) } catch {}   # a failure shows in the answer
    Read-HubAnswer $call
}

function Join-Hub {
    $body = @{}
    if ($script:token) { $body.token = $script:token }
    $a = Invoke-Hub POST '/pc/register' $body -NoAuth
    if ($a.Status -eq 403) {
        $script:token = $null
        throw 'the hub already has another PC: a parent must allow a new one (Kid Phone Parent: Replace his PC)'
    }
    if ($a.Status -ne 200) { throw "joining the hub: $($a.Error)" }
    $script:token = $a.Json.token
    [IO.File]::WriteAllText($TokenFile, $script:token)
    $script:listKey = $null
    $script:listCheckedAt = 0
    $script:version = $null
    Write-KidLog 'joined the hub as the PC' $Who
}

function Get-HisProfile {
    (Get-CimInstance Win32_UserProfile | Where-Object SID -eq $script:sid).LocalPath
}

# Where an app's program is for him: env vars as his, a * folder = the newest one that has the file.
function Resolve-Launch([string]$Pattern, [string]$Profile) {
    if (-not $Profile) { return $null }
    $p = $Pattern -replace '%LOCALAPPDATA%', "$Profile\AppData\Local" -replace '%APPDATA%', "$Profile\AppData\Roaming" -replace '%USERPROFILE%', $Profile
    $p = [Environment]::ExpandEnvironmentVariables($p)
    $star = $p.IndexOf('\*\')
    if ($star -lt 0) { if (Test-Path -LiteralPath $p) { return $p } else { return $null } }
    $base = $p.Substring(0, $star)
    $rest = $p.Substring($star + 3)
    foreach ($d in Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending) {
        $candidate = Join-Path $d.FullName $rest
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $null
}

# The apps from apps.json that are on the PC, for the parents' app; sent when the list changes.
function Send-AppList {
    $prof = Get-HisProfile
    $items = @()
    foreach ($app in $script:config.apps) {
        $exe = Resolve-Launch $app.launch $prof
        if (-not $exe) { continue }
        $item = @{ id = $app.id; label = $app.label }
        $png = try { [KidIcon]::Png($exe, 128) } catch { $null }
        if ($png) { $item.iconPng = $png }
        $items += $item
    }
    $key = ($items | ForEach-Object { "$($_.id)=$($_.iconPng.Length)" }) -join ';'
    if ($key -ne $script:listKey) {
        $a = Invoke-Hub PUT '/pc/apps' @{ apps = $items }
        if ($a.Status -ne 200) { throw "sending the apps: $($a.Error)" }
        $script:listKey = $key
        Write-KidLog ("apps on the PC sent to the hub: " + $(if ($items) { ($items | ForEach-Object { $_.label }) -join ', ' } else { 'none' })) $Who
    }
    $script:listCheckedAt = NowMs
}

# ---------- the parents' switches, kept here ----------

function Save-Grants {
    $out = @{ hubSeenAt = $script:hubSeenAt; grants = $script:grants }
    [IO.File]::WriteAllText($GrantsFile, ($out | ConvertTo-Json -Depth 4))
}

function Read-Grants {
    $script:grants = @{}
    $script:hubSeenAt = 0
    if (-not (Test-Path $GrantsFile)) { return }
    try {
        $saved = Get-Content $GrantsFile -Raw | ConvertFrom-Json
        $script:hubSeenAt = [long]$saved.hubSeenAt
        foreach ($p in $saved.grants.PSObject.Properties) {
            $script:grants[$p.Name] = @{ endsAt = $(if ($null -ne $p.Value.endsAt) { [long]$p.Value.endsAt } else { $null }); minutes = $p.Value.minutes }
        }
    } catch { Write-KidLog "grants.json unreadable, starting with nothing switched on: $($_.Exception.Message)" $Who }
}

# The hub's answer: every app switched on, and the time left of those on for a while.
function Update-Grants($state, [long]$Now) {
    $new = @{}
    foreach ($id in @($state.allowed | Where-Object { $_ })) { $new[$id] = @{ endsAt = $null; minutes = $null } }
    foreach ($t in @($state.appTimes | Where-Object { $_ -and $_.id })) {
        $heard = $Now + [long]$t.remainingSeconds * 1000
        $known = if ($script:grants.ContainsKey($t.id)) { $script:grants[$t.id].endsAt } else { $null }
        if ($null -ne $known -and [math]::Abs($known - $heard) -lt $SameEndMs) { $heard = $known }
        $new[$t.id] = @{ endsAt = $heard; minutes = $t.minutes }
    }
    $before = ($script:grants.Keys | Sort-Object | ForEach-Object { "$_@$($script:grants[$_].endsAt)" }) -join ','
    $after  = ($new.Keys | Sort-Object | ForEach-Object { "$_@$($new[$_].endsAt)" }) -join ','
    $script:grants = $new
    $script:hubSeenAt = $Now
    if ($before -ne $after) {
        $what = ($new.Keys | Sort-Object | ForEach-Object { if ($null -ne $new[$_].endsAt) { "$_ for $([math]::Ceiling(($new[$_].endsAt - $Now) / 60000)) min" } else { "$_ with no time limit" } }) -join ', '
        Write-KidLog ("hub: " + $(if ($what) { "switched on: $what" } else { 'nothing switched on' })) $Who
    }
    # Saved on every change, and once a minute to keep "hub last heard" fresh on disk.
    if ($before -ne $after -or ($Now - $script:grantsSavedAt) -ge 60000) {
        Save-Grants
        $script:grantsSavedAt = $Now
    }
}

# The apps he may use right now: switched on, with time left (or no limit while the hub answers).
function Get-Unlocked([long]$Now) {
    $known = @($script:config.apps | ForEach-Object { $_.id })
    @($script:grants.Keys | Where-Object {
        $g = $script:grants[$_]
        ($known -contains $_) -and $(if ($null -ne $g.endsAt) { $Now -lt $g.endsAt } else { ($Now - $script:hubSeenAt) -lt $HubSilenceMs })
    } | Sort-Object)
}

# ---------- making it so ----------

# Closes an app's programs running in his account (only his: never a parent's).
function Close-App($app) {
    $mine = "$env:COMPUTERNAME\$($script:config.user)"
    $procs = @(Get-Process -Name $app.processes -IncludeUserName -ErrorAction SilentlyContinue | Where-Object { $_.UserName -eq $mine })
    if (-not $procs) { return }
    $procs | Stop-Process -Force -ErrorAction SilentlyContinue
    Write-KidLog "closed $($app.label) ($($procs.Count) program(s) running)" $Who
}

function Sync-Rules([string[]]$Unlocked, [long]$Now) {
    if (($Now - $script:modeReadAt) -ge 10000) { $script:mode = Get-KidLockMode; $script:modeReadAt = $Now }
    if ($script:mode -eq 'Off') { Note 'mode' 'mode is Off: rules and closing left alone'; return }
    Note 'mode' "mode is $($script:mode)"
    $key = $Unlocked -join ','
    $changed = $key -ne $script:appliedKey
    $check = ($Now - $script:rulesCheckedAt) -ge $RulesCheckMs
    if ($changed -or ($check -and -not (Test-KidLockRules))) {
        Set-KidLockRules -Mode $script:mode -Unlocked $Unlocked -Who $Who
        $script:appliedKey = $key
        [IO.File]::WriteAllText($UnlockedFile, $key)
    }
    if ($check) { $script:rulesCheckedAt = $Now }
    # Rules only stop programs from starting: close what is running and no longer allowed, at once
    # when something was locked, and every few seconds anyway (in case anything slipped through).
    if ($script:mode -eq 'Enabled' -and ($changed -or ($Now - $script:closeCheckedAt) -ge $CloseCheckMs)) {
        foreach ($app in $script:config.apps) { if ($Unlocked -notcontains $app.id) { Close-App $app } }
        $script:closeCheckedAt = $Now
    }
}

# What his launcher shows: the tools, and each app with whether it's on and until when.
function Write-Now([string[]]$Unlocked, [long]$Now) {
    $apps = foreach ($app in $script:config.apps) {
        $on = $Unlocked -contains $app.id
        $g = if ($on) { $script:grants[$app.id] } else { $null }
        [ordered]@{ id = $app.id; label = $app.label; launch = $app.launch; allowed = $on
                    endsAt = $(if ($g) { $g.endsAt } else { $null }); minutes = $(if ($g) { $g.minutes } else { $null }) }
    }
    $tools = foreach ($t in $script:config.tools) { [ordered]@{ label = $t.label; aumid = $t.aumid } }
    $hubOk = ($Now - $script:hubSeenAt) -lt $HubSilenceMs
    $content = (@{ hubOk = $hubOk; tools = @($tools); apps = @($apps) } | ConvertTo-Json -Depth 5 -Compress)
    if ($content -eq $script:nowContent -and ($Now - $script:nowWrittenAt) -lt $NowEveryMs) { return }

    $doc = [ordered]@{ writtenAt = $Now; hubOk = $hubOk; tools = @($tools); apps = @($apps) }
    $temp = "$NowFile.tmp"
    [IO.File]::WriteAllText($temp, ($doc | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding $false))
    for ($i = 0; $i -lt 10; $i++) {
        try {
            Move-Item -LiteralPath $temp -Destination $NowFile -Force -ErrorAction Stop
            $script:nowContent = $content
            $script:nowWrittenAt = $Now
            return
        } catch { Start-Sleep -Milliseconds 50 }
    }
    Note 'now' "could not update now.json: $($Error[0].Exception.Message)"
}

# His home screen (and the time warnings) runs whenever he is signed in: if it isn't running, its
# task starts it again in his session.
function Sync-Launcher([long]$Now) {
    if (($Now - $script:launcherCheckedAt) -lt $LauncherCheckMs) { return }
    $script:launcherCheckedAt = $Now
    $mine = "$env:COMPUTERNAME\$($script:config.user)"
    $signedIn = [bool](Get-Process explorer -IncludeUserName -ErrorAction SilentlyContinue | Where-Object UserName -eq $mine)
    if (-not $signedIn) { return }
    if (Get-Process KidLauncher -IncludeUserName -ErrorAction SilentlyContinue | Where-Object UserName -eq $mine) { return }
    if (-not (Get-ScheduledTask -TaskName 'KidLock launcher' -ErrorAction SilentlyContinue)) { return }
    Start-ScheduledTask -TaskName 'KidLock launcher'
    Write-KidLog 'his home screen was not running: started it' $Who
}

# ---------- the hub, in the background ----------

# Starts the next long-poll, after joining the hub and sending the apps when needed.
function Start-Poll([long]$Now) {
    if (-not $script:token) { Join-Hub }
    if (($Now - $script:listCheckedAt) -ge $ListEveryMs) { Send-AppList }
    $q = if ($null -ne $script:version) { "?since=$($script:version)&wait=$PollWaitSec" } else { '' }
    $script:poll = Start-HubRequest GET "/pc/state$q" -TimeoutSec ($PollWaitSec + 15)
}

# Handles the long-poll's answer; returns when to start the next one.
function Complete-Poll([long]$Now) {
    $a = Read-HubAnswer $script:poll
    $script:poll = $null
    switch ($a.Status) {
        200 {
            $script:version = $a.Json.version
            Update-Grants $a.Json $Now
            Note 'hub' 'hub reachable'
            return $Now
        }
        401 {
            Note 'hub' 'the hub no longer knows this PC: joining again'
            try { Join-Hub; return $Now } catch { Note 'hub' "$_"; return $Now + 30000 }
        }
        0 { Note 'hub' "hub not reachable: $($a.Error)"; return $Now + 5000 }
        default { Note 'hub' "hub answered $($a.Status): $($a.Error)"; return $Now + 10000 }
    }
}

# ---------- start ----------

$script:config = Get-KidConfig
$script:sid = (Get-LocalUser -Name $script:config.user -ErrorAction Stop).SID.Value
$script:token = if (Test-Path $TokenFile) { (Get-Content $TokenFile -Raw).Trim() } else { $null }
$script:version = $null
$script:poll = $null
$script:nextPollAt = 0
$script:appliedKey = $null      # nothing applied by this run yet: the first round applies the rules
$script:mode = Get-KidLockMode
$script:modeReadAt = NowMs
$script:rulesCheckedAt = 0
$script:closeCheckedAt = 0
$script:listCheckedAt = 0
$script:listKey = $null
$script:grantsSavedAt = 0
$script:nowContent = $null
$script:nowWrittenAt = 0
$script:launcherCheckedAt = (NowMs) + 30000   # not in the first moments: a sign-in starts it by itself
Read-Grants
Start-KidLockEngine
Write-KidLog "agent started (version $AgentVersion, hub $($script:config.hub))" $Who
try { Disable-KidWidgets $Who } catch { Write-KidLog "could not switch widgets off: $($_.Exception.Message)" $Who }

$lines = Get-Content $KidLockLog -ErrorAction SilentlyContinue
if ($lines.Count -gt 3000) { $lines | Select-Object -Last 2000 | Set-Content $KidLockLog }

while ($true) {
    try {
        $now = NowMs
        if ($script:poll) {
            if ($script:poll.Task.IsCompleted) { $script:nextPollAt = Complete-Poll $now }
        } elseif ($now -ge $script:nextPollAt) {
            try { Start-Poll $now } catch {
                Note 'hub' "$_"
                $script:nextPollAt = $now + 15000
            }
        }
        $now = NowMs
        $unlocked = Get-Unlocked $now
        Sync-Rules $unlocked $now
        Write-Now $unlocked $now
        Sync-Launcher $now
    } catch {
        Note 'error' "error: $($_.Exception.Message) at line $($_.InvocationInfo.ScriptLineNumber)"
        Start-Sleep -Seconds 2
    }
    Start-Sleep -Milliseconds 500
}
