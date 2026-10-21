function Copy-GuardianBinaryWithRunAs {
    param([string]$RemotePath, [string]$LocalPath)
    # Do not use PowerShell 5 ">" to copy WAV: it can convert binary to text.
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $adbExecutable
    $info.Arguments = (@($deviceArguments) + @("exec-out", "run-as", $PackageName, "cat", $RemotePath)) -join " "
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $native = New-Object System.Diagnostics.Process
    $native.StartInfo = $info
    $target = $null
    try {
        [void]$native.Start()
        $target = [IO.File]::Create($LocalPath)
        $native.StandardOutput.BaseStream.CopyTo($target)
        $target.Dispose()
        $target = $null
        $errorText = $native.StandardError.ReadToEnd()
        $native.WaitForExit()
        if ($native.ExitCode -ne 0) { throw ("run-as failed: " + $errorText) }
    } finally {
        if ($target) { $target.Dispose() }
        $native.Dispose()
    }
}

function Get-GuardianWavHeader {
    param([string]$Path)
    $data = [IO.File]::ReadAllBytes($Path)
    if ($data.Length -lt 44) { return @{ valid=$false; reason="header too short" } }
    $riff = [Text.Encoding]::ASCII.GetString($data, 0, 4)
    $wave = [Text.Encoding]::ASCII.GetString($data, 8, 4)
    $rate = [BitConverter]::ToUInt32($data,24)
    $channels = [BitConverter]::ToUInt16($data,22)
    $bits = [BitConverter]::ToUInt16($data,34)
    $declaredBytes = [BitConverter]::ToUInt32($data,40)
    $valid = $riff -eq "RIFF" -and $wave -eq "WAVE" -and $channels -eq 2 -and $bits -eq 16 -and $rate -gt 0 -and $declaredBytes -eq ($data.Length-44)
    $duration = 0
    if ($rate -gt 0 -and $channels -gt 0 -and $bits -gt 0) {
        $duration = ($data.Length-44) / ($rate*$channels*$bits/8)
    }
    return @{ valid=$valid; sampleRateHz=$rate; channels=$channels; bits=$bits; durationSeconds=$duration; bytes=$data.Length }
}

function Collect-GuardianWavs {
    $remoteDirectory = "/sdcard/Android/data/$PackageName/files/guardian-diagnostics"
    $records = New-Object 'System.Collections.Generic.List[object]'
    try {
        $listing = Invoke-GuardianAdb @("shell", "ls", "-1", $remoteDirectory)
    } catch {
        $listing = Invoke-GuardianAdb @("shell", "run-as", $PackageName, "ls", "-1", $remoteDirectory)
    }
    foreach ($name in ($listing -split "\r?\n")) {
        $name = $name.Trim()
        if ($name -notmatch '^guardian-multimic-(\d{13})-[A-Za-z0-9_-]+\.wav$') { continue }
        # Only sessions started during this collection, not older recordings.
        if ([long]$Matches[1] -lt ($deviceStartMs-1000)) { continue }
        $wavDirectory = Join-Path $OutputDirectory "wav"
        if (-not (Test-Path -LiteralPath $wavDirectory)) { New-Item -ItemType Directory -Path $wavDirectory | Out-Null }
        $remoteFile = "$remoteDirectory/$name"
        $localFile = Join-Path $wavDirectory $name
        $method = "adb pull"
        try {
            [void](Invoke-GuardianAdb @("pull", $remoteFile, $localFile))
        } catch {
            $method = "run-as binary stream"
            Copy-GuardianBinaryWithRunAs $remoteFile $localFile
        }
        $header = Get-GuardianWavHeader $localFile
        $records.Add([pscustomobject]@{
            file=$name; method=$method; header=$header
            sha256=(Get-FileHash -LiteralPath $localFile -Algorithm SHA256).Hash.ToLowerInvariant()
        })
        if (-not $header.valid) { Write-Warning ("WAV incomplet : " + $name + ". Verifier que la protection est arretee.") }
    }
    ConvertTo-Json -InputObject @($records.ToArray()) -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory "wav-manifest.json") -Encoding UTF8
    $metadata["audioFilesCollected"] = $records.Count
    if ($records.Count -eq 0) {
        Write-Warning "Aucun nouveau WAV trouve. Le code existant n'enregistre que le diagnostic stereo explicite."
    }
}

