#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ProjectDirectory = 'C:\Projets\guardian-voice',
    [string]$PackageName = 'com.hugo3154.guardianvoice'
)
$ErrorActionPreference = 'Stop'
$toolsDirectory = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($toolsDirectory)) {
    $toolsDirectory = Split-Path -Path $MyInvocation.MyCommand.Path -Parent
}
$collector = Join-Path $toolsDirectory 'enregistrer-guardian-adaptatif.ps1'
$splitter = Join-Path $toolsDirectory 'preparer-envoi-diagnostic.ps1'
foreach ($script in @($collector,$splitter)) {
    if (!(Test-Path -LiteralPath $script -PathType Leaf)) { throw "Script manquant : $script" }
}
if (!(Select-String -LiteralPath $collector -SimpleMatch 'adaptive-candidate-3.5.3.2')) {
    throw 'Mettre a jour le collecteur vers 3.5.3.2 avant ce test de route.'
}
if (!(Test-Path -LiteralPath (Join-Path $ProjectDirectory 'package.json') -PathType Leaf)) {
    throw 'Indiquer la racine du projet Guardian avec -ProjectDirectory.'
}
$diagnostics = Join-Path $ProjectDirectory 'diagnostics-voix'
$previous = @{}
if (Test-Path -LiteralPath $diagnostics) {
    Get-ChildItem -LiteralPath $diagnostics -Filter '*.zip' -File | ForEach-Object { $previous[$_.FullName]=$true }
}
$engine = (Get-Process -Id $PID).Path
Write-Host 'Retour a la route initiale : AUTO, source MIC=1, quatre canaux Android, masque 15.'
Write-Host 'Le collecteur refusera une autre configuration. Cela ne garantit pas encore la qualite des mots.'
Write-Host 'Garder la position du telephone, la distance de 3 m et le bruit aussi proches que possible du dernier essai.'
& $engine -NoProfile -ExecutionPolicy Bypass -File $collector `
    -ProjectDirectory $ProjectDirectory -PackageName $PackageName `
    -ProcessingMode ADAPTIVE_CANDIDATE -CaptureRoute AUTO `
    -ExpectedAudioSource 1 -ExpectedCaptureChannels 4 -ExpectedChannelIndexMask 15 `
    -TestCase PHRASES -DurationSeconds 85 -DistanceMeters 3 -VoiceLevel SOFT `
    -NoiseDescription 'aspirateur - comparaison route AUTO initiale'
$collectorExitCode = $LASTEXITCODE
$created = @(Get-ChildItem -LiteralPath $diagnostics -Filter '*.zip' -File -ErrorAction SilentlyContinue |
    Where-Object { !$previous.ContainsKey($_.FullName) })
if ($created.Count -ne 1) {
    throw ('Impossible d identifier une seule nouvelle archive : ' + $created.Count + '. Les anciennes archives restent intactes.')
}
Write-Host ('Preparation pour envoi : ' + $created[0].FullName)
& $engine -NoProfile -ExecutionPolicy Bypass -File $splitter -ArchivePath $created[0].FullName
if ($LASTEXITCODE -ne 0) { throw 'Decoupage non termine. Conserver le ZIP original et le message affiche.' }
if ($collectorExitCode -ne 0) {
    throw 'La collecte est marquee en erreur. Arreter la protection et envoyer quand meme les parties et repartition.json pour examiner la route.'
}
Write-Host 'Collecte et preparation terminees. Envoyer toutes les parties et repartition.json du dossier indique.'
