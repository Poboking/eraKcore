# Start one process, measure wall clock and peak working set, pass the exit code through.
# Args live in a UTF-8 file (one token per line, no token contains a space) so CJK paths
# never round-trip through the console code page. Child stdout+stderr go to -OutFile.
# PeakWorkingSet64 must be sampled WHILE the process runs: after WaitForExit() the value
# reads back 0 on this host (measured 2026-11-01), so the loop below polls every 200 ms.
param(
    [string]$Exe,
    [string]$ArgFile,
    [string]$WorkDir,
    [string]$OutFile
)
$tokens = @(Get-Content -LiteralPath $ArgFile -Encoding UTF8 | Where-Object { $_ -ne '' })
foreach ($t in $tokens) {
    if ($t.Contains(' ')) { throw ("ArgFile token contains a space: " + $t) }
}
$si = New-Object System.Diagnostics.ProcessStartInfo
$si.FileName = $Exe
$si.UseShellExecute = $false
$si.WorkingDirectory = $WorkDir
$si.Arguments = ($tokens -join ' ')
$si.RedirectStandardOutput = $true
$si.RedirectStandardError = $true
$p = New-Object System.Diagnostics.Process
$p.StartInfo = $si
$sw = [System.Diagnostics.Stopwatch]::StartNew()
[void]$p.Start()
$peakWs = 0
$peakPriv = 0
while (-not $p.WaitForExit(200)) {
    $p.Refresh()
    if ($p.PeakWorkingSet64 -gt $peakWs) { $peakWs = $p.PeakWorkingSet64 }
    if ($p.PrivateMemorySize64 -gt $peakPriv) { $peakPriv = $p.PrivateMemorySize64 }
}
$out = $p.StandardOutput.ReadToEnd()
$err = $p.StandardError.ReadToEnd()
$sw.Stop()
if ($p.PeakWorkingSet64 -gt $peakWs) { $peakWs = $p.PeakWorkingSet64 }
if ($p.PrivateMemorySize64 -gt $peakPriv) { $peakPriv = $p.PrivateMemorySize64 }
Set-Content -LiteralPath $OutFile -Value ($out + "`n--- stderr ---`n" + $err) -Encoding UTF8
Write-Output ("TOKENS=" + $tokens.Count)
Write-Output ("RC=" + $p.ExitCode)
Write-Output ("WALL_MS=" + $sw.ElapsedMilliseconds)
Write-Output ("PEAK_WS_MB=" + [math]::Round($peakWs / 1MB, 1))
Write-Output ("PEAK_PRIV_MB=" + [math]::Round($peakPriv / 1MB, 1))
