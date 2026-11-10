$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$recon = 'D:\DS_WORKSPACE\raildispatch\_recon'
$out = Join-Path $recon 'nested'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$jarExe = 'C:\Program Files\Java\jdk-21\bin\jar.exe'

foreach ($jar in @('create.jar', 'aeronautics.jar')) {
  $z = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $recon $jar))
  foreach ($e in $z.Entries) {
    if ($e.FullName -like 'META-INF/jarjar/*.jar') {
      $stem = $jar -replace '\.jar$', ''
      $dest = Join-Path $out ($stem + '__' + $e.Name)
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true)
    }
  }
  $z.Dispose()
}
"--- nested jars ---"
$nested = Get-ChildItem -LiteralPath $out -Filter *.jar
$nested | ForEach-Object { $_.Name + '  (' + $_.Length + ')' }
"--- searching for net/createmod/catnip/data/Couple.class ---"
foreach ($n in $nested) {
  $hit = & $jarExe tf $n.FullName 2>$null | Select-String -Pattern 'catnip/data/Couple'
  if ($hit) { "FOUND in " + $n.Name; $hit | ForEach-Object { '    ' + $_.Line } }
}
"--- also searching all nested for 'Couple.class' generally ---"
foreach ($n in $nested) {
  $hit = & $jarExe tf $n.FullName 2>$null | Select-String -Pattern 'catnip'
  if ($hit) { "catnip entries in " + $n.Name + ': ' + $hit.Count }
}
