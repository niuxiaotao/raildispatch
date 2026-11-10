# Generates the mod's 16x16 textures.
#
# The art is kept as reproducible code rather than as opaque binary blobs: anyone can see exactly
# what is drawn, tweak a colour, and regenerate. Run it after changing a palette:
#     powershell -ExecutionPolicy Bypass -File tools\make-textures.ps1
#
# ASCII only, deliberately: Windows PowerShell 5.1 decodes a BOM-less script as ANSI, so non-ASCII
# text here would corrupt the file path arguments.

Add-Type -AssemblyName System.Drawing

$repoRoot = Split-Path -Parent $PSScriptRoot
$blockDir = Join-Path $repoRoot 'src\main\resources\assets\raildispatch\textures\block'
$itemDir  = Join-Path $repoRoot 'src\main\resources\assets\raildispatch\textures\item'
foreach ($dir in @($blockDir, $itemDir)) {
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
}

function Colour([int] $r, [int] $g, [int] $b) {
    return [System.Drawing.Color]::FromArgb(255, $r, $g, $b)
}

function Get-Pixel([string] $name, [int] $x, [int] $y) {
    $edge = ($x -eq 0 -or $y -eq 0 -or $x -eq 15 -or $y -eq 15)

    switch ($name) {
        'physics_cab' {
            if ($edge) { return (Colour 26 30 36) }
            if ($y -ge 4 -and $y -le 6 -and $x -ge 2 -and $x -le 13) { return (Colour 76 155 196) }
            if ($y -ge 10 -and $y -le 11 -and ($x -eq 3 -or $x -eq 5)) { return (Colour 200 200 200) }
            if ($x -eq 12 -and $y -ge 10 -and $y -le 11) { return (Colour 96 96 96) }
            return (Colour 46 52 64)
        }
        'physics_cab_on' {
            if ($edge) { return (Colour 26 30 36) }
            if ($y -ge 4 -and $y -le 6 -and $x -ge 2 -and $x -le 13) { return (Colour 111 207 74) }
            if ($y -ge 10 -and $y -le 11 -and ($x -eq 3 -or $x -eq 5)) { return (Colour 220 220 220) }
            if ($x -eq 12 -and $y -ge 10 -and $y -le 11) { return (Colour 124 252 90) }
            return (Colour 46 52 64)
        }
        'reverser' {
            if ($edge) { return (Colour 122 63 16) }
            $reach = 7 - [math]::Abs($y - 7)
            if ($x -ge 6 -and $x -le (6 + $reach)) { return (Colour 240 240 240) }
            if ($x -eq 5 -and $y -ge 4 -and $y -le 10) { return (Colour 60 32 8) }
            return (Colour 193 101 26)
        }
        'reverser_on' {
            if ($edge) { return (Colour 122 63 16) }
            $reach = 7 - [math]::Abs($y - 7)
            if ($x -ge 6 -and $x -le (6 + $reach)) { return (Colour 124 252 90) }
            if ($x -eq 5 -and $y -ge 4 -and $y -le 10) { return (Colour 60 32 8) }
            return (Colour 193 101 26)
        }
        'autopilot_console' {
            if ($edge) { return (Colour 18 22 28) }
            # A keypad: dark keys on a charcoal panel.
            $keyColumn = ($x -eq 2 -or $x -eq 5 -or $x -eq 8 -or $x -eq 11 -or $x -eq 14)
            $keyRow = ($y -eq 5 -or $y -eq 8 -or $y -eq 11 -or $y -eq 14)
            if ($keyColumn -and $keyRow) { return (Colour 74 82 96) }
            return (Colour 40 46 56)
        }
        'autopilot_console_front' {
            if ($edge) { return (Colour 18 22 28) }
            # A dark display, a dim amber "auto" lamp and three control keys.
            if ($y -ge 2 -and $y -le 5 -and $x -ge 2 -and $x -le 13) { return (Colour 38 58 72) }
            if ($y -eq 8 -and $x -ge 2 -and $x -le 13) { return (Colour 120 96 28) }
            if ($y -ge 10 -and $y -le 13 -and ($x -ge 2 -and $x -le 4)) { return (Colour 132 44 44) }
            if ($y -ge 10 -and $y -le 13 -and ($x -ge 6 -and $x -le 8)) { return (Colour 44 112 52) }
            if ($y -ge 10 -and $y -le 13 -and ($x -ge 10 -and $x -le 12)) { return (Colour 64 64 72) }
            return (Colour 34 40 50)
        }
        'autopilot_console_on' {
            if ($edge) { return (Colour 18 22 28) }
            $keyColumn = ($x -eq 2 -or $x -eq 5 -or $x -eq 8 -or $x -eq 11 -or $x -eq 14)
            $keyRow = ($y -eq 5 -or $y -eq 8 -or $y -eq 11 -or $y -eq 14)
            if ($keyColumn -and $keyRow) { return (Colour 76 150 120) }
            return (Colour 40 48 58)
        }
        'autopilot_console_front_on' {
            if ($edge) { return (Colour 18 22 28) }
            if ($y -ge 2 -and $y -le 5 -and $x -ge 2 -and $x -le 13) { return (Colour 92 208 232) }
            if ($y -eq 8 -and $x -ge 2 -and $x -le 13) { return (Colour 190 152 44) }
            if ($y -ge 10 -and $y -le 13 -and ($x -ge 2 -and $x -le 4)) { return (Colour 150 52 52) }
            if ($y -ge 10 -and $y -le 13 -and ($x -ge 6 -and $x -le 8)) { return (Colour 90 220 110) }
            if ($y -ge 10 -and $y -le 13 -and ($x -ge 10 -and $x -le 12)) { return (Colour 72 72 82) }
            return (Colour 34 42 52)
        }
        'dispatch_center' {
            if ($edge) { return (Colour 58 58 58) }
            if (($x -eq 3 -or $x -eq 7 -or $x -eq 11) -and ($y -eq 3 -or $y -eq 7 -or $y -eq 11)) {
                return (Colour 40 40 40)
            }
            if (($x -eq 3 -or $x -eq 7 -or $x -eq 11) -and ($y -eq 5 -or $y -eq 9)) {
                return (Colour 150 150 150)
            }
            return (Colour 110 110 110)
        }
        'address_plate' {
            if ($edge) { return (Colour 138 110 16) }
            if ($y -ge 6 -and $y -le 9 -and $x -ge 2 -and $x -le 13) { return (Colour 58 46 8) }
            return (Colour 217 176 32)
        }
        'vehicle_tag' {
            if ($x -eq 0 -or $y -eq 0 -or $x -eq 15 -or $y -eq 15) { return (Colour 168 156 128) }
            if ($x -ge 7 -and $x -le 8 -and $y -ge 1 -and $y -le 2) { return (Colour 60 60 60) }
            # A "V" for vehicle.
            if ($y -ge 5 -and $y -le 11 -and ($x -eq (4 + ($y - 5)) -or $x -eq (11 - ($y - 5)))) {
                return (Colour 70 70 70)
            }
            return (Colour 232 226 208)
        }
        'address_tag' {
            if ($x -eq 0 -or $y -eq 0 -or $x -eq 15 -or $y -eq 15) { return (Colour 154 124 20) }
            if ($x -ge 7 -and $x -le 8 -and $y -ge 1 -and $y -le 2) { return (Colour 60 60 60) }
            # A "#" for a destination code. Split into two booleans because PowerShell does not
            # continue a line that starts with a binary operator.
            $verticals = ($x -eq 5 -or $x -eq 10) -and $y -ge 4 -and $y -le 11
            $horizontals = ($y -eq 6 -or $y -eq 9) -and $x -ge 4 -and $x -le 11
            if ($verticals -or $horizontals) { return (Colour 70 60 10) }
            return (Colour 224 194 74)
        }
        default { return (Colour 255 0 255) }
    }
}

function New-Texture([string] $path, [string] $name) {
    $bitmap = New-Object System.Drawing.Bitmap 16, 16
    for ($y = 0; $y -lt 16; $y++) {
        for ($x = 0; $x -lt 16; $x++) {
            $bitmap.SetPixel($x, $y, (Get-Pixel $name $x $y))
        }
    }
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    Write-Host ("  {0,-18} -> {1}" -f $name, (Split-Path $path -Leaf))
}

Write-Host 'Generating block textures'
foreach ($name in @('physics_cab', 'physics_cab_on', 'autopilot_console', 'autopilot_console_front',
                    'autopilot_console_on', 'autopilot_console_front_on',
                    'reverser', 'reverser_on', 'dispatch_center', 'address_plate')) {
    New-Texture (Join-Path $blockDir ($name + '.png')) $name
}

Write-Host 'Generating item textures'
foreach ($name in @('vehicle_tag', 'address_tag')) {
    New-Texture (Join-Path $itemDir ($name + '.png')) $name
}

Write-Host 'Done.'
