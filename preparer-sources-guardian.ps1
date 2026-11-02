#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ProjectDirectory = 'C:\Projets\guardian-voice',
    [string]$OutputDirectory = '',
    [ValidateRange(1,24)][int]$PartMiB = 24
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectDirectory).Path.TrimEnd([char[]]@(92,47))
if (!(Test-Path -LiteralPath (Join-Path $project 'package.json') -PathType Leaf)) { throw 'La racine du projet doit contenir package.json.' }
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $parent = [IO.Directory]::GetParent($project)
    if ($null -eq $parent) { throw 'Indiquer un dossier de sortie hors du projet.' }
    $OutputDirectory = Join-Path $parent.FullName ('Guardian-SOURCES-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
}
$output = [IO.Path]::GetFullPath($OutputDirectory)
if ($output.Equals($project, [StringComparison]::OrdinalIgnoreCase) -or $output.StartsWith($project + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'La sortie doit etre hors du projet pour eviter de se rearchiver elle-meme.' }
if (Test-Path -LiteralPath $output) { throw 'Choisir un nouveau dossier de sortie inexistant.' }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
[void][IO.Directory]::CreateDirectory($output)
$skipDirectories = @('node_modules','.git','.gradle','.expo','.next','.cache','.cxx','.kotlin','build','dist','Pods','DerivedData','coverage','.idea','.vscode','.yarn')
$skipExtensions = @('.apk','.aab','.zip','.7z','.rar','.tar','.gz','.bz2','.xz','.onnx','.ort','.tflite','.bin','.f32','.aar','.so','.dll','.dylib','.jar','.class','.pyc','.wav','.mp3','.mp4','.m4a','.flac','.log')
$secretPatterns = @('.env','.env.*','*.jks','*.keystore','*.p12','*.pfx','*.pem','*.key','*.p8','*.mobileprovision','.netrc','auth.json','secrets.*','*.secrets.*','*credentials*.json','*service-account*.json','*serviceaccount*.json','*firebase-adminsdk*.json','google-services.json','GoogleService-Info.plist','key.properties','keystore.properties','.npmrc','.yarnrc*','local.properties')
$selected = New-Object 'System.Collections.Generic.List[object]'
$excluded = New-Object 'System.Collections.Generic.List[object]'
$queue = New-Object 'System.Collections.Generic.Queue[string]'
Write-Host ('Preparation des sources : ' + $project)
Write-Host 'Inventaire des fichiers (node_modules et caches ignores)...'
$queue.Enqueue($project)
while ($queue.Count -gt 0) {
    $directory = $queue.Dequeue()
    foreach ($item in @(Get-ChildItem -LiteralPath $directory -Force)) {
        $relative = $item.FullName.Substring($project.Length).TrimStart([char[]]@(92,47)).Replace([char]92,[char]47)
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            $excluded.Add([pscustomobject]@{path=$relative;reason='lien ou jonction non suivi'}); continue
        }
        if ($item.PSIsContainer) {
            if ($skipDirectories -contains $item.Name) { $excluded.Add([pscustomobject]@{path=$relative;reason='dependances ou sortie generee'}); continue }
            $queue.Enqueue($item.FullName); continue
        }
        $secret = $false
        foreach ($pattern in $secretPatterns) { if ($item.Name -like $pattern) { $secret=$true; break } }
        if ($secret) { $excluded.Add([pscustomobject]@{path=$relative;reason='configuration sensible ou locale exclue'}); continue }
        if ($skipExtensions -contains $item.Extension.ToLowerInvariant()) { $excluded.Add([pscustomobject]@{path=$relative;bytes=$item.Length;reason='binaire, modele, archive ou log exclu'}); continue }
        $weight = [long]$item.Length + 1024L + 2L * [Text.Encoding]::UTF8.GetByteCount($relative)
        if ($weight -gt $PartMiB * 1MB) { $excluded.Add([pscustomobject]@{path=$relative;bytes=$item.Length;reason='fichier individuel trop grand'}); continue }
        $selected.Add([pscustomobject]@{source=$item.FullName;path=$relative;bytes=$item.Length;weight=$weight})
    }
}
if ($selected.Count -eq 0) { throw 'Aucun fichier source selectionne.' }
$parts = New-Object 'System.Collections.Generic.List[object]'
$included = New-Object 'System.Collections.Generic.List[object]'
Write-Host ('Inventaire termine : ' + $selected.Count + ' fichiers retenus. Compression...')
$archive = $null
$partNumber = 0
$partWeight = 0L
$partPath = ''
function Close-Part {
    if ($null -ne $script:archive) {
        $script:archive.Dispose(); $script:archive = $null
        $size = (Get-Item -LiteralPath $script:partPath).Length
        if ($size -ge 32MB) { throw 'Une partie depasse la limite de transfert ; ne pas envoyer cette partie.' }
        $script:parts.Add([pscustomobject]@{archive=[IO.Path]::GetFileName($script:partPath);bytes=$size;sha256=(Get-FileHash -LiteralPath $script:partPath -Algorithm SHA256).Hash.ToLowerInvariant()})
    }
}
try {
    foreach ($file in @($selected | Sort-Object path)) {
        if ($null -eq $archive -or $partWeight + $file.weight -gt $PartMiB * 1MB) {
            Close-Part
            $partNumber++
            $partPath = Join-Path $output ('guardian-sources-{0:D2}.zip' -f $partNumber)
            Write-Host ('Creation : ' + [IO.Path]::GetFileName($partPath))
            $archive = [IO.Compression.ZipFile]::Open($partPath, [IO.Compression.ZipArchiveMode]::Create)
            $partWeight = 0L
        }
        $entry = $archive.CreateEntry($file.path, [IO.Compression.CompressionLevel]::Optimal)
        $destination = $entry.Open()
        try {
            if ([IO.Path]::GetFileName($file.path) -eq 'gradle.properties') {
                # Export a redacted copy only; never modify the original file.
                $text = [IO.File]::ReadAllText($file.source)
                $text = [regex]::Replace($text, '(?im)^(\s*[^#\r\n=]*(?:password|token|secret|api[_-]?key|private[_-]?key|store[_-]?file)[^\r\n=]*=)[^\r\n]*', '$1<REDACTED_BY_EXPORT>')
                $bytes = [Text.Encoding]::UTF8.GetBytes($text)
                $destination.Write($bytes,0,$bytes.Length)
                $redacted = $true
            } else {
                $source = [IO.File]::OpenRead($file.source)
                try { $source.CopyTo($destination) } finally { $source.Dispose() }
                $redacted = $false
            }
        } finally { $destination.Dispose() }
        $partWeight += $file.weight
        $included.Add([pscustomobject]@{path=$file.path;archive=[IO.Path]::GetFileName($partPath);sourceBytes=$file.bytes;redactedCopy=$redacted})
    }
    Close-Part
} finally { if ($null -ne $archive) { $archive.Dispose() } }
$manifest = [ordered]@{schema=1;scope='sources uniquement; aucun fichier original modifie ou supprime';partMiB=$PartMiB;parts=@($parts.ToArray());included=@($included.ToArray());excluded=@($excluded.ToArray());note='Les secrets integres directement dans du code ne sont pas detectes automatiquement. Verifier avant envoi.'}
$json = $manifest | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText((Join-Path $output 'MANIFESTE-AUDIT.json'),$json,(New-Object Text.UTF8Encoding($false)))
Write-Host ''
Write-Host ('EXPORT TERMINE : ' + $output)
Write-Host ('Fichiers inclus : ' + $included.Count + ' ; parties ZIP : ' + $parts.Count)
Write-Host 'Envoyer les guardian-sources-XX.zip et MANIFESTE-AUDIT.json. Ne pas renvoyer node_modules.'
Write-Host 'Les fichiers du projet original sont intacts. Verifier les eventuels secrets integres dans le code avant envoi.'
