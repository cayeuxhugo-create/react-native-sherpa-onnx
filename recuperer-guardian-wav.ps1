#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$SessionDirectory,
    [ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$DeviceId = ""
)
$ErrorActionPreference = "Stop"
$SessionDirectory = (Resolve-Path -LiteralPath $SessionDirectory).Path
$session = Get-Content -LiteralPath (Join-Path $SessionDirectory "session.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$PackageName = [string]$session.packageName
$prefix = [string]$session.audioPrefix
if ($PackageName -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') { throw "Identifiant d'application invalide dans session.json." }
if ($prefix -notmatch '^guardian-voice-\d{13}-(DISTANT|NOISY)-[A-Za-z0-9]{1,8}$') { throw "Préfixe audio invalide ou absent dans session.json." }
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
    if ($actual -ne $expected) { throw ("Copie incorrecte ou fichier encore en écriture : " + $Remote + ". Arrêter la protection puis relancer la récupération.") }
    $after = Invoke-SimpleAdb @("shell","run-as",$PackageName,"sha256sum",$Remote)
    $afterLines = @($after -split "\r?\n" | Where-Object { $_ -match '^[a-fA-F0-9]{64}\s+.+$' })
    if ($afterLines.Count -ne 1 -or $afterLines[0].Substring(0,64).ToLowerInvariant() -ne $expected) {
        throw ("Le fichier change encore sur le téléphone : " + $Remote + ". Arrêter la protection puis relancer la récupération.")
    }
    return $expected
}

function Repair-LocalGuardianWav {
    param([string]$Path)
    $file = Get-Item -LiteralPath $Path
    if ($file.Length -lt 46 -or $file.Length -gt 67108864) { throw "Taille WAV inattendue : $Path" }
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ([Text.Encoding]::ASCII.GetString($bytes,0,4) -ne "RIFF" -or
        [Text.Encoding]::ASCII.GetString($bytes,8,4) -ne "WAVE" -or
        [Text.Encoding]::ASCII.GetString($bytes,12,4) -ne "fmt " -or
        [Text.Encoding]::ASCII.GetString($bytes,36,4) -ne "data" -or
        [BitConverter]::ToUInt32($bytes,16) -ne 16 -or
        [BitConverter]::ToUInt16($bytes,20) -ne 1 -or
        [BitConverter]::ToUInt16($bytes,22) -ne 1 -or
        [BitConverter]::ToUInt32($bytes,24) -ne 16000 -or
        [BitConverter]::ToUInt32($bytes,28) -ne 32000 -or
        [BitConverter]::ToUInt16($bytes,32) -ne 2 -or
        [BitConverter]::ToUInt16($bytes,34) -ne 16 -or
        (($bytes.Length-44) % 2) -ne 0) { throw "Format WAV inattendu ; aucune réparation tentée : $Path" }
    $oldHeader = [Convert]::ToBase64String($bytes[0..43])
    $dataBytes = $bytes.Length-44
    $repaired = [BitConverter]::ToUInt32($bytes,4) -ne ($bytes.Length-8) -or [BitConverter]::ToUInt32($bytes,40) -ne $dataBytes
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { $pcmHash = ([BitConverter]::ToString($algorithm.ComputeHash($bytes,44,$dataBytes))).Replace("-","").ToLowerInvariant() } finally { $algorithm.Dispose() }
    if ($repaired) {
        [Array]::Copy([BitConverter]::GetBytes([uint32]($bytes.Length-8)),0,$bytes,4,4)
        [Array]::Copy([BitConverter]::GetBytes([uint32]$dataBytes),0,$bytes,40,4)
        [IO.File]::WriteAllBytes($Path,$bytes)
        $check = [IO.File]::ReadAllBytes($Path)
        $algorithm = [Security.Cryptography.SHA256]::Create()
        try { $afterHash = ([BitConverter]::ToString($algorithm.ComputeHash($check,44,$dataBytes))).Replace("-","").ToLowerInvariant() } finally { $algorithm.Dispose() }
        if ($pcmHash -ne $afterHash) { throw "Erreur d'intégrité des échantillons après réparation locale." }
    }
    return [pscustomobject]@{headerRepaired=$repaired; originalHeaderBase64=$oldHeader; sampleRate=16000; channels=1; bits=16; durationSeconds=($dataBytes/32000.0); dataSha256=$pcmHash}
}

[void](Invoke-SimpleAdb @("get-state"))
[void](Invoke-SimpleAdb @("shell","run-as",$PackageName,"id"))
$stamp = (Get-Date -Format "yyyyMMdd-HHmmss-fff")+"-"+[Guid]::NewGuid().ToString("N").Substring(0,6)
$output = Join-Path $SessionDirectory ("wav-recuperes-"+$stamp)
[void][IO.Directory]::CreateDirectory($output)
$records = New-Object 'System.Collections.Generic.List[object]'
$failures = New-Object 'System.Collections.Generic.List[string]'
Write-Host "Récupération de l'enregistrement existant : aucun nouveau test n'est lancé."
Write-Host "La protection doit être arrêtée. Aucune écriture ni suppression sur le téléphone."
foreach ($suffix in @("raw.wav","pregain.wav","truth.wav","asr.wav","vad.wav","index.tsv")) {
    $name = $prefix+"-"+$suffix
    $partial = Join-Path $output ($name+".partial")
    $local = Join-Path $output $name
    try {
        Write-Host ("Récupération : "+$suffix)
        $phoneHash = Copy-PrivateFile ("files/guardian-diagnostics/"+$name) $partial
        Move-Item -LiteralPath $partial -Destination $local
        $header = if ($suffix.EndsWith(".wav")) { Repair-LocalGuardianWav $local } else { $null }
        $records.Add([pscustomobject]@{file=$name; verifiedPhoneSha256=$phoneHash; localSha256=(Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant(); header=$header})
    } catch {
        $message = $name+" : "+$_.Exception.Message
        $failures.Add($message)
        Write-Warning $message
    }
}
$report = [ordered]@{recoveryVersion=1; recoveredUtc=[DateTime]::UtcNow.ToString("o"); audioPrefix=$prefix; completed=($failures.Count -eq 0); phoneFilesChanged=$false; files=@($records.ToArray()); errors=@($failures.ToArray())}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output "recuperation.json") -Encoding UTF8
$zip = $SessionDirectory+"-recupere-"+$stamp+".zip"
Compress-Archive -Path (Join-Path $SessionDirectory "*") -DestinationPath $zip -CompressionLevel Optimal
Write-Host ("ZIP à envoyer : "+$zip)
if ($failures.Count -gt 0) { throw "Récupération partielle : les fichiers réussis et les erreurs sont dans le ZIP. Aucun original n'a été supprimé." }
Write-Host "Cinq WAV récupérés et vérifiés. Les éventuels en-têtes ont été réparés uniquement sur les copies PC."
