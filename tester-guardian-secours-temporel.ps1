#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ProjectDirectory = 'C:\Projets\guardian-voice',
    [string]$PackageName = 'com.hugo3154.guardianvoice',
    [ValidateSet('3M_BRUIT','5M_CALME','SILENCE_BRUIT')][string]$Scenario = '3M_BRUIT',
    [ValidateSet('NORMAL','SOFT')][string]$VoiceLevel = 'SOFT',
    [ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$DeviceId = ''
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
if (!(Select-String -LiteralPath $collector -SimpleMatch 'adaptive-candidate-3.5.4.0')) {
    throw 'Mettre a jour le collecteur vers 3.5.4.0 avant ce test.'
}
if (!(Test-Path -LiteralPath (Join-Path $ProjectDirectory 'package.json') -PathType Leaf)) {
    throw 'Indiquer la racine du projet Guardian avec -ProjectDirectory.'
}
$distance = 3
$testCase = 'PHRASES'
$noise = 'aspirateur - secours temporel v3.5.4 AUTO'
switch ($Scenario) {
    '3M_BRUIT' {
        Write-Host 'Test a 3 m : allumer le meme bruit avant le demarrage et le maintenir pendant toute la prise.'
    }
    '5M_CALME' {
        $distance = 5
        $noise = 'calme - sans bruit ajoute - secours temporel v3.5.4 AUTO'
        Write-Host 'Test a 5 m au calme : garder le telephone immobile et ne pas ajouter de bruit.'
    }
    'SILENCE_BRUIT' {
        $testCase = 'SILENCE'
        $noise = 'aspirateur - aucune parole - secours temporel v3.5.4 AUTO'
        Write-Host 'Test de rejet : maintenir le bruit et ne prononcer aucun mot pendant toute la prise.'
    }
}
Write-Host 'Route controlee : AUTO, source MIC=1, quatre canaux Android, masque 15.'
Write-Host 'Le collecteur exige le nouvel APK v3.5.4 et le secours temporel actif.'
Write-Host 'Pour comparer, conserver la position du telephone, le placement du bruit et le niveau de voix.'
$diagnostics = Join-Path $ProjectDirectory 'diagnostics-voix'
$previous = @{}
if (Test-Path -LiteralPath $diagnostics) {
    Get-ChildItem -LiteralPath $diagnostics -Filter '*.zip' -File | ForEach-Object { $previous[$_.FullName]=$true }
}
$engine = (Get-Process -Id $PID).Path
$collectorArguments = @(
    '-NoProfile','-ExecutionPolicy','Bypass','-File',$collector,
    '-ProjectDirectory',$ProjectDirectory,'-PackageName',$PackageName,
    '-ProcessingMode','ADAPTIVE_CANDIDATE','-CaptureRoute','AUTO',
    '-ExpectedEngineRevision','guardian-voice-v3.5.4-temporal-rescue','-RequireTemporalRescue',
    '-ExpectedAudioSource','1','-ExpectedCaptureChannels','4','-ExpectedChannelIndexMask','15',
    '-TestCase',$testCase,'-DurationSeconds','85','-DistanceMeters',[string]$distance,
    '-VoiceLevel',$VoiceLevel,'-NoiseDescription',$noise
)
if ($DeviceId) { $collectorArguments += @('-DeviceId',$DeviceId) }
& $engine @collectorArguments
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
    throw 'La collecte est marquee en erreur. Arreter la protection et envoyer quand meme les parties et repartition.json pour examiner les preuves.'
}
Write-Host 'Collecte et preparation terminees. Envoyer toutes les parties et repartition.json du dossier indique.'
