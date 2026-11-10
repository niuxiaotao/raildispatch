<#
.SYNOPSIS
    Builds the Rail Dispatch mod completely OFFLINE, straight against the jars that a Minecraft
    instance already has on disk (no Gradle, no network, no Maven).

.DESCRIPTION
    A launcher's library folder already holds everything the JVM needs at runtime:
      * the full Minecraft jar in Mojang official mappings (client-<ver>-<ts>-srg.jar)
      * NeoForge's own classes                           (neoforge-<ver>-universal.jar)
      * NeoForge's patched-Minecraft overlay             (neoforge-<ver>-client.jar)
      * every third-party library listed by the version JSON
    and an instance's mods/ folder holds Create, Aeronautics, Simurail and friends. That is a
    perfectly good javac classpath, so this script drives javac and jar directly.

.NOTES
    The script is ASCII only and targets both Windows PowerShell 5.1 and PowerShell 7+, because a
    UTF-8 script without a BOM is read as ANSI by 5.1 and would corrupt non-ASCII paths.
    Placeholders such as ${mod_id} in neoforge.mods.toml are expanded from gradle.properties here,
    which is what Gradle's processResources would normally do.
#>
[CmdletBinding()]
param(
    [string] $MinecraftRoot    = 'D:\DAOXIAN\.minecraft',
    [string] $Instance         = '',
    [string] $MinecraftVersion = '',
    [string] $NeoVersion       = '',
    [string] $JavaHome         = '',
    [string] $BuildName        = '',
    [switch] $Clean,
    [switch] $Deploy,
    [switch] $SkipResources,
    [switch] $ShowClasspath,
    [switch] $Quiet
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot  = Split-Path -Parent $PSScriptRoot
$srcJava   = Join-Path $repoRoot 'src\main\java'
$srcRes    = Join-Path $repoRoot 'src\main\resources'
# -BuildName gives a private output tree, so two people (or two agents) can compile the same sources
# at the same time without overwriting each other's classes and jar.
$buildDir  = if ($BuildName) { Join-Path $repoRoot ('build-' + $BuildName) } else { Join-Path $repoRoot 'build' }
$classDir  = Join-Path $buildDir 'classes'
$libsDir   = Join-Path $buildDir 'libs'
$genDir    = Join-Path $buildDir 'generated-resources'
$libraries = Join-Path $MinecraftRoot 'libraries'
$versions  = Join-Path $MinecraftRoot 'versions'

# Every path below is passed with -LiteralPath, and that is not cosmetic: mod file names on a Chinese
# pack look like "[机械动力] create-1.21.1-6.0.10.jar", and PowerShell treats the square brackets as a
# wildcard character class. A positional path would then match nothing and - worse - fail silently, so
# the nested jars of every bracket-named mod (which is where Catnip's Pair, Flywheel, Ponder,
# Registrate and the real Aeronautics code live) would quietly vanish from the classpath.
function Say($message, $colour = 'Gray') {
    if (-not $Quiet) { Write-Host $message -ForegroundColor $colour }
}

# --------------------------------------------------------------------------- instance discovery
if (-not $Instance) {
    Say '==> Locating a Minecraft instance that has physics bogies' 'Cyan'
    $candidates = @()
    foreach ($dir in Get-ChildItem -LiteralPath $versions -Directory -ErrorAction SilentlyContinue) {
        $mods = Join-Path $dir.FullName 'mods'
        if (-not (Test-Path -LiteralPath $mods)) { continue }
        $hasPhysics = Get-ChildItem -LiteralPath $mods -Filter 'simurail*.jar' -File -ErrorAction SilentlyContinue
        $hasCreate  = Get-ChildItem -LiteralPath $mods -Filter 'create-1.21*.jar' -File -ErrorAction SilentlyContinue
        # Strongest signal by far: the instance the mod has actually been installed into is the one
        # whose mod versions the build must match, because those are the jars it will run against.
        $hasOurs    = Get-ChildItem -LiteralPath $mods -Filter 'raildispatch*.jar' -File -ErrorAction SilentlyContinue
        $neo = [regex]::Match($dir.Name, '(\d+\.\d+\.\d+)')
        $score = 0
        if ($hasOurs)    { $score += 5000 }
        if ($hasPhysics) { $score += 1000 }
        if ($hasCreate)  { $score += 100 }
        if ($neo.Success) { $score += [int]($neo.Groups[1].Value.Split('.')[2]) }
        $candidates += [pscustomobject]@{ Name = $dir.Name; Score = $score }
    }
    if ($candidates.Count -eq 0) { throw "No Minecraft instance with a mods folder found under $versions" }
    $Instance = ($candidates | Sort-Object Score -Descending | Select-Object -First 1).Name
    Say "    $Instance" 'Green'
}

$verDir  = Join-Path $versions $Instance
if (-not (Test-Path -LiteralPath $verDir)) { throw "Instance folder not found: $verDir" }
$modsDir = Join-Path $verDir 'mods'

if (-not $MinecraftVersion) {
    $match = [regex]::Match($Instance, '(\d+\.\d+(\.\d+)?)-')
    if ($match.Success) { $MinecraftVersion = $match.Groups[1].Value }
}
if (-not $NeoVersion) {
    # Anchored on the loader name: a bare version regex would match the Minecraft version instead.
    $match = [regex]::Match($Instance, 'NeoForge[_-](\d+\.\d+\.\d+)')
    if ($match.Success) { $NeoVersion = $match.Groups[1].Value }
}
if (-not $MinecraftVersion -or -not $NeoVersion) {
    throw "Could not derive the Minecraft and NeoForge versions from '$Instance'; pass -MinecraftVersion and -NeoVersion."
}
Say "==> Target: Minecraft $MinecraftVersion / NeoForge $NeoVersion" 'Cyan'

# --------------------------------------------------------------------------- properties
$props = @{}
foreach ($line in [IO.File]::ReadAllLines((Join-Path $repoRoot 'gradle.properties'))) {
    $m = [regex]::Match($line, '^\s*([A-Za-z0-9_.]+)\s*=\s*(.*)$')
    if ($m.Success) { $props[$m.Groups[1].Value] = $m.Groups[2].Value.Trim() }
}
$modId      = $props['mod_id']
$modVersion = $props['mod_version']
$jarName    = "$modId-$modVersion.jar"
Say "    mod_id=$modId mod_version=$modVersion"

# --------------------------------------------------------------------------- JDK
if (-not $JavaHome) {
    foreach ($candidate in @($env:JAVA_HOME) + @('C:\Program Files\Java\jdk-21', 'C:\Program Files\Java\latest')) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))) { $JavaHome = $candidate; break }
    }
}
if (-not $JavaHome) {
    foreach ($dir in Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue) {
        if (Test-Path -LiteralPath (Join-Path $dir.FullName 'bin\javac.exe')) { $JavaHome = $dir.FullName; break }
    }
}
if (-not $JavaHome) { throw 'No JDK with javac.exe found; pass -JavaHome <path>.' }
$javac  = Join-Path $JavaHome 'bin\javac.exe'
$jarExe = Join-Path $JavaHome 'bin\jar.exe'
Say "    JDK: $JavaHome"

# --------------------------------------------------------------------------- classpath
Say '==> Assembling the offline compile classpath' 'Cyan'
$cp   = New-Object System.Collections.Generic.List[string]
$seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)

function Add-Jar([string] $path, [string] $why) {
    if (-not $path) { return }
    if (-not (Test-Path -LiteralPath $path)) { return }
    if ($path -like '*-sources.jar' -or $path -like '*-javadoc.jar') { return }
    $leaf = Split-Path $path -Leaf
    if ($seen.Add($leaf)) { $cp.Add($path); Write-Verbose "  + [$why] $leaf" }
}

# 1. NeoForge: API classes first, then the patched-Minecraft overlay.
$neoDir = Join-Path $libraries "net\neoforged\neoforge\$NeoVersion"
Add-Jar (Join-Path $neoDir "neoforge-$NeoVersion-universal.jar") 'neoforge-api'
Add-Jar (Join-Path $neoDir "neoforge-$NeoVersion-client.jar")    'neoforge-mc-patch'

# 2. The full Minecraft jar in Mojang official mappings. The '-srg' file name is a legacy label
#    from the launcher pipeline; the classes inside use official (readable) names.
$mcClientDir = Join-Path $libraries 'net\minecraft\client'
$mcFound = $false
foreach ($dir in (Get-ChildItem -LiteralPath $mcClientDir -Directory -ErrorAction SilentlyContinue |
                  Where-Object { $_.Name -like "$MinecraftVersion-*" } | Sort-Object Name -Descending)) {
    $srg = Get-ChildItem -LiteralPath $dir.FullName -Filter '*-srg.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($srg) { Add-Jar $srg.FullName 'minecraft'; $mcFound = $true; break }
}
if (-not $mcFound) { throw "No Minecraft $MinecraftVersion jar found under $mcClientDir" }

# 3. Every library the launcher resolves for this instance, from its version JSON.
$verJson = Join-Path $verDir ($Instance + '.json')
if (Test-Path -LiteralPath $verJson) {
    $json = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($verJson)) | ConvertFrom-Json
    $present = 0
    foreach ($lib in $json.libraries) {
        $rel = $lib.downloads.artifact.path
        if (-not $rel) { continue }
        $abs = Join-Path $libraries ($rel -replace '/', '\')
        if (Test-Path -LiteralPath $abs) { Add-Jar $abs 'version-json'; $present++ }
    }
    Say "    version JSON libraries present: $present / $($json.libraries.Count)"
}

# 4. The instance's mods: Create, Aeronautics, Simurail, Railways and their dependencies.
$modCount = 0
if (Test-Path -LiteralPath $modsDir) {
    foreach ($mod in Get-ChildItem -LiteralPath $modsDir -Filter '*.jar' -File) { Add-Jar $mod.FullName 'instance-mod'; $modCount++ }
}
Say "    instance mods on classpath: $modCount"

# 4b. Jars nested inside those mods under META-INF/jarjar/. This step is mandatory, not optional:
#     Create ships Catnip (whose Pair type appears in Create's own public signatures), Flywheel,
#     Ponder and Registrate that way, and the Aeronautics "bundled" jar is only a wrapper around
#     the real Aeronautics/Offroad/Simulated code.
$jarJarCache = Join-Path $buildDir 'jarjar-cache'
$nestedCount = 0
try { Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue } catch { }
foreach ($mod in (Get-ChildItem -LiteralPath $modsDir -Filter '*.jar' -File -ErrorAction SilentlyContinue)) {
    $stampPath = Join-Path $jarJarCache ($mod.BaseName + '.stamp')
    $outDir = Join-Path $jarJarCache $mod.BaseName
    $stampValue = [string]$mod.LastWriteTimeUtc.Ticks
    $needsExtract = $true
    if ((Test-Path -LiteralPath $stampPath) -and (Test-Path -LiteralPath $outDir)) {
        if ([IO.File]::ReadAllText($stampPath) -eq $stampValue) { $needsExtract = $false }
    }
    if ($needsExtract) {
        if (Test-Path -LiteralPath $outDir) { Remove-Item -LiteralPath $outDir -Recurse -Force }
        New-Item -ItemType Directory -Path $outDir -Force | Out-Null
        try {
            $zip = [System.IO.Compression.ZipFile]::OpenRead($mod.FullName)
            try {
                foreach ($entry in $zip.Entries) {
                    if ($entry.FullName -notlike 'META-INF/jarjar/*.jar') { continue }
                    if ($entry.Length -eq 0) { continue }
                    $target = Join-Path $outDir (Split-Path $entry.FullName -Leaf)
                    $input = $entry.Open()
                    try {
                        $output = [IO.File]::Create($target)
                        try { $input.CopyTo($output) } finally { $output.Dispose() }
                    } finally { $input.Dispose() }
                }
            } finally { $zip.Dispose() }
        } catch {
            Say "    WARNING: could not read nested jars from $($mod.Name): $($_.Exception.Message)" 'Yellow'
        }
        [IO.File]::WriteAllText($stampPath, $stampValue)
    }
    foreach ($nested in (Get-ChildItem -LiteralPath $outDir -Filter '*.jar' -File -ErrorAction SilentlyContinue)) {
        Add-Jar $nested.FullName 'nested-jarjar'
        $nestedCount++
    }
}
Say "    nested jarjar jars on classpath: $nestedCount"

# 5. Anything dropped into libs/ by hand.
$localLibs = Join-Path $repoRoot 'libs'
if (Test-Path -LiteralPath $localLibs) {
    foreach ($mod in Get-ChildItem -LiteralPath $localLibs -Filter '*.jar' -File) { Add-Jar $mod.FullName 'libs' }
}

$cpString = ($cp -join ';')
Say "    classpath entries: $($cp.Count) ($([math]::Round($cpString.Length / 1024, 1)) KiB)" 'Green'
if ($ShowClasspath) { Write-Host $cpString }
if (-not $cpString) { throw 'Empty classpath; refusing to compile.' }

# --------------------------------------------------------------------------- clean
if ($Clean) {
    foreach ($dir in @($classDir, $genDir)) {
        if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }
    }
}
foreach ($dir in @($classDir, $libsDir, $genDir)) {
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
}

# --------------------------------------------------------------------------- resources
function Expand-Placeholders([string] $text) {
    $evaluator = {
        param($match)
        $key = $match.Groups[1].Value
        if ($script:props.ContainsKey($key)) { $script:props[$key] } else { $match.Value }
    }
    return [regex]::Replace($text, '\$\{([A-Za-z0-9_.]+)\}', $evaluator)
}

# --------------------------------------------------------------------------- resource check
# javac cannot see JSON, so a missing blockstate variant or a model pointing at a texture that does
# not exist would compile cleanly and then render as an invisible block. Fail the build instead.
$validator = Join-Path $PSScriptRoot 'validate-resources.ps1'
if ((Test-Path -LiteralPath $validator) -and -not $SkipResources) {
    Say '==> Checking resources' 'Cyan'
    & powershell -NoProfile -ExecutionPolicy Bypass -File $validator
    if ($LASTEXITCODE -ne 0) { throw 'resource check failed; fix the reported problems before packaging' }
    Say '    resources OK' 'Green'
}

if (-not $SkipResources) {
    Say '==> Staging resources (expanding ${placeholders})' 'Cyan'
    $utf8 = New-Object Text.UTF8Encoding($false)
    foreach ($file in Get-ChildItem -LiteralPath $srcRes -Recurse -File) {
        $rel  = $file.FullName.Substring($srcRes.Length).TrimStart('\')
        $dest = Join-Path $genDir $rel
        $parent = Split-Path -Parent $dest
        if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
        if (@('.toml', '.json', '.mcmeta') -contains $file.Extension) {
            $text = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($file.FullName))
            [IO.File]::WriteAllText($dest, (Expand-Placeholders $text), $utf8)
        } else {
            Copy-Item -LiteralPath $file.FullName -Destination $dest -Force
        }
    }
    $staged = @(Get-ChildItem -LiteralPath $genDir -Recurse -File).Count
    Say "    resources: $staged files" 'Green'
}

# --------------------------------------------------------------------------- compile
Say '==> Compiling Java sources' 'Cyan'
$sources = @(Get-ChildItem -LiteralPath $srcJava -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue)
if ($sources.Count -eq 0) { throw "No Java sources under $srcJava" }
Say "    sources: $($sources.Count) files" 'Green'
$argFile  = Join-Path $buildDir 'javac-args.txt'
$utf8NoBom = New-Object Text.UTF8Encoding($false)
# javac's argfile parser treats a backslash inside a quoted token as an escape character, which
# silently destroys a Windows classpath. Forward slashes are accepted everywhere and are safe.
$cpArg = $cpString.Replace('\', '/')
$argLines = New-Object System.Collections.Generic.List[string]
$argLines.Add('-encoding')
$argLines.Add('UTF-8')
$argLines.Add('-proc:none')
$argLines.Add('-nowarn')
$argLines.Add('-Xlint:-options')
$argLines.Add('-source')
$argLines.Add('21')
$argLines.Add('-target')
$argLines.Add('21')
$argLines.Add('-d')
$argLines.Add('"' + $classDir.Replace('\', '/') + '"')
$argLines.Add('-cp')
$argLines.Add('"' + $cpArg + '"')
foreach ($source in $sources) { $argLines.Add('"' + $source.FullName.Replace('\', '/') + '"') }
[IO.File]::WriteAllLines($argFile, $argLines, $utf8NoBom)

$stopwatch = [Diagnostics.Stopwatch]::StartNew()
# Native stderr must be captured, not streamed: with ErrorActionPreference = Stop, PowerShell 5.1
# turns the *first* line a native tool writes to stderr into a terminating NativeCommandError, which
# would hide every diagnostic after the first one.
$javacLog = Join-Path $buildDir 'javac.log'
# Truncate explicitly: a pipeline that produces no output never reaches Set-Content, which would
# leave the previous run's diagnostics in place and make the error count a lie.
[IO.File]::WriteAllText($javacLog, '')
$savedPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
# -J options are only accepted on the command line, never inside an argfile. Forcing English keeps
# the diagnostics readable: javac otherwise localises them into the console code page.
& $javac '-J-Duser.language=en' '-J-Duser.country=US' "@$argFile" 2>&1 |
    ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $javacLog -Encoding UTF8
$exit = $LASTEXITCODE
$ErrorActionPreference = $savedPreference
$stopwatch.Stop()
if ($exit -ne 0) {
    Say '    javac diagnostics:' 'Red'
    foreach ($line in [IO.File]::ReadAllLines($javacLog)) { Write-Host "      $line" }
    throw "javac failed with exit code $exit; full log at $javacLog"
}
# @() around the pipeline: with no matches Select-String returns $null, and under StrictMode
# accessing .Count on $null throws instead of yielding 0.
$diagnostics = @(Select-String -Path $javacLog -Pattern 'error:' -ErrorAction SilentlyContinue).Count
Say ("    javac OK in {0:N1}s ({1} error lines)" -f $stopwatch.Elapsed.TotalSeconds, $diagnostics) 'Green'

if (-not $SkipResources) {
    Copy-Item -Path (Join-Path $genDir '*') -Destination $classDir -Recurse -Force
}

# --------------------------------------------------------------------------- package
Say '==> Packaging' 'Cyan'
$outJar = Join-Path $libsDir $jarName
if (Test-Path -LiteralPath $outJar) { Remove-Item -LiteralPath $outJar -Force }
& $jarExe --create --file $outJar -C $classDir .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }
$size = (Get-Item -LiteralPath $outJar).Length
Write-Host ''
Write-Host ("BUILD OK  ->  {0}  ({1:N1} KiB)" -f $outJar, ($size / 1KB)) -ForegroundColor Green

# --------------------------------------------------------------------------- deploy
# Copies the jar straight into the instance being played, so the edit/build/test loop does not need a
# manual step. Older copies are removed rather than left beside the new one: two versions of the same
# mod in mods\ is a confusing way to lose an afternoon.
if ($Deploy) {
    # Refuse to replace the jar while the game is running.
    #
    # This is not politeness, it is correctness. The game loads classes lazily out of the jar, and replacing
    # the file underneath it invalidates the archive the classloader is still reading from: classes already
    # loaded keep working, and any class not yet loaded then fails with NoClassDefFoundError even though it
    # is present in the jar. That crash points at innocent code and costs a debugging round - it has already
    # happened once. A running game holds its log file open, which makes a reliable liveness test.
    $lockProbe = Join-Path $verDir 'logs\latest.log'
    $gameRunning = $false
    if (Test-Path -LiteralPath $lockProbe) {
        try {
            $probe = [IO.File]::Open($lockProbe, 'Open', 'Write', 'None')
            $probe.Close()
        } catch {
            $gameRunning = $true
        }
    }
    if ($gameRunning) {
        Say '    REFUSING to deploy: the game is running.' 'Red'
        Say '    Replacing a mod jar under a running game breaks class loading for that session; classes not' 'Red'
        Say '    yet loaded then fail with NoClassDefFoundError, which looks exactly like a code bug.' 'Red'
        Say '    Close the game, then run with -Deploy again.' 'Yellow'
        throw 'deploy refused: the game is running'
    }
    $target = Join-Path $modsDir $jarName
    foreach ($stale in (Get-ChildItem -LiteralPath $modsDir -Filter "$modId-*.jar" -File -ErrorAction SilentlyContinue)) {
        if ($stale.FullName -ne $target) {
            Remove-Item -LiteralPath $stale.FullName -Force -ErrorAction SilentlyContinue
            Say "    removed stale $($stale.Name)" 'Yellow'
        }
    }
    try {
        Copy-Item -LiteralPath $outJar -Destination $target -Force
        Say "DEPLOYED -> $target" 'Green'
    } catch {
        Say "    could not deploy (is the game running and holding the file?): $($_.Exception.Message)" 'Red'
    }
    $logFile = Join-Path $verDir 'logs\latest.log'
    Say "    log: $logFile"
}
