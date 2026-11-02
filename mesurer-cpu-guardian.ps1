param(
  [string]$Package = 'com.hugo3154.guardianvoice',
  [int]$DurationSeconds = 180,
  [int]$IntervalSeconds = 2,
  [string]$OutputCsv = (Join-Path $env:TEMP 'guardian-cpu.csv')
)
$ErrorActionPreference = 'Stop'
if ($DurationSeconds -lt 10 -or $IntervalSeconds -lt 1) { throw 'Durée >= 10 s et intervalle >= 1 s requis' }
if ($Package -notmatch '^[A-Za-z0-9_.]+$') { throw 'Nom de package invalide' }
function Read-Adb {
  param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
  $result = & adb @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Échec adb : $Arguments" }
  return (($result -join "`n").Trim())
}
# Android Linux USER_HZ is normally 100; verify it, do not silently assume it.
$hzText = Read-Adb @('shell', 'getconf', 'CLK_TCK')
$clockTicks = 0.0
if (-not [double]::TryParse($hzText, [ref]$clockTicks) -or $clockTicks -le 0) {
  throw 'CLK_TCK indisponible : mesure interrompue plutôt que de supposer une fréquence'
}
$rows = @()
$watch = [Diagnostics.Stopwatch]::StartNew()
$previous = $null
while ($watch.Elapsed.TotalSeconds -lt $DurationSeconds) {
  $processIds = Read-Adb @('shell', 'pidof', $Package)
  $guardianProcessId = ($processIds -split '\s+')[0]
  if ($guardianProcessId -notmatch '^\d+$') { throw 'Processus Guardian absent' }
  $stat = Read-Adb @('shell', 'cat', "/proc/$guardianProcessId/stat")
  $closeParen = $stat.LastIndexOf(')')
  if ($closeParen -lt 0) { throw 'Format /proc/stat invalide' }
  # Fields after comm: state is index 0; utime/stime are fields 14/15 (indices 11/12).
  $fields = $stat.Substring($closeParen + 1).Trim() -split '\s+'
  $ticks = [double]$fields[11] + [double]$fields[12]
  $startTicks = $fields[19] # field 22 detects a restarted process, even if PID is reused.
  $elapsed = $watch.Elapsed.TotalSeconds
  $corePercent = $null
  if ($null -ne $previous -and $previous.ProcessId -eq $guardianProcessId -and $previous.StartTicks -eq $startTicks) {
    $delta = $elapsed - $previous.Elapsed
    if ($delta -gt 0 -and $ticks -ge $previous.Ticks) {
      $corePercent = 100.0 * ($ticks - $previous.Ticks) / $clockTicks / $delta
    }
  }
  $rows += [pscustomobject]@{Utc=[DateTime]::UtcNow.ToString('o'); ProcessId=$guardianProcessId; ElapsedSeconds=$elapsed; CpuPercentOfOneCore=$corePercent; ProcessStartTicks=$startTicks}
  $previous = @{ProcessId=$guardianProcessId; StartTicks=$startTicks; Ticks=$ticks; Elapsed=$elapsed}
  $rows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $OutputCsv
  Start-Sleep -Seconds $IntervalSeconds
}
Write-Output "Mesure enregistrée : $OutputCsv"
Write-Output '100 % = un cœur occupé ; ce n’est ni le pourcentage de tout le téléphone ni une mesure d’autonomie.'
