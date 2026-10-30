#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ProjectDirectory,
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$')][string]$PackageName,
    [ValidateSet("DISTANT","NOISY")][string]$Profile = "DISTANT",
    [ValidateRange(1,120)][int]$DurationSeconds = 75,
    [ValidateSet("AUTO","VOICE_RECOGNITION_MONO","MIC_MONO","MIC_STEREO_FOCUS")]
    [string]$CaptureRoute = "AUTO",
    [ValidateRange(-1,20)][int]$ExpectedAudioSource = -1,
    [ValidateRange(0,30)][int]$ExpectedCaptureChannels = 0,
    [ValidateRange(0,1073741823)][int]$ExpectedChannelIndexMask = 0,
    [ValidateSet("FREE","SILENCE","WORDS","PHRASES")][string]$TestCase = "PHRASES",
    [ValidateSet("ORIGINAL","CANDIDATE","CANDIDATE_GAIN2","CANDIDATE_GAIN4","CONTINUOUS_STUDY","CONTINUOUS_QUALITY_STUDY","WIENER_STUDY","REFERENCE_NOISE_STUDY","ADAPTIVE_CANDIDATE")][string]$ProcessingMode = "ADAPTIVE_CANDIDATE",
    [ValidateRange(0,20)][double]$DistanceMeters = 0,
    [ValidateSet("NORMAL","SOFT")][string]$VoiceLevel = "NORMAL",
    [string]$NoiseDescription = "non precise",
    [ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$DeviceId = ""
)
$ErrorActionPreference = "Stop"
$Profile = $Profile.ToUpperInvariant()
$ProcessingMode = $ProcessingMode.ToUpperInvariant()
$isStudyMode = $ProcessingMode -like "CONTINUOUS_*" -or $ProcessingMode -eq "WIENER_STUDY"
$isReferenceMode = $ProcessingMode -eq "REFERENCE_NOISE_STUDY"
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
function Get-StudyChannelIndex {
    param($Event)
    if ([string]$Event.lane -match '^WIENER_DD_ADAPTIVE_CHANNEL_([0-9]+)$') { return [int]$Matches[1] }
    if ([string]$Event.lane -in @("WIENER_DD_ADAPTIVE","WIENER_DD_ADAPTIVE_MONO")) { return -1 }
    if ($null -ne $Event.channelIndex) { return [int]$Event.channelIndex }
    return -1
}
# NCNN utilise 1 thread, sans runtime OpenMP.
function Get-PrimaryFsmnReady {
    param([object[]]$Events)
    @($Events | Where-Object {
        $_.event -eq "FSMN_READY" -and $_.model -ceq "FSMN_OFFLINE_NCNN_FP32" -and
        $_.required -eq $true -and $_.source -ceq "PRIMARY_ORIGINAL_PCM" -and
        $_.executionBackend -ceq "NCNN_SERIAL_CPU" -and
        $_.targetPeak -eq 0.4 -and $_.maxInputGain -eq 64 -and $_.minInputGain -eq 1
    })
}
function Save-FsmnMetadata {
    param([string]$SessionId, [object[]]$Events)
    if (-not $SessionId) { return }
    $same = @($Events | Where-Object { $_.session -eq $SessionId })
    $fsmnReady = @($same | Where-Object { $_.event -eq "FSMN_READY" })
    $results = @($same | Where-Object { $_.event -eq "FSMN_RESULT" })
    $evaluated = @($results | Where-Object { $_.evaluated -eq $true })
    $accepted = @($evaluated | Where-Object { $_.success -eq $true -and $_.accepted -eq $true })
    $rejected = @($evaluated | Where-Object { $_.success -eq $true -and $_.accepted -eq $false })
    $skipped = @($results | Where-Object { $_.evaluated -eq $false })
    $failed = @($evaluated | Where-Object { $_.success -ne $true })
    $errors = @($same | Where-Object { $_.event -in @("FSMN_START_FAILED","FSMN_ERROR","FSMN_UNAVAILABLE","VOICE_GATE_START_FAILED","FIRERED_START_FAILED") })
    $invalidModels = @($results | Where-Object { $_.model -cne "FSMN_OFFLINE_NCNN_FP32" })
    if ($errors.Count -gt 0 -or $failed.Count -gt 0) {
        $problem = "Confirmation FSMN indisponible ou en erreur ; fichiers recuperables conserves, bloc non valide."
        if (-not $studyProblems.Contains($problem)) { $studyProblems.Add($problem) }
    }
    if ($invalidModels.Count -gt 0) {
        $problem = "Modele FSMN_RESULT inattendu ; bloc non valide."
        if (-not $studyProblems.Contains($problem)) { $studyProblems.Add($problem) }
    }
    $metadata["fsmnConfirmation"] = [ordered]@{
        requiredBySession=$metadata["fsmnConfirmationRequired"]
        startupConfirmed=($metadata["fsmnStartupConfirmed"] -eq $true)
        expectedModel="FSMN_OFFLINE_NCNN_FP32"; source="PRIMARY_ORIGINAL_PCM"
        expectedExecutionBackend="NCNN_SERIAL_CPU"; executionBackendBySession=$metadata["fsmnExecutionBackend"]
        executionBackendDescription="NCNN, 1 thread, sans runtime OpenMP."
        targetPeak=0.4; maxInputGain=64; minInputGain=1
        readyEvents=$fsmnReady; results=$results; evaluated=$evaluated.Count
        admitted=$accepted.Count; rejected=$rejected.Count; skippedEarlierGate=$skipped.Count
        failed=$failed.Count; errors=$errors
        skippedOriginalReject=@($skipped | Where-Object { $_.reason -eq "SKIPPED_ORIGINAL_REJECT" }).Count
        skippedCoherentReject=@($skipped | Where-Object { $_.reason -eq "SKIPPED_COHERENT_REJECT" }).Count
        resultMeaning="Native FSMN admission after earlier gates; evaluated=false is a skipped check, not a model rejection or inference error."
    }
    $admissions = @($same | Where-Object { $_.event -eq "FIRERED_RESULT" })
    $admissionChecks = @()
    foreach ($admission in $admissions) {
        $gate = @($results | Where-Object { $_.segment -eq $admission.segment -and $_.source -eq "PRIMARY_ORIGINAL_PCM" })
        $consistent = ($gate.Count -eq 1 -and $admission.fsmnConfirmationRequired -eq $true)
        if ($admission.accepted -eq $true -and ($gate.Count -ne 1 -or $gate[-1].evaluated -ne $true -or $gate[-1].success -ne $true -or $gate[-1].accepted -ne $true)) { $consistent=$false }
        if ($admission.admissionReason -eq "CALIBRATED_FILTERED_DUAL_NEURAL") {
            $filteredProof = @($same | Where-Object { $_.event -eq "ADAPTIVE_ADMISSION" -and $_.segment -eq $admission.segment })
            $consistent = ($filteredProof.Count -eq 1 -and $filteredProof[0].calibrationClean -eq $true -and
                $filteredProof[0].filteredFireRedAccepted -eq $true -and $filteredProof[0].filteredFsmnAccepted -eq $true -and
                $filteredProof[0].usedFiltered -eq $true -and $admission.fsmnConfirmationRequired -eq $true)
        }
        if (-not $consistent) {
            $problem = "Preuve FSMN absente ou incoherente pour le segment " + [string]$admission.segment + "."
            if (-not $studyProblems.Contains($problem)) { $studyProblems.Add($problem) }
        }
        $admissionChecks += [pscustomobject]@{segment=$admission.segment; admitted=$admission.accepted; admissionReason=$admission.admissionReason; fsmnRecords=$gate.Count; consistent=$consistent}
    }
    $metadata["primaryAdmissionEvents"] = $admissions
    $metadata["fsmnAdmissionChecks"] = $admissionChecks
    $metadata["adaptiveAdmissions"] = @($same | Where-Object { $_.event -eq "ADAPTIVE_ADMISSION" })
    $metadata["adaptiveFilteredWindows"] = @($same | Where-Object { $_.event -eq "ADAPTIVE_ADMISSION" -and $_.usedFiltered -eq $true }).Count
    $metadata["adaptiveFilterAvailableWindows"] = @($same | Where-Object { $_.event -eq "ADAPTIVE_ADMISSION" -and $_.filteredAvailable -eq $true }).Count
    $metadata["adaptiveErrors"] = @($same | Where-Object { $_.event -eq "ADAPTIVE_VALIDATION_ERROR" })
    $metadata["asrMicrophoneSelections"] = @($same | Where-Object { $_.event -eq "ASR_MICROPHONE_SELECTION" })
    $metadata["asrMicrophoneQualities"] = @($same | Where-Object { $_.event -eq "ASR_MICROPHONE_QUALITY" })
    $metadata["asrInputIdentities"] = @($same | Where-Object { $_.event -eq "ASR_INPUT" })
    $metadata["diagnosticDuplicateOffers"] = @($same | Where-Object { $_.event -eq "STUDY_QWEN_OFFER" -and $_.status -eq "DUPLICATE_PCM" })
    $metadata["microphoneSelectionMeaning"] = "Best measured confirmed speech-band contrast on complete original channels; not a guarantee of word accuracy. The selected channel supplies the ASR PCM."

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
# BEGIN REFERENCE EXPORT VALIDATION
function Assert-GuardianReferenceExport {
    param([string]$Directory)
    $registry = @(Import-Csv -LiteralPath (Join-Path $Directory 'reference-files.csv'))
    $timeline = @(Import-Csv -LiteralPath (Join-Path $Directory 'reference-timeline.csv'))
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $full = @{}; $pairs = @(); $clocks = @{}
    foreach ($row in $registry) {
        $relative = [string]$row.path
        if ($relative -notmatch '^epoch-[0-9]+/(segment-[0-9]+/(EXACT|END_PLUS_2S)/)?guardian-voice-[A-Za-z0-9._-]+[.](wav|f32|tsv)$' -or -not $seen.Add($relative)) { throw 'Chemin reference duplique ou interdit.' }
        $file = Join-Path $Directory $relative.Replace('/',[IO.Path]::DirectorySeparatorChar)
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw ('Export reference manquant : '+$relative) }
        if ($row.kind -eq 'index') { if (-not $relative.EndsWith('-index.tsv')) { throw 'Index reference invalide.' }; continue }
        if ($row.kind -notin @('full','pair') -or $row.lane -notin @('primary','reference','short','long')) { throw 'Type ou voie reference invalide.' }
        $samples=[long]$row.samples; $start=[long]$row.globalStart
        if ($samples -le 0 -or $start -lt 0) { throw 'Bornes reference invalides.' }
        $length=(Get-Item -LiteralPath $file).Length
        if (($relative.EndsWith('.f32') -and $length -ne $samples*4) -or ($relative.EndsWith('.wav') -and $length -ne 44+$samples*2)) { throw ('Longueur reference incorrecte : '+$relative) }
        if (-not $relative.EndsWith('.f32')) { continue }
        $entry=@{path=$file; start=$start; samples=$samples; epoch=[int]$row.epoch; lane=[string]$row.lane; segment=[long]$row.segment; variant=[string]$row.variant}
        if ($row.kind -eq 'full') {
            $key=[string]$row.epoch+':'+[string]$row.lane
            if ($full.ContainsKey($key)) { throw 'Voie continue reference dupliquee.' }
            $full[$key]=$entry
        } else {
            if ($samples -gt 128000 -or $row.variant -notin @('EXACT','END_PLUS_2S')) { throw 'Fenetre reference depasse 8 s ou variante inconnue.' }
            $pairs += $entry
        }
    }
    foreach ($row in $timeline) {
        $key=[string]$row.epoch+':'+[string]$row.lane
        if (-not $full.ContainsKey($key)) { throw 'Horloge reference sans voie continue.' }
        $origin=[long]$row.originSample; $start=[long]$row.globalStart; $end=[long]$row.globalEnd
        $offset=[long]$row.fileSampleStart; $count=[long]$row.sampleCount
        if (-not $clocks.ContainsKey($key)) { $clocks[$key]=0L }
        if ($count -le 0 -or $end-$start -ne $count -or $offset -ne $clocks[$key] -or $start -ne $origin+$offset -or $origin -ne $full[$key].start) { throw 'Trou ou decalage dans la timeline reference.' }
        $clocks[$key]+=$count
    }
    foreach ($key in $full.Keys) {
        if (-not $clocks.ContainsKey($key) -or $clocks[$key] -ne $full[$key].samples) { throw 'Timeline reference incomplete.' }
    }
    $groups=@{}
    foreach ($entry in $pairs) {
        $key=[string]$entry.epoch+':'+$entry.lane
        if (-not $full.ContainsKey($key)) { throw 'Paire reference sans source continue.' }
        $source=$full[$key]; $offset=($entry.start-$source.start)*4; $bytes=$entry.samples*4
        if ($offset -lt 0 -or $entry.start+$entry.samples -gt $source.start+$source.samples) { throw 'Paire reference hors couverture reelle.' }
        $buffer=New-Object byte[] ([int]$bytes)
        $stream=[IO.File]::OpenRead($source.path)
        try {
            [void]$stream.Seek($offset,[IO.SeekOrigin]::Begin)
            $read=0
            while ($read -lt $buffer.Length) {
                $part=$stream.Read($buffer,$read,$buffer.Length-$read)
                if ($part -le 0) { throw 'Source reference tronquee.' }
                $read+=$part
            }
        } finally { $stream.Dispose() }
        $hash=[Security.Cryptography.SHA256]::Create()
        try { $expected=([BitConverter]::ToString($hash.ComputeHash($buffer))).Replace('-','').ToLowerInvariant() }
        finally { $hash.Dispose() }
        if ((Get-FileHash -LiteralPath $entry.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Paire reference differente du meme intervalle continu.' }
        $group=[string]$entry.epoch+':'+[string]$entry.segment+':'+$entry.variant
        if (-not $groups.ContainsKey($group)) { $groups[$group]=@() }
        $groups[$group]+=$entry
    }
    foreach ($group in $groups.Values) {
        # Read Hashtable keys explicitly: Select-Object -ExpandProperty differs in Windows PowerShell 5.1.
        if ($group.Count -ne 4 -or @($group | ForEach-Object { [string]($_['lane']) } | Select-Object -Unique).Count -ne 4 -or @($group | ForEach-Object { [string]$_.start+':'+[string]$_.samples } | Select-Object -Unique).Count -ne 1) { throw 'Les quatre voies reference ne partagent pas la meme fenetre.' }
    }
    return [ordered]@{files=$registry.Count; fullFloatStreams=$full.Count; pairedWindows=$groups.Count; pairedFloatStreams=$pairs.Count; timelineRows=$timeline.Count; byteExactPairs=$true; syntheticPaddingAllowed=$false}
}
# END REFERENCE EXPORT VALIDATION

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
    collectorVersion="adaptive-candidate-3.5.3.2"; profile=$Profile; captureRouteRequested=$CaptureRoute; durationRequestedSeconds=$DurationSeconds
    packageName=$PackageName; startUtc=[DateTime]::UtcNow.ToString("o")
    phoneModel=Invoke-SimpleAdb @("shell","getprop","ro.product.model")
    android=Invoke-SimpleAdb @("shell","getprop","ro.build.version.release")
    completed=$false
}
$failure = $null; $process = $null; $restoreFailed = $false
$studyProblems = New-Object 'System.Collections.Generic.List[string]'
$timer = $null
$markers = New-Object 'System.Collections.Generic.List[object]'
$plan = @()
switch ($TestCase) {
    "WORDS" {
        $plan = @(@{at=20;text="Bonjour"},@{at=28;text="Merci"},@{at=36;text="Oui"},@{at=44;text="Bonjour"},@{at=52;text="Merci"},@{at=60;text="Oui"})
    }
    "PHRASES" {
        $plan = @(@{at=20;text="Je vais chercher mes chaussures et je reviens."},@{at=35;text="La fenêtre de la cuisine est ouverte."},@{at=50;text="Le téléphone est posé sur la table."})
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
    if ($ProcessingMode -eq "ADAPTIVE_CANDIDATE") {
        Write-Host "CANDIDAT ADAPTATIF : garder 20 secondes sans parole apres le redemarrage, puis suivre les consignes."
        Write-Host "Le filtre exige deux canaux synchrones ; sans eux, la voie originale reste active. Les traces indiquent la voie reellement utilisee."
    }
    if ($isReferenceMode) {
        Write-Host "REFERENCE BRUIT : allumer le bruit stationnaire avant d'activer la protection et le maintenir pendant toute la capture."
        Write-Host "Garder au moins 20 secondes sans parole apres le redemarrage. Les secondes 2 a 15 de la capture doivent contenir uniquement ce bruit. Ne pas parler avant PARLER. Cette consigne devra etre confirmee par votre observation, pas par un VAD."
        $metadata["referenceNoiseProtocol"] = [ordered]@{longPrefixStartSample=32000; longPrefixEndSample=240000; required="NO_SPEECH_PREFIX"; verification="USER_REPORT_REQUIRED"; diagnosticOnly=$true; productionQualification=$false}
    }
    if ($TestCase -in @("PHRASES","WORDS")) {
        Write-Host "A memoriser avant de vous eloigner :"
        foreach ($item in $plan) { Write-Host (" - " + $item.text) }
        Write-Host "Si l'ecran est illisible a distance : compter au moins 20 secondes sans parole apres le redemarrage, puis lire chaque consigne une fois avec environ 10 secondes de pause. Decrire le deroulement reel a la fin."
    }
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
        if ($knownOld.Count -gt 0) { throw "Le moteur a démarré avec une ancienne révision diagnostique. Installer le module FireRed + etude reference/bruit v3.5.3 et son APK pour ce collecteur." }
        $starts = @($events | Where-Object { $_.event -eq "SESSION_START" -and $_.diagnosticControlRevision -ge 3 -and $_.profile -eq $Profile -and $_.diagnosticsEnabledBy -eq "PRIVATE_FILE" })
        foreach ($candidate in @($starts | Sort-Object elapsedMs -Descending)) {
            if ($candidate.processingMode -ne $ProcessingMode) { throw "Le mode de traitement demande n'a pas ete applique. Nouveau redemarrage complet necessaire." }
            $same = @($events | Where-Object { $_.session -eq $candidate.session })
            # A previously ready session may already have ended while the user
            # was responding to the prompt. Never time a test against its WAV.
            if (@($same | Where-Object { $_.event -eq "SESSION_END" }).Count -gt 0) { continue }
            if ($candidate.engineRevision -ne "guardian-voice-v3.5.3-adaptive-candidate") {
                throw "Ce test exige le nouvel APK FireRed + etude reference/bruit v3.5.3. La revision observee est : $($candidate.engineRevision)"
            }
            if ($ProcessingMode -eq "ADAPTIVE_CANDIDATE" -and $candidate.adaptiveTranscription -ne $true) {
                throw "Le candidat adaptatif n'est pas actif : test interrompu pour eviter une comparaison incorrecte."
            }
            $metadata["session"] = $candidate.session
            $metadata["fsmnConfirmationRequired"] = ($candidate.fsmnConfirmationRequired -eq $true)
            $metadata["fsmnExecutionBackend"] = $candidate.fsmnExecutionBackend
            if ($isReferenceMode) {
                $metadata["referenceSessionConfiguration"] = [ordered]@{enabled=$candidate.referenceNoiseStudy; windowSources=$candidate.referenceNoiseWindowSources; filteredVadBlockSamples=$candidate.referenceNoiseFilteredVadBlockSamples; filteredOfferPolicy=$candidate.referenceNoiseFilteredOfferPolicy; maxFilteredOffers=$candidate.referenceNoiseMaxFilteredOffers; model=$candidate.studyModel; role=$candidate.referenceNoiseRole; profiles=$candidate.referenceNoiseProfiles; contextSamples=$candidate.referenceNoiseContextSamples; maxAsrSamples=$candidate.referenceNoiseMaxAsrSamples; calibrationAssumption=$candidate.referenceNoiseCalibrationAssumption; retainedMonoWienerModel=$candidate.retainedMonoWienerModel}
            }
            $metadata["fsmnSessionConfiguration"] = [ordered]@{model=$candidate.fsmnModel; targetPeak=$candidate.fsmnTargetPeak; maxInputGain=$candidate.fsmnMaxInputGain; executionBackend=$candidate.fsmnExecutionBackend}
            $fsmnStartupErrors = @($same | Where-Object { $_.event -in @("FSMN_START_FAILED","FSMN_ERROR","FSMN_UNAVAILABLE","VOICE_GATE_START_FAILED","FIRERED_START_FAILED") })
            if ($fsmnStartupErrors.Count -gt 0 -and @($same | Where-Object { $_.event -eq "WAV_READY" }).Count -eq 0) {
                $metadata["fsmnStartupConfirmed"] = $false
                throw "La barriere vocale FireRed/FSMN n'a pas pu demarrer avant la capture ; voir guardian.log. Aucun bloc valide."
            }
            if (@($same | Where-Object { $_.event -eq "FIRERED_READY" }).Count -eq 0 -and $fsmnStartupErrors.Count -eq 0) { continue }
            if (@($same | Where-Object { $_.event -in @("WAV_ERROR","WAV_UNAVAILABLE") }).Count -gt 0) { throw "Le moteur signale une erreur WAV : voir guardian.log." }
            if ($isReferenceMode) {
                $referenceReady = @($same | Where-Object { $_.event -eq "REFERENCE_STUDY_READY" -and $_.model -ceq "WIENER_CROSS_CHANNEL_REFERENCE" -and $_.role -ceq "SHADOW_NO_ALERT" -and $_.executionBackend -ceq "NCNN_SERIAL_CPU" -and $_.profiles -ceq "SHORT_0P5_3_AND_LONG_2_15" -and $_.contextSamples -eq 32000 -and $_.maxAsrSamples -eq 128000 -and $_.calibrationAssumption -ceq "PROTOCOL_NO_SPEECH_PREFIX_UNVERIFIED" -and $_.windowSources -ceq "PRIMARY_ORIGINAL_AND_FILTERED_STREAM_LONG" -and $_.fixedVadBlockSamples -eq 800 -and $_.filteredOfferPolicy -ceq "FIRERED_CONTEXT_PREFERRED_FSMN_LOGGED_ONLY" -and $_.maxFilteredDiagnosticOffers -eq 16 })
                $referenceInvalid = @($same | Where-Object { $_.event -eq "REFERENCE_STUDY_INVALID" })
                if ($referenceReady.Count -eq 0 -and $referenceInvalid.Count -eq 0) { continue }
                $metadata["referenceStartupConfirmed"] = ($referenceReady.Count -eq 1 -and $referenceInvalid.Count -eq 0)
                if (-not $metadata["referenceStartupConfirmed"]) { $studyProblems.Add("REFERENCE_STUDY_READY absent ou etude invalide ; recuperation des fichiers seulement.") }
            }
            if ($isStudyMode) {
                if (@($same | Where-Object { $_.event -eq "CONTINUOUS_STUDY_INVALID" }).Count -gt 0) { throw "La voie continue est invalide : voir guardian.log. Le chemin principal reste independant." }
                $studyReady = @($same | Where-Object { $_.event -eq "CONTINUOUS_STUDY_READY" })
                if ($studyReady.Count -eq 0) { continue }
                if ($ProcessingMode -eq "WIENER_STUDY" -and @($studyReady | Where-Object { $_.model -ne "WIENER_DD_ADAPTIVE" -or $_.role -ne "SHADOW_NO_ALERT" -or $_.processingScope -ne "PER_CHANNEL_COHERENT" }).Count -gt 0) {
                    throw "La voie Wiener attendue n'est pas confirmee (WIENER_DD_ADAPTIVE, SHADOW_NO_ALERT, PER_CHANNEL_COHERENT) ; voir guardian.log."
                }
            }
            $wavReady = @($same | Where-Object { $_.event -eq "WAV_READY" })
            if ($wavReady.Count -gt 0 -and @($same | Where-Object { $_.event -eq "AUDIO_FRAME" }).Count -gt 0) {
                $validFsmnReady = @(Get-PrimaryFsmnReady $same)
                $metadata["fsmnStartupConfirmed"] = ($candidate.fsmnConfirmationRequired -eq $true -and $candidate.fsmnExecutionBackend -ceq "NCNN_SERIAL_CPU" -and $validFsmnReady.Count -gt 0 -and $fsmnStartupErrors.Count -eq 0)
                if ($candidate.fsmnConfirmationRequired -ne $true) {
                    $studyProblems.Add("SESSION_START ne confirme pas fsmnConfirmationRequired=true ; bloc non valide.")
                }
                if ($candidate.fsmnExecutionBackend -cne "NCNN_SERIAL_CPU") {
                    $studyProblems.Add("SESSION_START ne confirme pas fsmnExecutionBackend=NCNN_SERIAL_CPU ; bloc non valide.")
                }
                if ($validFsmnReady.Count -eq 0) {
                    $studyProblems.Add("FSMN_READY absent ou incorrect : FSMN_OFFLINE_NCNN_FP32, PRIMARY_ORIGINAL_PCM, NCNN_SERIAL_CPU, cible 0.4 / gain maximal 64 requis.")
                }
                if (-not $metadata["fsmnStartupConfirmed"]) {
                    Write-Warning "Demarrage FSMN non confirme : collecte des fichiers recuperables uniquement ; ce bloc restera non valide."
                }
                if ($isReferenceMode -and ($candidate.referenceNoiseWindowSources -cne "PRIMARY_ORIGINAL_AND_FILTERED_STREAM_LONG" -or $candidate.referenceNoiseFilteredVadBlockSamples -ne 800 -or $candidate.referenceNoiseMaxFilteredOffers -ne 16 -or $candidate.referenceNoiseStudy -ne $true -or $candidate.studyModel -cne "WIENER_CROSS_CHANNEL_REFERENCE" -or $candidate.referenceNoiseRole -cne "SHADOW_NO_ALERT" -or $candidate.referenceNoiseProfiles -cne "SHORT_0P5_3_AND_LONG_2_15" -or $candidate.referenceNoiseContextSamples -ne 32000 -or $candidate.referenceNoiseMaxAsrSamples -ne 128000 -or $candidate.referenceNoiseCalibrationAssumption -cne "PROTOCOL_NO_SPEECH_PREFIX_UNVERIFIED")) {
                    $studyProblems.Add("SESSION_START ne confirme pas le protocole REFERENCE_NOISE_STUDY attendu ; bloc non valide.")
                }
                $session = $candidate; $ready = $wavReady[-1]; break
            }
        }
        if (-not $session) { Start-Sleep -Milliseconds 400 }
    } while (-not $session -and [DateTime]::UtcNow -lt $deadline)
    if (-not $session) {
        $observed = @(Read-DiagnosticEvents | Where-Object { $_.event -eq "SESSION_START" })
        if ($observed.Count -gt 0 -and $observed[-1].diagnosticControlRevision -lt 3) {
            throw "Le moteur a démarré, mais ce collecteur exige le module FireRed + etude reference/bruit v3.5.3 et son nouvel APK. Les logs permettent de vérifier la version installée."
        }
        throw "Aucun démarrage complet avec WAV confirmé. Vérifier l'arrêt complet puis la réactivation de la protection ; voir guardian.log et adb-errors.log. Ce message ne prouve pas que le build est incorrect."
    }
    $capture = @($events | Where-Object { $_.session -eq $session.session -and $_.event -eq "CAPTURE_CONFIG" })
    if ($CaptureRoute -ne "AUTO") {
        $expectedSource = if ($CaptureRoute -eq "VOICE_RECOGNITION_MONO") { 6 } else { 1 }
        $channelsApplied = $capture.Count -gt 0 -and $(if ($CaptureRoute -eq "MIC_STEREO_FOCUS") {
            $capture[-1].channels -ge 2 -and $capture[-1].channels -le 30
        } else { $capture[-1].channels -eq 1 })
        if ($capture.Count -eq 0 -or $capture[-1].audioSource -ne $expectedSource -or -not $channelsApplied) {
            throw "La route audio demandée n'a pas été appliquée. Le téléphone a peut-être refusé cette configuration ; voir audio-routing.log."
        }
    }
    if ($capture.Count -gt 0) { $metadata["captureConfig"] = $capture[-1] }
    $metadata["expectedAudioSource"] = $ExpectedAudioSource
    if ($ExpectedAudioSource -ge 0 -and ($capture.Count -eq 0 -or $capture[-1].audioSource -ne $ExpectedAudioSource)) {
        throw ("Route non conforme : source Android " + $ExpectedAudioSource + " attendue. Arreter la protection. Configuration observee : " + ($capture | ConvertTo-Json -Compress))
    }
    $metadata["expectedCaptureChannels"] = $ExpectedCaptureChannels
    $metadata["expectedChannelIndexMask"] = $ExpectedChannelIndexMask
    if ($ExpectedCaptureChannels -gt 0 -and ($capture.Count -eq 0 -or $capture[-1].channels -ne $ExpectedCaptureChannels)) {
        throw ("Route non conforme : " + $ExpectedCaptureChannels + " canaux Android attendus. Configuration observee : " + ($capture | ConvertTo-Json -Compress))
    }
    if ($ExpectedChannelIndexMask -gt 0 -and ($capture.Count -eq 0 -or $capture[-1].requestedChannelIndexMask -ne $ExpectedChannelIndexMask)) {
        throw ("Route non conforme : masque de canaux " + $ExpectedChannelIndexMask + " attendu. Configuration observee : " + ($capture | ConvertTo-Json -Compress))
    }
    if ($ExpectedAudioSource -ge 0 -or $ExpectedCaptureChannels -gt 0 -or $ExpectedChannelIndexMask -gt 0) {
        Write-Host ("Route verifiee : source=" + $capture[-1].audioSource + ", canaux=" + $capture[-1].channels + ", masque=" + $capture[-1].requestedChannelIndexMask)
    }
    $prefix = [string]$ready.prefix
    if ($prefix -notmatch '^guardian-voice-\d{13}-(DISTANT|NOISY)-[A-Za-z0-9]{1,8}$') { throw "Nom d'enregistrement invalide dans les logs." }
    $metadata["session"] = $session.session
    $metadata["audioPrefix"] = $prefix
    $metadata["recordStartUtc"] = [DateTime]::UtcNow.ToString("o")
    [void](Invoke-SimpleAdb @("shell","log","-p","i","-t","GuardianVoiceDiag",("SIMPLE_RECORD_BEGIN_"+$Profile)))
    if ($metadata["fsmnStartupConfirmed"]) {
        if ($isReferenceMode -or $TestCase -ne "FREE") { Write-Host "ENREGISTREMENT ACTIF : garder le silence jusqu'a PARLER." }
        else { Write-Host "ENREGISTREMENT ACTIF : parler maintenant." }
    }
    else { Write-Host "RECUPERATION DIAGNOSTIQUE : demarrage FSMN non confirme ; ce bloc ne sera pas valide." }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    Add-TestMarker "BEGIN"
    $nextPrompt = 0
    $freeReferencePrompted = $false
    if ($TestCase -ne "FREE") { Write-Host "Garder le silence. Lire uniquement lorsque PARLER est affiche. Aucun signal sonore ne sera emis." }
    $memoryCaptured = $false
    $lastRemaining = -1
    $lastSessionCheck = -1
    while ($timer.Elapsed.TotalSeconds -lt $DurationSeconds) {
        if ($process.HasExited) { throw "La lecture des logs s'est interrompue." }
        if ($isReferenceMode -and $TestCase -eq "FREE" -and -not $freeReferencePrompted -and $timer.Elapsed.TotalSeconds -ge 20) {
            Write-Host "PARLER : parole libre, avec quelques pauses."
            Add-TestMarker "FREE_AFTER_PREFIX"
            $freeReferencePrompted = $true
        }
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
    if ($Profile -eq "NOISY" -and -not $isStudyMode -and -not $isReferenceMode) {
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
            if ($relative -notmatch '^((channel-[0-9]+/)?(segment-[0-9]+/)?guardian-voice-[A-Za-z0-9._-]+[.](wav|f32|tsv)|[A-Za-z][A-Za-z0-9_-]*[.]tsv)$') { throw "Fichier d'etude inattendu." }
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
        $channelSkippedWindows = @($studyEvents | Where-Object { $_.event -eq "CONTINUOUS_STUDY_CHANNEL_WINDOW_SKIPPED" })
        $metadata["continuousStudyChannelWindowsSkipped"] = $channelSkippedWindows
        $missingChannelPayloads = @($studyEvents | Where-Object { $_.event -eq "CONTINUOUS_STUDY_CHANNEL_PAYLOAD_MISSING" })
        $metadata["continuousStudyMissingChannelPayloads"] = $missingChannelPayloads
        if ($missingChannelPayloads.Count -gt 0 -or [long]$studyEnd[-1].missingChannelPayloads -gt 0) {
            $studyProblems.Add("Charge utile multicanal perdue ; aucune substitution mono, etude non valide.")
        }
        $metadata["continuousStudyWindowsComplete"] = ([long]$studyEnd[-1].incompleteWindows -eq 0 -and $skippedStudyWindows.Count -eq 0)
        $filteredWindows = @($studyWindows | Where-Object { $_.startupBypass -eq $false })
        $metadata["filteredComparisonWindows"] = $filteredWindows.Count
        $metadata["comparativeStudy"] = ($filteredWindows.Count -gt 0)
        if ($ProcessingMode -eq "WIENER_STUDY") {
            $metadata["wienerStudy"] = [ordered]@{
                model="WIENER_DD_ADAPTIVE"; role="SHADOW_NO_ALERT"; processingScope="PER_CHANNEL_COHERENT"
                mainQwenInput="ORIGINAL"; gtcrnEnabled=$false
                windows=$studyWindows.Count; filteredComparisonWindows=$filteredWindows.Count
                startupBypassWindows=@($studyWindows | Where-Object { $_.startupBypass -eq $true }).Count
                calibrationReadyWindows=@($studyWindows | Where-Object { $_.calibrationReady -eq $true }).Count
                calibrationValidWindows=@($studyWindows | Where-Object { $_.calibrationValid -eq $true }).Count
                calibrationNote="Startup bypass is original audio. WORDS/PHRASES require at least one filtered comparison window; SILENCE may have no candidate."
            }
            if ($TestCase -in @("WORDS","PHRASES") -and $filteredWindows.Count -eq 0) {
                $studyProblems.Add("Etude non comparative : aucune fenetre filtree apres calibration pour le test de parole.")
            }
            # The timeline keeps the capture clock, including late channel origins
            # and real gaps. Never subtract originSample or join global intervals.
            $timeline = Join-Path $studyDir "channel-timeline.tsv"
            $timelineRows = @()
            $timelineCounts = @{}; $timelineEnds = @{}; $timelineChannels = @{}
            if (-not (Test-Path -LiteralPath $timeline)) {
                $studyProblems.Add("Chronologie globale des canaux absente : channel-timeline.tsv.")
            } else {
                $timelineRows = @(Import-Csv -LiteralPath $timeline -Delimiter "`t")
                if ($timelineRows.Count -eq 0) { $studyProblems.Add("Chronologie globale des canaux vide.") }
                foreach ($row in $timelineRows) {
                    $values = @{}; $numeric = $true
                    foreach ($name in @("channelIndex","routeRevision","originSample","globalStart","globalEnd","fileSampleStart","sampleCount","sequence","capturedAtNs")) {
                        $parsed = 0L
                        if (-not [long]::TryParse([string]$row.$name,[ref]$parsed)) { $numeric=$false; break }
                        $values[$name] = $parsed
                    }
                    if (-not $numeric -or $row.stage -notin @("truth","asr")) {
                        $studyProblems.Add("Ligne de chronologie des canaux invalide."); continue
                    }
                    $key = [string]$values.channelIndex + ":" + $row.stage
                    $expectedFileStart = if ($timelineCounts.ContainsKey($key)) { [long]$timelineCounts[$key] } else { 0L }
                    $previousGlobalEnd = if ($timelineEnds.ContainsKey($key)) { [long]$timelineEnds[$key] } else { 0L }
                    if ($values.channelIndex -lt -1 -or $values.channelIndex -gt 29 -or $values.routeRevision -lt 0 -or
                        $values.originSample -lt 0 -or $values.globalStart -lt $values.originSample -or
                        $values.globalStart -lt $previousGlobalEnd -or $values.sampleCount -le 0 -or
                        $values.globalEnd -ne ($values.globalStart+$values.sampleCount) -or
                        $values.globalEnd -gt [long]$studyEnd[-1].inputSamples -or
                        $values.fileSampleStart -ne $expectedFileStart -or $values.capturedAtNs -lt 0) {
                        $studyProblems.Add("Chronologie globale incoherente : " + $key)
                    }
                    $timelineCounts[$key] = $expectedFileStart + $values.sampleCount
                    $timelineEnds[$key] = $values.globalEnd
                    $timelineChannels[[string]$values.channelIndex] = $true
                }
            }
            foreach ($channel in @($timelineChannels.Keys | Sort-Object)) {
                $folder = if ([int]$channel -ge 0) { "channel-"+$channel+"/" } else { "" }
                $indexPattern = "^"+[regex]::Escape($studyRemote+"/"+$folder)+"guardian-voice-[A-Za-z0-9._-]+-index[.]tsv$"
                if (@($studyFiles | Where-Object { $_ -match $indexPattern }).Count -ne 1) { $studyProblems.Add("Index continu manquant pour le canal " + $channel) }
                foreach ($stage in @("truth","asr")) {
                    $pattern = "^"+[regex]::Escape($studyRemote+"/"+$folder)+"guardian-voice-[A-Za-z0-9._-]+-"+$stage
                    $waves = @($studyFiles | Where-Object { $_ -match ($pattern+"[.]wav$") })
                    $floats = @($studyFiles | Where-Object { $_ -match ($pattern+"[.]f32$") })
                    if ($waves.Count -ne 1 -or $floats.Count -ne 1) {
                        $studyProblems.Add("Charge utile continue WAV/Float manquante pour le canal " + $channel + ", " + $stage)
                    } else {
                        $localWave = Join-Path $studyDir $waves[0].Substring($studyRemote.Length+1).Replace('/',[IO.Path]::DirectorySeparatorChar)
                        $h = Get-GuardianWavHeader $localWave
                        if ([long](($h.bytes-44)/2) -ne [long]$timelineCounts[([string]$channel+":"+$stage)]) {
                            $studyProblems.Add("Chronologie et WAV divergent pour le canal " + $channel + ", " + $stage)
                        }
                    }
                }
            }
            foreach ($w in $studyWindows) {
                $channel = Get-StudyChannelIndex $w
                if ([long]$w.samples -le 0 -or [long]$w.startSample -lt 0 -or
                    [long]$w.endSample -ne ([long]$w.startSample+[long]$w.samples) -or
                    [long]$w.endSample -gt [long]$studyEnd[-1].inputSamples) {
                    $studyProblems.Add("Position globale de fenetre incoherente : canal " + $channel + ", segment " + $w.segment)
                }
                $folder = if ($channel -ge 0) { "channel-"+$channel+"/" } else { "" }
                $pairRoot = $studyRemote+"/"+$folder+"segment-"+[string]$w.segment+"/"
                foreach ($suffix in @("truth.wav","asr.wav","truth.f32","asr.f32","index.tsv")) {
                    $payload = @($studyFiles | Where-Object { $_ -match ("^"+[regex]::Escape($pairRoot)+"guardian-voice-[A-Za-z0-9._-]+-"+[regex]::Escape($suffix)+"$") })
                    if ($payload.Count -ne 1) { $studyProblems.Add("Charge utile de comparaison manquante : canal " + $channel + ", segment " + $w.segment + ", " + $suffix) }
                    elseif ($suffix.EndsWith(".wav")) {
                        $localPair = Join-Path $studyDir $payload[0].Substring($studyRemote.Length+1).Replace('/',[IO.Path]::DirectorySeparatorChar)
                        $pairHeader = Get-GuardianWavHeader $localPair
                        if ([long](($pairHeader.bytes-44)/2) -ne [long]$w.samples) { $studyProblems.Add("Longueur de paire incoherente : canal " + $channel + ", segment " + $w.segment) }
                    }
                }
            }
            $sumOutputSamples = 0L
            foreach ($key in $timelineCounts.Keys) { if ($key.EndsWith(":asr")) { $sumOutputSamples += [long]$timelineCounts[$key] } }
            if ($studyEnd[-1].outputSamplesScope -eq "SUM_OF_CHANNEL_OUTPUTS" -and [long]$studyEnd[-1].outputSamples -ne $sumOutputSamples) {
                $studyProblems.Add("Total des echantillons de sortie incoherent avec la chronologie des canaux.")
            }
            if ($null -ne $studyEnd[-1].channelPairs -and [long]$studyEnd[-1].channelPairs -ne $studyWindows.Count) {
                $studyProblems.Add("Nombre de paires de canaux incoherent avec les fenetres exportees.")
            }
            $metadata["channelTimelineRows"] = $timelineRows.Count
            $metadata["channelTimelineChannels"] = @($timelineChannels.Keys | ForEach-Object { [int]$_ } | Sort-Object)
            $metadata["channelTimelineClock"] = "GLOBAL_CAPTURE_SAMPLE_CLOCK_WITH_GAPS"
        }
        $asrRequests = @($studyEvents | Where-Object { $_.event -eq "CONTINUOUS_STUDY_ASR_OFFER" })
        $asrOffers = @($asrRequests | Where-Object { $_.accepted -eq $true })
        $asrDeclined = @($asrRequests | Where-Object { $_.accepted -ne $true })
        $asrTerminals = @($studyEvents | Where-Object { $_.event -eq "STUDY_QWEN_RESULT" -and ($ProcessingMode -ne "WIENER_STUDY" -or $_.lane -match '^WIENER_DD_ADAPTIVE(_CHANNEL_[0-9]+|_MONO)?$') })
        $asrResults = @($asrTerminals | Where-Object { $_.eligible -eq $true -and $_.success -eq $true })
        $asrDropped = @($asrTerminals | Where-Object { $_.eligible -eq $false -or $_.status -eq "DROPPED" })
        $asrFailed = @($asrTerminals | Where-Object { $_.eligible -eq $true -and $_.success -ne $true })
        $asrEmpty = @($asrResults | Where-Object { $_.status -eq "EMPTY" })
        $queueOffers = @($studyEvents | Where-Object { $_.event -eq "STUDY_QWEN_OFFER" })
        $associations = @()
        $perChannel = @()
        $matchedResults = 0
        foreach ($offer in $asrOffers) {
            $channel = Get-StudyChannelIndex $offer
            $matching = @($asrResults | Where-Object { $_.segment -eq $offer.segment -and (Get-StudyChannelIndex $_) -eq $channel })
            $terminals = @($asrTerminals | Where-Object { $_.segment -eq $offer.segment -and (Get-StudyChannelIndex $_) -eq $channel })
            $gate = @($studyWindows | Where-Object { $_.segment -eq $offer.segment -and (Get-StudyChannelIndex $_) -eq $channel })
            $matchedResults += $matching.Count
            $associations += [pscustomobject]@{
                segment=$offer.segment; channelIndex=$channel; lane=$offer.lane
                sourceEvidence=if($gate.Count){$gate[-1].sourceEvidence}else{$null}
                rawRejectedFilteredAccepted=if($gate.Count){$gate[-1].rawRejectedFilteredAccepted}else{$null}
                primaryAccepted=if($gate.Count){$gate[-1].primaryAccepted}else{$null}
                channelOriginalAccepted=if($gate.Count){$gate[-1].channelOriginalAccepted}else{$null}
                enhancedAccepted=if($gate.Count){$gate[-1].enhancedAccepted}else{$null}
                startupBypass=if($gate.Count){$gate[-1].startupBypass}else{$null}
                resultCount=$matching.Count; results=$matching
                terminalCount=$terminals.Count; terminalResults=$terminals
            }
        }
        $channelIndices = @(@($timelineChannels.Keys) + @($studyWindows | ForEach-Object { Get-StudyChannelIndex $_ }) | ForEach-Object { [int]$_ } | Sort-Object -Unique)
        foreach ($channel in $channelIndices) {
            $channelWindows = @($studyWindows | Where-Object { (Get-StudyChannelIndex $_) -eq $channel })
            $perChannel += [pscustomobject]@{
                channelIndex=$channel; windows=$channelWindows.Count
                skippedWindows=@($channelSkippedWindows | Where-Object { (Get-StudyChannelIndex $_) -eq $channel }).Count
                filteredComparisonWindows=@($channelWindows | Where-Object { $_.startupBypass -eq $false }).Count
                startupBypassWindows=@($channelWindows | Where-Object { $_.startupBypass -eq $true }).Count
                primaryAcceptedWindows=@($channelWindows | Where-Object { $_.primaryAccepted -eq $true }).Count
                originalAcceptedWindows=@($channelWindows | Where-Object { $_.channelOriginalAccepted -eq $true }).Count
                enhancedAcceptedWindows=@($channelWindows | Where-Object { $_.enhancedAccepted -eq $true }).Count
                rawRejectedFilteredAcceptedWindows=@($channelWindows | Where-Object { $_.rawRejectedFilteredAccepted -eq $true }).Count
                diagnosticAsrRequested=@($asrRequests | Where-Object { (Get-StudyChannelIndex $_) -eq $channel }).Count
                diagnosticAsrDeclined=@($asrDeclined | Where-Object { (Get-StudyChannelIndex $_) -eq $channel }).Count
                diagnosticAsrOffered=@($asrOffers | Where-Object { (Get-StudyChannelIndex $_) -eq $channel }).Count
                diagnosticAsrResults=@($asrResults | Where-Object { (Get-StudyChannelIndex $_) -eq $channel }).Count
                diagnosticAsrDropped=@($asrDropped | Where-Object { (Get-StudyChannelIndex $_) -eq $channel }).Count
                diagnosticAsrFailed=@($asrFailed | Where-Object { (Get-StudyChannelIndex $_) -eq $channel }).Count
                diagnosticAsrEmpty=@($asrEmpty | Where-Object { (Get-StudyChannelIndex $_) -eq $channel }).Count
            }
        }
        $metadata["perChannelComparisons"] = $perChannel
        $metadata["coherentGateResults"] = $associations
        $metadata["diagnosticAsrResultEvents"] = $asrTerminals
        $metadata["diagnosticAsrQueueOfferEvents"] = $queueOffers
        $metadata["diagnosticAsrQueueLimits"] = @($queueOffers | Where-Object { $null -ne $_.maxPendingDiagnostics } | ForEach-Object { $_.maxPendingDiagnostics } | Sort-Object -Unique)
        $metadata["diagnosticAsrDropped"] = $asrDropped.Count
        $metadata["diagnosticAsrFailed"] = $asrFailed.Count
        $metadata["diagnosticAsrEmpty"] = $asrEmpty.Count
        $metadata["diagnosticAsrMeaning"] = "Results count successful eligible decodes, including explicit EMPTY text. Declined, failed, dropped or pending work does not prove silence."
        $metadata["diagnosticAsrRequested"] = $asrRequests.Count
        $metadata["diagnosticAsrRequestEvents"] = $asrRequests
        $metadata["diagnosticAsrDeclined"] = $asrDeclined.Count
        $metadata["diagnosticAsrOffered"] = $asrOffers.Count
        $metadata["diagnosticAsrResults"] = $asrResults.Count
        $metadata["diagnosticAsrUnmatchedResults"] = $asrResults.Count-$matchedResults
        $metadata["diagnosticAsrComplete"] = (@($associations | Where-Object { $_.resultCount -ne 1 }).Count -eq 0 -and $matchedResults -eq $asrResults.Count)
        $metadata["diagnosticAsrCoverageComplete"] = ($metadata["diagnosticAsrComplete"] -and $asrDeclined.Count -eq 0 -and $asrFailed.Count -eq 0 -and $asrDropped.Count -eq 0)
        $metadata["diagnosticAsrPartial"] = -not $metadata["diagnosticAsrCoverageComplete"]
        if ($asrDeclined.Count -gt 0) { Write-Warning "Des decodages diagnostiques ont ete refuses ; comparer seulement les canaux effectivement decodes, sans compter les refus comme du silence." }
        if (-not $metadata["diagnosticAsrComplete"]) { Write-Warning "Certains decodages diagnostiques manquent ou ne correspondent pas au canal/segment offert ; ne pas les compter comme du silence." }
        if (-not $studyEnd[-1].valid) { $studyProblems.Add("Etude continue invalidee ; fichiers disponibles recuperes.") }
        if (-not $metadata["continuousStudyWindowsComplete"]) { $studyProblems.Add("Fenetres d'etude manquantes ou incompletes ; fichiers disponibles recuperes, bloc non valide.") }
    }

    if ($ProcessingMode -eq "ADAPTIVE_CANDIDATE") {
        $asrInputs = @(Read-DiagnosticEvents | Where-Object { $_.session -eq $session.session -and $_.event -eq "ASR_INPUT" })
        foreach ($inputEvent in $asrInputs) {
            $prefix = [string]$inputEvent.windowExportPrefix
            if ($prefix -notmatch ('^adaptive-asr/' + [regex]::Escape([string]$session.session) + '/segment-[0-9]+/guardian-voice-[A-Za-z0-9._-]+$')) {
                throw "Identite de la fenetre ASR absente ou invalide."
            }
            foreach ($suffix in @("-truth.wav","-truth.f32","-asr.wav","-asr.f32","-index.tsv")) {
                $relative = $prefix + $suffix
                $local = Join-Path $output $relative.Replace('/',[IO.Path]::DirectorySeparatorChar)
                [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($local))
                Copy-PrivateFile ("files/guardian-diagnostics/"+$relative) $local
                $header = if ($suffix.EndsWith(".wav")) { Get-GuardianWavHeader $local } else { @{valid=$true;kind="asr-window-sidecar"} }
                if (-not $header.valid) { throw "Fenetre WAV ASR incomplete." }
                $records.Add([pscustomobject]@{file=$relative;header=$header;sha256=(Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()})
            }
            $pcmPath = Join-Path $output (($prefix+"-asr.f32").Replace('/',[IO.Path]::DirectorySeparatorChar))
            if ((Get-Item -LiteralPath $pcmPath).Length -ne ([long]$inputEvent.samples*4) -or
                (Get-FileHash -LiteralPath $pcmPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne [string]$inputEvent.pcmSha256) {
                throw "Les octets exportes ne correspondent pas a l'entree Qwen."
            }
        }
        $metadata["adaptiveAsrWindowCount"] = $asrInputs.Count
    }
    if ($isReferenceMode) {
        $referenceDeadline=[DateTime]::UtcNow.AddSeconds(20)
        do {
            $referenceEvents=@(Read-DiagnosticEvents | Where-Object { $_.session -eq $session.session })
            $referenceEnd=@($referenceEvents | Where-Object { $_.event -eq "REFERENCE_STUDY_END" })
            if ($referenceEnd.Count -gt 0) { break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $referenceDeadline)
        if ($referenceEnd.Count -ne 1) { throw "La voie reference n'a pas finalise ses fichiers ; journaux conserves." }
        if ([string]$session.session -notmatch '^[A-Za-z0-9-]+$') { throw "Identifiant de session reference invalide." }
        $referenceRemote="files/guardian-diagnostics/reference-study/"+[string]$session.session
        $referenceDirectory=Join-Path $output "reference-study"
        [void][IO.Directory]::CreateDirectory($referenceDirectory)
        $referenceFiles=(Invoke-SimpleAdb @("shell","run-as",$PackageName,"find",$referenceRemote,"-type","f")) -split "\r?\n"
        foreach ($remote in $referenceFiles) {
            if (-not $remote.StartsWith($referenceRemote+"/",[StringComparison]::Ordinal)) { throw "Chemin reference inattendu." }
            $relative=$remote.Substring($referenceRemote.Length+1)
            if ($relative -notmatch '^(reference-(files|timeline)[.]csv|epoch-[0-9]+/(segment-[0-9]+/(EXACT|END_PLUS_2S)/)?guardian-voice-[A-Za-z0-9._-]+[.](wav|f32|tsv))$') { throw "Fichier reference interdit." }
            $local=Join-Path $referenceDirectory $relative.Replace('/',[IO.Path]::DirectorySeparatorChar)
            [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($local))
            Copy-PrivateFile $remote $local
            $header=if ($relative.EndsWith('.wav')) { Get-GuardianWavHeader $local } else { @{valid=$true;kind="reference-sidecar"} }
            if (-not $header.valid) { throw "WAV reference incomplet." }
            $records.Add([pscustomobject]@{file=("reference-study/"+$relative); header=$header; sha256=(Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()})
        }
        $metadata["referenceStudy"]=$referenceEnd[-1]
        $metadata["referenceExports"]=Assert-GuardianReferenceExport $referenceDirectory
        $metadata["referenceApplicable"]=($referenceEnd[-1].pairEverAvailable -eq $true)
        if ($metadata["referenceExports"].pairedWindows -ne ([int]$referenceEnd[-1].exactPairs+[int]$referenceEnd[-1].contextPairs)) { $studyProblems.Add("Nombre de fenetres reference different du manifeste exporte.") }
        if (@($referenceEvents | Where-Object { $_.event -eq "REFERENCE_STUDY_WINDOW" -and ($_.diagnosticForced -ne $true -or $_.productionEligible -ne $false -or $_.role -cne "SHADOW_NO_ALERT") }).Count -gt 0) { $studyProblems.Add("Fenetre reference sans statut diagnostique explicite.") }
        $metadata["referenceWindows"]=@($referenceEvents | Where-Object { $_.event -eq "REFERENCE_STUDY_WINDOW" })
        $metadata["referenceDiagnosticResults"]=@($referenceEvents | Where-Object { $_.event -eq "STUDY_QWEN_RESULT" -and $_.lane -like "REFERENCE_NOISE_LONG_*" })
        $metadata["referenceFilteredFallbacks"]=@($referenceEvents | Where-Object { $_.event -eq "REFERENCE_FILTERED_FALLBACK" })
        $metadata["referenceFilteredCandidates"]=@($referenceEvents | Where-Object { $_.event -eq "REFERENCE_FILTERED_CANDIDATE" })
        $metadata["referenceFilteredEnd"]=@($referenceEvents | Where-Object { $_.event -eq "REFERENCE_FILTERED_END" })
        $metadata["referenceDiagnosticMeaning"]="Forced shadow ASR, including acoustic rejections; never product alerts. Missing/declined diagnostics are not silence."
        if (-not $referenceEnd[-1].valid) { $studyProblems.Add("La voie reference a ete invalidee ; fichiers recuperables conserves.") }
        if (-not $metadata["referenceApplicable"]) { Write-Warning "Pas de paire de canaux exploitable : capture originale conservee, comparaison intercanaux non applicable. WIENER_STUDY reste disponible en mono." }
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
            $metadata["multimicCollected"] = $true
            $metadata["multimicCaptureFormat"] = "PER_CHANNEL_FLOAT32_AND_PCM16"
        }
    }
    # Adaptive capture exports every source separately. Its input loop bypasses
    # the legacy stereo writer, whose header-only file is not a source recording.
    # Require legacy stereo only when the verified adaptive exports are absent.
    if ($CaptureRoute -eq "MIC_STEREO_FOCUS" -and -not $metadata["adaptiveChannelSelection"]) {
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
        $metadata["multimicCaptureFormat"] = "LEGACY_STEREO_PCM16"
    }
    $exportErrors = @(Read-DiagnosticEvents | Where-Object { $_.session -eq $session.session -and $_.event -in @("WAV_ERROR","WAV_FINALIZE_ERROR","WAV_INVALID_PCM","GTCRN_COMPARISON_ERROR") })
    if ($exportErrors.Count -gt 0) { throw "Erreur d'export audio pendant la session ; les fichiers disponibles sont conserves." }
    if ($TestCase -ne "FREE") {
        $metadata["actualObservations"] = Read-Host "Paroles reellement prononcees, consigne ratee, bruit ou incident (indiquer conforme si tout a ete suivi)"
    }
    if ($isReferenceMode) {
        $metadata["referenceNoiseUserReport"] = Read-Host "Les secondes 2 a 15 de capture contenaient-elles seulement le bruit prevu, sans parole ? Decrire OUI/NON/INCONNU et les incidents reels"
        $metadata["referenceNoiseProtocol"]["verification"] = "USER_REPORT_RECORDED_UNADJUDICATED"
    }
    (Invoke-SimpleAdb @("shell","dumpsys","meminfo",$PackageName)) | Set-Content -LiteralPath (Join-Path $output "meminfo-after.txt") -Encoding UTF8
    Save-FsmnMetadata ([string]$metadata["session"]) @(Read-DiagnosticEvents)
    $metadata["studyValidationErrors"] = @($studyProblems.ToArray())
    if ($studyProblems.Count -gt 0) { throw ($studyProblems -join " ") }
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
    Save-FsmnMetadata ([string]$metadata["session"]) @(Read-DiagnosticEvents)
    if ($studyProblems.Count -gt 0) {
        $metadata["completed"] = $false
        if (-not $failure) { $failure = $studyProblems -join " "; $metadata["error"] = $failure; Write-Warning $failure }
    }
    $metadata["studyValidationErrors"] = @($studyProblems.ToArray())
    $metadata["endUtc"] = [DateTime]::UtcNow.ToString("o")
    $metadata["controlsRestored"] = -not $restoreFailed
    if ($restoreFailed) { $metadata["controlBackupDirectory"] = $work }
    $metadata | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $output "session.json") -Encoding UTF8
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
