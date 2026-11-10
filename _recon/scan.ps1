param(
  [string]$Jar,
  [string]$Pattern,
  [int]$Context = 50
)
$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
$rx = [regex]$Pattern
$hits = 0
foreach ($e in $zip.Entries) {
  if ($e.Length -eq 0) { continue }
  if ($e.FullName -notmatch '\.(class|json|txt|xml|toml|properties)$') { continue }
  $s = $e.Open()
  $ms = New-Object System.IO.MemoryStream
  $s.CopyTo($ms)
  $s.Close()
  $bytes = $ms.ToArray()
  $ms.Close()
  $txt = [System.Text.Encoding]::GetEncoding(28591).GetString($bytes)
  $m = $rx.Matches($txt)
  if ($m.Count -gt 0) {
    $hits++
    $seen = @{}
    foreach ($x in $m) {
      $st = [Math]::Max(0, $x.Index - $Context)
      $ln = [Math]::Min(2 * $Context + $x.Length, $txt.Length - $st)
      $ctx = $txt.Substring($st, $ln) -replace '[^\x20-\x7E]', '.'
      if (-not $seen.ContainsKey($ctx)) { $seen[$ctx] = 1; "  [$($e.FullName)] $ctx" }
    }
  }
}
$zip.Dispose()
"### total entries matching: $hits"
