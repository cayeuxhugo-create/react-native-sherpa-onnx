#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ProjectDirectory = "",
    [string]$ArchivePath = ""
)
$ErrorActionPreference = "Stop"
# Resolve script-relative defaults after parameter binding (Windows PowerShell 5.1).
if ([string]::IsNullOrWhiteSpace($ArchivePath)) {
    $installerDirectory = $PSScriptRoot
    if ([string]::IsNullOrWhiteSpace($installerDirectory) -and $MyInvocation.MyCommand.Path) {
        $installerDirectory = Split-Path -Path $MyInvocation.MyCommand.Path -Parent
    }
    if ([string]::IsNullOrWhiteSpace($installerDirectory)) {
        throw "Indiquer -ArchivePath avec le chemin complet du ZIP v3.5.3."
    }
    $ArchivePath = Join-Path $installerDirectory "modules-voix-v3.5.3-adaptive-candidate.zip"
}
$expectedZipHash = "c042320d8dd77edaf4f9f684f885705abb6fc1dc0a1bc3f4236b5e1f8b76e756"

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
        throw "Indiquer -ArchivePath vers l archive locale v3.5.3. Aucun telechargement implicite."
    }
    if ((Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedZipHash) {
        throw "Le ZIP ne correspond pas à la version v3.5.3-adaptive-candidate attendue. Aucun fichier du projet n'a été modifié."
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
    if ($manifest.version -ne "guardian-voice-v3.5.3-adaptive-candidate") { throw "Version du manifeste incorrecte." }
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
