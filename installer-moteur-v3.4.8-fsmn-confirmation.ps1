#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ProjectDirectory = "",
    [string]$ArchivePath = ""
)
$ErrorActionPreference = "Stop"
$expectedZipHash = "1dfa41a7eff53759f05c4c1947dd6187a4093a5e0d1d3503db6e9baa8fa781c3"
$downloadUrl = "https://raw.githubusercontent.com/cayeuxhugo-create/react-native-sherpa-onnx/11d512e48c8cab6de9a6e393d65b3404d89f8002/modules-voix-v3.4.8-fsmn-confirmation.zip"

function Assert-GuardianModule {
    param([string]$Root, $Expected)
    $actual = @(Get-ChildItem -LiteralPath $Root -Recurse -Force -File)
    if ($actual.Count -ne $Expected.Count) {
        throw ("Nombre de fichiers inattendu dans " + $Root + ": " + $actual.Count + " au lieu de " + $Expected.Count)
    }
    foreach ($relative in $Expected.Keys) {
        $path = Join-Path $Root ($relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw ("Fichier manquant : " + $relative) }
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -ne $Expected[$relative]) { throw ("Contenu incorrect : " + $relative) }
    }
}

if ([string]::IsNullOrWhiteSpace($ProjectDirectory)) {
    $currentProject = (Get-Location).Path
    if (Test-Path -LiteralPath (Join-Path $currentProject 'package.json') -PathType Leaf) {
        $ProjectDirectory = $currentProject
    } else {
        $ProjectDirectory = Read-Host "Chemin du projet Expo (dossier contenant package.json)"
    }
}
if ([string]::IsNullOrWhiteSpace($ProjectDirectory)) { throw "Indiquer -ProjectDirectory avec le dossier de l'application." }
$ProjectDirectory = $ProjectDirectory.Trim().Trim('"')
if (-not (Test-Path -LiteralPath $ProjectDirectory -PathType Container)) { throw "Dossier de projet introuvable." }
$ProjectDirectory = (Resolve-Path -LiteralPath $ProjectDirectory).Path
$package = Join-Path $ProjectDirectory "package.json"
if (-not (Test-Path -LiteralPath $package -PathType Leaf)) { throw "Ce dossier ne contient pas package.json. Indiquer la racine de l'application Expo." }
[void](Get-Content -LiteralPath $package -Raw -Encoding UTF8 | ConvertFrom-Json)
$parent = [IO.Directory]::GetParent($ProjectDirectory)
if (-not $parent) { throw "La racine du disque ne peut pas servir de projet." }
$projectName = Split-Path -Path $ProjectDirectory -Leaf
$stamp = (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-" + [Guid]::NewGuid().ToString("N").Substring(0,8)
# Keep the backup outside the project and outside modules, so Expo does not
# autolink a second copy and EAS does not upload the backup with the app.
$backupDirectory = Join-Path $parent.FullName ($projectName + "-guardian-sauvegarde-" + $stamp)
$modulesDirectory = Join-Path $ProjectDirectory "modules"
$target = Join-Path $modulesDirectory "guardian-native-protection"
if ((Test-Path -LiteralPath $target) -and -not (Test-Path -LiteralPath $target -PathType Container)) { throw "La destination existe mais n'est pas un dossier." }
$temp = Join-Path ([IO.Path]::GetTempPath()) ("guardian-install-" + [Guid]::NewGuid().ToString("N"))
[void][IO.Directory]::CreateDirectory($temp)
$oldMoved = $false
$copyStarted = $false
try {
    $zipPath = Join-Path $temp "module.zip"
    if ($ArchivePath) {
        $ArchivePath = (Resolve-Path -LiteralPath $ArchivePath).Path
        Copy-Item -LiteralPath $ArchivePath -Destination $zipPath
    } else {
        Write-Host "Téléchargement de la version v3.4.8-fsmn-confirmation vérifiée..."
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath -UseBasicParsing
    }
    if ((Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedZipHash) {
        throw "Le ZIP ne correspond pas à la version v3.4.8-fsmn-confirmation attendue. Aucun fichier du projet n'a été modifié."
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $staging = Join-Path $temp "extraction"
    [void][IO.Directory]::CreateDirectory($staging)
    $archive = [IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName.Replace('\', '/')
            if ($name.StartsWith('/') -or $name.Contains(':') -or (($name -split '/') -contains '..')) { throw "Chemin interdit dans le ZIP." }
            if (-not $seen.Add($name)) { throw "Chemin dupliqué dans le ZIP." }
        }
    } finally { $archive.Dispose() }
    [IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $staging)
    $manifest = Get-Content -LiteralPath (Join-Path $staging "MANIFESTE.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.version -ne "guardian-voice-v3.4.8-fsmn-confirmation") { throw "Version du manifeste incorrecte." }
    $expected = New-Object 'System.Collections.Generic.Dictionary[string,string]' ([StringComparer]::OrdinalIgnoreCase)
    $prefix = "modules/guardian-native-protection/"
    foreach ($property in $manifest.files.PSObject.Properties) {
        if ($property.Name.StartsWith($prefix, [StringComparison]::Ordinal)) {
            $expected.Add($property.Name.Substring($prefix.Length), [string]$property.Value)
        }
    }
    if ($expected.Count -lt 50) { throw "Manifeste incomplet." }
    $source = Join-Path $staging "modules/guardian-native-protection"
    Assert-GuardianModule $source $expected
    Write-Host ("Archive vérifiée : " + $expected.Count + " fichiers du module.")
    [void][IO.Directory]::CreateDirectory($modulesDirectory)
    if (Test-Path -LiteralPath $target) {
        [void][IO.Directory]::CreateDirectory($backupDirectory)
        [IO.Directory]::Move($target, (Join-Path $backupDirectory "guardian-native-protection"))
        $oldMoved = $true
        Write-Host ("Ancien module sauvegardé : " + $backupDirectory)
    }
    $copyStarted = $true
    Copy-Item -LiteralPath $source -Destination $target -Recurse -Force
    Assert-GuardianModule $target $expected
    Write-Host ""
    Write-Host ("Installation vérifiée : " + $expected.Count + " fichiers conformes au manifeste.")
    Write-Host ("Module installé : " + $target)
    Write-Host "Les fichiers de l'application principale, hors module remplacé, restent inchangés."
    Write-Host "Relancer maintenant votre compilation Android habituelle depuis ce projet."
    Write-Host "Ce script ne lance pas EAS et ne garantit pas une compilation complète de l'APK."
} catch {
    $originalError = $_.Exception.Message
    if ($copyStarted -or $oldMoved) {
        try {
            [void][IO.Directory]::CreateDirectory($backupDirectory)
            if (Test-Path -LiteralPath $target) {
                [IO.Directory]::Move($target, (Join-Path $backupDirectory "installation-incomplete"))
            }
            if ($oldMoved) {
                [IO.Directory]::Move((Join-Path $backupDirectory "guardian-native-protection"), $target)
                Write-Warning "Installation annulée : l'ancien module a été restauré."
            }
        } catch {
            Write-Warning ("Restauration automatique impossible. Sauvegarde : " + $backupDirectory + ". " + $_.Exception.Message)
        }
    }
    if (-not $oldMoved -and -not $copyStarted) { Write-Warning "Si le dossier est verrouille : fermer VS Code/Android Studio/Metro et les terminaux ouverts dans le module, arreter Gradle, puis relancer cette commande. Aucun remplacement force ne sera tente." }
    throw $originalError
} finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue }
}
