"""Generate the mod's 16x16 block and item textures in one coherent brass/steel style.

The reference is a Create-style machine: a bevelled brass plate with concentric inlays on top,
a riveted gunmetal body, wood/brass trim and a red inspection window.  Everything here is drawn
from a small palette so the blocks read as one set rather than four unrelated textures.
"""
from PIL import Image
import random

OUT = r"D:\DS_WORKSPACE\raildispatch\src\main\resources\assets\raildispatch\textures"
random.seed(20261018)

P = {
    "clear": (0, 0, 0, 0),
    "black": (14, 14, 16, 255),
    "iron_d": (28, 29, 32, 255),
    "iron": (45, 47, 51, 255),
    "iron_l": (63, 66, 72, 255),
    "steel_d": (78, 82, 89, 255),
    "steel": (103, 108, 116, 255),
    "steel_l": (140, 146, 154, 255),
    "brass_d": (88, 61, 28, 255),
    "brass": (150, 110, 50, 255),
    "brass_l": (196, 156, 82, 255),
    "brass_xl": (232, 198, 118, 255),
    "brass_hi": (255, 232, 164, 255),
    "wood_d": (66, 40, 21, 255),
    "wood": (104, 63, 31, 255),
    "red_d": (72, 16, 15, 255),
    "red": (154, 30, 26, 255),
    "red_l": (219, 64, 43, 255),
    "green_d": (24, 58, 33, 255),
    "green": (54, 126, 58, 255),
    "green_l": (110, 196, 96, 255),
    "teal_d": (18, 46, 48, 255),
    "teal": (40, 92, 92, 255),
    "teal_l": (86, 158, 148, 255),
    "glass_d": (24, 52, 64, 255),
    "glass": (54, 104, 120, 255),
    "glass_l": (128, 184, 194, 255),
    "map_d": (22, 44, 34, 255),
    "map": (46, 82, 60, 255),
    "map_l": (76, 122, 86, 255),
    "amber": (207, 146, 54, 255),
}


def canvas(bg="iron_d"):
    return [[P[bg] for _ in range(16)] for _ in range(16)]


def rect(im, x0, y0, x1, y1, c):
    for y in range(max(0, y0), min(15, y1) + 1):
        for x in range(max(0, x0), min(15, x1) + 1):
            im[y][x] = P[c]


def px(im, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        im[y][x] = P[c]


def line(im, x0, y0, x1, y1, c):
    dx, dy = abs(x1 - x0), abs(y1 - y0)
    sx = 1 if x0 < x1 else -1
    sy = 1 if y0 < y1 else -1
    err = dx - dy
    while True:
        px(im, x0, y0, c)
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 > -dy:
            err -= dy
            x0 += sx
        if e2 < dx:
            err += dx
            y0 += sy


def weather(im, x0, y0, x1, y1, base, light, dark, chance=0.16):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if im[y][x] != P[base]:
                continue
            r = random.random()
            if r < chance * 0.55:
                im[y][x] = P[light]
            elif r < chance:
                im[y][x] = P[dark]


def rivet(im, x, y):
    px(im, x, y, "steel_l")
    px(im, x + 1, y, "steel")
    px(im, x, y + 1, "steel_d")
    px(im, x + 1, y + 1, "iron_d")


def brass_band(im, y0=1, y1=3):
    rect(im, 1, y0, 14, y0, "wood_d")
    rect(im, 1, y0 + 1, 14, y0 + 1, "brass_d")
    rect(im, 1, y0 + 2, 14, y0 + 2, "brass")
    if y1 > y0 + 2:
        rect(im, 1, y0 + 3, 14, y0 + 3, "brass_l")
    for x in (4, 11):
        rect(im, x, y0, x, y0 + 2, "brass_d")
    for x in (2, 13):
        px(im, x, y0 + 2, "brass_hi")


# --------------------------------------------------------------------------- autopilot console
def console_top():
    im = canvas("iron_d")
    rect(im, 0, 0, 15, 15, "black")
    rect(im, 1, 1, 14, 14, "iron")
    rect(im, 2, 2, 13, 13, "brass_d")
    rect(im, 3, 3, 12, 12, "brass")
    # bevelled plate
    rect(im, 3, 3, 12, 3, "brass_l")
    rect(im, 3, 3, 3, 12, "brass_l")
    rect(im, 3, 12, 12, 12, "brass_d")
    rect(im, 12, 3, 12, 12, "brass_d")
    # concentric inlay, like a turntable plate
    rect(im, 4, 4, 11, 11, "brass_xl")
    rect(im, 5, 5, 10, 10, "brass_d")
    rect(im, 6, 6, 9, 9, "brass_l")
    rect(im, 7, 7, 8, 8, "brass_xl")
    px(im, 7, 7, "brass_d")
    px(im, 8, 8, "brass_d")
    # four cardinal notches and corner brackets
    for x, y in ((8, 3), (8, 12), (3, 8), (12, 8)):
        px(im, x, y, "brass_d")
    for x, y in ((1, 1), (14, 1), (1, 14), (14, 14)):
        px(im, x, y, "steel_l")
        px(im, x, y + 1 if y < 8 else y - 1, "steel_d")
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        rivet(im, x, y)
    weather(im, 3, 3, 12, 12, "brass", "brass_l", "brass_d", 0.14)
    return im


def console_side(lit=False):
    im = canvas("iron_d")
    rect(im, 0, 0, 15, 15, "black")
    rect(im, 1, 1, 14, 14, "iron")
    brass_band(im)
    # panel seams and edge highlights
    for x in (4, 11):
        rect(im, x, 4, x, 14, "iron_l")
    rect(im, 1, 4, 1, 14, "steel")
    rect(im, 14, 4, 14, 14, "iron_d")
    # red inspection window
    rect(im, 3, 7, 12, 9, "black")
    rect(im, 4, 7, 11, 9, "red_d")
    rect(im, 4, 8, 11, 8, "red_l" if lit else "red")
    for x in (4, 6, 8, 10):
        rect(im, x, 7, x, 9, "red_d")
    px(im, 5, 8, "red_l" if lit else "red")
    # small brass maker's plate
    rect(im, 3, 11, 6, 13, "brass_d")
    rect(im, 4, 11, 5, 12, "brass_l")
    px(im, 4, 12, "brass_hi")
    # rivets
    for x, y in ((2, 5), (13, 5), (2, 12), (13, 12)):
        rivet(im, x, y)
    if lit:
        px(im, 12, 5, "green_l")
        rect(im, 11, 5, 12, 5, "green_d")
    rect(im, 1, 14, 14, 14, "iron_d")
    rect(im, 1, 15, 14, 15, "black")
    weather(im, 2, 4, 13, 6, "iron", "iron_l", "iron_d", 0.1)
    return im


def console_front(lit=False):
    im = canvas("iron_d")
    rect(im, 0, 0, 15, 15, "black")
    rect(im, 1, 1, 14, 14, "iron")
    brass_band(im)
    # screen bezel
    rect(im, 2, 4, 13, 9, "black")
    rect(im, 3, 4, 12, 8, "brass_d")
    rect(im, 4, 5, 11, 7, "teal_d")
    if lit:
        rect(im, 4, 5, 11, 7, "teal")
        rect(im, 4, 5, 11, 5, "teal_l")
        for x in (4, 6, 8, 10):
            px(im, x, 7, "teal_l")
        # small route graph on the screen
        for x, y in ((5, 6), (6, 6), (7, 5), (8, 5), (9, 6), (10, 6)):
            px(im, x, y, "green_l")
    else:
        rect(im, 4, 5, 11, 5, "teal")
    # lower control row: red mushroom, green button, brass lever
    rect(im, 2, 9, 13, 9, "brass_d")
    rect(im, 3, 11, 5, 13, "black")
    rect(im, 3, 11, 5, 13, "red_d")
    rect(im, 4, 12, 4, 12, "red_l" if lit else "red")
    px(im, 4, 11, "red_l" if lit else "red")
    rect(im, 7, 11, 9, 13, "black")
    rect(im, 7, 11, 9, 13, "green_d")
    px(im, 8, 12, "green_l" if lit else "green")
    px(im, 8, 11, "green_l" if lit else "green")
    # lever slot
    rect(im, 11, 10, 12, 14, "black")
    rect(im, 11, 10, 12, 10, "steel")
    px(im, 11, 10, "brass_xl")
    px(im, 11, 11, "brass_l")
    px(im, 11, 12, "brass")
    # corner rivets
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        rivet(im, x, y)
    rect(im, 1, 14, 14, 14, "iron_d")
    rect(im, 1, 15, 14, 15, "black")
    return im


# --------------------------------------------------------------------------- physics cab
def cab_top():
    im = canvas("iron_d")
    rect(im, 0, 0, 15, 15, "black")
    rect(im, 1, 1, 14, 14, "brass_d")
    rect(im, 2, 2, 13, 13, "brass")
    rect(im, 2, 2, 13, 2, "brass_l")
    rect(im, 2, 2, 2, 13, "brass_l")
    rect(im, 2, 13, 13, 13, "brass_d")
    rect(im, 13, 2, 13, 13, "brass_d")
    # roof vent
    rect(im, 5, 6, 10, 9, "iron_d")
    rect(im, 6, 7, 9, 8, "black")
    for x in (6, 8):
        rect(im, x, 6, x, 9, "brass_d")
    rect(im, 3, 3, 4, 4, "brass_xl")
    rect(im, 11, 3, 12, 4, "brass_xl")
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        rivet(im, x, y)
    weather(im, 3, 3, 12, 12, "brass", "brass_l", "brass_d", 0.12)
    return im


def cab_side(lit=False):
    im = canvas("iron")
    rect(im, 0, 0, 15, 15, "black")
    rect(im, 1, 1, 14, 14, "iron")
    brass_band(im)
    # side window
    rect(im, 3, 5, 12, 8, "black")
    rect(im, 4, 5, 11, 8, "brass_d")
    rect(im, 5, 6, 10, 7, "glass_l" if lit else "glass")
    px(im, 5, 6, "glass_l")
    px(im, 6, 6, "glass_l")
    line(im, 5, 7, 10, 7, "glass_d")
    # lower body panels and rivets
    rect(im, 3, 10, 12, 12, "iron_d")
    rect(im, 4, 10, 11, 10, "iron_l")
    for x, y in ((2, 4), (13, 4), (2, 12), (13, 12)):
        rivet(im, x, y)
    if lit:
        rect(im, 12, 6, 13, 7, "amber")
        px(im, 12, 6, "brass_hi")
    rect(im, 1, 14, 14, 14, "iron_d")
    rect(im, 1, 15, 14, 15, "black")
    return im


def cab_front(lit=False):
    im = canvas("iron")
    rect(im, 0, 0, 15, 15, "black")
    rect(im, 1, 1, 14, 14, "iron")
    brass_band(im)
    # big cab windshield
    rect(im, 2, 4, 13, 8, "black")
    rect(im, 3, 4, 12, 8, "brass_d")
    rect(im, 4, 5, 11, 7, "glass" if not lit else "glass_l")
    for d in range(0, 6):
        px(im, 5 + d, 5 + min(d, 2), "glass_l")
    px(im, 4, 5, "glass_l")
    rect(im, 11, 5, 11, 7, "glass_d")
    # control desk
    rect(im, 2, 10, 13, 10, "brass_d")
    for x in (4, 6, 8, 10):
        rect(im, x, 11, x, 13, "black")
        px(im, x, 11, "brass_xl" if not lit else "red_l")
        px(im, x, 12, "brass_l")
        px(im, x, 13, "steel")
    # gauges
    rect(im, 2, 11, 3, 12, "black")
    px(im, 2, 11, "teal_l" if lit else "teal")
    px(im, 3, 12, "green_l" if lit else "green")
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        rivet(im, x, y)
    rect(im, 1, 14, 14, 14, "iron_d")
    rect(im, 1, 15, 14, 15, "black")
    return im


# --------------------------------------------------------------------------- dispatch centre
def dispatch_side(front=False):
    im = canvas("iron_d")
    rect(im, 0, 0, 15, 15, "black")
    rect(im, 1, 1, 14, 14, "iron")
    brass_band(im)
    # map board
    rect(im, 2, 5, 13, 10, "black")
    rect(im, 3, 5, 12, 9, "brass_d")
    rect(im, 4, 6, 11, 8, "map_d")
    for x in range(4, 12, 2):
        rect(im, x, 6, x, 8, "map")
    for y in (6, 8):
        rect(im, 4, y, 11, y, "map")
    # route markers
    for x, y in ((5, 7), (7, 6), (9, 7), (10, 8)):
        px(im, x, y, "red_l" if (front and x % 2) else "amber")
    # lower signal levers
    rect(im, 2, 11, 13, 11, "brass_d")
    rect(im, 4, 12, 4, 13, "red_d")
    px(im, 4, 12, "red_l")
    rect(im, 7, 12, 7, 13, "green_d")
    px(im, 7, 12, "green_l")
    rect(im, 10, 12, 10, 13, "black")
    px(im, 10, 12, "brass_xl")
    for x, y in ((2, 3), (13, 3), (2, 12), (13, 12)):
        rivet(im, x, y)
    rect(im, 1, 14, 14, 14, "iron_d")
    rect(im, 1, 15, 14, 15, "black")
    return im


def dispatch_top():
    im = canvas("iron_d")
    rect(im, 0, 0, 15, 15, "black")
    rect(im, 1, 1, 14, 14, "brass_d")
    rect(im, 2, 2, 13, 13, "brass")
    # inset map table
    rect(im, 3, 4, 12, 11, "black")
    rect(im, 4, 4, 11, 10, "brass_d")
    rect(im, 5, 5, 10, 9, "map_d")
    for x in range(5, 11, 2):
        rect(im, x, 5, x, 9, "map")
    for y in (5, 7, 9):
        rect(im, 5, y, 10, y, "map")
    for x, y in ((6, 6), (8, 7), (9, 8)):
        px(im, x, y, "amber")
    px(im, 7, 6, "red_l")
    # brass corners and rivets
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        rivet(im, x, y)
    rect(im, 3, 3, 4, 3, "brass_l")
    rect(im, 11, 12, 12, 12, "brass_d")
    return im


# --------------------------------------------------------------------------- address plate
def address_plate():
    im = canvas("brass")
    rect(im, 0, 0, 15, 15, "black")
    rect(im, 1, 1, 14, 14, "brass")
    rect(im, 1, 1, 14, 1, "brass_xl")
    rect(im, 1, 1, 1, 14, "brass_xl")
    rect(im, 1, 14, 14, 14, "brass_d")
    rect(im, 14, 1, 14, 14, "brass_d")
    # dark engraved address panel
    rect(im, 2, 5, 13, 10, "black")
    rect(im, 3, 5, 12, 9, "iron_d")
    # two stamped lines + route code
    for x in (4, 6, 8, 10):
        rect(im, x, 6, x + 1, 6, "brass_xl")
    for x in (4, 5, 7, 8, 10):
        rect(im, x, 8, x, 8, "brass_l")
    rect(im, 11, 6, 11, 8, "red_l")
    px(im, 11, 7, "red")
    # corner rivets
    for x, y in ((2, 2), (13, 2), (2, 11), (13, 11)):
        rivet(im, x, y)
    weather(im, 2, 2, 13, 13, "brass", "brass_l", "brass_d", 0.1)
    return im


# --------------------------------------------------------------------------- item tags
def vehicle_tag():
    im = canvas("clear")
    # brass luggage-tag shape
    rect(im, 3, 3, 12, 13, "black")
    rect(im, 4, 3, 11, 12, "brass")
    rect(im, 4, 3, 11, 3, "brass_xl")
    rect(im, 4, 11, 11, 11, "brass_d")
    rect(im, 11, 4, 11, 11, "brass_d")
    # top grommet + hole
    rect(im, 7, 2, 8, 3, "black")
    px(im, 7, 2, "clear")
    px(im, 8, 2, "clear")
    rect(im, 6, 4, 9, 4, "brass_d")
    # embossed locomotive silhouette
    rect(im, 5, 7, 8, 9, "iron_d")
    rect(im, 5, 6, 6, 6, "iron_d")
    rect(im, 8, 6, 9, 6, "iron_d")
    px(im, 5, 9, "red_l")
    px(im, 9, 9, "amber")
    rect(im, 5, 10, 9, 10, "brass_d")
    # stamped border
    rect(im, 4, 12, 11, 12, "brass_l")
    return im


def address_tag():
    im = canvas("clear")
    rect(im, 3, 4, 12, 12, "black")
    rect(im, 4, 4, 11, 11, "brass")
    rect(im, 4, 4, 11, 4, "brass_xl")
    rect(im, 4, 11, 11, 11, "brass_d")
    rect(im, 11, 5, 11, 10, "brass_d")
    # red top stripe
    rect(im, 4, 5, 11, 5, "red_d")
    rect(im, 5, 5, 10, 5, "red_l")
    # address lines
    for x in (5, 7, 9):
        rect(im, x, 7, x + 1, 7, "iron_d")
    for x in (5, 7):
        rect(im, x, 9, x + 1, 9, "iron_d")
    # pin
    rect(im, 7, 2, 8, 4, "steel")
    px(im, 7, 2, "steel_l")
    px(im, 8, 3, "iron_d")
    rivet(im, 4, 10)
    return im


def save(im, rel):
    path = OUT + "\\" + rel
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), im[y][x])
    img.save(path)


save(console_top(), "block\\autopilot_console_top.png")
save(console_side(False), "block\\autopilot_console.png")
save(console_side(True), "block\\autopilot_console_on.png")
save(console_front(False), "block\\autopilot_console_front.png")
save(console_front(True), "block\\autopilot_console_front_on.png")

save(cab_top(), "block\\physics_cab_top.png")
save(cab_side(False), "block\\physics_cab.png")
save(cab_side(True), "block\\physics_cab_on.png")
save(cab_front(False), "block\\physics_cab_front.png")
save(cab_front(True), "block\\physics_cab_front_on.png")

save(dispatch_top(), "block\\dispatch_center_top.png")
save(dispatch_side(False), "block\\dispatch_center_side.png")
save(dispatch_side(True), "block\\dispatch_center_front.png")
save(dispatch_side(False), "block\\dispatch_center.png")

save(address_plate(), "block\\address_plate.png")
save(vehicle_tag(), "item\\vehicle_tag.png")
save(address_tag(), "item\\address_tag.png")

# contact sheet for inspection, 8x nearest-neighbour
names = [
    ("console_top", console_top()), ("console_side", console_side(False)),
    ("console_front", console_front(False)), ("console_front_on", console_front(True)),
    ("cab_top", cab_top()), ("cab_side", cab_side(False)),
    ("cab_front", cab_front(False)), ("cab_front_on", cab_front(True)),
    ("dispatch_top", dispatch_top()), ("dispatch_side", dispatch_side(False)),
    ("dispatch_front", dispatch_side(True)), ("address_plate", address_plate()),
    ("vehicle_tag", vehicle_tag()), ("address_tag", address_tag()),
    ("console_on", console_side(True)), ("cab_on", cab_side(True)),
]
S = 8
sheet = Image.new("RGBA", (4 * 16 * S, 4 * 16 * S), (20, 20, 22, 255))
for i, (name, im) in enumerate(names):
    tile = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            tile.putpixel((x, y), im[y][x])
    tile = tile.resize((16 * S, 16 * S), Image.NEAREST)
    sheet.alpha_composite(tile, ((i % 4) * 16 * S, (i // 4) * 16 * S))
sheet.save(r"D:\DS_WORKSPACE\raildispatch\build\texture-preview.png")
print("textures written")
