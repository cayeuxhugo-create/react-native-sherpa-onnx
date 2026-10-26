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
    [ValidateSet("FREE","SILENCE","WORDS","PHRASES")][string]$TestCase = "FREE",
    [ValidateSet("ORIGINAL","CANDIDATE","CANDIDATE_GAIN2","CANDIDATE_GAIN4","CONTINUOUS_STUDY","CONTINUOUS_QUALITY_STUDY","WIENER_STUDY")][string]$ProcessingMode = "WIENER_STUDY",
    [ValidateRange(0,20)][double]$DistanceMeters = 0,
    [ValidateSet("NORMAL","SOFT")][string]$VoiceLevel = "NORMAL",
    [string]$NoiseDescription = "non precise",
    [ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$DeviceId = ""
)
$ErrorActionPreference = "Stop"
$Profile = $Profile.ToUpperInvariant()
$ProcessingMode = $ProcessingMode.ToUpperInvariant()
$isStudyMode = $ProcessingMode -like "CONTINUOUS_*" -or $ProcessingMode -eq "WIENER_STUDY"
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
    if (@($Events | Where-Object { $_.session -eq $Session.session -and $_.event -eq "CONTINUOUS_STUDY_INVALID" }).Count -gt 0) {
        throw "La voie d'etude a ete invalidee ; conserver les logs. Le chemin principal reste independant."
    }
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

# Float sidecars are headerless IEEE-754 binary32, mono 16kHz, little endian.
# Their hashes and exact length must match the corresponding finalized WAV.
function Copy-GuardianFloat {
    param([string]$RemoteBase, [string]$LocalBase, [string]$RecordName, $Header)
    $localFloat = $LocalBase + ".f32"
    Copy-PrivateFile ($RemoteBase + ".f32") $localFloat
    $bytes = (Get-Item -LiteralPath $localFloat).Length
    $samples = [long](($Header.bytes - 44) / 2)
    if ($bytes -ne $samples * 4) { throw ("Export flottant incomplet : " + $RecordName) }
    $records.Add([pscustomobject]@{file=$RecordName; header=@{valid=$true; format="FLOAT32_LE_HEADERLESS"; sampleRateHz=16000; channels=1; samples=$samples; bytes=$bytes}; sha256=(Get-FileHash -LiteralPath $localFloat -Algorithm SHA256).Hash.ToLowerInvariant()})
}
function Add-TestMarker {
    param([string]$Label, [string]$ExpectedText = "", [double]$PlannedSeconds = -1)
    $before = [DateTime]::UtcNow.ToString("o")
    [void](Invoke-SimpleAdb @("shell","log","-p","i","-t","GuardianVoiceDiag",("FLOAT_TEST_"+$Label)))
    $frames = @(Read-DiagnosticEvents | Where-Object { $_.session -eq $session.session -and $_.event -eq "AUDIO_FRAME" })
    $reference = if ($frames.Count -gt 0) { $frames[-1] } else { $null }
    $markers.Add([pscustomobject]@{label=$Label; plannedSeconds=$PlannedSeconds; hostBeforeUtc=$before; hostAfterUtc=[DateTime]::UtcNow.ToString("o"); elapsedSeconds=if($timer){$timer.Elapsed.TotalSeconds}else{0}; expectedText=$ExpectedText; lastDeviceAudioFrame=$reference})
}
if ($TestCase -ne "FREE" -and $DurationSeconds -lt 75) { throw "Un test annote exige au moins 75 secondes." }

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
    collectorVersion="wiener-study-3.4.6"; profile=$Profile; captureRouteRequested=$CaptureRoute; durationRequestedSeconds=$DurationSeconds
    packageName=$PackageName; startUtc=[DateTime]::UtcNow.ToString("o")
    phoneModel=Invoke-SimpleAdb @("shell","getprop","ro.product.model")
    android=Invoke-SimpleAdb @("shell","getprop","ro.build.version.release")
    completed=$false
}
$failure = $null; $process = $null; $restoreFailed = $false
$timer = $null
$markers = New-Object 'System.Collections.Generic.List[object]'
$plan = @()
switch ($TestCase) {
    "WORDS" {
        $plan = @(@{at=15;text="Bonjour"},@{at=23;text="Merci"},@{at=31;text="Oui"},@{at=39;text="Bonjour"},@{at=47;text="Merci"},@{at=55;text="Oui"})
    }
    "PHRASES" {
        $plan = @(@{at=15;text="Je vais chercher mes chaussures et je reviens."},@{at=30;text="La fenêtre de la cuisine est ouverte."},@{at=45;text="Le téléphone est posé sur la table."})
    }
}
$metadata["testCase"] = $TestCase
$metadata["processingModeRequested"] = $ProcessingMode
$metadata["distanceMeters"] = $DistanceMeters
$metadata["voiceLevel"] = $VoiceLevel
$metadata["noiseDescription"] = $NoiseDescription
$metadata["floatFormat"] = "FLOAT32_LE_HEADERLESS"
$metadata["timingNote"] = "Markers are prompts, not evidence that speech occurred; WAV begins with capture and ends when protection stops. Device clocks are recorded separately from host UTC."
@{testCase=$TestCase; prompts=$plan; expectationOnly=$true} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output "test-plan.json") -Encoding UTF8
try {
    (Invoke-SimpleAdb @("shell","dumpsys","meminfo",$PackageName)) | Set-Content -LiteralPath (Join-Path $output "meminfo-before.txt") -Encoding UTF8
    $metadata["notes"] = Read-Host "Notes facultatives : distance, voix douce, type de bruit"
    Write-Host ("Dossier de l'enregistrement : " + $output)
    Write-Host ("Profil : " + $Profile + ". Durée : " + $DurationSeconds + " secondes.")
    Write-Host ("Test : " + $TestCase + "; distance : " + $DistanceMeters + " m; voix : " + $VoiceLevel + "; bruit : " + $NoiseDescription + "; route : " + $CaptureRoute)
    Write-Host "Les voix alentour seront enregistrees. Garder le telephone immobile et lire les consignes affichees."
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
    Set-PrivateControl "guardian-voice-processing.txt" $ProcessingMode
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
        if ($knownOld.Count -gt 0) { throw "Le moteur a démarré avec une ancienne révision diagnostique. Installer le module FireRed + etude Wiener v3.4.6 et son APK pour ce collecteur." }
        $starts = @($events | Where-Object { $_.event -eq "SESSION_START" -and $_.diagnosticControlRevision -ge 3 -and $_.profile -eq $Profile -and $_.diagnosticsEnabledBy -eq "PRIVATE_FILE" })
        foreach ($candidate in @($starts | Sort-Object elapsedMs -Descending)) {
            if ($candidate.processingMode -ne $ProcessingMode) { throw "Le mode de traitement demande n'a pas ete applique. Nouveau redemarrage complet necessaire." }
            $same = @($events | Where-Object { $_.session -eq $candidate.session })
            # A previously ready session may already have ended while the user
            # was responding to the prompt. Never time a test against its WAV.
            if (@($same | Where-Object { $_.event -eq "SESSION_END" }).Count -gt 0) { continue }
            if ($candidate.engineRevision -ne "guardian-voice-v3.4.6-wiener-study") {
                throw "Ce test exige le nouvel APK FireRed + etude Wiener v3.4.6. La revision observee est : $($candidate.engineRevision)"
            }
            if (@($same | Where-Object { $_.event -eq "FIRERED_START_FAILED" }).Count -gt 0) { throw "FireRed n'a pas pu demarrer : voir guardian.log." }
            if (@($same | Where-Object { $_.event -eq "FIRERED_READY" }).Count -eq 0) { continue }
            if (@($same | Where-Object { $_.event -in @("WAV_ERROR","WAV_UNAVAILABLE") }).Count -gt 0) { throw "Le moteur signale une erreur WAV : voir guardian.log." }
            if ($isStudyMode) {
                if (@($same | Where-Object { $_.event -eq "CONTINUOUS_STUDY_INVALID" }).Count -gt 0) { throw "La voie continue est invalide : voir guardian.log. Le chemin principal reste independant." }
                $studyReady = @($same | Where-Object { $_.event -eq "CONTINUOUS_STUDY_READY" })
                if ($studyReady.Count -eq 0) { continue }
                if ($ProcessingMode -eq "WIENER_STUDY" -and @($studyReady | Where-Object { $_.model -ne "WIENER_DD_ADAPTIVE" -or $_.role -ne "SHADOW_NO_ALERT" }).Count -gt 0) {
                    throw "La voie Wiener attendue n'est pas confirmee (WIENER_DD_ADAPTIVE, SHADOW_NO_ALERT) ; voir guardian.log."
                }
            }
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
            throw "Le moteur a démarré, mais ce collecteur exige le module FireRed + etude Wiener v3.4.6 et son nouvel APK. Les logs permettent de vérifier la version installée."
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
    Add-TestMarker "BEGIN"
    $nextPrompt = 0
    if ($TestCase -ne "FREE") { Write-Host "Garder le silence. Lire uniquement lorsque PARLER est affiche. Aucun signal sonore ne sera emis." }
    $memoryCaptured = $false
    $lastRemaining = -1
    $lastSessionCheck = -1
    while ($timer.Elapsed.TotalSeconds -lt $DurationSeconds) {
        if ($process.HasExited) { throw "La lecture des logs s'est interrompue." }
        while ($nextPrompt -lt $plan.Count -and $timer.Elapsed.TotalSeconds -ge $plan[$nextPrompt].at) {
            $prompt = $plan[$nextPrompt]
            Write-Host ("PARLER : " + $prompt.text)
            Add-TestMarker ("PROMPT_" + ($nextPrompt+1)) $prompt.text $prompt.at
            $nextPrompt++
        }
        if (-not $memoryCaptured -and $timer.Elapsed.TotalSeconds -ge 20) {
            (Invoke-SimpleAdb @("shell","dumpsys","meminfo",$PackageName)) | Set-Content -LiteralPath (Join-Path $output "meminfo-active.txt") -Encoding UTF8
            $memoryCaptured = $true
        }
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
    Add-TestMarker "END"
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
        if ($suffix.EndsWith(".wav")) {
            $baseName = $name.Substring(0,$name.Length-4)
            Copy-GuardianFloat ("files/guardian-diagnostics/"+$baseName) (Join-Path $wavDir $baseName) ($baseName+".f32") $header
        }
        if ($suffix -in @("asr.wav","vad.wav") -and $header.durationSeconds -lt ($DurationSeconds-0.1)) {
            throw ("WAV plus court que le test demande : " + $name + ". Bloc non valide.")
        }
    }
    $limits = @(Read-DiagnosticEvents | Where-Object { $_.session -eq $session.session -and $_.event -eq "WAV_LIMIT" })
    if ($limits.Count -gt 0) { throw "La limite de 180 secondes WAV a été atteinte. Les fichiers sont récupérés, mais ne couvrent pas forcément tout le test ; recommencer en démarrant rapidement après la protection." }
    if ($Profile -eq "NOISY" -and -not $isStudyMode) {
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
            if ($suffix.EndsWith(".wav")) {
                $baseName = $name.Substring(0,$name.Length-4)
                Copy-GuardianFloat ("files/guardian-diagnostics/gtcrn-comparison/"+$baseName) (Join-Path $shadowDir $baseName) ("gtcrn-comparison/"+$baseName+".f32") $header
            }
            if ($suffix.EndsWith(".wav") -and [math]::Abs($header.durationSeconds - ([double]$shadow[-1].samples / 16000)) -gt 0.0001) { throw ("Longueur de comparaison incorrecte : " + $name) }
            $records.Add([pscustomobject]@{file=("gtcrn-comparison/"+$name); header=$header; sha256=(Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()})
        }
    }
    if ($isStudyMode) {
        $studyDeadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            $studyEvents = @(Read-DiagnosticEvents | Where-Object { $_.session -eq $session.session })
            $studyEnd = @($studyEvents | Where-Object { $_.event -eq "CONTINUOUS_STUDY_END" })
            if ($studyEnd.Count -gt 0) { break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $studyDeadline)
        if ($studyEnd.Count -ne 1) { throw "La voie continue n'a pas finalise ses fichiers ; logs conserves." }
        $studyRemote = "files/guardian-diagnostics/continuous-study/" + [string]$session.session
        if ([string]$session.session -notmatch '^[A-Za-z0-9-]+$') { throw "Identifiant de session invalide." }
        $studyFiles = (Invoke-SimpleAdb @("shell","run-as",$PackageName,"find",$studyRemote,"-type","f")) -split "\r?\n"
        $studyDir = Join-Path $output "continuous-study"
        foreach ($remote in $studyFiles) {
            if (-not $remote.StartsWith($studyRemote+"/",[StringComparison]::Ordinal)) { throw "Chemin d'etude inattendu." }
            $relative = $remote.Substring($studyRemote.Length+1)
            if ($relative -notmatch '^(segment-[0-9]+/)?guardian-voice-[A-Za-z0-9._-]+[.](wav|f32|tsv)$') { throw "Fichier d'etude inattendu." }
            $local = Join-Path $studyDir $relative.Replace('/',[IO.Path]::DirectorySeparatorChar)
            [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($local))
            Copy-PrivateFile $remote $local
            $header = if ($relative.EndsWith(".wav")) { Get-GuardianWavHeader $local } else { @{valid=$true;kind="study-sidecar"} }
            if (-not $header.valid) { throw "WAV continu incomplet." }
            if ($relative.EndsWith(".f32")) {
                $paired = [IO.Path]::ChangeExtension($local,"wav")
                $waveRemote = [IO.Path]::ChangeExtension($remote,"wav").Replace('\','/')
                if (-not (Test-Path -LiteralPath $paired)) { Copy-PrivateFile $waveRemote $paired }
                $wavHeader = Get-GuardianWavHeader $paired
                if (-not $wavHeader.valid -or (Get-Item -LiteralPath $local).Length -ne ([long]$wavHeader.bytes-44)*2) { throw "Float continu incomplet." }
            }
            $records.Add([pscustomobject]@{file=("continuous-study/"+$relative);header=$header;sha256=(Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()})
        }
        $metadata["continuousStudy"] = $studyEnd[-1]
        $metadata["continuousStudyReady"] = @($studyEvents | Where-Object { $_.event -eq "CONTINUOUS_STUDY_READY" })[-1]
        $studyWindows = @($studyEvents | Where-Object { $_.event -eq "CONTINUOUS_STUDY_WINDOW" })
        $metadata["continuousStudyWindows"] = $studyWindows
        $skippedStudyWindows = @($studyEvents | Where-Object { $_.event -eq "CONTINUOUS_STUDY_WINDOW_SKIPPED" })
        $metadata["continuousStudyWindowsSkipped"] = $skippedStudyWindows
        $metadata["continuousStudyWindowsComplete"] = ([long]$studyEnd[-1].incompleteWindows -eq 0 -and $skippedStudyWindows.Count -eq 0)
        if ($ProcessingMode -eq "WIENER_STUDY") {
            # Startup may pass original samples through while noise calibration is
            # contaminated. This is an explicit study outcome, not invalid audio.
            $metadata["wienerStudy"] = [ordered]@{
                model="WIENER_DD_ADAPTIVE"; role="SHADOW_NO_ALERT"
                mainQwenInput="ORIGINAL"; gtcrnEnabled=$false
                windows=$studyWindows.Count
                startupBypassWindows=@($studyWindows | Where-Object { $_.startupBypass -eq $true }).Count
                calibrationReadyWindows=@($studyWindows | Where-Object { $_.calibrationReady -eq $true }).Count
                calibrationValidWindows=@($studyWindows | Where-Object { $_.calibrationValid -eq $true }).Count
                calibrationNote="A valid study may contain startup bypass when calibration is not ready or valid; inspect each window before comparing enhancement."
            }
        }
        $asrOffers = @($studyEvents | Where-Object { $_.event -eq "CONTINUOUS_STUDY_ASR_OFFER" -and $_.accepted })
        $asrResults = @($studyEvents | Where-Object { $_.event -eq "STUDY_QWEN_RESULT" -and $_.eligible -and ($ProcessingMode -ne "WIENER_STUDY" -or $_.lane -eq "WIENER_DD_ADAPTIVE") })
        $metadata["diagnosticAsrOffered"] = $asrOffers.Count
        $metadata["diagnosticAsrResults"] = $asrResults.Count
        $metadata["diagnosticAsrComplete"] = ($asrOffers.Count -eq $asrResults.Count)
        if ($asrOffers.Count -ne $asrResults.Count) { Write-Warning "Certains decodages diagnostiques n'ont pas termine avant l'arret ; ne pas les compter comme du silence." }
        if (-not $studyEnd[-1].valid) { throw "Etude continue invalidee ; fichiers disponibles recuperes." }
        if (-not $metadata["continuousStudyWindowsComplete"]) { throw "Fenetres d'etude manquantes ou incompletes ; fichiers disponibles recuperes, bloc non valide." }
    }
    if ($CaptureRoute -in @("AUTO","MIC_STEREO_FOCUS")) {
        $readyLines = @(Get-Content -LiteralPath $logPath -Encoding UTF8 | Where-Object { $_ -match 'ADAPTIVE_CHANNELS_READY channels=[0-9]+ enabled=' })
        if ($readyLines.Count -ne 1) { throw "Demarrage de capture adaptive absent ou multiple ; voir audio-routing.log." }
        if ($readyLines[0] -match 'channels=([0-9]+) enabled=(true|false)') {
            $metadata["actualCaptureChannels"] = [int]$Matches[1]
            $metadata["adaptiveChannelSelection"] = ($Matches[2] -eq "true")
        }
        if ($metadata["adaptiveChannelSelection"]) {
            $deadline = [DateTime]::UtcNow.AddSeconds(15)
            do {
                $savedChannels = @(Get-Content -LiteralPath $logPath -Encoding UTF8 | Where-Object { $_ -match 'ADAPTIVE_CHANNELS_WAV_SAVED ' })
                if ($savedChannels.Count -gt 0) { break }
                Start-Sleep -Milliseconds 250
            } while ([DateTime]::UtcNow -lt $deadline)
            if ($savedChannels.Count -ne 1) { throw "Les WAV des canaux ne sont pas finalises ; conserver les logs." }
            $remoteRoot = "files/guardian-diagnostics/adaptive-channels/" + [string]$session.session
            if ([string]$session.session -notmatch '^[A-Za-z0-9-]+$') { throw "Session invalide." }
            $files = (Invoke-SimpleAdb @("shell","run-as",$PackageName,"find",$remoteRoot,"-type","f")) -split "\r?\n"
            foreach ($remote in $files) {
                if (-not $remote.StartsWith($remoteRoot+"/",[StringComparison]::Ordinal)) { throw "Chemin de canal invalide." }
                $relative = $remote.Substring($remoteRoot.Length+1)
                if ($relative -ne "selection.tsv" -and $relative -notmatch '^channel-[0-9]+/guardian-voice-[A-Za-z0-9._-]+[.](wav|f32|tsv)$') { throw "Fichier de canal inattendu." }
                $local = Join-Path (Join-Path $output "adaptive-channels") $relative.Replace('/',[IO.Path]::DirectorySeparatorChar)
                [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($local))
                Copy-PrivateFile $remote $local
                $header = if ($relative.EndsWith(".wav")) { Get-GuardianWavHeader $local } else { @{valid=$true;kind="channel-sidecar"} }
                if (-not $header.valid) { throw "WAV de canal incomplet." }
                if ($relative.EndsWith(".f32")) {
                    $paired = [IO.Path]::ChangeExtension($local,"wav")
                    if (-not (Test-Path -LiteralPath $paired)) { Copy-PrivateFile ([IO.Path]::ChangeExtension($remote,"wav").Replace('\','/')) $paired }
                    $h = Get-GuardianWavHeader $paired
                    if (-not $h.valid -or (Get-Item -LiteralPath $local).Length -ne ([long]$h.bytes-44)*2) { throw "Float de canal incomplet." }
                }
                $records.Add([pscustomobject]@{file=("adaptive-channels/"+$relative);header=$header;sha256=(Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()})
            }
            $channelWavs = @($files | Where-Object { $_ -match '/channel-[0-9]+/[^/]+-raw[.]wav$' })
            $channelFloats = @($files | Where-Object { $_ -match '/channel-[0-9]+/[^/]+-raw[.]f32$' })
            if ($channelWavs.Count -ne $metadata["actualCaptureChannels"] -or $channelFloats.Count -ne $metadata["actualCaptureChannels"]) { throw "Il manque un canal WAV/Float ; fichiers disponibles recuperes." }
            if ($savedChannels[-1] -notmatch 'dropped=0 limited=false') { throw "Diagnostic des canaux incomplet (pertes ou limite) ; fichiers disponibles recuperes." }
            $trace = Join-Path $output "adaptive-channels/selection.tsv"
            $metadata["channelTraceFrames"] = @(Import-Csv -LiteralPath $trace -Delimiter "`t").Count
            if ($metadata["channelTraceFrames"] -lt 1) { throw "Trace de selection vide." }
        }
    }
    if ($CaptureRoute -eq "MIC_STEREO_FOCUS") {
        $pattern = 'MULTIMIC DIAGNOSTIC SAVED : path=(/storage/emulated/0/Android/data/' + [regex]::Escape($PackageName) + '/files/guardian-diagnostics/guardian-multimic-[0-9]{13}-VOICE_FOCUS[.]wav),'
        $stereoPaths = @(Get-Content -LiteralPath $logPath -Encoding UTF8 | ForEach-Object { if ($_ -match $pattern) { $Matches[1] } })
        if ($stereoPaths.Count -ne 1) { throw "WAV multicanal finalise absent ou ambigu ; les autres fichiers sont conserves." }
        $stereoDir = Join-Path $output "multimic"
        [void][IO.Directory]::CreateDirectory($stereoDir)
        $stereoName = [IO.Path]::GetFileName($stereoPaths[0])
        $stereoLocal = Join-Path $stereoDir $stereoName
        Copy-PrivateFile $stereoPaths[0] $stereoLocal
        $stereoHeader = Get-GuardianWavHeader $stereoLocal
        if (-not $stereoHeader.valid -or $stereoHeader.channels -ne 2 -or $stereoHeader.sampleRateHz -ne 16000 -or $stereoHeader.durationSeconds -lt ($DurationSeconds-0.1)) { throw "WAV multicanal invalide ou incomplet." }
        $records.Add([pscustomobject]@{file=("multimic/"+$stereoName); header=$stereoHeader; sha256=(Get-FileHash -LiteralPath $stereoLocal -Algorithm SHA256).Hash.ToLowerInvariant()})
        $metadata["multimicCollected"] = $true
    }
    $exportErrors = @(Read-DiagnosticEvents | Where-Object { $_.session -eq $session.session -and $_.event -in @("WAV_ERROR","WAV_FINALIZE_ERROR","WAV_INVALID_PCM","GTCRN_COMPARISON_ERROR") })
    if ($exportErrors.Count -gt 0) { throw "Erreur d'export audio pendant la session ; les fichiers disponibles sont conserves." }
    if ($TestCase -ne "FREE") {
        $metadata["actualObservations"] = Read-Host "Paroles reellement prononcees, consigne ratee, bruit ou incident (indiquer conforme si tout a ete suivi)"
    }
    (Invoke-SimpleAdb @("shell","dumpsys","meminfo",$PackageName)) | Set-Content -LiteralPath (Join-Path $output "meminfo-after.txt") -Encoding UTF8
    $metadata["completed"] = $true
} catch {
    $failure = $_.Exception.Message
    $metadata["error"] = $failure
    Write-Warning $failure
} finally {
    ConvertTo-Json -InputObject @($markers.ToArray()) -Depth 12 | Set-Content -LiteralPath (Join-Path $output "test-markers.json") -Encoding UTF8
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
            ($_ -match 'GuardianAudioEngine:' -and $_ -match 'ADAPTIVE_CHANNELS|Runtime Audio Profile|Microphone actif|MULTIMIC|[Rr]out|[Cc]apture|[Dd]irection|[Ff]ocal') -or
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
    if ((Get-Item -LiteralPath ($output+".zip")).Length -ge 32MB) {
        $splitter = Join-Path $PSScriptRoot "preparer-envoi-diagnostic.ps1"
        if (Test-Path -LiteralPath $splitter) {
            try { & $splitter -ArchivePath ($output+".zip") }
            catch { Write-Warning ("Partition automatique echouee, ZIP original conserve : " + $_.Exception.Message) }
        } else { Write-Host "ZIP superieur a 32 Mio : utiliser preparer-envoi-diagnostic.ps1 avant envoi." }
    }
}
if ($failure) { throw $failure }
