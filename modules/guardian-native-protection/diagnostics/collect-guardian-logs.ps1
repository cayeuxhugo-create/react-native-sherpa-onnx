#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._:-]+$')]
    [string]$DeviceId = "",
    [string]$OutputDirectory = "",
    [switch]$IncludeWav,
    [switch]$Full,
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$')]
    [string]$PackageName = ""
)
$ErrorActionPreference = "Stop"
$adbExecutable = (Get-Command adb -ErrorAction Stop).Source
$deviceArguments = @()
if ($DeviceId) { $deviceArguments = @("-s", $DeviceId) }

function Invoke-GuardianAdb {
    param([string[]]$Arguments)
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $result = & $adbExecutable @deviceArguments @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($exitCode -ne 0) { throw ("adb failed: " + ($result -join " ")) }
    return (($result -join [Environment]::NewLine).Trim())
}

function Copy-GuardianBinaryWithRunAs {
    param([string]$RemotePath, [string]$LocalPath)
    # Do not use PowerShell 5 ">" to copy WAV: it can convert binary to text.
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $adbExecutable
    $info.Arguments = (@($deviceArguments) + @("exec-out", "run-as", $PackageName, "cat", $RemotePath)) -join " "
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $native = New-Object System.Diagnostics.Process
    $native.StartInfo = $info
    $target = $null
    try {
        [void]$native.Start()
        $target = [IO.File]::Create($LocalPath)
        $native.StandardOutput.BaseStream.CopyTo($target)
        $target.Dispose()
        $target = $null
        $errorText = $native.StandardError.ReadToEnd()
        $native.WaitForExit()
        if ($native.ExitCode -ne 0) { throw ("run-as failed: " + $errorText) }
    } finally {
        if ($target) { $target.Dispose() }
        $native.Dispose()
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

function Collect-GuardianWavs {
    $remoteDirectory = "/sdcard/Android/data/$PackageName/files/guardian-diagnostics"
    $records = New-Object 'System.Collections.Generic.List[object]'
    try {
        $listing = Invoke-GuardianAdb @("shell", "ls", "-1", $remoteDirectory)
    } catch {
        $listing = Invoke-GuardianAdb @("shell", "run-as", $PackageName, "ls", "-1", $remoteDirectory)
    }
    foreach ($name in ($listing -split "\r?\n")) {
        $name = $name.Trim()
        if ($name -notmatch '^guardian-(?:multimic|voice)-(\d{13})-[A-Za-z0-9_-]+\.(?:wav|tsv)$') { continue }
        # Only sessions started during this collection, not older recordings.
        if ([long]$Matches[1] -lt ($deviceStartMs-1000)) { continue }
        $wavDirectory = Join-Path $OutputDirectory "wav"
        if (-not (Test-Path -LiteralPath $wavDirectory)) { New-Item -ItemType Directory -Path $wavDirectory | Out-Null }
        $remoteFile = "$remoteDirectory/$name"
        $localFile = Join-Path $wavDirectory $name
        $method = "adb pull"
        try {
            [void](Invoke-GuardianAdb @("pull", $remoteFile, $localFile))
        } catch {
            $method = "run-as binary stream"
            Copy-GuardianBinaryWithRunAs $remoteFile $localFile
        }
        $header = if ($name.EndsWith(".wav")) { Get-GuardianWavHeader $localFile } else { @{ valid=$true; kind="alignment-index" } }
        $records.Add([pscustomobject]@{
            file=$name; method=$method; header=$header
            sha256=(Get-FileHash -LiteralPath $localFile -Algorithm SHA256).Hash.ToLowerInvariant()
        })
        if (-not $header.valid) { Write-Warning ("WAV incomplet : " + $name + ". Verifier que la protection est arretee.") }
    }
    ConvertTo-Json -InputObject @($records.ToArray()) -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory "wav-manifest.json") -Encoding UTF8
    $metadata["audioFilesCollected"] = $records.Count
    if ($records.Count -eq 0) {
        Write-Warning "Aucun nouveau WAV trouve : verifier le module vocal v3 et le fichier guardian-record-wav.txt."
    }
}


if ((Invoke-GuardianAdb @("get-state")) -ne "device") {
    throw "Connect and authorize one Android phone. With multiple devices, use -DeviceId."
}
$deviceStartMs = [long](Invoke-GuardianAdb @("shell", "date", "+%s")) * 1000
if (-not $PackageName) {
    try {
        $installed = Invoke-GuardianAdb @("shell", "pm", "list", "packages")
        $candidates = @($installed -split "\r?\n" | ForEach-Object { $_.Trim() -replace '^package:', '' } | Where-Object { $_ -match 'guardian' })
        if ($candidates.Count -eq 1) { $PackageName = $candidates[0]; Write-Host ("Application detectee : " + $PackageName) }
    } catch { Write-Host "Detection automatique indisponible." }
    if (-not $PackageName) { $PackageName = Read-Host "Identifiant Android de votre application (exemple : com.exemple.guardian)" }
    if ($PackageName -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') { throw "Identifiant Android invalide" }
}
if (-not $OutputDirectory) {
    $desktop = [Environment]::GetFolderPath("Desktop")
    if (-not $desktop) { $desktop = (Get-Location).Path }
    $OutputDirectory = Join-Path $desktop ("GuardianVoiceTest-" + (Get-Date -Format "yyyyMMdd-HHmmss-fff"))
}
if (Test-Path -LiteralPath $OutputDirectory) {
    throw "The output folder already exists. Choose a new folder to preserve previous results."
}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$logPath = Join-Path $OutputDirectory "guardian.log"
$casesPath = Join-Path $OutputDirectory "cases.csv"
$rows = New-Object 'System.Collections.Generic.List[object]'
$metadata = [ordered]@{
    collectorVersion = 3
    requestedNativeRevision = "guardian-voice-v3"
    startUtc = [DateTime]::UtcNow.ToString("o")
    manufacturer = Invoke-GuardianAdb @("shell", "getprop", "ro.product.manufacturer")
    model = Invoke-GuardianAdb @("shell", "getprop", "ro.product.model")
    android = Invoke-GuardianAdb @("shell", "getprop", "ro.build.version.release")
    apiLevel = Invoke-GuardianAdb @("shell", "getprop", "ro.build.version.sdk")
    cpuAbi = Invoke-GuardianAdb @("shell", "getprop", "ro.product.cpu.abi")
    adb = (& $adbExecutable version | Select-Object -First 1)
    collectorRecordsAudio = $false
    collectExistingAppWav = [bool]$IncludeWav
    deviceStartMs = $deviceStartMs
}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory "phone.json") -Encoding UTF8
$expected = "Je suis dans le salon près de la fenêtre. Le train arrive demain à dix heures. Peux-tu ouvrir la porte du jardin ?"
$cases = @(
    [pscustomobject]@{ id="D0_SILENCE"; profile="DISTANT"; distance=0; noise="quiet"; spoken=$false; instructions="Sans parler, laisser le telephone dans le calme pendant 30 secondes." },
    [pscustomobject]@{ id="D1_VOICE"; profile="DISTANT"; distance=1; noise="quiet"; spoken=$true; instructions="A 1 metre, lire les trois phrases doucement, avec 3 secondes entre les phrases." },
    [pscustomobject]@{ id="D3_VOICE"; profile="DISTANT"; distance=3; noise="quiet"; spoken=$true; instructions="A 3 metres, relire les trois phrases avec la meme voix douce." },
    [pscustomobject]@{ id="D5_VOICE"; profile="DISTANT"; distance=5; noise="quiet"; spoken=$true; instructions="A 5 metres, relire les trois phrases avec la meme voix douce." },
    [pscustomobject]@{ id="N0_NOISE"; profile="NOISY"; distance=0; noise="background"; spoken=$false; instructions="Avec le bruit de fond choisi, ne pas parler pendant 30 secondes." },
    [pscustomobject]@{ id="N1_VOICE"; profile="NOISY"; distance=1; noise="background"; spoken=$true; instructions="Avec le meme bruit, lire les trois phrases doucement a 1 metre." },
    [pscustomobject]@{ id="N3_VOICE"; profile="NOISY"; distance=3; noise="background"; spoken=$true; instructions="Avec le meme bruit, relire les trois phrases doucement a 3 metres." },
    [pscustomobject]@{ id="N5_VOICE"; profile="NOISY"; distance=5; noise="background"; spoken=$true; instructions="Avec le meme bruit, relire les trois phrases doucement a 5 metres." }
)
Write-Host ""
Write-Host "Le ZIP contiendra les transcriptions et, avec -IncludeWav, les voix enregistrees."
Write-Host "Installer le module vocal v3, reconstruire Android et activer voiceDiagnosticsEnabled=true."
Write-Host "Le script prepare des fichiers de test temporaires ; les fichiers existants seront restaures."
Write-Host "Il faudra arreter puis redemarrer la protection a chaque changement de profil."
Write-Host "Choisir un bruit sans voix pour ce premier essai (ventilateur, par exemple)."
Write-Host "Le niveau de bruit doit rester identique entre N0, N1, N3 et N5."
Write-Host ("Dossier : " + $OutputDirectory)
if (-not $Full) { $cases = @($cases | Where-Object { $_.id -in @("D0_SILENCE","D1_VOICE","N0_NOISE","N1_VOICE") }) }
$process = $null
$controlBackups = New-Object 'System.Collections.Generic.List[object]'
function Set-GuardianTestControl {
    param([string]$Name, [string]$Value)
    $remote = "/sdcard/Android/data/$PackageName/files/$Name"
    $existing = @($controlBackups | Where-Object { $_.name -eq $Name })
    if ($existing.Count -eq 0) {
        # A failed read is never interpreted as an absent file.
        $listing = Invoke-GuardianAdb @("shell", "ls", "-1", "/sdcard/Android/data/$PackageName/files")
        $exists = @($listing -split "\r?\n" | Where-Object { $_.Trim() -eq $Name }).Count -gt 0
        $backup = Join-Path $OutputDirectory ("backup-" + $Name)
        if ($exists) {
            try { [void](Invoke-GuardianAdb @("pull", $remote, $backup)) }
            catch { Copy-GuardianBinaryWithRunAs $remote $backup }
        }
        $controlBackups.Add([pscustomobject]@{ name=$Name; remote=$remote; existed=$exists; backup=$backup })
    }
    $local = Join-Path $OutputDirectory ("test-" + $Name)
    [IO.File]::WriteAllText($local, $Value, (New-Object Text.UTF8Encoding($false)))
    [void](Invoke-GuardianAdb @("push", $local, $remote))
}
function Confirm-GuardianSession {
    param([string]$Profile, [int]$SkipLines)
    $deadline = [DateTime]::UtcNow.AddSeconds(12)
    do {
        $events = @(Get-Content -LiteralPath $logPath -Encoding UTF8 | Select-Object -Skip $SkipLines | ForEach-Object {
            if ($_ -match 'GuardianVoiceDiag.*?(\{.*\})') {
                try { $Matches[1] | ConvertFrom-Json } catch { }
            }
        })
        $sessions = @($events | Where-Object { $_.event -eq "SESSION_START" -and $_.profile -eq $Profile -and $_.engineRevision -eq "guardian-voice-v3" })
        foreach ($session in $sessions) {
            $same = @($events | Where-Object { $_.session -eq $session.session -and $_.profile -eq $Profile })
            $frames = @($same | Where-Object { $_.event -eq "AUDIO_FRAME" })
            $ready = @($same | Where-Object { $_.event -eq "WAV_READY" })
            $wavErrors = @($same | Where-Object { $_.event -in @("WAV_ERROR","WAV_UNAVAILABLE","WAV_FINALIZE_ERROR") })
            if ($IncludeWav -and $wavErrors.Count -gt 0) { throw "Le module signale une erreur d'enregistrement WAV. Verifier guardian.log." }
            if ($frames.Count -gt 0 -and (-not $IncludeWav -or $ready.Count -gt 0)) {
                $metadata["verified" + $Profile + "Session"] = $session.session
                Write-Host ("Session verifiee : " + $Profile + ", audio actif, WAV=" + [bool]$ready.Count)
                return
            }
        }
        Start-Sleep -Milliseconds 400
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Session non verifiee : la version v3, le profil demande ou les diagnostics WAV sont absents. Installer le module v3, activer voiceDiagnosticsEnabled=true, puis arreter/redemarrer la protection. Les logs sont conserves."
}
try {
    $logArguments = @($deviceArguments) + @(
        "logcat", "-v", "threadtime", "-T", "1",
        "GuardianVoiceDiag:I", "GuardianTest:I", "GuardianSpeechInput:I",
        "GuardianQwenProgressive:I", "GuardianQwenRescue:I", "GuardianAudioEngine:I",
        "GuardianWordTimeline:I", "GuardianVoiceEngine:I", "GuardianConversation:I",
        "GuardianAudioProfile:I", "GuardianAudioProbe:I", "GuardianVoiceFocus:I", "GuardianAudioEvidence:I", "*:S"
    )
    $startParameters = @{
        FilePath=$adbExecutable; ArgumentList=$logArguments; PassThru=$true; NoNewWindow=$true
        RedirectStandardOutput=$logPath
        RedirectStandardError=(Join-Path $OutputDirectory "adb-errors.log")
    }
    $process = Start-Process @startParameters
    Start-Sleep -Milliseconds 300
    if ($process.HasExited) { throw "logcat stopped. Check adb-errors.log." }
    Set-GuardianTestControl "guardian-record-wav.txt" $(if ($IncludeWav) { "ON" } else { "OFF" })
    $previousProfile = ""
    foreach ($case in $cases) {
        if ($case.profile -ne $previousProfile) {
            Write-Host ""
            Set-GuardianTestControl "guardian-voice-profile.txt" $case.profile
            $skipLines = @(Get-Content -LiteralPath $logPath -Encoding UTF8).Count
            Write-Host ("Profil demande : " + $case.profile)
            [void](Read-Host "Arreter COMPLETEMENT puis redemarrer la protection, puis Entree")
            Confirm-GuardianSession $case.profile $skipLines
            $previousProfile = $case.profile
        }
        Write-Host ""
        Write-Host ("--- " + $case.id + " ---")
        Write-Host $case.instructions
        if ($case.spoken) { Write-Host $expected }
        [void](Read-Host "Entree pour commencer ce cas")
        $started = [DateTime]::UtcNow.ToString("o")
        [void](Invoke-GuardianAdb @("shell", "log", "-p", "i", "-t", "GuardianTest", ("BEGIN_" + $case.id)))
        [void](Read-Host "Effectuer le test, attendre 8 secondes apres la derniere phrase, puis Entree")
        [void](Invoke-GuardianAdb @("shell", "log", "-p", "i", "-t", "GuardianTest", ("END_" + $case.id)))
        $ended = [DateTime]::UtcNow.ToString("o")
        if (([DateTime]::Parse($ended)-[DateTime]::Parse($started)).TotalSeconds -lt 8) {
            Write-Warning "Cas trop court : il ne permettra pas de juger le moteur."
        }
        $notes = Read-Host "Notes : type de bruit, orientation, resultat visible, phrase differente si besoin"
        $rows.Add([pscustomobject]@{
            caseId=$case.id; expectedProfile=$case.profile; distanceMeters=$case.distance
            noise=$case.noise; expectedText=$(if ($case.spoken) { $expected } else { "" })
            startUtc=$started; endUtc=$ended; notes=$notes
        })
        $rows | Export-Csv -LiteralPath $casesPath -NoTypeInformation -Encoding UTF8
    }
    if ($IncludeWav) {
        [void](Read-Host "Arreter completement la protection pour finaliser les WAV, puis Entree")
        try { Collect-GuardianWavs } catch {
            $_.Exception.Message | Set-Content -LiteralPath (Join-Path $OutputDirectory "wav-errors.txt") -Encoding UTF8
            Write-Warning "Recuperation WAV incomplete : voir wav-errors.txt. Les logs sont conserves."
        }
    }
} finally {
    foreach ($control in $controlBackups) {
        try {
            if ($control.existed) { [void](Invoke-GuardianAdb @("push", $control.backup, $control.remote)) }
            else { [void](Invoke-GuardianAdb @("shell", "rm", "-f", $control.remote)) }
        } catch { Write-Warning ("Restaurer le fichier de test : " + $control.remote + ". " + $_.Exception.Message) }
    }
    if ($process -and -not $process.HasExited) {
        Start-Sleep -Milliseconds 300
        Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
        $process.WaitForExit()
    }
    if ($rows.Count -gt 0) { $rows | Export-Csv -LiteralPath $casesPath -NoTypeInformation -Encoding UTF8 }
    $metadata["endUtc"] = [DateTime]::UtcNow.ToString("o")
    $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory "phone.json") -Encoding UTF8
    $structured = 0
    if (Test-Path -LiteralPath $logPath) {
        $structured = @(Select-String -LiteralPath $logPath -Pattern 'GuardianVoiceDiag.*"schema"\s*:\s*1' -Encoding UTF8).Count
    }
    @(
        ("Completed cases: " + $rows.Count)
        ("Structured diagnostic records: " + $structured)
        "Profiles are verified from fresh SESSION_START and AUDIO_FRAME records before each group."
        "Temporary test files request the profile and optional mono WAV recording. No uploads and no logcat clearing."
    ) | Set-Content -LiteralPath (Join-Path $OutputDirectory "collection-summary.txt") -Encoding UTF8
    if ($structured -eq 0) {
        Write-Warning "Aucun log structure : verifier le nouveau module natif et voiceDiagnosticsEnabled=true."
    }
    Compress-Archive -Path (Join-Path $OutputDirectory "*") -DestinationPath ($OutputDirectory + ".zip") -CompressionLevel Optimal
    Write-Host ("ZIP a envoyer : " + $OutputDirectory + ".zip")
}

