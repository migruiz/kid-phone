# KidLock tidy: the child's desktop, taskbar and Start show only what he may open. Set up 24/10/2026.
# Runs as SYSTEM when he signs in (task "KidLock tidy at his sign-in"), and can be run by hand.
# The first run must be as an admin who has the tools (piscos): SYSTEM can't make Store-app shortcuts.
# Safe to run any number of times. Anything it removes is kept in C:\ProgramData\kidlock\removed\.
# Log: C:\ProgramData\kidlock\kidlock.log

$d       = 'C:\ProgramData\kidlock'
$config  = Get-Content (Join-Path $d 'apps.json') -Raw | ConvertFrom-Json
$user    = $config.user
$log     = Join-Path $d 'kidlock.log'
$removed = Join-Path $d 'removed'
function Log($m) { "$(Get-Date -Format s)  [tidy] $m" | Add-Content -Path $log }

# The everyday tools (apps.json "defaultOn"), for his desktop shortcuts and Start pins: name -> app ID.
# Their app IDs come from catalog.json, which the agent writes from what's installed.
$apps = [ordered]@{}
$catalog = @{}
if (Test-Path "$d\catalog.json") {
    try { (Get-Content "$d\catalog.json" -Raw | ConvertFrom-Json).apps | ForEach-Object { $catalog[$_.id] = $_ } } catch {}
}
foreach ($id in @($config.defaultOn)) {
    $c = $catalog[$id]
    if ($c -and $c.aumid) { $apps[$c.label] = $c.aumid }
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
[ComImport, Guid("000214F9-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IShellLinkW {
    void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder f, int c, IntPtr fd, uint fl);
    void GetIDList(out IntPtr p); void SetIDList(IntPtr p);
    void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder s, int c); void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string s);
    void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder s, int c); void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string s);
    void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder s, int c); void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string s);
    void GetHotkey(out short h); void SetHotkey(short h); void GetShowCmd(out int c); void SetShowCmd(int c);
    void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder s, int c, out int i); void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string s, int i);
    void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string s, uint r); void Resolve(IntPtr h, uint f); void SetPath([MarshalAs(UnmanagedType.LPWStr)] string s);
}
[ComImport, Guid("0000010b-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IPersistFile {
    void GetClassID(out Guid g); [PreserveSig] int IsDirty();
    void Load([MarshalAs(UnmanagedType.LPWStr)] string f, uint m); void Save([MarshalAs(UnmanagedType.LPWStr)] string f, bool r);
    void SaveCompleted([MarshalAs(UnmanagedType.LPWStr)] string f); void GetCurFile([MarshalAs(UnmanagedType.LPWStr)] out string f);
}
[ComImport, Guid("00021401-0000-0000-C000-000000000046")] class CShellLink { }
public static class KidLockAppLink {
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)] static extern int SHParseDisplayName(string n, IntPtr b, out IntPtr p, uint i, out uint o);
    [DllImport("ole32.dll")] static extern void CoTaskMemFree(IntPtr p);
    // A shortcut to a Store-style app, exactly like dragging it out of Start: the icon follows the app.
    public static void Create(string appId, string lnk) {
        IntPtr pidl; uint o;
        int hr = SHParseDisplayName("shell:AppsFolder\\" + appId, IntPtr.Zero, out pidl, 0, out o);
        if (hr != 0) Marshal.ThrowExceptionForHR(hr);
        try { var l = (IShellLinkW)new CShellLink(); l.SetIDList(pidl); ((IPersistFile)l).Save(lnk, true); }
        finally { CoTaskMemFree(pidl); }
    }
}
'@ -ErrorAction SilentlyContinue

function Stash($item, $why) {
    $dest = Join-Path $removed ($why + '\' + $item.Name)
    New-Item -ItemType Directory (Split-Path $dest) -Force | Out-Null
    Move-Item -LiteralPath $item.FullName -Destination $dest -Force
    Log "removed $why\$($item.Name)"
}

try {
    $sid  = (Get-LocalUser $user -ErrorAction Stop).SID.Value
    $prof = (Get-CimInstance Win32_UserProfile | Where-Object SID -eq $sid).LocalPath
    if (-not $prof) { Log "no profile yet for $user; nothing to tidy"; return }

    # 1. His desktop: only the allowed apps
    $desktops = @("$prof\Desktop", "$prof\OneDrive\Desktop") | Where-Object { Test-Path $_ }
    foreach ($desk in $desktops) {
        Get-ChildItem $desk -File | Where-Object { $_.Extension -in '.lnk', '.url' -and -not $apps.Contains($_.BaseName) } | ForEach-Object { Stash $_ 'his-desktop' }
    }
    foreach ($name in $apps.Keys) {
        $lnk = "$prof\Desktop\$name.lnk"
        if (-not (Test-Path $lnk)) {
            try { [KidLockAppLink]::Create($apps[$name], $lnk); Log "added desktop shortcut $name" } catch { Log "could not add $name : $($_.Exception.Message)" }
        }
    }

    # 2. The shared desktop shows on every account: move its shortcuts to piscos's own desktop
    Get-ChildItem 'C:\Users\Public\Desktop' -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -in '.lnk', '.url' } | ForEach-Object {
        Move-Item -LiteralPath $_.FullName -Destination "C:\Users\piscos\Desktop\$($_.Name)" -Force
        Log "moved shared desktop shortcut $($_.Name) to piscos's desktop"
    }

    # 3. His own Start menu entries for things he can't open
    $sm = "$prof\AppData\Roaming\Microsoft\Windows\Start Menu\Programs"
    foreach ($rel in 'System Tools', 'Windows PowerShell', 'Roblox', 'Administrative Tools.lnk', 'OneDrive.lnk') {
        $p = Join-Path $sm $rel
        if (Test-Path $p) { Stash (Get-Item $p) 'his-start-menu' }
    }

    # 4. His registry (loaded while he is signed in; otherwise load it for a moment)
    $root = "Registry::HKEY_USERS\$sid"
    $loaded = Test-Path $root
    if (-not $loaded) {
        reg.exe load "HKU\KidLockTidy" "$prof\NTUSER.DAT" | Out-Null
        $root = 'Registry::HKEY_USERS\KidLockTidy'
    }
    try {
        # nothing of his starts by itself at sign-in (it would only be blocked)
        $run = "$root\Software\Microsoft\Windows\CurrentVersion\Run"
        if (Test-Path $run) {
            (Get-Item $run).Property | ForEach-Object { Remove-ItemProperty -Path $run -Name $_; Log "removed sign-in start: $_" }
        }
        # Start: hide "All apps" and "Recommended".
        # Taskbar: the apps he has open, the clock and the volume; nothing else. No pinned apps,
        # search box, Task View, widgets (news), Copilot or chat; no Wi-Fi, battery or security
        # icons, notifications panel or show-desktop corner. He can't change the taskbar's settings
        # to bring them back, nor end the home screen from it. (His home screen shows the battery.)
        $sets = [ordered]@{
            "$root\Software\Microsoft\Windows\CurrentVersion\Policies\Explorer" = @{
                NoStartMenuMorePrograms = 1; TaskbarNoPinnedList = 1; NoPinningToTaskbar = 1
                HideSCANetwork = 1; HideSCAPower = 1; HideSCAHealth = 1; NoSetTaskbar = 1; LockTaskbar = 1 }
            "$root\Software\Policies\Microsoft\Windows\Explorer" = @{
                HideRecommendedSection = 1; DisableNotificationCenter = 1 }
            "$root\Software\Policies\Microsoft\Windows\WindowsCopilot" = @{ TurnOffWindowsCopilot = 1 }
            "$root\Software\Microsoft\Windows\CurrentVersion\Search" = @{ SearchboxTaskbarMode = 0 }
            # (widgets: TaskbarDa is refused by Windows; the agent switches widgets off laptop-wide)
            "$root\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced" = @{
                ShowTaskViewButton = 0; TaskbarMn = 0; ShowCopilotButton = 0; TaskbarSd = 0 }
            "$root\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced\TaskbarDeveloperSettings" = @{ TaskbarEndTask = 0 }
            "$root\Control Panel\Bluetooth" = @{ 'Notification Area Icon' = 0 }
            # The language indicator ("ENG"): with the desktop language bar on (below) and hidden (3).
            "$root\Software\Microsoft\CTF\LangBar" = @{ ShowStatus = 3 }
        }
        foreach ($key in $sets.Keys) {
            if (-not (Test-Path $key)) { New-Item $key -Force | Out-Null }
            foreach ($name in $sets[$key].Keys) {
                try { New-ItemProperty $key -Name $name -Value $sets[$key][$name] -PropertyType DWord -Force -ErrorAction Stop | Out-Null }
                catch { Log "could not set $name : $($_.Exception.Message)" }
            }
        }
        # The pinned list used to come from a layout file; with no pinned apps it isn't needed.
        # The clock and volume stay (the parents' choice, 25/10/2026), so the switches that hid them go.
        Remove-ItemProperty "$root\Software\Policies\Microsoft\Windows\Explorer" -Name StartLayoutFile, LockedStartLayout, DisableControlCenter -ErrorAction SilentlyContinue
        Remove-ItemProperty "$root\Software\Microsoft\Windows\CurrentVersion\Policies\Explorer" -Name NoTrayItemsDisplay, HideClock, HideSCAVolume -ErrorAction SilentlyContinue

        # "Use the desktop language bar" is one bit of UserPreferencesMask (6th byte, lowest bit),
        # a value shared with other settings: only that bit changes.
        $desk = "$root\Control Panel\Desktop"
        $mask = (Get-ItemProperty $desk -Name UserPreferencesMask -ErrorAction SilentlyContinue).UserPreferencesMask
        if ($mask -and $mask.Length -ge 6 -and -not ($mask[5] -band 1)) {
            $mask[5] = $mask[5] -bor 1
            Set-ItemProperty $desk -Name UserPreferencesMask -Value ([byte[]]$mask) -Type Binary
            Log "desktop language bar on (so the language indicator can be hidden)"
        }

        # Keyboard: Irish, and only that one, so there's nothing to switch (the parents' choice,
        # 25/10/2026). The display language stays as it is. Takes effect at his next sign-in.
        $irish = '00001809'
        $up = "$root\Control Panel\International\User Profile"
        $lang = @((Get-ItemProperty $up -ErrorAction SilentlyContinue).Languages)[0]
        if ($lang -and (Test-Path "$up\$lang")) {
            $langKey = Get-Item "$up\$lang"
            $inputs = @($langKey.Property | Where-Object { $_ -match '^[0-9A-Fa-f]{4}:' })
            $lcid = if ($inputs) { $inputs[0].Substring(0, 4) } else { '0809' }
            $want = "${lcid}:$irish"
            if ($inputs.Count -ne 1 -or $inputs[0] -ne $want) {
                foreach ($i in $inputs) { Remove-ItemProperty "$up\$lang" -Name $i }
                New-ItemProperty "$up\$lang" -Name $want -Value 1 -PropertyType DWord -Force | Out-Null
                Log "keyboard for $lang set to Irish ($want)"
            }
        }
        $pre = "$root\Keyboard Layout\Preload"
        if (-not (Test-Path $pre)) { New-Item $pre -Force | Out-Null }
        $current = (Get-ItemProperty $pre).PSObject.Properties | Where-Object Name -match '^\d+$'
        if (@($current).Count -ne 1 -or $current.Value -ne $irish) {
            $current | ForEach-Object { Remove-ItemProperty $pre -Name $_.Name }
            New-ItemProperty $pre -Name '1' -Value $irish -PropertyType String -Force | Out-Null
        }
        Remove-Item "$root\Keyboard Layout\Substitutes\*" -ErrorAction SilentlyContinue

        # Tray: every app's icon (Boot Camp, Roblox...) goes into the hidden-icons menu, and that
        # menu's arrow is hidden below. New icons go there by themselves.
        Get-ChildItem "$root\Control Panel\NotifyIconSettings" -ErrorAction SilentlyContinue | ForEach-Object {
            if ((Get-ItemProperty $_.PSPath -Name IsPromoted -ErrorAction SilentlyContinue).IsPromoted -ne 0) {
                New-ItemProperty $_.PSPath -Name IsPromoted -Value 0 -PropertyType DWord -Force | Out-Null
            }
        }
    } finally {
        if (-not $loaded) { [gc]::Collect(); reg.exe unload 'HKU\KidLockTidy' | Out-Null }
    }

    # 4b. The hidden-icons arrow in the tray. This lives in his other registry file (UsrClass.dat),
    # loaded while he is signed in.
    $classes = "Registry::HKEY_USERS\${sid}_Classes"
    $classesLoaded = Test-Path $classes
    if (-not $classesLoaded) {
        reg.exe load "HKU\KidLockTidyClasses" "$prof\AppData\Local\Microsoft\Windows\UsrClass.dat" | Out-Null
        $classes = 'Registry::HKEY_USERS\KidLockTidyClasses'
    }
    try {
        $tn = "$classes\Local Settings\Software\Microsoft\Windows\CurrentVersion\TrayNotify"
        if (Test-Path $classes) {
            if (-not (Test-Path $tn)) { New-Item $tn -Force | Out-Null }
            New-ItemProperty $tn -Name SystemTrayChevronVisibility -Value 0 -PropertyType DWord -Force | Out-Null
        }
    } catch { Log "could not hide the hidden-icons arrow: $($_.Exception.Message)" }
    finally {
        if (-not $classesLoaded) { [gc]::Collect(); reg.exe unload 'HKU\KidLockTidyClasses' | Out-Null }
    }

    # 4c. Start's pinned apps: only his tools (Windows' own "Configure Start pins" setting for his
    # account, written where the device-management channel keeps it).
    try {
        $json = @{ pinnedList = @($apps.Values | ForEach-Object { @{ packagedAppId = $_ } }) } | ConvertTo-Json -Depth 4 -Compress
        $provider = 'FE24B94A-1E02-4259-A0C0-EF2E2B7F95D9'
        $cur = "HKLM:\SOFTWARE\Microsoft\PolicyManager\current\$sid\Start"
        $pk = "HKLM:\SOFTWARE\Microsoft\PolicyManager\providers\$provider\default\$sid\Start"
        foreach ($k in $cur, $pk) { if (-not (Test-Path $k)) { New-Item $k -Force | Out-Null } }
        if ((Get-ItemProperty $cur -Name ConfigureStartPins -ErrorAction SilentlyContinue).ConfigureStartPins -ne $json) {
            New-ItemProperty $pk -Name ConfigureStartPins -Value $json -PropertyType String -Force | Out-Null
            New-ItemProperty $cur -Name ConfigureStartPins -Value $json -PropertyType String -Force | Out-Null
            New-ItemProperty $cur -Name ConfigureStartPins_ProviderSet -Value 1 -PropertyType DWord -Force | Out-Null
            New-ItemProperty $cur -Name ConfigureStartPins_WinningProvider -Value $provider -PropertyType String -Force | Out-Null
            Log "Start pins set to his tools"
        }
    } catch { Log "could not set Start pins: $($_.Exception.Message)" }

    # 5. The old taskbar layout file (pins are off now)
    Remove-Item 'C:\ProgramData\KidLockLayout\taskbar.xml' -ErrorAction SilentlyContinue

    # 6. OneDrive's own start-up task for him
    Get-ScheduledTask -TaskName "OneDrive * Task-$sid" -ErrorAction SilentlyContinue | Where-Object State -ne 'Disabled' | ForEach-Object {
        Disable-ScheduledTask -TaskName $_.TaskName -TaskPath $_.TaskPath | Out-Null; Log "disabled $($_.TaskName)"
    }
    Log "done"
} catch {
    Log "FAILED: $($_.Exception.Message)"
    Log "done (failed)"
}
