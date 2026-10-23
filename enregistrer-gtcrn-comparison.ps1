#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ProjectDirectory,
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$')][string]$PackageName,
    [ValidateSet("DISTANT","NOISY")][string]$Profile = "DISTANT",
    [ValidateRange(1,120)][int]$DurationSeconds = 60,
    [ValidateSet("AUTO","VOICE_RECOGNITION_MONO","MIC_MONO","MIC_STEREO_FOCUS")]
    [string]$CaptureRoute = "AUTO",
    [ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$DeviceId = ""
)
$ErrorActionPreference = "Stop"
$Profile = $Profile.ToUpperInvariant()
$adbExecutable = (Get-Command adb -ErrorAction Stop).Source
$deviceArguments = @()
if ($DeviceId) { $deviceArguments = @("-s",$DeviceId) }
function Invoke-SimpleAdb {
    param([string[]]$Arguments)
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $value = & $adbExecutable @deviceArguments @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    if ($code -ne 0) { throw ("adb : " + ($value -join " ")) }
    return (($value -join [Environment]::NewLine).Trim())
}
function Copy-PrivateFile {
    param([string]$Remote, [string]$Local)
    $hashOutput = Invoke-SimpleAdb @("shell","run-as",$PackageName,"sha256sum",$Remote)
    $hashLines = @($hashOutput -split "\r?\n" | Where-Object { $_ -match '^[a-fA-F0-9]{64}\s+.+$' })
    if ($hashLines.Count -ne 1) { throw ("Empreinte distante absente : " + $Remote) }
    $expected = $hashLines[0].Substring(0,64).ToLowerInvariant()
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $adbExecutable
    $info.Arguments = (@($deviceArguments) + @("exec-out","run-as",$PackageName,"cat",$Remote)) -join " "
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $native = New-Object Diagnostics.Process
    $native.StartInfo = $info
    $target = $null; $started = $false
    try {
        [void]$native.Start(); $started = $true
        $stderr = $native.StandardError.ReadToEndAsync()
        $target = [IO.File]::Create($Local)
        $native.StandardOutput.BaseStream.CopyTo($target)
        $target.Dispose(); $target = $null
        $native.WaitForExit()
        $errorText = $stderr.GetAwaiter().GetResult()
        if ($native.ExitCode -ne 0 -or $errorText.Trim()) { throw ("Lecture du fichier privé impossible : " + $errorText) }
    } finally {
        if ($target) { $target.Dispose() }
        if ($started -and -not $native.HasExited) { $native.Kill(); $native.WaitForExit() }
        $native.Dispose()
    }
    $actual = (Get-FileHash -LiteralPath $Local -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw ("Copie incorrecte : " + $Remote + ". PC=" + $actual + ", téléphone=" + $expected) }
}
function Push-PrivateControl {
    param([string]$Name, [string]$Local)
    $remoteTemp = "/data/local/tmp/guardian-simple-" + [Guid]::NewGuid().ToString("N") + ".txt"
    try {
        [void](Invoke-SimpleAdb @("push",$Local,$remoteTemp))
        [void](Invoke-SimpleAdb @("shell","chmod","644",$remoteTemp))
        [void](Invoke-SimpleAdb @("shell","run-as",$PackageName,"cp",$remoteTemp,("files/"+$Name)))
    } finally {
        try { [void](Invoke-SimpleAdb @("shell","rm","-f",$remoteTemp)) } catch { Write-Warning ("Fichier temporaire ADB à retirer : " + $remoteTemp) }
    }
}
function Set-PrivateControl {
    param([string]$Name, [string]$Value)
    if (@($backups | Where-Object { $_.name -eq $Name }).Count -eq 0) {
        $listing = Invoke-SimpleAdb @("shell","run-as",$PackageName,"ls","-1","files")
        $exists = @($listing -split "\r?\n" | Where-Object { $_.Trim() -eq $Name }).Count -gt 0
        $backup = Join-Path $work ("backup-"+$Name)
        if ($exists) { Copy-PrivateFile ("files/"+$Name) $backup }
        $backups.Add([pscustomobject]@{name=$Name; existed=$exists; backup=$backup})
    }
    $local = Join-Path $work $Name
    [IO.File]::WriteAllText($local,$Value,(New-Object Text.UTF8Encoding($false)))
    Push-PrivateControl $Name $local
}
function Read-DiagnosticEvents {
    if (Test-Path -LiteralPath $logPath) {
        Get-Content -LiteralPath $logPath -Encoding UTF8 | ForEach-Object {
            if ($_ -match 'GuardianVoiceDiag.*?(\{.*\})') {
                try { $Matches[1] | ConvertFrom-Json } catch { }
            }
        }
    }
}
function Assert-SimpleSessionActive {
    param($Session, [object[]]$Events)
    if (@($Events | Where-Object { $_.session -eq $Session.session -and $_.event -eq "SESSION_END" }).Count -gt 0) {
        throw "La session vocale s'est arretee pendant le test. Les fichiers partiels sont conserves ; ce bloc n'est pas valide."
    }
    if (@($Events | Where-Object { $_.event -eq "SESSION_START" -and $_.session -ne $Session.session -and $_.elapsedMs -gt $Session.elapsedMs }).Count -gt 0) {
        throw "Le moteur a change de session pendant le test. Bloc non valide ; voir guardian.log."
    }
}
function Get-GuardianWavHeader {
    param([string]$Path)
    $data = [IO.File]::ReadAllBytes($Path)
    if ($data.Length -lt 44) { return @{ valid=$false; reason="header too short" } }
    $riff = [Text.Encoding]::ASCII.GetString($data, 0, 4)
    $wave = [Text.Encoding]::ASCII.GetString($data, 8, 4)
    $rate = [BitConverter]::ToUInt32($data,24)
    $channels = [BitConverter]::ToUInt16($data,22)
    $bits = [BitConverter]::ToUInt16($data,34)
    $declaredBytes = [BitConverter]::ToUInt32($data,40)
    $valid = $riff -eq "RIFF" -and $wave -eq "WAVE" -and $channels -in @(1,2) -and $bits -eq 16 -and $rate -gt 0 -and $declaredBytes -eq ($data.Length-44)
    $duration = 0
    if ($rate -gt 0 -and $channels -gt 0 -and $bits -gt 0) {
        $duration = ($data.Length-44) / ($rate*$channels*$bits/8)
    }
    return @{ valid=$valid; sampleRateHz=$rate; channels=$channels; bits=$bits; durationSeconds=$duration; bytes=$data.Length }
}

$ProjectDirectory = (Resolve-Path -LiteralPath $ProjectDirectory).Path
if (-not (Test-Path -LiteralPath (Join-Path $ProjectDirectory "package.json"))) { throw "Indiquer la racine du projet contenant package.json." }
if (@((Invoke-SimpleAdb @("get-state")) -split "\r?\n" | Where-Object { $_.Trim() -eq "device" }).Count -ne 1) { throw "Téléphone inaccessible." }
[void](Invoke-SimpleAdb @("shell","run-as",$PackageName,"id"))
$base = Join-Path $ProjectDirectory "diagnostics-voix"
[void][IO.Directory]::CreateDirectory($base)
$ignore = Join-Path $base ".gitignore"
if (-not (Test-Path -LiteralPath $ignore)) { [IO.File]::WriteAllText($ignore,"*`n!.gitignore`n") }
$output = Join-Path $base ("Enregistrement-" + (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-" + [Guid]::NewGuid().ToString("N").Substring(0,6))
[void][IO.Directory]::CreateDirectory($output)
$logPath = Join-Path $output "guardian.log"
$work = Join-Path ([IO.Path]::GetTempPath()) ("guardian-simple-"+[Guid]::NewGuid().ToString("N"))
[void][IO.Directory]::CreateDirectory($work)
$backups = New-Object 'System.Collections.Generic.List[object]'
$records = New-Object 'System.Collections.Generic.List[object]'
$metadata = [ordered]@{
    collectorVersion="gtcrn-comparison-3.4.1"; profile=$Profile; captureRouteRequested=$CaptureRoute; durationRequestedSeconds=$DurationSeconds
    packageName=$PackageName; startUtc=[DateTime]::UtcNow.ToString("o")
    phoneModel=Invoke-SimpleAdb @("shell","getprop","ro.product.model")
    android=Invoke-SimpleAdb @("shell","getprop","ro.build.version.release")
    completed=$false
}
$failure = $null; $process = $null; $restoreFailed = $false
try {
    $metadata["notes"] = Read-Host "Notes facultatives : distance, voix douce, type de bruit"
    Write-Host ("Dossier de l'enregistrement : " + $output)
    Write-Host ("Profil : " + $Profile + ". Durée : " + $DurationSeconds + " secondes.")
    Write-Host "Parler librement, puis garder quelques secondes de silence. Les voix alentour seront enregistrées."
    $arguments = @($deviceArguments)+@("logcat","-v","threadtime","-T","1",
        "GuardianVoiceDiag:V","GuardianSpeechInput:V","GuardianQwenProgressive:V",
        "GuardianQwenRescue:V","GuardianAudioEngine:V","GuardianWordTimeline:V",
        "GuardianVoiceEngine:V","GuardianConversation:V","GuardianAudioProfile:V",
        "GuardianAudioProbe:V","GuardianVoiceFocus:V","GuardianAudioEvidence:V",
        "GuardianProtectionService:V","GuardianCore:V","GuardianAudioCapabilities:V","AndroidRuntime:E","*:S")
    $process = Start-Process -FilePath $adbExecutable -ArgumentList $arguments -PassThru -NoNewWindow -RedirectStandardOutput $logPath -RedirectStandardError (Join-Path $output "adb-errors.log")
    Start-Sleep -Milliseconds 300
    if ($process.HasExited) { throw "Le lecteur de logs ADB s'est arrêté : voir adb-errors.log." }
    $source = "AUTO"; $multiMic = "AUTO"
    switch ($CaptureRoute) {
        "VOICE_RECOGNITION_MONO" { $source="VOICE_RECOGNITION"; $multiMic="OFF" }
        "MIC_MONO" { $source="MIC"; $multiMic="OFF" }
        "MIC_STEREO_FOCUS" { $source="MIC"; $multiMic="VOICE_FOCUS" }
    }
    Set-PrivateControl "guardian-audio-source.txt" $source
    Set-PrivateControl "guardian-multimic.txt" $multiMic
    Set-PrivateControl "guardian-record-wav.txt" "ON"
    Set-PrivateControl "guardian-voice-profile.txt" $Profile
    [void](Read-Host "Arrêter complètement puis redémarrer la protection dans Guardian, puis Entrée")
    $session = $null; $ready = $null
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $events = @(Read-DiagnosticEvents)
        $knownOld = @($events | Where-Object { $_.event -eq "SESSION_START" -and $_.diagnosticControlRevision -lt 3 })
        if ($knownOld.Count -gt 0) { throw "Le moteur a démarré avec une ancienne révision diagnostique. Installer le module FireRed + comparaison GTCRN v3.4.1 et son APK pour ce collecteur." }
        $starts = @($events | Where-Object { $_.event -eq "SESSION_START" -and $_.diagnosticControlRevision -ge 3 -and $_.profile -eq $Profile -and $_.diagnosticsEnabledBy -eq "PRIVATE_FILE" })
        foreach ($candidate in @($starts | Sort-Object elapsedMs -Descending)) {
            $same = @($events | Where-Object { $_.session -eq $candidate.session })
            # A previously ready session may already have ended while the user
            # was responding to the prompt. Never time a test against its WAV.
            if (@($same | Where-Object { $_.event -eq "SESSION_END" }).Count -gt 0) { continue }
            if ($candidate.engineRevision -ne "guardian-voice-v3.4.1-gtcrn-comparison") {
                throw "Ce test exige le nouvel APK FireRed + comparaison GTCRN v3.4.1. La revision observee est : $($candidate.engineRevision)"
            }
            if (@($same | Where-Object { $_.event -eq "FIRERED_START_FAILED" }).Count -gt 0) { throw "FireRed n'a pas pu demarrer : voir guardian.log." }
            if (@($same | Where-Object { $_.event -eq "FIRERED_READY" }).Count -eq 0) { continue }
            if (@($same | Where-Object { $_.event -in @("WAV_ERROR","WAV_UNAVAILABLE") }).Count -gt 0) { throw "Le moteur signale une erreur WAV : voir guardian.log." }
            $wavReady = @($same | Where-Object { $_.event -eq "WAV_READY" })
            if ($wavReady.Count -gt 0 -and @($same | Where-Object { $_.event -eq "AUDIO_FRAME" }).Count -gt 0) {
                $session = $candidate; $ready = $wavReady[-1]; break
            }
        }
        if (-not $session) { Start-Sleep -Milliseconds 400 }
    } while (-not $session -and [DateTime]::UtcNow -lt $deadline)
    if (-not $session) {
        $observed = @(Read-DiagnosticEvents | Where-Object { $_.event -eq "SESSION_START" })
        if ($observed.Count -gt 0 -and $observed[-1].diagnosticControlRevision -lt 3) {
            throw "Le moteur a démarré, mais ce collecteur exige le module FireRed + comparaison GTCRN v3.4.1 et son nouvel APK. Les logs permettent de vérifier la version installée."
        }
        throw "Aucun démarrage complet avec WAV confirmé. Vérifier l'arrêt complet puis la réactivation de la protection ; voir guardian.log et adb-errors.log. Ce message ne prouve pas que le build est incorrect."
    }
    $capture = @($events | Where-Object { $_.session -eq $session.session -and $_.event -eq "CAPTURE_CONFIG" })
    if ($CaptureRoute -ne "AUTO") {
        $expectedSource = if ($CaptureRoute -eq "VOICE_RECOGNITION_MONO") { 6 } else { 1 }
        $expectedChannels = if ($CaptureRoute -eq "MIC_STEREO_FOCUS") { 2 } else { 1 }
        if ($capture.Count -eq 0 -or $capture[-1].audioSource -ne $expectedSource -or $capture[-1].channels -ne $expectedChannels) {
            throw "La route audio demandée n'a pas été appliquée. Le téléphone a peut-être refusé cette configuration ; voir audio-routing.log."
        }
    }
    if ($capture.Count -gt 0) { $metadata["captureConfig"] = $capture[-1] }
    $prefix = [string]$ready.prefix
    if ($prefix -notmatch '^guardian-voice-\d{13}-(DISTANT|NOISY)-[A-Za-z0-9]{1,8}$') { throw "Nom d'enregistrement invalide dans les logs." }
    $metadata["session"] = $session.session
    $metadata["audioPrefix"] = $prefix
    $metadata["recordStartUtc"] = [DateTime]::UtcNow.ToString("o")
    [void](Invoke-SimpleAdb @("shell","log","-p","i","-t","GuardianVoiceDiag",("SIMPLE_RECORD_BEGIN_"+$Profile)))
    Write-Host "ENREGISTREMENT ACTIF : parler maintenant."
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $lastRemaining = -1
    $lastSessionCheck = -1
    while ($timer.Elapsed.TotalSeconds -lt $DurationSeconds) {
        if ($process.HasExited) { throw "La lecture des logs s'est interrompue." }
        $second = [int][math]::Floor($timer.Elapsed.TotalSeconds)
        if ($second -ne $lastSessionCheck) {
            $current = @(Read-DiagnosticEvents)
            Assert-SimpleSessionActive $session $current
            $lastSessionCheck = $second
        }
        $remaining = [int][math]::Ceiling($DurationSeconds-$timer.Elapsed.TotalSeconds)
        if ($remaining -ne $lastRemaining -and ($remaining % 10 -eq 0 -or $remaining -le 5)) {
            Write-Host ("Encore " + $remaining + " s..."); $lastRemaining = $remaining
        }
        Start-Sleep -Milliseconds 250
    }
    Assert-SimpleSessionActive $session @(Read-DiagnosticEvents)
    $metadata["recordEndUtc"] = [DateTime]::UtcNow.ToString("o")
    [void](Invoke-SimpleAdb @("shell","log","-p","i","-t","GuardianVoiceDiag","SIMPLE_RECORD_END"))
    [void](Read-Host "Durée terminée. Arrêter complètement la protection pour fermer les WAV, puis Entrée")
    $deadline = [DateTime]::UtcNow.AddSeconds(6)
    do {
        $saved = @(Read-DiagnosticEvents | Where-Object { $_.session -eq $session.session -and $_.event -eq "WAV_SAVED" -and $_.stage -in @("raw","pregain","truth","asr","vad") } | Sort-Object stage -Unique)
        if ($saved.Count -ge 5) { break }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($saved.Count -lt 5) { throw "Les cinq WAV ne sont pas finalisés. Vérifier l'arrêt de la protection ; logs conservés." }
    $wavDir = Join-Path $output "wav"
    [void][IO.Directory]::CreateDirectory($wavDir)
    foreach ($suffix in @("raw.wav","pregain.wav","truth.wav","asr.wav","vad.wav","index.tsv")) {
        $name = $prefix+"-"+$suffix
        $local = Join-Path $wavDir $name
        Copy-PrivateFile ("files/guardian-diagnostics/"+$name) $local
        $header = if ($suffix.EndsWith(".wav")) { Get-GuardianWavHeader $local } else { @{valid=$true; kind="alignment-index"} }
        $records.Add([pscustomobject]@{file=$name; header=$header; sha256=(Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()})
        if (-not $header.valid) { throw ("WAV incomplet : " + $name) }
        if ($suffix -in @("asr.wav","vad.wav") -and $header.durationSeconds -lt ($DurationSeconds-0.1)) {
            throw ("WAV plus court que le test demande : " + $name + ". Bloc non valide.")
        }
    }
    $limits = @(Read-DiagnosticEvents | Where-Object { $_.session -eq $session.session -and $_.event -eq "WAV_LIMIT" })
    if ($limits.Count -gt 0) { throw "La limite de 180 secondes WAV a été atteinte. Les fichiers sont récupérés, mais ne couvrent pas forcément tout le test ; recommencer en démarrant rapidement après la protection." }
    if ($Profile -eq "NOISY") {
        $shadowDeadline = [DateTime]::UtcNow.AddSeconds(6)
        do {
            $shadow = @(Read-DiagnosticEvents | Where-Object { $_.session -eq $session.session -and $_.event -eq "GTCRN_COMPARISON_SAVED" })
            if ($shadow.Count -gt 0) { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $shadowDeadline)
        if ($shadow.Count -eq 0 -or $shadow[-1].pairs -le 0) { throw "Aucune paire GTCRN/original disponible ; les logs et WAV principaux sont conserves." }
        $shadowErrors = @(Read-DiagnosticEvents | Where-Object { $_.session -eq $session.session -and $_.event -in @("GTCRN_COMPARISON_ERROR","WAV_ERROR","WAV_FINALIZE_ERROR") })
        if ($shadowErrors.Count -gt 0) { throw "Erreur dans l'enregistrement GTCRN : logs conserves." }
        $metadata["gtcrnComparison"] = $shadow[-1]
        $shadowPrefix = [string]$shadow[-1].prefix
        if ($shadowPrefix -notmatch '^guardian-voice-[A-Za-z0-9._-]+$') { throw "Prefixe comparaison GTCRN invalide." }
        $shadowDir = Join-Path $output "gtcrn-comparison"
        [void][IO.Directory]::CreateDirectory($shadowDir)
        foreach ($suffix in @("truth.wav","asr.wav","index.tsv")) {
            $name = $shadowPrefix+"-"+$suffix
            $local = Join-Path $shadowDir $name
            Copy-PrivateFile ("files/guardian-diagnostics/gtcrn-comparison/"+$name) $local
            $header = if ($suffix.EndsWith(".wav")) { Get-GuardianWavHeader $local } else { @{valid=$true; kind="paired-alignment-index"} }
            if (-not $header.valid) { throw ("WAV de comparaison incomplet : " + $name) }
            if ($suffix.EndsWith(".wav") -and [math]::Abs($header.durationSeconds - ([double]$shadow[-1].samples / 16000)) -gt 0.0001) { throw ("Longueur de comparaison incorrecte : " + $name) }
            $records.Add([pscustomobject]@{file=("gtcrn-comparison/"+$name); header=$header; sha256=(Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()})
        }
    }
    $metadata["completed"] = $true
} catch {
    $failure = $_.Exception.Message
    $metadata["error"] = $failure
    Write-Warning $failure
} finally {
    foreach ($control in $backups) {
        try {
            if ($control.existed) { Push-PrivateControl $control.name $control.backup }
            else { [void](Invoke-SimpleAdb @("shell","run-as",$PackageName,"rm","-f",("files/"+$control.name))) }
        } catch { $restoreFailed=$true; Write-Warning ("Réglage à restaurer : files/"+$control.name+" ; sauvegarde : "+$work) }
    }
    if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue; $process.WaitForExit() }
    # Keep a readable subset of hardware discovery, route decisions and runtime
    # confirmation alongside the complete log. An empty subset is not evidence
    # that routing failed: startup logs may be absent or filtered by the device.
    $routeLines = @()
    if (Test-Path -LiteralPath $logPath) {
        $routeLines = @(Get-Content -LiteralPath $logPath -Encoding UTF8 | Where-Object {
            $_ -match 'GuardianAudio(Capabilities|Probe|Profile|Evidence):' -or
            ($_ -match 'GuardianAudioEngine:' -and $_ -match 'Runtime Audio Profile|Microphone actif|MULTIMIC|[Rr]out|[Cc]apture|[Dd]irection|[Ff]ocal') -or
            ($_ -match 'GuardianCore:' -and $_ -match 'MULTIMIC|[Rr]out|[Ss]ource|[Cc]apture|GTCRN') -or
            ($_ -match 'GuardianVoiceDiag:' -and $_ -match 'CAPTURE_CONFIG|SESSION_START')
        })
    }
    $routeLines | Set-Content -LiteralPath (Join-Path $output "audio-routing.log") -Encoding UTF8
    $metadata["captureRouteTraceLines"] = $routeLines.Count
    if ($routeLines.Count -eq 0) {
        Write-Warning "Aucune trace de sélection audio retrouvée. Cela ne prouve pas un échec de sélection ; vérifier le redémarrage complet de la protection."
    }
    $metadata["endUtc"] = [DateTime]::UtcNow.ToString("o")
    $metadata["controlsRestored"] = -not $restoreFailed
    if ($restoreFailed) { $metadata["controlBackupDirectory"] = $work }
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output "session.json") -Encoding UTF8
    ConvertTo-Json -InputObject @($records.ToArray()) -Depth 5 | Set-Content -LiteralPath (Join-Path $output "wav-manifest.json") -Encoding UTF8
    if (-not $restoreFailed) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
    Compress-Archive -Path (Join-Path $output "*") -DestinationPath ($output+".zip") -CompressionLevel Optimal
    Write-Host ("ZIP à envoyer : " + $output + ".zip")
}
if ($failure) { throw $failure }
