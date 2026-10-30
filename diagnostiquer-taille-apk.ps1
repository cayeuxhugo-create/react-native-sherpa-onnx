#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ApkPath = '',
    [string]$PreviousApkPath = '',
    [string]$ReportDirectory = ''
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Resolve-ApkFile([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or !(Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw ('Fichier APK introuvable : ' + $Path)
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Find-LocalAapt {
    foreach ($name in @('aapt.exe','aapt','aapt2.exe','aapt2')) {
        $command = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { return $command.Source }
    }
    $roots = @($env:ANDROID_SDK_ROOT,$env:ANDROID_HOME)
    if ($env:LOCALAPPDATA) { $roots += (Join-Path $env:LOCALAPPDATA 'Android/Sdk') }
    foreach ($sdk in @($roots | Where-Object { $_ } | Select-Object -Unique)) {
        $buildTools = Join-Path $sdk 'build-tools'
        if (!(Test-Path -LiteralPath $buildTools -PathType Container)) { continue }
        $versions = @(Get-ChildItem -LiteralPath $buildTools -Directory | Sort-Object -Property @{
            Expression = { try { [version]($_.Name -replace '-.*$','') } catch { [version]'0.0' } }
            Descending = $true
        })
        foreach ($version in $versions) {
            foreach ($name in @('aapt.exe','aapt','aapt2.exe','aapt2')) {
                $candidate = Join-Path $version.FullName $name
                if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
            }
        }
    }
    return $null
}

function Read-Debuggable([string]$Path, [string]$Tool) {
    $result = [ordered]@{value=$null; status='unknown'; tool=$Tool; reason='Outil aapt local indisponible.'}
    if (!$Tool) { return [pscustomobject]$result }
    try {
        $oldPreference = $ErrorActionPreference
        try {
            # A native tool can report an unsupported manifest on stderr.
            # Its failure must not prevent inspection of the ZIP sizes.
            $ErrorActionPreference = 'Continue'
            $lines = @(& $Tool dump badging $Path 2>&1)
            $exitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $oldPreference }
        $text = ($lines | ForEach-Object { $_.ToString() }) -join "`n"
        if ($exitCode -eq 0 -and $text -match '(?m)^package:\s+name=') {
            $result.value = ($text -match '(?m)^application-debuggable(?:\s|$)')
            $result.status = 'detected'
            $result.reason = 'Attribut lu avec aapt dump badging.'
        } else { $result.reason = 'aapt ne permet pas de lire cet attribut ; statut inconnu.' }
    } catch { $result.reason = 'Execution de aapt impossible ; statut inconnu.' }
    return [pscustomobject]$result
}

function Read-Apk([string]$Path, [string]$Aapt) {
    $file = Get-Item -LiteralPath $Path
    $beforeBytes = $file.Length
    $beforeWrite = $file.LastWriteTimeUtc
    $entries = New-Object 'System.Collections.Generic.List[object]'
    try { $archive = [IO.Compression.ZipFile]::OpenRead($Path) }
    catch { throw ('Ce fichier ne peut pas etre lu comme une archive APK : ' + $file.Name) }
    try {
        if (!$archive.GetEntry('AndroidManifest.xml')) { throw ('AndroidManifest.xml absent de : ' + $file.Name) }
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            $name = $entry.FullName
            $category = 'other'
            $abi = $null
            if ($name -cmatch '^lib/([^/]+)/') { $category='native'; $abi=$Matches[1] }
            elseif ($name.StartsWith('assets/',[StringComparison]::Ordinal)) { $category='assets' }
            elseif ($name -cmatch '^classes[0-9]*[.]dex$') { $category='dex' }
            elseif ($name -ceq 'resources.arsc' -or $name.StartsWith('res/',[StringComparison]::Ordinal)) { $category='resources' }
            $entries.Add([pscustomobject]@{name=$name; category=$category; abi=$abi;
                compressedBytes=[long]$entry.CompressedLength; uncompressedBytes=[long]$entry.Length})
        }
    } finally { $archive.Dispose() }
    $after = Get-Item -LiteralPath $Path
    if ($after.Length -ne $beforeBytes -or $after.LastWriteTimeUtc -ne $beforeWrite) {
        throw 'L APK a change pendant la lecture. Attendre la fin de la compilation puis recommencer.'
    }
    $categories = @()
    foreach ($category in @('native','assets','dex','resources','other')) {
        $matching = @($entries | Where-Object { $_.category -eq $category })
        $categories += [pscustomobject]@{name=$category; entries=$matching.Count;
            compressedBytes=[long](($matching | Measure-Object compressedBytes -Sum).Sum);
            uncompressedBytes=[long](($matching | Measure-Object uncompressedBytes -Sum).Sum)}
    }
    $abis = @($entries | Where-Object { $_.abi } | Group-Object abi | Sort-Object Name | ForEach-Object {
        [pscustomobject]@{name=$_.Name; entries=$_.Count;
            compressedBytes=[long](($_.Group | Measure-Object compressedBytes -Sum).Sum);
            uncompressedBytes=[long](($_.Group | Measure-Object uncompressedBytes -Sum).Sum)}
    })
    $compressed = [long](($entries | Measure-Object compressedBytes -Sum).Sum)
    return [pscustomobject]@{
        path=$Path; name=$file.Name; fileBytes=$beforeBytes; lastWriteUtc=$beforeWrite.ToString('o');
        entryCount=$entries.Count; entriesCompressedBytes=$compressed;
        entriesUncompressedBytes=[long](($entries | Measure-Object uncompressedBytes -Sum).Sum);
        zipAndSigningOverheadBytes=($beforeBytes-$compressed);
        debuggable=(Read-Debuggable $Path $Aapt); categories=@($categories); abis=@($abis);
        largestEntries=@($entries | Sort-Object compressedBytes -Descending | Select-Object -First 20);
        entries=@($entries.ToArray())
    }
}

function Compare-Apk($Current, $Previous) {
    $before = New-Object 'System.Collections.Generic.Dictionary[string,object]' ([StringComparer]::Ordinal)
    $after = New-Object 'System.Collections.Generic.Dictionary[string,object]' ([StringComparer]::Ordinal)
    foreach ($entry in $Previous.entries) { $before[$entry.name]=$entry }
    foreach ($entry in $Current.entries) { $after[$entry.name]=$entry }
    $changes = @()
    foreach ($name in @((@($before.Keys) + @($after.Keys)) | Sort-Object -Unique -CaseSensitive)) {
        $a=if ($after.ContainsKey($name)) {$after[$name]} else {$null}
        $b=if ($before.ContainsKey($name)) {$before[$name]} else {$null}
        if ($a -and $b -and $a.compressedBytes -eq $b.compressedBytes -and $a.uncompressedBytes -eq $b.uncompressedBytes) { continue }
        $changes += [pscustomobject]@{name=$name; status=$(if (!$b) {'added'} elseif (!$a) {'removed'} else {'sizeChanged'});
            compressedBytesDelta=([long]$a.compressedBytes-[long]$b.compressedBytes);
            uncompressedBytesDelta=([long]$a.uncompressedBytes-[long]$b.uncompressedBytes)}
    }
    $abiChanges = @()
    $abiNames = @(@($Current.abis | ForEach-Object { $_.name }) + @($Previous.abis | ForEach-Object { $_.name }) | Sort-Object -Unique)
    foreach ($name in $abiNames) {
        $a=@($Current.abis | Where-Object { $_.name -eq $name }); $b=@($Previous.abis | Where-Object { $_.name -eq $name })
        $abiChanges += [pscustomobject]@{name=$name;
            compressedBytesDelta=([long](($a | Measure-Object compressedBytes -Sum).Sum)-[long](($b | Measure-Object compressedBytes -Sum).Sum));
            uncompressedBytesDelta=([long](($a | Measure-Object uncompressedBytes -Sum).Sum)-[long](($b | Measure-Object uncompressedBytes -Sum).Sum))}
    }
    $categoryChanges = @()
    foreach ($a in $Current.categories) {
        $b=$Previous.categories | Where-Object { $_.name -eq $a.name }
        $categoryChanges += [pscustomobject]@{name=$a.name; compressedBytesDelta=($a.compressedBytes-$b.compressedBytes);
            uncompressedBytesDelta=($a.uncompressedBytes-$b.uncompressedBytes)}
    }
    return [pscustomobject]@{fileBytesDelta=($Current.fileBytes-$Previous.fileBytes);
        percentageChange=$(if ($Previous.fileBytes -gt 0) {100.0*($Current.fileBytes-$Previous.fileBytes)/$Previous.fileBytes} else {$null});
        comparisonScope='ZIP entry sizes only; equal size does not prove identical content.';
        abiChanges=@($abiChanges); categoryChanges=@($categoryChanges);
        entryChanges=@($changes | Sort-Object @{Expression={[Math]::Abs($_.compressedBytesDelta)};Descending=$true})}
}

if ([string]::IsNullOrWhiteSpace($ApkPath)) { $ApkPath=Read-Host 'Chemin du fichier APK a examiner' }
$ApkPath=Resolve-ApkFile $ApkPath.Trim().Trim('"')
if ($PreviousApkPath) { $PreviousApkPath=Resolve-ApkFile $PreviousApkPath.Trim().Trim('"') }
$aapt=Find-LocalAapt
$current=Read-Apk $ApkPath $aapt
$previous=$null; $comparison=$null
if ($PreviousApkPath) { $previous=Read-Apk $PreviousApkPath $aapt; $comparison=Compare-Apk $current $previous }
if ([string]::IsNullOrWhiteSpace($ReportDirectory)) {
    $userDirectory=$env:USERPROFILE
    if (!$userDirectory) { $userDirectory=[Environment]::GetFolderPath('UserProfile') }
    if (!$userDirectory) { throw 'Indiquer -ReportDirectory pour enregistrer les rapports.' }
    $ReportDirectory=Join-Path $userDirectory 'Guardian-apk-audit'
}
[void][IO.Directory]::CreateDirectory($ReportDirectory)
$ReportDirectory=(Resolve-Path -LiteralPath $ReportDirectory).Path
$stem='taille-apk-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6)
$jsonPath=Join-Path $ReportDirectory ($stem+'.json')
$textPath=Join-Path $ReportDirectory ($stem+'.txt')
$report=[pscustomobject]@{schema=1; createdUtc=[DateTime]::UtcNow.ToString('o');
    scope='APK ZIP inspection only; no installed app data or RAM measurement; no file upload.';
    current=$current; previous=$previous; comparison=$comparison}
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
$lines=New-Object 'System.Collections.Generic.List[string]'
$lines.Add('APK : '+$current.name)
$lines.Add(('Taille du fichier : {0:N2} Mio ({1} octets)' -f ($current.fileBytes/1MB),$current.fileBytes))
$debugLabel=if ($null -eq $current.debuggable.value) {'inconnu'} elseif ($current.debuggable.value) {'oui'} else {'non'}
$lines.Add('APK debogable : '+$debugLabel+' ; '+$current.debuggable.reason)
$lines.Add('Tailles compressees des entrees ZIP :')
foreach ($category in $current.categories) { $lines.Add(('  {0} : {1:N2} Mio ({2} fichiers)' -f $category.name,($category.compressedBytes/1MB),$category.entries)) }
$lines.Add(('En-tetes ZIP, alignement et signature : {0:N2} Mio' -f ($current.zipAndSigningOverheadBytes/1MB)))
$lines.Add('Bibliotheques natives par architecture :')
foreach ($abi in $current.abis) { $lines.Add(('  {0} : {1:N2} Mio compresses ; {2:N2} Mio decompressees' -f $abi.name,($abi.compressedBytes/1MB),($abi.uncompressedBytes/1MB))) }
$lines.Add('Les 20 plus grosses entrees ZIP, par taille compressee :')
foreach ($entry in $current.largestEntries) { $lines.Add(('  {0:N2} Mio : {1}' -f ($entry.compressedBytes/1MB),$entry.name)) }
if ($comparison) {
    $lines.Add(('Comparaison avec {0} : {1:+0.00;-0.00;0.00} Mio ({2} octets)' -f $previous.name,($comparison.fileBytesDelta/1MB),$comparison.fileBytesDelta))
    $lines.Add('Les deltas portent sur les tailles ; des tailles egales ne prouvent pas des contenus identiques.')
}
$lines.Add('Lecture seule des APK. Aucun modele, diagnostic ou fichier de l application n a ete supprime ou envoye.')
$lines | Set-Content -LiteralPath $textPath -Encoding UTF8
$lines | ForEach-Object { Write-Host $_ }
Write-Host ('Rapport texte : '+$textPath)
Write-Host ('Rapport JSON : '+$jsonPath)
