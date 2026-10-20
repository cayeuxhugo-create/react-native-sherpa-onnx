#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ProjectDirectory = 'C:\Projets\guardian-voice',
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$')]
    [string]$PackageName = 'com.hugo3154.guardianvoice',
    [ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$DeviceId = ''
)
$ErrorActionPreference = 'Stop'
$ProjectDirectory = (Resolve-Path -LiteralPath $ProjectDirectory).Path
if (-not (Test-Path -LiteralPath (Join-Path $ProjectDirectory 'package.json'))) { throw 'Projet Expo introuvable.' }
$collector = Join-Path $PSScriptRoot 'enregistrer-guardian.ps1'
$recovery = Join-Path $PSScriptRoot 'recuperer-guardian-wav.ps1'
foreach ($script in @($collector,$recovery)) {
    if (-not (Test-Path -LiteralPath $script)) { throw "Script requis manquant : $script" }
}
$adb = (Get-Command adb -ErrorAction Stop).Source
$deviceArgs = @(); if ($DeviceId) { $deviceArgs = @('-s',$DeviceId) }
$base = Join-Path $ProjectDirectory 'diagnostics-voix'
[void][IO.Directory]::CreateDirectory($base)
$batch = Join-Path $base ('Etude-silence-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,6))
[void][IO.Directory]::CreateDirectory($batch)
$results = New-Object 'System.Collections.Generic.List[object]'
$failure = $null
try {
    for ($i=1; $i -le 6; $i++) {
        $session = $null
        $condition = if ($i -le 3) { 'SILENCE' } else { 'BRUIT_SANS_VOIX' }
        Write-Host "Bloc $i/6 : $condition, 100 secondes. Ne pas parler."
        if ($i -eq 4) { Write-Host 'Allumer un ventilateur ou un bruit constant sans voix. Conserver ce bruit pour les trois derniers blocs.' }
        Write-Host 'La commande ferme Guardian avant chaque bloc. Au signal du collecteur, ouvrir Guardian et activer la protection.'
        & $adb @deviceArgs shell am force-stop $PackageName
        if ($LASTEXITCODE -ne 0) { throw 'Impossible de fermer Guardian.' }
        $before = @(Get-ChildItem -LiteralPath $base -Directory | Select-Object -ExpandProperty FullName)
        $arguments = @{ProjectDirectory=$ProjectDirectory;PackageName=$PackageName;Profile='DISTANT';DurationSeconds=100}
        if ($DeviceId) { $arguments['DeviceId']=$DeviceId }
        $collectorError = $null
        try { & $collector @arguments } catch { $collectorError = $_.Exception.Message }
        $new = @(Get-ChildItem -LiteralPath $base -Directory | Where-Object { $_.Name -like 'Enregistrement-*' -and $_.FullName -notin $before })
        if ($new.Count -ne 1) { throw 'Impossible de retrouver un unique dossier du bloc. Les fichiers existants sont conserves.' }
        $session = $new[0].FullName
        $meta = Get-Content -LiteralPath (Join-Path $session 'session.json') -Raw | ConvertFrom-Json
        if (-not $meta.recordStartUtc -or -not $meta.recordEndUtc -or (([DateTime]$meta.recordEndUtc)-([DateTime]$meta.recordStartUtc)).TotalSeconds -lt 99) {
            throw "Bloc trop court ou non demarre : $session"
        }
        if (-not $meta.controlsRestored) { throw "Les reglages doivent etre restaures avant de poursuivre : $session" }
        $recovered = $false
        if ($collectorError -or -not $meta.completed) {
            if (-not $meta.audioPrefix) { throw "Capture non demarree : $collectorError. Dossier : $session" }
            Write-Host 'Collecte incomplete : fermeture de Guardian, puis recuperation verifiee des WAV.'
            & $adb @deviceArgs shell am force-stop $PackageName
            if ($LASTEXITCODE -ne 0) { throw 'Impossible de fermer Guardian avant recuperation.' }
            $recoverArguments = @{SessionDirectory=$session}
            if ($DeviceId) { $recoverArguments['DeviceId']=$DeviceId }
            & $recovery @recoverArguments
            $reports = @(Get-ChildItem -LiteralPath $session -Filter 'recuperation.json' -Recurse -File)
            $valid = @($reports | Where-Object { (Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json).completed })
            if ($valid.Count -eq 0) { throw "Recuperation incomplete : $session" }
            $recovered = $true
        }
        $destination = Join-Path $batch ("Bloc-$i-$condition")
        Copy-Item -LiteralPath $session -Destination $destination -Recurse
        $results.Add([pscustomobject]@{block=$i;condition=$condition;requestedSeconds=100;recovered=$recovered;collectorError=$collectorError})
    }
} catch {
    $failure = $_.Exception.Message; Write-Warning $failure
    if ($session -and (Test-Path -LiteralPath $session)) {
        Copy-Item -LiteralPath $session -Destination (Join-Path $batch 'Bloc-incomplet') -Recurse
    }
}
finally {
    [pscustomobject]@{completed=($results.Count -eq 6 -and -not $failure);requestedTotalSeconds=600;blocks=@($results.ToArray());error=$failure} |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $batch 'etude.json') -Encoding UTF8
    Compress-Archive -Path (Join-Path $batch '*') -DestinationPath ($batch+'.zip') -CompressionLevel Optimal
    Write-Host ('ZIP a envoyer : '+$batch+'.zip')
}
if ($failure) { throw $failure }
