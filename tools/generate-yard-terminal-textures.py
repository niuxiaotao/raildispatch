"""Textures for the vehicle yard terminal."""
from PIL import Image

OUT = r"D:\DS_WORKSPACE\raildispatch\src\main\resources\assets\raildispatch\textures\block"
P = {
    "black": (14, 14, 16, 255), "iron_d": (28, 29, 32, 255), "iron": (45, 47, 51, 255),
    "steel": (103, 108, 116, 255), "steel_l": (140, 146, 154, 255),
    "brass_d": (88, 61, 28, 255), "brass": (150, 110, 50, 255), "brass_l": (196, 156, 82, 255),
    "brass_xl": (232, 198, 118, 255), "teal_d": (18, 46, 48, 255), "teal": (40, 92, 92, 255),
    "teal_l": (86, 158, 148, 255), "green_l": (110, 196, 96, 255), "red_l": (219, 64, 43, 255),
}


def canvas(bg="iron"):
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


side = canvas("iron")
rect(side, 0, 0, 15, 15, "black")
rect(side, 1, 1, 14, 14, "iron")
rect(side, 1, 1, 14, 2, "brass_d")
rect(side, 1, 2, 14, 2, "brass")
rect(side, 1, 3, 14, 3, "brass_l")
rect(side, 3, 6, 12, 12, "iron_d")
rect(side, 4, 7, 11, 11, "iron")
rect(side, 5, 8, 10, 9, "brass_d")
for x, y in ((2, 5), (13, 5), (2, 12), (13, 12)):
    rivet(side, x, y)
rect(side, 1, 14, 14, 14, "iron_d")
rect(side, 1, 15, 14, 15, "black")
save(side, "yard_terminal_side.png")

top = canvas("brass")
rect(top, 0, 0, 15, 15, "black")
rect(top, 1, 1, 14, 14, "brass")
rect(top, 1, 1, 14, 1, "brass_xl")
rect(top, 1, 1, 1, 14, "brass_xl")
rect(top, 1, 14, 14, 14, "brass_d")
rect(top, 14, 1, 14, 14, "brass_d")
rect(top, 4, 4, 11, 11, "brass_d")
rect(top, 5, 5, 10, 10, "brass_l")
rect(top, 6, 6, 9, 9, "brass")
for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
    rivet(top, x, y)
save(top, "yard_terminal_top.png")

front = canvas("iron")
rect(front, 0, 0, 15, 15, "black")
rect(front, 1, 1, 14, 14, "iron")
rect(front, 1, 1, 14, 2, "brass_d")
rect(front, 1, 2, 14, 2, "brass")
rect(front, 1, 3, 14, 3, "brass_l")
rect(front, 2, 4, 13, 10, "black")
rect(front, 3, 5, 12, 9, "teal_d")
rect(front, 3, 5, 12, 5, "teal")
px(front, 4, 6, "teal_l"); px(front, 5, 6, "teal_l")
rect(front, 4, 7, 11, 7, "teal")
rect(front, 4, 8, 9, 8, "teal_l")
rect(front, 2, 11, 13, 11, "brass_d")
for x in (4, 6, 8, 10):
    rect(front, x, 12, x, 13, "black")
    px(front, x, 12, "brass_xl")
for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
    rivet(front, x, y)
rect(front, 1, 14, 14, 14, "iron_d")
rect(front, 1, 15, 14, 15, "black")
save(front, "yard_terminal_front.png")
print("yard terminal textures written")
