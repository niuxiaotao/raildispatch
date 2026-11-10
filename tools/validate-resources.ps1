# Validates the mod's resources before packaging.
#
# javac cannot see JSON, so a missing blockstate variant or a model pointing at a texture that does
# not exist compiles perfectly and then shows up in game as an invisible block. This script catches
# that class of mistake:
#   1. every JSON file under src/main/resources parses;
#   2. every block registered in RDBlocks.java has a blockstate, and every blockstate variant key is
#      a legal combination of that block's properties - and the set of keys is COMPLETE, which is the
#      part that silently breaks rendering;
#   3. every model or texture id referenced from a blockstate, model or item model resolves to a file;
#   4. every registered item has an item model and a translation key in both language files.
#
# ASCII only: Windows PowerShell 5.1 decodes a BOM-less script as ANSI.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$resources = Join-Path $repoRoot 'src\main\resources'
$javaRoot = Join-Path $repoRoot 'src\main\java'
$assets = Join-Path $resources 'assets\raildispatch'
$namespace = 'raildispatch'

# Property domain per block, mirroring createBlockStateDefinition() in the block sources. Kept as an
# explicit table so that adding a property to a block without extending its blockstate is caught.
$blockProperties = @{
    'physics_cab'       = @{ 'facing' = @('north', 'east', 'south', 'west'); 'powered' = @('true', 'false') }
    'autopilot_console' = @{ 'facing' = @('north', 'east', 'south', 'west'); 'powered' = @('true', 'false') }
    'reverser'        = @{ 'facing' = @('north', 'east', 'south', 'west'); 'inverted' = @('true', 'false'); 'powered' = @('true', 'false') }
    'dispatch_center' = @{}
    'ballast_slab'    = @{ 'type' = @('bottom','top','double'); 'waterlogged' = @('true','false') }
    'ballast_stairs'  = @{ 'facing' = @('north','east','south','west'); 'half' = @('top','bottom'); 'shape' = @('straight','inner_left','inner_right','outer_left','outer_right'); 'waterlogged' = @('true','false') }
    'address_plate'   = @{}
}

$problems = New-Object System.Collections.Generic.List[string]
$checked = 0

# Strict mode throws on a missing property, and a model legitimately has no "textures" or "elements".
function Get-Property($object, [string] $name) {
    if ($null -eq $object) { return $null }
    $property = $object.PSObject.Properties[$name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Read-Json([string] $path) {
    $text = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($path))
    return ($text | ConvertFrom-Json)
}

# Blockstates of a block with no properties use the empty string as their single variant key, and
# Windows PowerShell 5.1's ConvertFrom-Json cannot build an object with an empty property name. So
# variant keys and model ids are read with a regex instead of being deserialised.
function Get-VariantKeys([string] $path) {
    $text = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($path))
    $keys = New-Object System.Collections.Generic.List[string]
    foreach ($match in [regex]::Matches($text, '"([^"]*)"\s*:\s*\{[^{}]*"model"')) { $keys.Add($match.Groups[1].Value) }
    return $keys
}

function Get-ModelIds([string] $path) {
    $text = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($path))
    $ids = New-Object System.Collections.Generic.List[string]
    foreach ($match in [regex]::Matches($text, '"model"\s*:\s*"([^"]+)"')) { $ids.Add($match.Groups[1].Value) }
    return $ids
}

# ------------------------------------------------------------------ 1. every JSON parses
# JavaScriptSerializer is used for the syntax check rather than ConvertFrom-Json, because a
# blockstate of a property-less block keys its single variant on the empty string and PowerShell 5.1's
# ConvertFrom-Json cannot build an object with an empty property name. That is a limitation of the
# cmdlet, not invalid JSON - and this check must not report it as one.
Add-Type -AssemblyName System.Web.Extensions
$jsonChecker = New-Object System.Web.Script.Serialization.JavaScriptSerializer
Get-ChildItem $resources -Recurse -Filter '*.json' -File | ForEach-Object {
    $checked++
    # Capture the path first: inside a catch block $_ is the ErrorRecord, not the pipeline object.
    $path = $_.FullName
    try {
        $text = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($path))
        $jsonChecker.DeserializeObject($text) | Out-Null
    } catch {
        $problems.Add("invalid JSON: $path - $($_.Exception.Message)")
    }
}

# A variant key is a set of "property=value" pairs, and Minecraft writes them sorted by property name.
function Normalize-VariantKey([string] $key) {
    if ($key -eq '') { return '' }
    return ((($key -split ',') | Sort-Object) -join ',')
}

# ------------------------------------------------------------------ registered names
function Get-RegisteredNames([string] $file, [string] $pattern) {
    $text = [IO.File]::ReadAllText((Join-Path $javaRoot $file))
    $names = New-Object System.Collections.Generic.List[string]
    foreach ($match in [regex]::Matches($text, $pattern)) { $names.Add($match.Groups[1].Value) }
    return $names
}

$blocks = Get-RegisteredNames 'com\raildispatch\registry\RDBlocks.java' 'register\("([a-z_]+)"'
$items = Get-RegisteredNames 'com\raildispatch\registry\RDItems.java' 'register(?:SimpleBlockItem)?\("([a-z_]+)"'
Write-Host ("blocks: {0}" -f ($blocks -join ', '))
Write-Host ("items : {0}" -f ($items -join ', '))

# ------------------------------------------------------------------ 2. blockstates are complete
foreach ($block in $blocks) {
    $statePath = Join-Path $assets "blockstates\$block.json"
    if (-not (Test-Path -LiteralPath $statePath)) {
        $problems.Add("missing blockstate: blockstates\$block.json")
        continue
    }
    $expected = @('')
    if ($blockProperties.ContainsKey($block)) {
        # Sorted by property name, because that is the order Minecraft uses in a variant key.
        foreach ($property in ($blockProperties[$block].Keys | Sort-Object)) {
            $next = @()
            foreach ($prefix in $expected) {
                foreach ($value in $blockProperties[$block][$property]) {
                    $next += if ($prefix -eq '') { "$property=$value" } else { "$prefix,$property=$value" }
                }
            }
            $expected = $next
        }
    }
    $expected = @($expected | ForEach-Object { Normalize-VariantKey $_ })

    $actual = @(Get-VariantKeys $statePath | ForEach-Object { Normalize-VariantKey $_ })
    foreach ($key in $expected) {
        if ($actual -notcontains $key) { $problems.Add("blockstate $block.json is missing the variant '$key'") }
    }
    foreach ($key in $actual) {
        if ($key -ne '' -and $expected -notcontains $key) {
            $problems.Add("blockstate $block.json has an unexpected variant '$key'")
        }
    }
    Write-Host ("  {0,-16} {1} variant(s), expected {2}" -f $block, $actual.Count, $expected.Count)
}

# ------------------------------------------------------------------ 3. model and texture references resolve
function Resolve-Reference([string] $id, [string] $kind) {
    if ($id -like '#*') { return }                       # texture variable, resolved by the parent
    if ($id -notlike "$namespace`:*") { return }          # vanilla or another mod's asset
    $relative = $id.Substring($namespace.Length + 1)
    $candidates = switch ($kind) {
        'model'   { @((Join-Path $assets "models\$relative.json")) }
        'texture' { @((Join-Path $assets "textures\$relative.png")) }
        default   { @() }
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) { return }
    }
    $script:problems.Add("unresolved $kind reference: $id")
}

Get-ChildItem (Join-Path $assets 'blockstates') -Recurse -Filter '*.json' -File | ForEach-Object {
    foreach ($id in (Get-ModelIds $_.FullName)) { Resolve-Reference $id 'model' }
}

Get-ChildItem (Join-Path $assets 'models') -Recurse -Filter '*.json' -File | ForEach-Object {
        $json = Read-Json $_.FullName
        $parent = Get-Property $json 'parent'
        if ($parent) { Resolve-Reference $parent 'model' }
        $textures = Get-Property $json 'textures'
        if ($textures) {
            foreach ($property in $textures.PSObject.Properties) { Resolve-Reference $property.Value 'texture' }
        }
        $elements = Get-Property $json 'elements'
        if ($elements) {
            foreach ($element in $elements) {
                foreach ($face in $element.faces.PSObject.Properties) { Resolve-Reference $face.Value.texture 'texture' }
            }
        }
    }

# ------------------------------------------------------------------ 4. item models and translations
$languages = @('en_us', 'zh_cn')
foreach ($item in $items) {
    if (-not (Test-Path -LiteralPath (Join-Path $assets "models\item\$item.json"))) {
        $problems.Add("missing item model: models\item\$item.json")
    }
    $key = if ($blocks -contains $item) { "block.$namespace.$item" } else { "item.$namespace.$item" }
    foreach ($language in $languages) {
        $lang = Read-Json (Join-Path $assets "lang\$language.json")
        if ($lang.PSObject.Properties.Name -notcontains $key) {
            $problems.Add("lang\$language.json has no translation for $key")
        }
    }
}

Write-Host ''
if ($problems.Count -gt 0) {
    Write-Host ("{0} problem(s):" -f $problems.Count) -ForegroundColor Red
    foreach ($problem in $problems) { Write-Host "  - $problem" -ForegroundColor Red }
    exit 1
}
Write-Host ("resource check OK ({0} JSON files)" -f $checked) -ForegroundColor Green
