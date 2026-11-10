"""Textures for the smart retarder: the retarder body with a green gap sensor."""
from PIL import Image

OUT = r"D:\DS_WORKSPACE\raildispatch\src\main\resources\assets\raildispatch\textures\block"
P = {
    "black": (14, 14, 16, 255), "iron_d": (28, 29, 32, 255), "iron": (45, 47, 51, 255),
    "steel": (103, 108, 116, 255), "steel_l": (140, 146, 154, 255),
    "brass_d": (88, 61, 28, 255), "brass": (150, 110, 50, 255), "brass_l": (196, 156, 82, 255),
    "brass_xl": (232, 198, 118, 255), "green_d": (24, 58, 33, 255), "green": (54, 126, 58, 255),
    "green_l": (110, 196, 96, 255), "red_d": (72, 16, 15, 255), "red": (154, 30, 26, 255),
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


def rivet(im, x, y):
    px(im, x, y, "steel_l"); px(im, x + 1, y, "steel")
    px(im, x, y + 1, "steel"); px(im, x + 1, y + 1, "iron_d")


def save(im, name):
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), im[y][x])
    img.save(OUT + "\\" + name)


top = canvas("iron_d")
rect(top, 0, 0, 15, 15, "black")
rect(top, 1, 1, 14, 14, "iron")
rect(top, 2, 2, 13, 13, "steel")
for x0, x1 in ((3, 6), (9, 12)):
    rect(top, x0, 2, x1, 13, "brass_d")
    rect(top, x0, 3, x1, 3, "brass_l")
    rect(top, x0, 6, x1, 9, "brass")
    rect(top, x0, 7, x1, 8, "brass_xl")
# sensor eye
rect(top, 7, 4, 8, 11, "black")
rect(top, 7, 5, 8, 10, "green_d")
px(top, 7, 6, "green_l")
px(top, 7, 8, "green_l")
for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
    rivet(top, x, y)
save(top, "smart_retarder_top.png")

side = canvas("iron_d")
rect(side, 0, 0, 15, 15, "black")
rect(side, 1, 1, 14, 14, "iron")
rect(side, 1, 1, 14, 2, "brass_d")
rect(side, 1, 2, 14, 2, "brass")
rect(side, 1, 3, 14, 3, "brass_l")
rect(side, 3, 6, 12, 11, "iron_d")
rect(side, 4, 7, 11, 10, "green_d")
rect(side, 4, 8, 11, 9, "green")
for x in (4, 6, 8, 10):
    rect(side, x, 7, x, 10, "green_d")
px(side, 5, 8, "green_l")
px(side, 9, 8, "green_l")
for x, y in ((2, 5), (13, 5), (2, 12), (13, 12)):
    rivet(side, x, y)
rect(side, 1, 14, 14, 14, "iron_d")
rect(side, 1, 15, 14, 15, "black")
save(side, "smart_retarder_side.png")
print("smart retarder textures written")
