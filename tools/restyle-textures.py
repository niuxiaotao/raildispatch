"""Generate all 16x16/32x32 block and item textures and multi-element block models for Rail Dispatch.

Visual style:
- Brushed dark industrial iron casing matching Create's industrial_iron_block palette
  (#28292c .. #8e9296, dark outer rim, inner bevelled frame, vertical/horizontal corrugated plate,
  recessed matte-black grille/screen wells).
- Sky-blue CRN terminal display (#1c7cb2 .. #58aee8) with crisp white/crimson CRN badge (#e8eced / #a81216)
  and tilted isometric tablet sprite for the hand-held yard terminal.
- Multi-element 3D block models (sloped operator console desk, hooded trackside dispatch cabinet,
  twin-jaw rail retarders, sensor-equipped smart retarder, levered automatic uncoupler post).
"""
from PIL import Image
import json
import os
import random

ROOT = r"D:\DS_WORKSPACE\raildispatch\src\main\resources\assets\raildispatch"
BLOCK_TEX = os.path.join(ROOT, "textures", "block")
ITEM_TEX = os.path.join(ROOT, "textures", "item")
BLOCK_MOD = os.path.join(ROOT, "models", "block")

os.makedirs(BLOCK_TEX, exist_ok=True)
os.makedirs(ITEM_TEX, exist_ok=True)
os.makedirs(BLOCK_MOD, exist_ok=True)

random.seed(20261021)

P = {
    "clear":   (0, 0, 0, 0),
    # Industrial dark iron ramp (matched to Create industrial_iron + user's grey/black block ref)
    "rim":     (28, 29, 31, 255),
    "well_0":  (14, 15, 16, 255),
    "well_1":  (20, 21, 23, 255),
    "well_2":  (27, 29, 31, 255),
    "iron_0":  (43, 44, 47, 255),
    "iron_1":  (54, 56, 59, 255),
    "iron_2":  (67, 70, 73, 255),
    "iron_3":  (81, 85, 88, 255),
    "iron_4":  (98, 103, 106, 255),
    "iron_5":  (118, 123, 126, 255),
    "iron_hi": (142, 147, 150, 255),
    # CRN blue display & badge
    "scr_off": (13, 30, 44, 255),
    "scr_dim": (19, 58, 86, 255),
    "scr_mid": (28, 124, 178, 255),
    "scr_bri": (56, 156, 212, 255),
    "scr_hi":  (108, 194, 240, 255),
    "wht":     (236, 240, 242, 255),
    "wht_d":   (194, 200, 204, 255),
    "crn_red": (172, 20, 24, 255),
    "crn_dk":  (116, 12, 15, 255),
    "crn_hi":  (222, 54, 52, 255),
    # Functional accents (indicators, brake shoes, uncoupler latch)
    "amb":     (214, 152, 46, 255),
    "amb_d":   (132, 88, 24, 255),
    "amb_hi":  (246, 202, 102, 255),
    "grn":     (46, 168, 84, 255),
    "grn_hi":  (112, 226, 138, 255),
    "grn_d":   (20, 78, 40, 255),
}


def make_grid(w=16, h=16, bg="iron_1"):
    return [[P[bg] for _ in range(w)] for _ in range(h)]


def px(im, x, y, c):
    h = len(im)
    w = len(im[0])
    if 0 <= x < w and 0 <= y < h:
        im[y][x] = P[c] if isinstance(c, str) else c


def rect(im, x0, y0, x1, y1, c):
    h = len(im)
    w = len(im[0])
    col = P[c] if isinstance(c, str) else c
    for y in range(max(0, y0), min(h - 1, y1) + 1):
        for x in range(max(0, x0), min(w - 1, x1) + 1):
            im[y][x] = col


def frame(im, x0, y0, x1, y1, top_left, bot_right):
    for x in range(x0, x1 + 1):
        px(im, x, y0, top_left)
        px(im, x, y1, bot_right)
    for y in range(y0 + 1, y1):
        px(im, x0, y, top_left)
        px(im, x1, y, bot_right)


def save_grid(im, path):
    h = len(im)
    w = len(im[0])
    img = Image.new("RGBA", (w, h))
    flat = [im[y][x] for y in range(h) for x in range(w)]
    img.putdata(flat)
    img.save(path)


def industrial_plate(vertical=True):
    """16x16 industrial iron plate matching the grey/black cube reference."""
    im = make_grid(16, 16, "iron_2")
    # Outer dark seam + bevelled inner border
    frame(im, 0, 0, 15, 15, "iron_0", "rim")
    frame(im, 1, 1, 14, 14, "iron_4", "iron_1")
    frame(im, 2, 2, 13, 13, "iron_1", "iron_3")
    # Brushed corrugated interior (3..12)
    tones = ["iron_2", "iron_3", "iron_4", "iron_3"]
    for i in range(3, 13):
        base = tones[(i - 3) % len(tones)]
        for j in range(3, 13):
            x, y = (i, j) if vertical else (j, i)
            c = base
            # subtle deterministic grain
            hval = ((x * 37 + y * 17) ^ (x * y)) & 7
            if hval == 0 and base != "iron_4":
                c = "iron_4"
            elif hval == 3 and base != "iron_2":
                c = "iron_2"
            px(im, x, y, c)
    # Corner rivets at (1,1), (14,1), (1,14), (14,14) and inner bolts
    for rx, ry in ((1, 1), (14, 1), (1, 14), (14, 14)):
        px(im, rx, ry, "iron_hi" if ry == 1 else "iron_4")
    for rx, ry in ((3, 3), (12, 3), (3, 12), (12, 12)):
        px(im, rx, ry, "iron_5")
    return im


def matte_grille_face():
    """16x16 dark carbon/mesh inset inside an industrial iron frame (right face of reference cube)."""
    im = industrial_plate(vertical=True)
    frame(im, 2, 2, 13, 13, "rim", "iron_3")
    for y in range(3, 13):
        for x in range(3, 13):
            d = (x + y) % 3
            c = "well_2" if d == 0 else ("well_1" if d == 1 else "well_0")
            px(im, x, y, c)
    return im


def draw_crn_badge(im, x0, y0, w=8, h=5):
    """Crisp white-top / crimson-bottom CRN emblem inside a blue screen."""
    top_h = max(2, h // 2 + (h % 2))
    rect(im, x0, y0, x0 + w - 1, y0 + top_h - 1, "wht")
    rect(im, x0, y0 + top_h, x0 + w - 1, y0 + h - 1, "crn_red")
    # Rounded corner feel via subtle shading
    px(im, x0, y0, "wht_d")
    px(im, x0 + w - 1, y0, "wht_d")
    px(im, x0, y0 + h - 1, "crn_dk")
    px(im, x0 + w - 1, y0 + h - 1, "crn_dk")
    # Red micro-glyphs for C R N in white band + white train front in red band
    if w >= 8 and h >= 5:
        # C
        px(im, x0 + 1, y0 + 1, "crn_red")
        px(im, x0 + 2, y0 + 1, "crn_red")
        px(im, x0 + 1, y0 + 2, "crn_red")
        # R
        px(im, x0 + 4, y0 + 1, "crn_red")
        px(im, x0 + 4, y0 + 2, "crn_red")
        # N
        px(im, x0 + 6, y0 + 1, "crn_red")
        px(im, x0 + 6, y0 + 2, "crn_red")
        # Train nose icon in lower red half
        px(im, x0 + 3, y0 + 3, "wht")
        px(im, x0 + 4, y0 + 3, "wht")
        px(im, x0 + 3, y0 + 4, "wht_d")
        px(im, x0 + 4, y0 + 4, "wht_d")


def console_screen_face(powered=False, wide_hud=False):
    """32x32 high-detail display face so the CRN emblem & blue glass screen read cleanly."""
    im = make_grid(32, 32, "iron_2")
    # Outer 2px industrial iron frame matching 16x16 casing scale
    frame(im, 0, 0, 31, 31, "iron_0", "rim")
    frame(im, 1, 1, 30, 30, "iron_0", "rim")
    frame(im, 2, 2, 29, 29, "iron_4", "iron_1")
    frame(im, 3, 3, 28, 28, "iron_4", "iron_1")
    # Corner rivets (2x2)
    for rx, ry in ((2, 2), (28, 2), (2, 28), (28, 28)):
        rect(im, rx, ry, rx + 1, ry + 1, "iron_hi" if ry == 2 else "iron_4")
    # Recessed matte bezel (x=4..27, y=4..27)
    frame(im, 4, 4, 27, 27, "rim", "iron_3")
    rect(im, 5, 5, 26, 26, "well_0")

    # Screen glass area (x=6..25, y=6..19)
    bg_main = "scr_mid" if powered else "scr_off"
    bg_top = "scr_hi" if powered else "scr_dim"
    bg_bot = "scr_dim" if powered else "well_1"
    rect(im, 6, 6, 25, 19, bg_main)
    rect(im, 6, 6, 25, 6, bg_top)
    rect(im, 6, 19, 25, 19, bg_bot)

    if wide_hud:
        # Track interlocking diagram: main line + diverging siding + signal aspects
        rect(im, 8, 10, 23, 11, "wht")
        px(im, 13, 12, "scr_hi")
        px(im, 14, 13, "scr_hi")
        px(im, 15, 14, "scr_hi")
        rect(im, 16, 14, 23, 15, "grn_hi")
        rect(im, 8, 10, 9, 11, "grn_hi")
        rect(im, 22, 10, 23, 11, "amb_hi")
        rect(im, 8, 16, 12, 16, "scr_hi")
    else:
        # Crisp centered CRN rounded app badge inside plenty of sky-blue margin (x=10..21, y=8..17)
        bx0, by0, bw, bh = 10, 8, 12, 10
        rect(im, bx0, by0, bx0 + bw - 1, by0 + 4, "wht")
        rect(im, bx0, by0 + 5, bx0 + bw - 1, by0 + bh - 1, "crn_red")
        # Rounded corners on badge (cut 4 outer corners back to screen blue)
        for cx_c, cy_c in ((bx0, by0), (bx0 + bw - 1, by0),
                           (bx0, by0 + bh - 1), (bx0 + bw - 1, by0 + bh - 1)):
            px(im, cx_c, cy_c, bg_main)
        # Crisp 3px-tall "C R N" letters in crimson on white upper half (y=9..11)
        # C at x=11..13
        rect(im, 11, 9, 13, 9, "crn_red")
        px(im, 11, 10, "crn_red")
        rect(im, 11, 11, 13, 11, "crn_red")
        # R at x=15..17
        rect(im, 15, 9, 16, 9, "crn_red")
        px(im, 15, 10, "crn_red"); px(im, 17, 10, "crn_red")
        px(im, 15, 11, "crn_red"); px(im, 16, 11, "crn_red"); px(im, 17, 11, "crn_red")
        px(im, 16, 10, "crn_red"); px(im, 16, 11, "wht")
        # N at x=19..20 (3 cols 18..20 -> shift C=11..12, R=14..16, N=18..20)
        rect(im, 11, 9, 20, 11, "wht")  # clear letter band cleanly
        # C (11..12)
        px(im, 11, 9, "crn_red"); px(im, 12, 9, "crn_red")
        px(im, 11, 10, "crn_red")
        px(im, 11, 11, "crn_red"); px(im, 12, 11, "crn_red")
        # R (14..16)
        px(im, 14, 9, "crn_red"); px(im, 15, 9, "crn_red")
        px(im, 14, 10, "crn_red"); px(im, 15, 10, "crn_red"); px(im, 16, 9, "crn_red")
        px(im, 14, 11, "crn_red"); px(im, 16, 11, "crn_red")
        # N (18..20)
        for yy in (9, 10, 11):
            px(im, 18, yy, "crn_red")
            px(im, 20, yy, "crn_red")
        px(im, 19, 9, "crn_red"); px(im, 19, 10, "crn_red")
        # White high-speed train nose icon in red lower half (x=14..17, y=14..16)
        rect(im, 14, 14, 17, 16, "wht")
        px(im, 15, 14, "crn_dk"); px(im, 16, 14, "crn_dk")  # windshield
        px(im, 14, 16, "wht_d"); px(im, 17, 16, "wht_d")    # headlights

        if not powered:
            for y in range(6, 20):
                for x in range(6, 26):
                    r, g, b, a = im[y][x]
                    im[y][x] = (int(r * 0.48), int(g * 0.56), int(b * 0.66), a)

    # Lower control strip (y=21..25)
    rect(im, 6, 20, 25, 20, "iron_1")
    rect(im, 6, 21, 25, 25, "well_1")
    for bx in (8, 12, 16):
        rect(im, bx, 22, bx + 2, 24, "iron_4" if powered else "iron_3")
        rect(im, bx, 22, bx + 2, 22, "iron_hi" if powered else "iron_4")
    # Status lamp
    lamp = "grn_hi" if powered else "crn_red"
    rect(im, 22, 22, 23, 24, lamp)
    return im


def console_desk_top():
    """Top plate with recessed louver vents and tactile throttle track."""
    im = industrial_plate(vertical=False)
    # Recessed vent grille on upper half (y=3..6)
    rect(im, 3, 3, 12, 6, "well_1")
    for y in (3, 5):
        rect(im, 4, y, 11, y, "well_0")
        rect(im, 4, y + 1, 11, y + 1, "iron_2")
    # Control keypad pad on lower half (y=8..12)
    frame(im, 3, 8, 12, 12, "iron_0", "iron_4")
    rect(im, 4, 9, 11, 11, "well_2")
    for x in (5, 7, 9):
        px(im, x, 10, "iron_5")
    px(im, 11, 10, "scr_bri")
    return im


def retarder_top_tex(smart=False):
    """Dark industrial sleeper plate with clamping rail jaws and status strip."""
    im = industrial_plate(vertical=True)
    # Central rail channel
    rect(im, 2, 2, 13, 13, "well_1")
    # Twin heavy clamping brake beams
    for x0, x1 in ((2, 5), (10, 13)):
        rect(im, x0, 1, x1, 14, "iron_1")
        rect(im, x0 + 1, 1, x1 - 1, 14, "iron_3")
        for y in (2, 6, 9, 13):
            px(im, x0 + 1, y, "iron_hi")
            px(im, x1 - 1, y, "iron_0")
    # Inner friction shoes + centre sensor/actuator core
    accent_hi = "scr_hi" if smart else "amb_hi"
    accent = "scr_mid" if smart else "amb"
    accent_d = "scr_dim" if smart else "amb_d"
    rect(im, 5, 2, 5, 13, accent)
    rect(im, 10, 2, 10, 13, accent)
    rect(im, 7, 3, 8, 12, "well_0")
    for y in (4, 7, 10):
        rect(im, 7, y, 8, y + 1, accent_d)
        px(im, 7, y, accent_hi)
    return im


def retarder_side_tex(smart=False):
    im = industrial_plate(vertical=False)
    accent = "scr_mid" if smart else "amb"
    accent_hi = "scr_hi" if smart else "amb_hi"
    rect(im, 2, 11, 13, 14, "well_1")
    for x in range(3, 13, 3):
        rect(im, x, 12, x + 1, 13, accent)
        px(im, x, 12, accent_hi)
    return im


def uncoupler_side_tex(front=False):
    im = industrial_plate(vertical=True)
    rect(im, 4, 3, 11, 13, "well_1")
    frame(im, 4, 3, 11, 13, "rim", "iron_3")
    if front:
        # CRN indicator + yellow/red release actuator bar
        rect(im, 5, 4, 10, 6, "scr_mid")
        rect(im, 5, 4, 10, 4, "scr_hi")
        rect(im, 6, 5, 9, 5, "wht")
        rect(im, 5, 8, 10, 10, "amb")
        rect(im, 5, 8, 10, 8, "amb_hi")
        rect(im, 5, 10, 10, 10, "amb_d")
        px(im, 6, 9, "well_0")
        px(im, 8, 9, "well_0")
        px(im, 10, 9, "well_0")
    else:
        for y in (5, 7, 9, 11):
            rect(im, 5, y, 10, y, "well_0")
            rect(im, 5, y + 1, 10, y + 1, "iron_2")
    return im


def uncoupler_top_tex():
    im = industrial_plate(vertical=False)
    frame(im, 4, 4, 11, 11, "iron_4", "rim")
    rect(im, 5, 5, 10, 10, "well_1")
    rect(im, 6, 6, 9, 9, "amb")
    px(im, 6, 6, "amb_hi")
    px(im, 7, 6, "amb_hi")
    return im


def address_plate_tex():
    im = industrial_plate(vertical=False)
    frame(im, 2, 4, 13, 11, "rim", "iron_4")
    rect(im, 3, 5, 12, 10, "scr_dim")
    rect(im, 4, 6, 11, 7, "wht")
    rect(im, 4, 8, 8, 9, "amb_hi")
    return im


def make_handheld_terminal_32():
    """32x32 tilted isometric tablet sprite matching the user's CRN tablet reference."""
    W = 32
    im = make_grid(W, W, "clear")
    cx, cy = 15.5, 15.8
    for py in range(W):
        for p_x in range(W):
            best = None
            for dz in (0.0, 1.0, 2.0, 2.8):
                dx = p_x - cx
                dy = (py - dz) - cy
                # Isometric tablet tilted ~32 deg clockwise like reference
                u = dx * 0.76 + dy * 0.92
                v = -dx * 0.64 + dy * 0.98
                if abs(u) <= 9.8 and abs(v) <= 13.0:
                    if abs(u) + abs(v) > 21.0:
                        continue
                    best = (dz, u, v)
                    break
            if best is None:
                continue
            dz, u, v = best
            if dz > 0.0:
                px(im, p_x, py, "well_0" if dz >= 1.8 else "well_2")
                continue
            au, av = abs(u), abs(v)
            if au > 8.4 or av > 11.4 or (au + av > 18.8):
                c = "iron_2" if (u + v < -4.5) else ("well_2" if (u + v < 6.5) else "well_0")
                px(im, p_x, py, c)
            elif av > 9.8:
                c = "iron_1" if v < 0 else "well_1"
                px(im, p_x, py, c)
            else:
                # Active sky-blue screen with central rounded CRN app icon
                if au <= 4.6 and av <= 4.7 and (au + av <= 8.1):
                    if v < -0.15:
                        c = "wht"
                        if -3.5 <= v <= -0.9 and -3.5 <= u <= 3.5:
                            iu = int(round((u + 3.5) / 7.0 * 8))
                            iv = int(round((v + 3.5) / 2.6 * 2))
                            is_c = (iu == 0 and 0 <= iv <= 2) or (iu == 1 and iv in (0, 2))
                            is_r = (iu == 3 and 0 <= iv <= 2) or (iu == 4 and iv in (0, 1)) or (iu == 5 and iv in (0, 2))
                            is_n = (iu in (6, 8) and 0 <= iv <= 2) or (iu == 7 and iv == 1)
                            if is_c or is_r or is_n:
                                c = "crn_red"
                    else:
                        c = "crn_red" if (u < 2.4) else "crn_dk"
                        if au <= 1.8 and 1.0 <= v <= 3.4:
                            if 1.3 <= v <= 2.2 and au <= 1.1:
                                c = "crn_dk"
                            else:
                                c = "wht"
                    px(im, p_x, py, c)
                else:
                    sheen = u * 0.5 + v * 0.7
                    c = "scr_hi" if sheen < -7.5 else ("scr_bri" if sheen < -2.0 else "scr_mid")
                    if au > 7.6 or av > 9.0:
                        c = "scr_dim"
                    px(im, p_x, py, c)
    return im


def make_tag_item(is_vehicle=True):
    """16x16 industrial data cartridge / transponder tag in the same dark iron + CRN palette."""
    im = make_grid(16, 16, "clear")
    rect(im, 3, 2, 12, 14, "well_0")
    rect(im, 4, 3, 11, 13, "iron_1")
    frame(im, 4, 3, 11, 13, "iron_4", "well_2")
    # Hanging clip / connector pins at top
    rect(im, 6, 1, 9, 2, "iron_4")
    px(im, 7, 2, "well_0")
    px(im, 8, 2, "well_0")
    # Colour header band (CRN blue for vehicle tag, crimson/amber for destination address tag)
    if is_vehicle:
        rect(im, 5, 4, 10, 7, "scr_mid")
        rect(im, 5, 4, 10, 4, "scr_hi")
        rect(im, 6, 5, 9, 6, "wht")
        px(im, 7, 5, "crn_red")
        px(im, 8, 5, "crn_red")
    else:
        rect(im, 5, 4, 10, 7, "crn_red")
        rect(im, 5, 4, 10, 4, "crn_hi")
        rect(im, 6, 5, 9, 6, "wht")
        px(im, 7, 5, "well_0")
    # Data stripes at bottom
    rect(im, 5, 9, 10, 9, "iron_4")
    rect(im, 5, 11, 8, 11, "iron_3")
    px(im, 10, 11, "grn_hi" if is_vehicle else "amb_hi")
    return im


# --------------------------------------------------------------------------- write textures
plate_v = industrial_plate(vertical=True)
plate_h = industrial_plate(vertical=False)
grille = matte_grille_face()

# Autopilot console
save_grid(plate_v, os.path.join(BLOCK_TEX, "autopilot_console.png"))
save_grid(grille, os.path.join(BLOCK_TEX, "autopilot_console_on.png"))
save_grid(console_desk_top(), os.path.join(BLOCK_TEX, "autopilot_console_top.png"))
save_grid(console_screen_face(powered=False, wide_hud=False), os.path.join(BLOCK_TEX, "autopilot_console_front.png"))
save_grid(console_screen_face(powered=True, wide_hud=False), os.path.join(BLOCK_TEX, "autopilot_console_front_on.png"))

# Legacy physics cab (kept visually unified for worlds that placed one)
save_grid(plate_v, os.path.join(BLOCK_TEX, "physics_cab.png"))
save_grid(plate_v, os.path.join(BLOCK_TEX, "physics_cab_on.png"))
save_grid(console_desk_top(), os.path.join(BLOCK_TEX, "physics_cab_top.png"))
save_grid(console_screen_face(powered=False, wide_hud=False), os.path.join(BLOCK_TEX, "physics_cab_front.png"))
save_grid(console_screen_face(powered=True, wide_hud=False), os.path.join(BLOCK_TEX, "physics_cab_front_on.png"))

# Dispatch center
save_grid(plate_v, os.path.join(BLOCK_TEX, "dispatch_center.png"))
save_grid(plate_v, os.path.join(BLOCK_TEX, "dispatch_center_side.png"))
save_grid(plate_h, os.path.join(BLOCK_TEX, "dispatch_center_top.png"))
save_grid(console_screen_face(powered=True, wide_hud=True), os.path.join(BLOCK_TEX, "dispatch_center_front.png"))

# Legacy yard terminal block + active handheld yard terminal item
save_grid(plate_v, os.path.join(BLOCK_TEX, "yard_terminal_side.png"))
save_grid(console_desk_top(), os.path.join(BLOCK_TEX, "yard_terminal_top.png"))
save_grid(console_screen_face(powered=True, wide_hud=False), os.path.join(BLOCK_TEX, "yard_terminal_front.png"))
save_grid(make_handheld_terminal_32(), os.path.join(ITEM_TEX, "yard_terminal.png"))

# Trackside yard devices & tags
save_grid(retarder_top_tex(smart=False), os.path.join(BLOCK_TEX, "retarder_top.png"))
save_grid(retarder_side_tex(smart=False), os.path.join(BLOCK_TEX, "retarder_side.png"))
save_grid(retarder_top_tex(smart=True), os.path.join(BLOCK_TEX, "smart_retarder_top.png"))
save_grid(retarder_side_tex(smart=True), os.path.join(BLOCK_TEX, "smart_retarder_side.png"))

save_grid(uncoupler_side_tex(front=False), os.path.join(BLOCK_TEX, "automatic_uncoupler_side.png"))
save_grid(uncoupler_side_tex(front=True), os.path.join(BLOCK_TEX, "automatic_uncoupler_front.png"))
save_grid(uncoupler_top_tex(), os.path.join(BLOCK_TEX, "automatic_uncoupler_top.png"))

save_grid(address_plate_tex(), os.path.join(BLOCK_TEX, "address_plate.png"))
save_grid(grille, os.path.join(BLOCK_TEX, "reverser.png"))
save_grid(grille, os.path.join(BLOCK_TEX, "reverser_on.png"))

save_grid(make_tag_item(is_vehicle=True), os.path.join(ITEM_TEX, "vehicle_tag.png"))
save_grid(make_tag_item(is_vehicle=False), os.path.join(ITEM_TEX, "address_tag.png"))


# --------------------------------------------------------------------------- write 3D block models
def write_json(path, obj):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, indent=2)
        fh.write("\n")


DISPLAY_BLOCK = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
}


def console_model(front_tex, side_tex="raildispatch:block/autopilot_console",
                  top_tex="raildispatch:block/autopilot_console_top",
                  grille_tex="raildispatch:block/autopilot_console_on"):
    """3D cab control stand: heavy pedestal, front operator desk shelf, tilted 22.5-deg display head, rear vent grille."""
    return {
        "parent": "minecraft:block/block",
        "display": DISPLAY_BLOCK,
        "textures": {
            "particle": side_tex,
            "side": side_tex,
            "top": top_tex,
            "front": front_tex,
            "grille": grille_tex,
        },
        "elements": [
            # Base pedestal (full footprint up to y=9, stepped front desk)
            {
                "from": [0, 0, 0],
                "to": [16, 8, 16],
                "faces": {
                    "down":  {"uv": [0, 0, 16, 16], "texture": "#side", "cullface": "down"},
                    "up":    {"uv": [0, 0, 16, 16], "texture": "#top"},
                    "north": {"uv": [0, 8, 16, 16], "texture": "#side"},
                    "south": {"uv": [0, 8, 16, 16], "texture": "#grille"},
                    "west":  {"uv": [0, 8, 16, 16], "texture": "#side"},
                    "east":  {"uv": [0, 8, 16, 16], "texture": "#side"},
                },
            },
            # Rear upper equipment housing (z=5..16, y=8..16)
            {
                "from": [0, 8, 5],
                "to": [16, 16, 16],
                "faces": {
                    "up":    {"uv": [0, 5, 16, 16], "texture": "#top", "cullface": "up"},
                    "north": {"uv": [0, 0, 16, 10], "texture": "#front"},
                    "south": {"uv": [0, 0, 16, 8], "texture": "#grille", "cullface": "south"},
                    "west":  {"uv": [5, 0, 16, 8], "texture": "#side"},
                    "east":  {"uv": [0, 0, 11, 8], "texture": "#side"},
                },
            },
            # Side cheek wings framing the operator screen
            {
                "from": [0, 8, 2],
                "to": [2, 15, 5],
                "faces": {
                    "up":    {"uv": [0, 2, 2, 5], "texture": "#top"},
                    "north": {"uv": [14, 1, 16, 8], "texture": "#side"},
                    "west":  {"uv": [2, 1, 5, 8], "texture": "#side"},
                    "east":  {"uv": [11, 1, 14, 8], "texture": "#side"},
                },
            },
            {
                "from": [14, 8, 2],
                "to": [16, 15, 5],
                "faces": {
                    "up":    {"uv": [14, 2, 16, 5], "texture": "#top"},
                    "north": {"uv": [0, 1, 2, 8], "texture": "#side"},
                    "west":  {"uv": [2, 1, 5, 8], "texture": "#side"},
                    "east":  {"uv": [11, 1, 14, 8], "texture": "#side"},
                },
            },
            # Angled CRN monitor bezel (tilted -22.5 deg towards operator on north side)
            {
                "from": [2, 7.5, 3.2],
                "to": [14, 15.0, 5.2],
                "rotation": {"angle": 22.5, "axis": "x", "origin": [8, 8, 4.5]},
                "faces": {
                    "north": {"uv": [2, 2, 14, 13], "texture": "#front"},
                    "up":    {"uv": [2, 0, 14, 2], "texture": "#side"},
                    "west":  {"uv": [0, 2, 2, 10], "texture": "#side"},
                    "east":  {"uv": [14, 2, 16, 10], "texture": "#side"},
                },
            },
            # Tactile throttle/brake lever module on front desk shelf (z=1..3.5, y=8..9.5)
            {
                "from": [3, 8, 1],
                "to": [13, 9, 3.5],
                "faces": {
                    "up":    {"uv": [3, 8, 13, 13], "texture": "#top"},
                    "north": {"uv": [3, 10, 13, 13], "texture": "#front"},
                    "west":  {"uv": [1, 11, 4, 13], "texture": "#side"},
                    "east":  {"uv": [12, 11, 15, 13], "texture": "#side"},
                },
            },
        ],
    }


def dispatch_center_model():
    """Trackside wayside interlocking cabinet: industrial iron cube with recessed matte grille sides and hooded front HUD."""
    return {
        "parent": "minecraft:block/block",
        "display": DISPLAY_BLOCK,
        "textures": {
            "particle": "raildispatch:block/dispatch_center_side",
            "side": "raildispatch:block/dispatch_center_side",
            "top": "raildispatch:block/dispatch_center_top",
            "front": "raildispatch:block/dispatch_center_front",
            "grille": "raildispatch:block/autopilot_console_on",
        },
        "elements": [
            # Main cabinet chassis (recessed 1px on north for visor hood)
            {
                "from": [0, 0, 1],
                "to": [16, 16, 16],
                "faces": {
                    "down":  {"uv": [0, 0, 16, 16], "texture": "#side", "cullface": "down"},
                    "up":    {"uv": [0, 0, 16, 16], "texture": "#top", "cullface": "up"},
                    "north": {"uv": [0, 0, 16, 16], "texture": "#front"},
                    "south": {"uv": [0, 0, 16, 16], "texture": "#grille", "cullface": "south"},
                    "west":  {"uv": [1, 0, 16, 16], "texture": "#side", "cullface": "west"},
                    "east":  {"uv": [0, 0, 15, 16], "texture": "#side", "cullface": "east"},
                },
            },
            # Protective industrial bezel frame around north HUD face (z=0..1)
            {"from": [0, 14, 0], "to": [16, 16, 1], "faces": {
                "up": {"uv": [0, 0, 16, 1], "texture": "#top"},
                "down": {"uv": [0, 15, 16, 16], "texture": "#side"},
                "north": {"uv": [0, 0, 16, 2], "texture": "#side"},
                "west": {"uv": [0, 0, 1, 2], "texture": "#side"},
                "east": {"uv": [15, 0, 16, 2], "texture": "#side"},
            }},
            {"from": [0, 0, 0], "to": [16, 2, 1], "faces": {
                "up": {"uv": [0, 14, 16, 15], "texture": "#side"},
                "down": {"uv": [0, 15, 16, 16], "texture": "#side"},
                "north": {"uv": [0, 14, 16, 16], "texture": "#side"},
                "west": {"uv": [0, 14, 1, 16], "texture": "#side"},
                "east": {"uv": [15, 14, 16, 16], "texture": "#side"},
            }},
            {"from": [0, 2, 0], "to": [2, 14, 1], "faces": {
                "north": {"uv": [0, 2, 2, 14], "texture": "#side"},
                "west": {"uv": [0, 2, 1, 14], "texture": "#side"},
                "east": {"uv": [1, 2, 2, 14], "texture": "#side"},
            }},
            {"from": [14, 2, 0], "to": [16, 14, 1], "faces": {
                "north": {"uv": [14, 2, 16, 14], "texture": "#side"},
                "west": {"uv": [14, 2, 15, 14], "texture": "#side"},
                "east": {"uv": [15, 2, 16, 14], "texture": "#side"},
            }},
        ],
    }


def yard_terminal_block_model():
    """12/16-high pedestal kiosk matching YardTerminalBlock's 12px VoxelShape."""
    return {
        "parent": "minecraft:block/block",
        "display": DISPLAY_BLOCK,
        "textures": {
            "particle": "raildispatch:block/yard_terminal_side",
            "side": "raildispatch:block/yard_terminal_side",
            "top": "raildispatch:block/yard_terminal_top",
            "front": "raildispatch:block/yard_terminal_front",
            "grille": "raildispatch:block/autopilot_console_on",
        },
        "elements": [
            {
                "from": [1, 0, 1],
                "to": [15, 8, 15],
                "faces": {
                    "down":  {"uv": [1, 1, 15, 15], "texture": "#side", "cullface": "down"},
                    "up":    {"uv": [1, 1, 15, 15], "texture": "#top"},
                    "north": {"uv": [1, 8, 15, 16], "texture": "#side"},
                    "south": {"uv": [1, 8, 15, 16], "texture": "#grille"},
                    "west":  {"uv": [1, 8, 15, 16], "texture": "#side"},
                    "east":  {"uv": [1, 8, 15, 16], "texture": "#side"},
                },
            },
            {
                "from": [2, 7.5, 3],
                "to": [14, 12, 13],
                "rotation": {"angle": 22.5, "axis": "x", "origin": [8, 8, 8]},
                "faces": {
                    "up":    {"uv": [2, 2, 14, 13], "texture": "#front"},
                    "north": {"uv": [2, 10, 14, 14], "texture": "#front"},
                    "south": {"uv": [2, 2, 14, 6], "texture": "#side"},
                    "west":  {"uv": [3, 2, 13, 6], "texture": "#side"},
                    "east":  {"uv": [3, 2, 13, 6], "texture": "#side"},
                },
            },
        ],
    }


def retarder_model(smart=False):
    """3D twin-beam hydraulic rail retarder matching the 2px VoxelShape with sculpted raised jaws."""
    prefix = "smart_retarder" if smart else "retarder"
    elems = [
        # Base bedplate (y=0..1.5)
        {
            "from": [0, 0, 0],
            "to": [16, 1.5, 16],
            "faces": {
                "down":  {"uv": [0, 0, 16, 16], "texture": "#side", "cullface": "down"},
                "up":    {"uv": [0, 0, 16, 16], "texture": "#top"},
                "north": {"uv": [0, 14, 16, 16], "texture": "#side"},
                "south": {"uv": [0, 14, 16, 16], "texture": "#side"},
                "west":  {"uv": [0, 14, 16, 16], "texture": "#side"},
                "east":  {"uv": [0, 14, 16, 16], "texture": "#side"},
            },
        },
        # Left clamping brake beam (x=2..5.5, y=1.5..3.0)
        {
            "from": [2, 1.5, 1],
            "to": [5.5, 3.0, 15],
            "faces": {
                "up":    {"uv": [2, 1, 6, 15], "texture": "#top"},
                "north": {"uv": [2, 12, 6, 14], "texture": "#side"},
                "south": {"uv": [2, 12, 6, 14], "texture": "#side"},
                "west":  {"uv": [1, 12, 15, 14], "texture": "#side"},
                "east":  {"uv": [5, 1, 6, 15], "texture": "#top"},
            },
        },
        # Right clamping brake beam (x=10.5..14, y=1.5..3.0)
        {
            "from": [10.5, 1.5, 1],
            "to": [14, 3.0, 15],
            "faces": {
                "up":    {"uv": [10, 1, 14, 15], "texture": "#top"},
                "north": {"uv": [10, 12, 14, 14], "texture": "#side"},
                "south": {"uv": [10, 12, 14, 14], "texture": "#side"},
                "west":  {"uv": [10, 1, 11, 15], "texture": "#top"},
                "east":  {"uv": [1, 12, 15, 14], "texture": "#side"},
            },
        },
    ]
    if smart:
        # Central radar/optical gap sensor pod
        elems.append({
            "from": [6.5, 1.5, 4],
            "to": [9.5, 3.2, 12],
            "faces": {
                "up":    {"uv": [7, 4, 9, 12], "texture": "#top"},
                "north": {"uv": [6, 12, 10, 14], "texture": "#side"},
                "south": {"uv": [6, 12, 10, 14], "texture": "#side"},
                "west":  {"uv": [4, 12, 12, 14], "texture": "#side"},
                "east":  {"uv": [4, 12, 12, 14], "texture": "#side"},
            },
        })
    return {
        "parent": "minecraft:block/block",
        "display": DISPLAY_BLOCK,
        "textures": {
            "particle": f"raildispatch:block/{prefix}_side",
            "top": f"raildispatch:block/{prefix}_top",
            "side": f"raildispatch:block/{prefix}_side",
        },
        "elements": elems,
    }


def automatic_uncoupler_model():
    """Trackside actuator bollard matching the [3,0,3]..[13,14,13] VoxelShape."""
    return {
        "parent": "minecraft:block/block",
        "display": DISPLAY_BLOCK,
        "textures": {
            "particle": "raildispatch:block/automatic_uncoupler_side",
            "top": "raildispatch:block/automatic_uncoupler_top",
            "side": "raildispatch:block/automatic_uncoupler_side",
            "front": "raildispatch:block/automatic_uncoupler_front",
        },
        "elements": [
            # Heavy bolted base plinth
            {
                "from": [3, 0, 3],
                "to": [13, 2, 13],
                "faces": {
                    "down":  {"uv": [3, 3, 13, 13], "texture": "#side", "cullface": "down"},
                    "up":    {"uv": [3, 3, 13, 13], "texture": "#top"},
                    "north": {"uv": [3, 14, 13, 16], "texture": "#side"},
                    "south": {"uv": [3, 14, 13, 16], "texture": "#side"},
                    "west":  {"uv": [3, 14, 13, 16], "texture": "#side"},
                    "east":  {"uv": [3, 14, 13, 16], "texture": "#side"},
                },
            },
            # Main industrial iron column
            {
                "from": [5, 2, 5],
                "to": [11, 11, 11],
                "faces": {
                    "north": {"uv": [5, 3, 11, 13], "texture": "#front"},
                    "south": {"uv": [5, 3, 11, 13], "texture": "#front"},
                    "west":  {"uv": [5, 3, 11, 13], "texture": "#side"},
                    "east":  {"uv": [5, 3, 11, 13], "texture": "#side"},
                },
            },
            # Upper actuator head with CRN status strip & release latch
            {
                "from": [4, 11, 4],
                "to": [12, 14, 12],
                "faces": {
                    "up":    {"uv": [4, 4, 12, 12], "texture": "#top"},
                    "down":  {"uv": [4, 4, 12, 12], "texture": "#side"},
                    "north": {"uv": [4, 4, 12, 10], "texture": "#front"},
                    "south": {"uv": [4, 4, 12, 10], "texture": "#front"},
                    "west":  {"uv": [4, 4, 12, 10], "texture": "#side"},
                    "east":  {"uv": [4, 4, 12, 10], "texture": "#side"},
                },
            },
            # Side actuator arm bar
            {
                "from": [3, 8, 7],
                "to": [13, 9.5, 9],
                "faces": {
                    "up":    {"uv": [5, 8, 11, 9], "texture": "#front"},
                    "down":  {"uv": [5, 10, 11, 11], "texture": "#front"},
                    "north": {"uv": [5, 8, 11, 10], "texture": "#front"},
                    "south": {"uv": [5, 8, 11, 10], "texture": "#front"},
                    "west":  {"uv": [6, 6, 9, 9], "texture": "#top"},
                    "east":  {"uv": [6, 6, 9, 9], "texture": "#top"},
                },
            },
        ],
    }


write_json(os.path.join(BLOCK_MOD, "autopilot_console.json"),
           console_model("raildispatch:block/autopilot_console_front"))
write_json(os.path.join(BLOCK_MOD, "autopilot_console_on.json"),
           console_model("raildispatch:block/autopilot_console_front_on"))
write_json(os.path.join(BLOCK_MOD, "physics_cab.json"),
           console_model("raildispatch:block/physics_cab_front",
                         side_tex="raildispatch:block/physics_cab",
                         top_tex="raildispatch:block/physics_cab_top"))
write_json(os.path.join(BLOCK_MOD, "physics_cab_on.json"),
           console_model("raildispatch:block/physics_cab_front_on",
                         side_tex="raildispatch:block/physics_cab_on",
                         top_tex="raildispatch:block/physics_cab_top"))
write_json(os.path.join(BLOCK_MOD, "dispatch_center.json"), dispatch_center_model())
write_json(os.path.join(BLOCK_MOD, "yard_terminal.json"), yard_terminal_block_model())
write_json(os.path.join(BLOCK_MOD, "retarder.json"), retarder_model(smart=False))
write_json(os.path.join(BLOCK_MOD, "smart_retarder.json"), retarder_model(smart=True))
write_json(os.path.join(BLOCK_MOD, "automatic_uncoupler.json"), automatic_uncoupler_model())

print("all textures and 3D block models generated")
