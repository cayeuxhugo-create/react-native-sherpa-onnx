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

function Get-QwenRemoteHash {
    param([string]$Path)
    $result = Invoke-QwenAdb @("shell", "run-as", $PackageName, "sha256sum", $Path)
    # Ignore adb daemon startup lines, but require a full checksum record.
    $records = @($result -split "\r?\n" | Where-Object { $_ -match '^[0-9a-fA-F]{64}\s+.+$' })
    if ($records.Count -ne 1) { throw ("Réponse SHA-256 inattendue : " + $result) }
    return $records[0].Substring(0,64).ToLowerInvariant()
}

function Get-QwenRemoteSize {
    param([string]$Path)
    $text = Invoke-QwenAdb @("shell", "run-as", $PackageName, "stat", "-c", "%s", $Path)
    $lines = @($text -split "\r?\n" | Where-Object { $_ -match '^\d+$' })
    if ($lines.Count -ne 1) { throw ("Taille distante illisible : " + $text) }
    return [long]$lines[0]
}

function Send-QwenVerifiedFile {
    param([string]$LocalPath, [string]$RemotePath, [string]$PublicPath)
    $expected = (Get-FileHash -LiteralPath $LocalPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $expectedSize = (Get-Item -LiteralPath $LocalPath).Length
    $publicParent = $PublicPath.Substring(0, $PublicPath.LastIndexOf('/'))
    [void](Invoke-QwenAdb @("shell", "mkdir", "-p", $publicParent))
    $copied = $false
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            # Use adb's native sync protocol, as verified on the user's phone.
            # No model data passes through a PowerShell/.NET stdin pipe.
            $summary = Invoke-QwenAdb @("push", $LocalPath, $PublicPath)
            Write-Host $summary
            [void](Invoke-QwenAdb @("shell", "chmod", "644", $PublicPath))
            [void](Invoke-QwenAdb @("shell", "chmod", "-R", "a+rX", $publicRoot))
            $actual = Get-QwenRemoteHash $PublicPath
            $actualSize = Get-QwenRemoteSize $PublicPath
            if ($actual -ne $expected -or $actualSize -ne $expectedSize) {
                throw ("Copie temporaire différente : PC=" + $expectedSize + " octets, SHA256=" + $expected + "; téléphone=" + $actualSize + " octets, SHA256=" + $actual)
            }
            $copied = $true
            break
        } catch {
            if ($attempt -eq 3) { throw ("Transfert échoué après 3 essais. " + $_.Exception.Message) }
            Write-Warning ("Nouvel essai du fichier. " + $_.Exception.Message)
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $copied) { throw "Fichier non vérifié." }
    [void](Invoke-QwenAdb @("shell", "run-as", $PackageName, "cp", $PublicPath, $RemotePath))
    $actual = Get-QwenRemoteHash $RemotePath
    $actualSize = Get-QwenRemoteSize $RemotePath
    if ($actual -ne $expected -or $actualSize -ne $expectedSize) {
        throw ("Copie privée différente : PC=" + $expectedSize + " octets, SHA256=" + $expected + "; téléphone=" + $actualSize + " octets, SHA256=" + $actual)
    }
    Write-Host ("Vérifié dans Guardian : " + $expectedSize + " octets, SHA256=" + $actual)
    [void](Invoke-QwenAdb @("shell", "rm", "-f", $PublicPath))
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
if (@((Invoke-QwenAdb @("get-state")) -split "\r?\n" | Where-Object { $_.Trim() -eq "device" }).Count -ne 1) { throw "Connecter et autoriser un seul téléphone, ou préciser -DeviceId." }
try { [void](Invoke-QwenAdb @("shell", "run-as", $PackageName, "id")) }
catch { throw ("Le transfert privé nécessite un APK débogable autorisant run-as. Aucun modèle modifié. " + $_.Exception.Message) }
# Verify the checksum utility before creating/writing model directories.
[void](Get-QwenRemoteHash "/dev/null")
$token = [Guid]::NewGuid().ToString("N")
$stage = "files/models/.guardian-qwen-" + $token
$publicRoot = "/data/local/tmp/guardian-qwen-" + $token
$target = "files/models/" + $modelName
$backup = "files/models/" + $modelName + ".backup-" + $token
$createdStage = $false
$createdPublic = $false
$movedOld = $false
$published = $false
Write-Host "Installateur Qwen v3 : adb push, copie privée, contrôle des tailles et SHA-256."
Write-Host "La protection doit être arrêtée pendant le transfert."
try {
    [void](Invoke-QwenAdb @("shell", "run-as", $PackageName, "mkdir", "-p", $stage))
    $createdStage = $true
    [void](Invoke-QwenAdb @("shell", "mkdir", "-p", $publicRoot))
    $createdPublic = $true
    foreach ($name in $files) {
        $local = Join-Path $SourceDirectory $name
        $remote = $stage + "/" + $name
        $parent = $remote.Substring(0, $remote.LastIndexOf('/'))
        [void](Invoke-QwenAdb @("shell", "run-as", $PackageName, "mkdir", "-p", $parent))
        Write-Host ("Transfert et vérification : " + $name)
        Send-QwenVerifiedFile $local $remote ($publicRoot + "/" + $name)
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
    if ($createdPublic) {
        try { [void](Invoke-QwenAdb @("shell", "rm", "-rf", $publicRoot)) }
        catch { Write-Warning ("Dossier temporaire ADB à nettoyer : " + $publicRoot) }
    }
    if ($createdStage -and -not $published) {
        try { [void](Invoke-QwenAdb @("shell", "run-as", $PackageName, "rm", "-rf", $stage)) }
        catch { Write-Warning ("Copie temporaire à nettoyer : " + $stage) }
    }
}
