# KidLock rules by hand. Runs as SYSTEM (Windows only accepts the rules from SYSTEM; see
# kidlock-rules.ps1). From an admin session, run it through a one-shot SYSTEM scheduled task.
#
#   -Mode AuditOnly   watch only: nothing is blocked, Windows logs what it would have blocked
#   -Mode Enabled     blocking on (normal)
#   -Mode Off         removes the rules (escape hatch); the agent then leaves them alone
#
# Apps a parent has switched on right now (unlocked.txt, kept by the agent) stay switched on.
# Log: C:\ProgramData\kidlock\kidlock.log, ending with "done <mode>".

param([ValidateSet('AuditOnly', 'Enabled', 'Off')][string]$Mode = 'Enabled')

. "$PSScriptRoot\kidlock-rules.ps1"

try {
    Write-KidLog "---- applying mode $Mode as $(whoami)"
    $file = Join-Path $KidLockDir 'unlocked.txt'
    $unlocked = @(if (Test-Path $file) { (Get-Content $file -Raw).Trim() -split ',' | Where-Object { $_ } })
    Set-KidLockRules -Mode $Mode -Unlocked $unlocked
    Write-KidLog "done $Mode"
} catch {
    Write-KidLog "FAILED: $($_.Exception.Message)"
    Write-KidLog "done $Mode (failed)"
}
