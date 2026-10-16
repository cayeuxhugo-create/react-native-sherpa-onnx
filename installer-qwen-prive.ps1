#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$SourceDirectory,
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$')]
    [string]$PackageName,
    [ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$DeviceId = ""
)
$ErrorActionPreference = "Stop"
$adbExecutable = (Get-Command adb -ErrorAction Stop).Source
$deviceArguments = @()
if ($DeviceId) { $deviceArguments = @("-s", $DeviceId) }
$modelName = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"

function Invoke-QwenAdb {
    param([string[]]$Arguments)
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $adbExecutable @deviceArguments @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    if ($code -ne 0) { throw ("adb : " + ($output -join " ")) }
    return (($output -join [Environment]::NewLine).Trim())
}

function Send-QwenBinary {
    param([string]$LocalPath, [string]$RemotePath)
    # Never pipe model bytes through PowerShell 5's text pipeline or use ">".
    # dd receives stdin directly under the application's UID via run-as.
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $adbExecutable
    $info.Arguments = (@($deviceArguments) + @("exec-in", "run-as", $PackageName, "dd", ("of=" + $RemotePath), "bs=1048576")) -join " "
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $native = New-Object Diagnostics.Process
    $native.StartInfo = $info
    $inputFile = $null
    $started = $false
    try {
        [void]$native.Start()
        $started = $true
        $stdout = $native.StandardOutput.ReadToEndAsync()
        $stderr = $native.StandardError.ReadToEndAsync()
        $inputFile = [IO.File]::OpenRead($LocalPath)
        $inputFile.CopyTo($native.StandardInput.BaseStream)
        $inputFile.Dispose(); $inputFile = $null
        $native.StandardInput.Close()
        $native.WaitForExit()
        [void]$stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        if ($native.ExitCode -ne 0) { throw ("Copie binaire refusée : " + $errorText) }
    } finally {
        if ($inputFile) { $inputFile.Dispose() }
        if ($started) {
            try { $native.StandardInput.Close() } catch { }
            if (-not $native.HasExited) { $native.Kill(); $native.WaitForExit() }
        }
        $native.Dispose()
    }
}

$SourceDirectory = (Resolve-Path -LiteralPath $SourceDirectory).Path
$required = @("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx", "tokenizer/vocab.json", "tokenizer/merges.txt", "tokenizer/tokenizer_config.json")
foreach ($name in $required) {
    $file = Join-Path $SourceDirectory $name
    if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or (Get-Item -LiteralPath $file).Length -le 0) { throw ("Fichier requis absent ou vide : " + $file) }
}
$files = New-Object 'System.Collections.Generic.List[string]'
foreach ($name in @("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx")) { $files.Add($name) }
$sourcePrefix = $SourceDirectory.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
foreach ($file in (Get-ChildItem -LiteralPath (Join-Path $SourceDirectory "tokenizer") -Recurse -File)) {
    $name = $file.FullName.Substring($sourcePrefix.Length).Replace('\','/')
    if ($name -notmatch '^[A-Za-z0-9_./-]+$' -or (($name -split '/') -contains '..')) { throw ("Nom de fichier non pris en charge : " + $name) }
    $files.Add($name)
}
if ((Invoke-QwenAdb @("get-state")) -ne "device") { throw "Connecter et autoriser un seul téléphone, ou préciser -DeviceId." }
try { [void](Invoke-QwenAdb @("shell", "run-as", $PackageName, "id")) }
catch { throw ("Le transfert privé nécessite un APK débogable autorisant run-as. Aucun modèle modifié. " + $_.Exception.Message) }
# Verify the checksum utility before creating/writing model directories.
[void](Invoke-QwenAdb @("shell", "run-as", $PackageName, "sha256sum", "/dev/null"))
$token = [Guid]::NewGuid().ToString("N")
$stage = "files/models/.guardian-qwen-" + $token
$target = "files/models/" + $modelName
$backup = "files/models/" + $modelName + ".backup-" + $token
$createdStage = $false
$movedOld = $false
$published = $false
Write-Host "La protection doit être arrêtée pendant le transfert."
try {
    [void](Invoke-QwenAdb @("shell", "run-as", $PackageName, "mkdir", "-p", $stage))
    $createdStage = $true
    foreach ($name in $files) {
        $local = Join-Path $SourceDirectory $name
        $remote = $stage + "/" + $name
        $parent = $remote.Substring(0, $remote.LastIndexOf('/'))
        [void](Invoke-QwenAdb @("shell", "run-as", $PackageName, "mkdir", "-p", $parent))
        Write-Host ("Transfert et vérification : " + $name)
        Send-QwenBinary $local $remote
        $expected = (Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()
        $actual = Invoke-QwenAdb @("shell", "run-as", $PackageName, "sha256sum", $remote)
        if ($actual -notmatch ('^' + $expected + '\s')) { throw ("Somme SHA-256 incorrecte : " + $name) }
    }
    # test -e has a legitimate exit code 1 when the target is absent.
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $existOutput = & $adbExecutable @deviceArguments shell run-as $PackageName test -e $target 2>&1
        $existCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    if ($existCode -notin @(0,1) -or ($existCode -eq 1 -and $existOutput)) { throw ("Impossible de vérifier l'ancien modèle : " + ($existOutput -join " ")) }
    if ($existCode -eq 0) {
        [void](Invoke-QwenAdb @("shell", "run-as", $PackageName, "mv", $target, $backup))
        $movedOld = $true
    }
    [void](Invoke-QwenAdb @("shell", "run-as", $PackageName, "mv", $stage, $target))
    $published = $true
    $createdStage = $false
    Write-Host ("Installation privée vérifiée : " + $files.Count + " fichiers identiques au PC.")
    Write-Host ("Chemin dans l'application : " + $target)
    if ($movedOld) { Write-Host ("Ancien modèle privé conservé : " + $backup) }
    Write-Host "La copie externe existante est conservée. Rouvrir Guardian puis redémarrer la protection."
} catch {
    $reason = $_.Exception.Message
    if ($movedOld -and -not $published) {
        try { [void](Invoke-QwenAdb @("shell", "run-as", $PackageName, "mv", $backup, $target)) }
        catch { Write-Warning ("Ancien modèle à restaurer depuis " + $backup + ". " + $_.Exception.Message) }
    }
    throw $reason
} finally {
    if ($createdStage -and -not $published) {
        try { [void](Invoke-QwenAdb @("shell", "run-as", $PackageName, "rm", "-rf", $stage)) }
        catch { Write-Warning ("Copie temporaire à nettoyer : " + $stage) }
    }
}
