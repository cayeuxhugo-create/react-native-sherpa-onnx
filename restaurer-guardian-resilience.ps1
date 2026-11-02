#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ProjectDirectory,
    [Parameter(Mandatory=$true)][string]$BackupDirectory
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectDirectory).Path
$backup = (Resolve-Path -LiteralPath $BackupDirectory).Path
if (!(Test-Path -LiteralPath (Join-Path $project 'package.json') -PathType Leaf)) { throw 'Projet Expo invalide' }
$source = Join-Path $backup 'guardian-native-protection'
if (!(Test-Path -LiteralPath (Join-Path $source 'expo-module.config.json') -PathType Leaf)) { throw 'Sauvegarde du module introuvable' }
$target = Join-Path $project 'modules\guardian-native-protection'
$parent = [IO.Directory]::GetParent($project)
if ($null -eq $parent) { throw 'Le projet ne peut pas etre la racine du disque' }
$prefix = $project.TrimEnd([char[]]@('\','/')) + [IO.Path]::DirectorySeparatorChar
if ($backup.Equals($project, [StringComparison]::OrdinalIgnoreCase) -or $backup.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'La sauvegarde doit etre hors du projet' }
$stamp = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$retained = Join-Path $parent.FullName ((Split-Path $project -Leaf) + '-guardian-correctif-retire-' + $stamp)
[void][IO.Directory]::CreateDirectory($retained)
$moved = $false
try {
    if (Test-Path -LiteralPath $target) {
        [IO.Directory]::Move($target, (Join-Path $retained 'guardian-native-protection'))
        $moved = $true
    }
    [IO.Directory]::Move($source, $target)
} catch {
    if ($moved -and !(Test-Path -LiteralPath $target)) {
        [IO.Directory]::Move((Join-Path $retained 'guardian-native-protection'), $target)
    }
    throw
}
Write-Host ('Ancien module restaure : ' + $target)
Write-Host ('Correctif conserve hors projet : ' + $retained)
Write-Host 'Reconstruire maintenant l application Android avec la signature habituelle.'
