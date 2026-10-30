#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-fA-F0-9]{40}$')][string]$SourceRef,
    [string]$ProjectDirectory = 'C:\Projets\guardian-voice',
    [string]$DestinationDirectory = ''
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath (Join-Path $ProjectDirectory 'package.json') -PathType Leaf)) {
    throw 'Indiquer la racine du projet Guardian avec -ProjectDirectory.'
}
if ([string]::IsNullOrWhiteSpace($DestinationDirectory)) {
    $userDirectory = $env:USERPROFILE
    if ([string]::IsNullOrWhiteSpace($userDirectory)) { $userDirectory = [Environment]::GetFolderPath('UserProfile') }
    if ([string]::IsNullOrWhiteSpace($userDirectory)) { throw 'Indiquer -DestinationDirectory pour les outils.' }
    $DestinationDirectory = Join-Path $userDirectory 'Guardian-v3.5.4'
}
$DestinationDirectory = [IO.Path]::GetFullPath($DestinationDirectory)
$ProjectDirectory = (Resolve-Path -LiteralPath $ProjectDirectory).Path
$baseUrl = 'https://raw.githubusercontent.com/cayeuxhugo-create/react-native-sherpa-onnx/' + $SourceRef.ToLowerInvariant()
$installerName = 'installer-moteur-v3.5.4-temporal-rescue.ps1'
$archiveName = 'modules-voix-v3.5.4-temporal-rescue.zip'
$toolNames = @('enregistrer-guardian-adaptatif.ps1','tester-guardian-secours-temporel.ps1',
    'preparer-envoi-diagnostic.ps1','LIVRAISON-SECOURS-TEMPOREL-V3.5.4.md')
$names = @($installerName,$archiveName) + $toolNames
$staging = Join-Path ([IO.Path]::GetTempPath()) ('guardian-download-' + [Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($staging)
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    foreach ($name in $names) {
        Write-Host ('Telechargement : ' + $name)
        Invoke-WebRequest -Uri ($baseUrl + '/' + $name) -OutFile (Join-Path $staging $name) -UseBasicParsing
    }
    $installerText = Get-Content -LiteralPath (Join-Path $staging $installerName) -Raw -Encoding UTF8
    $hashMatch = [regex]::Match($installerText, '\$expectedZipHash = "([a-f0-9]{64})"')
    $zipPath = Join-Path $staging $archiveName
    if (!$hashMatch.Success -or (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $hashMatch.Groups[1].Value) {
        throw 'Empreinte du ZIP incorrecte ou livraison non finalisee. Aucun outil existant ni module ne sera remplace.'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $entry = $archive.GetEntry('MANIFESTE.json')
        if (!$entry) { throw 'Manifeste absent du ZIP.' }
        $reader = New-Object IO.StreamReader($entry.Open(),[Text.Encoding]::UTF8)
        try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
    if ($manifest.version -cne 'guardian-voice-v3.5.4-temporal-rescue') { throw 'Version du manifeste incorrecte.' }
    foreach ($name in $toolNames) {
        $expectedToolHash = [string]$manifest.tools.$name
        $actualToolHash = (Get-FileHash -LiteralPath (Join-Path $staging $name) -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($expectedToolHash -cnotmatch '^[a-f0-9]{64}$' -or $actualToolHash -cne $expectedToolHash) {
            throw ('Outil non conforme au manifeste : ' + $name + '. Aucun remplacement effectue.')
        }
    }
    [void][IO.Directory]::CreateDirectory($DestinationDirectory)
    foreach ($name in $names) { Copy-Item -LiteralPath (Join-Path $staging $name) -Destination (Join-Path $DestinationDirectory $name) -Force }
    $engine = (Get-Process -Id $PID).Path
    & $engine -NoProfile -ExecutionPolicy Bypass -File (Join-Path $DestinationDirectory $installerName) `
        -ProjectDirectory $ProjectDirectory -ArchivePath (Join-Path $DestinationDirectory $archiveName)
    if ($LASTEXITCODE -ne 0) { throw "L'installation du module a echoue. Conserver le message affiche et relancer depuis $DestinationDirectory." }
    Write-Host ('Outils verifies : ' + $DestinationDirectory)
    Write-Host 'Reconstruire et installer maintenant l application Android avec votre methode et votre signature habituelles.'
    Write-Host 'Commande Expo locale si vous utilisez habituellement cette methode : npx expo run:android --device'
    Write-Host 'Puis lancer tester-guardian-secours-temporel.ps1 avec -Scenario 3M_BRUIT, puis -Scenario 5M_CALME.'
} finally {
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue }
}
