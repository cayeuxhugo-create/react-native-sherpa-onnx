#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ArchivePath)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourcePath = (Resolve-Path -LiteralPath $ArchivePath).Path
$limit = 24MB
$source = [IO.Compression.ZipFile]::OpenRead($sourcePath)
$target = $null
try {
    $files = @($source.Entries | Where-Object { -not ($_.FullName.EndsWith('/') -or $_.FullName.EndsWith('\')) })
    if ($files.Count -eq 0) { throw "Archive vide." }
    foreach ($entry in $files) {
        if ($entry.Length -gt $limit) { throw ("Un fichier depasse 24 Mio : " + $entry.FullName + ". Conserver l'archive et transmettre cette erreur.") }
    }
    $output = Join-Path ([IO.Path]::GetDirectoryName($sourcePath)) ([IO.Path]::GetFileNameWithoutExtension($sourcePath) + "-envoi-" + [Guid]::NewGuid().ToString("N").Substring(0,8))
    [void][IO.Directory]::CreateDirectory($output)
    $part = 0; $currentBytes = 0L
    $mapping = New-Object 'System.Collections.Generic.List[object]'
    foreach ($entry in $files) {
        if ($null -eq $target -or ($currentBytes + $entry.Length) -gt $limit) {
            if ($null -ne $target) { $target.Dispose(); $target = $null }
            $part++; $currentBytes = 0L
            $partName = "partie-" + $part.ToString("00") + ".zip"
            $target = [IO.Compression.ZipFile]::Open((Join-Path $output $partName), [IO.Compression.ZipArchiveMode]::Create)
        }
        $destination = $target.CreateEntry($entry.FullName, [IO.Compression.CompressionLevel]::Fastest)
        $inputStream = $null; $outputStream = $null
        try {
            $inputStream = $entry.Open(); $outputStream = $destination.Open()
            $inputStream.CopyTo($outputStream)
        } finally {
            if ($null -ne $outputStream) { $outputStream.Dispose() }
            if ($null -ne $inputStream) { $inputStream.Dispose() }
        }
        $currentBytes += $entry.Length
        $mapping.Add([pscustomobject]@{archive=$partName; file=$entry.FullName; bytes=$entry.Length})
    }
    $target.Dispose(); $target = $null
    $mapping.ToArray() | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output "repartition.json") -Encoding UTF8
    $parts = @(Get-ChildItem -LiteralPath $output -Filter '*.zip' -File)
    foreach ($file in $parts) {
        if ($file.Length -ge 32MB) { throw ("Partie trop grande : " + $file.FullName) }
        Write-Host ($file.Name + " : " + [math]::Round($file.Length / 1MB,2) + " Mio")
    }
    Write-Host ("Dossier a ouvrir : " + $output)
    Write-Host ("Envoyer les " + $parts.Count + " fichiers partie-*.zip de ce dossier. Le ZIP original est conserve, les fichiers audio ne sont pas modifies.")
} finally {
    if ($null -ne $target) { $target.Dispose() }
    $source.Dispose()
}
