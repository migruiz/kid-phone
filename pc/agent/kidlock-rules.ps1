# KidLock rules: what the child's Windows account may run. Dot-sourced by kidlock-agent.ps1 and
# kidlock-policy.ps1; defines functions only.
#
# Windows only accepts these rules from SYSTEM, through the device-management "AppLocker CSP"
# channel (the MDM bridge). On Windows Pro, AppLocker set the usual way (Set-AppLockerPolicy) is
# ignored: nothing is blocked and nothing is logged.
#
# Only his account is affected: every rule either allows everyone, or denies his account alone.
# Shape: everyone may run anything; he is denied everything except Windows' own folder and apps
# (minus the tools listed below), the always-allowed tools in apps.json, the launcher, and the
# apps in apps.json while a parent has switched them on (by their signer).

$KidLockDir = 'C:\ProgramData\kidlock'
$KidLockLog = Join-Path $KidLockDir 'kidlock.log'

function Write-KidLog([string]$Message, [string]$Who = '') {
    $tag = if ($Who) { "[$Who] " } else { '' }
    "$(Get-Date -Format s)  $tag$Message" | Add-Content -Path $KidLockLog
}

function Get-KidConfig { Get-Content (Join-Path $KidLockDir 'apps.json') -Raw | ConvertFrom-Json }

$KidLockNs      = 'root\cimv2\mdm\dmmap'
$KidLockParent  = './Vendor/MSFT/AppLocker/ApplicationLaunchRestrictions/KidLock'
$KidLockClasses = [ordered]@{ EXE = 'MDM_AppLocker_ApplicationLaunchRestrictions01_EXE03'; StoreApps = 'MDM_AppLocker_ApplicationLaunchRestrictions01_StoreApps03' }

# Program folders outside Windows that Windows itself needs in his session, plus the launcher.
$KidAllowedPaths = @(
    '%WINDIR%\*',
    '%PROGRAMFILES%\WindowsApps\*',                       # packaged apps: decided by the packaged-app rules
    '%PROGRAMFILES%\Microsoft\EdgeWebView\*',              # WebView2: used by parts of Windows (not a browser)
    '%PROGRAMFILES%\Windows Defender\*',
    '%OSDRIVE%\ProgramData\Microsoft\Windows Defender\Platform\*',  # Defender's own updates (admin-only folder)
    '%PROGRAMFILES%\Common Files\microsoft shared\ink\*',  # touch keyboard and handwriting
    '%PROGRAMFILES%\Boot Camp\Bootcamp.exe',               # brightness and volume keys
    '%PROGRAMFILES%\KidLock\*'                             # his launcher (admin-only folder)
)

# Windows tools he may not open even though they live in the Windows folder.
$KidDeniedTools = @(
    '%SYSTEM32%\cmd.exe', '%SYSTEM32%\WindowsPowerShell\*', '%SYSTEM32%\wscript.exe', '%SYSTEM32%\cscript.exe',
    '%SYSTEM32%\mshta.exe', '%WINDIR%\regedit.exe', '%SYSTEM32%\regedt32.exe', '%SYSTEM32%\reg.exe',
    '%SYSTEM32%\Taskmgr.exe', '%SYSTEM32%\mmc.exe', '%SYSTEM32%\control.exe', '%SYSTEM32%\msiexec.exe',
    '%SYSTEM32%\certutil.exe', '%SYSTEM32%\bitsadmin.exe', '%SYSTEM32%\wsl.exe', '%SYSTEM32%\bash.exe',
    '%SYSTEM32%\wslconfig.exe', '%SYSTEM32%\regsvr32.exe', '%SYSTEM32%\msra.exe', '%SYSTEM32%\mstsc.exe',
    '%SYSTEM32%\forfiles.exe', '%SYSTEM32%\pcalua.exe', '%SYSTEM32%\PresentationHost.exe',
    '%SYSTEM32%\RecoveryDrive.exe', '%SYSTEM32%\systemreset.exe',
    '%SYSTEM32%\SecurityHealthSystray.exe',   # only the shield in the tray; Windows Security keeps protecting the PC
    '%WINDIR%\Microsoft.NET\*', '%WINDIR%\hh.exe'
)

# Folders inside Windows that ordinary users can write to: nothing there may run for him.
$KidDeniedWritable = @(
    '%WINDIR%\Tasks\*', '%WINDIR%\Temp\*', '%WINDIR%\tracing\*', '%WINDIR%\Registration\CRMLog\*',
    '%WINDIR%\PLA\*', '%WINDIR%\debug\*', '%WINDIR%\ServiceState\*',
    '%SYSTEM32%\FxsTmp\*', '%SYSTEM32%\com\dmp\*', '%SYSTEM32%\Microsoft\Crypto\RSA\MachineKeys\*',
    '%SYSTEM32%\spool\PRINTERS\*', '%SYSTEM32%\spool\SERVERS\*', '%SYSTEM32%\spool\drivers\color\*',
    '%SYSTEM32%\Tasks\*', '%SYSTEM32%\Tasks_Migrated\*', '%SYSTEM32%\LogFiles\*'
)

function KidX([string]$s) { [System.Security.SecurityElement]::Escape($s) }
function KidPathCond($p) { "<FilePathCondition Path=`"$(KidX $p)`" />" }
function KidPubCond($pub, $prod) {
    "<FilePublisherCondition PublisherName=`"$(KidX $pub)`" ProductName=`"$(KidX $prod)`" BinaryName=`"*`"><BinaryVersionRange LowSection=`"0.0.0.0`" HighSection=`"*`" /></FilePublisherCondition>"
}

function Remove-KidLockRules {
    foreach ($c in $KidLockClasses.Values) {
        Get-CimInstance -Namespace $KidLockNs -ClassName $c -ErrorAction SilentlyContinue | Where-Object ParentID -eq $KidLockParent | Remove-CimInstance
    }
}

function Test-KidLockRules {
    [bool](Get-CimInstance -Namespace $KidLockNs -ClassName $KidLockClasses.EXE -ErrorAction SilentlyContinue | Where-Object ParentID -eq $KidLockParent)
}

# Windows' app checking (AppIDSvc and its filter driver) must run from boot, before anyone signs in.
function Start-KidLockEngine {
    Set-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Services\AppIDSvc' -Name Start -Value 2
    Start-Service AppIDSvc -ErrorAction SilentlyContinue
    sc.exe start applockerfltr | Out-Null
}

<#
  Applies his rules. -Mode: AuditOnly (nothing blocked, Windows logs what it would block), Enabled
  (blocking on) or Off (rules removed). -Unlocked: ids of apps in apps.json a parent has switched
  on right now; their signer is allowed for him.
  Changes the rules in place when they exist, so there is never a moment without them.
#>
function Set-KidLockRules {
    param([ValidateSet('AuditOnly', 'Enabled', 'Off')][string]$Mode, [string[]]$Unlocked = @(), [string]$Who = '')
    if ($Mode -eq 'Off') {
        Remove-KidLockRules
        'Off' | Set-Content (Join-Path $KidLockDir 'mode.txt')
        Write-KidLog 'rules removed (mode Off)' $Who
        return
    }
    $config = Get-KidConfig
    $sid = (Get-LocalUser -Name $config.user -ErrorAction Stop).SID.Value
    $signers = @($config.apps | Where-Object { $Unlocked -contains $_.id } | ForEach-Object { $_.signer } | Where-Object { $_ } | Sort-Object -Unique)

    # Desktop programs
    $exe = New-Object System.Text.StringBuilder
    [void]$exe.Append("<RuleCollection Type=`"Exe`" EnforcementMode=`"$Mode`">")
    [void]$exe.Append("<FilePathRule Id=`"$([guid]::NewGuid())`" Name=`"Everyone else: anything`" Description=`"`" UserOrGroupSid=`"S-1-1-0`" Action=`"Allow`"><Conditions>$(KidPathCond '*')</Conditions></FilePathRule>")
    $exceptions = @($KidAllowedPaths | ForEach-Object { KidPathCond $_ }) + @($signers | ForEach-Object { KidPubCond $_ '*' })
    [void]$exe.Append("<FilePathRule Id=`"$([guid]::NewGuid())`" Name=`"$(KidX $config.user): only Windows and allowed programs`" Description=`"`" UserOrGroupSid=`"$sid`" Action=`"Deny`"><Conditions>$(KidPathCond '*')</Conditions><Exceptions>$($exceptions -join '')</Exceptions></FilePathRule>")
    foreach ($p in $KidDeniedTools + $KidDeniedWritable) {
        [void]$exe.Append("<FilePathRule Id=`"$([guid]::NewGuid())`" Name=`"$(KidX $config.user): not $(KidX $p)`" Description=`"`" UserOrGroupSid=`"$sid`" Action=`"Deny`"><Conditions>$(KidPathCond $p)</Conditions></FilePathRule>")
    }
    [void]$exe.Append('</RuleCollection>')

    # Packaged (Store-style) apps: Windows' own system apps plus the always-allowed tools
    $msWindows = 'CN=Microsoft Windows, O=Microsoft Corporation, L=Redmond, S=Washington, C=US'
    $msCorp    = 'CN=Microsoft Corporation, O=Microsoft Corporation, L=Redmond, S=Washington, C=US'
    $appx = New-Object System.Text.StringBuilder
    [void]$appx.Append("<RuleCollection Type=`"Appx`" EnforcementMode=`"$Mode`">")
    [void]$appx.Append("<FilePublisherRule Id=`"$([guid]::NewGuid())`" Name=`"Everyone else: any packaged app`" Description=`"`" UserOrGroupSid=`"S-1-1-0`" Action=`"Allow`"><Conditions>$(KidPubCond '*' '*')</Conditions></FilePublisherRule>")
    $appxExceptions = @(KidPubCond $msWindows '*') + @($config.tools | ForEach-Object { KidPubCond $msCorp $_.package })
    [void]$appx.Append("<FilePublisherRule Id=`"$([guid]::NewGuid())`" Name=`"$(KidX $config.user): only Windows' own and allowed apps`" Description=`"`" UserOrGroupSid=`"$sid`" Action=`"Deny`"><Conditions>$(KidPubCond '*' '*')</Conditions><Exceptions>$($appxExceptions -join '')</Exceptions></FilePublisherRule>")
    [void]$appx.Append('</RuleCollection>')

    # Check the XML is well formed before handing it to Windows
    [xml]$exe.ToString() | Out-Null
    [xml]$appx.ToString() | Out-Null
    $exe.ToString()  | Set-Content (Join-Path $KidLockDir 'last-exe-rules.xml')
    $appx.ToString() | Set-Content (Join-Path $KidLockDir 'last-app-rules.xml')

    $policies = @{ EXE = $exe.ToString(); StoreApps = $appx.ToString() }
    foreach ($name in $KidLockClasses.Keys) {
        $class = $KidLockClasses[$name]
        $current = Get-CimInstance -Namespace $KidLockNs -ClassName $class -ErrorAction SilentlyContinue | Where-Object ParentID -eq $KidLockParent
        if ($current) {
            $current.Policy = KidX $policies[$name]
            Set-CimInstance -CimInstance $current -ErrorAction Stop
        } else {
            New-CimInstance -Namespace $KidLockNs -ClassName $class -Property @{ ParentID = $KidLockParent; InstanceID = $name; Policy = (KidX $policies[$name]) } -ErrorAction Stop | Out-Null
        }
    }
    Start-KidLockEngine
    $Mode | Set-Content (Join-Path $KidLockDir 'mode.txt')
    $what = if ($Unlocked) { 'switched on: ' + ($Unlocked -join ', ') } else { 'no apps switched on' }
    Write-KidLog "rules applied, mode $Mode, $what" $Who
}

<#
  No widgets (news and weather) on the taskbar. Windows only allows this for the whole laptop, so
  the family account loses them too (the parents' choice, 25/10/2026). Windows refuses this setting
  from scripts and programs (its "UCPD" guard), but takes it through the device-management channel,
  as SYSTEM, like his rules.
#>
function Disable-KidWidgets([string]$Who = '') {
    $class = 'MDM_Policy_Config01_NewsAndInterests02'
    $c = Get-CimInstance -Namespace $KidLockNs -ClassName $class -ErrorAction SilentlyContinue
    if ($c -and $c.AllowNewsAndInterests -eq 0) { return }
    if ($c) {
        $c.AllowNewsAndInterests = 0
        Set-CimInstance -CimInstance $c -ErrorAction Stop
    } else {
        New-CimInstance -Namespace $KidLockNs -ClassName $class -Property @{ ParentID = './Vendor/MSFT/Policy/Config'; InstanceID = 'NewsAndInterests'; AllowNewsAndInterests = 0 } -ErrorAction Stop | Out-Null
    }
    Write-KidLog 'widgets (news and weather) switched off for the laptop' $Who
}

function Get-KidLockMode {
    $f = Join-Path $KidLockDir 'mode.txt'
    if (Test-Path $f) { (Get-Content $f -TotalCount 1).Trim() } else { 'Enabled' }
}
