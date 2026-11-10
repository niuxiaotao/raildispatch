"""Textures for the two yard blocks: retarder shoes and the automatic uncoupler post."""
from PIL import Image
import random

OUT = r"D:\DS_WORKSPACE\raildispatch\src\main\resources\assets\raildispatch\textures\block"
random.seed(4242)
P = {
    "black": (14, 14, 16, 255), "iron_d": (28, 29, 32, 255), "iron": (45, 47, 51, 255),
    "iron_l": (63, 66, 72, 255), "steel": (103, 108, 116, 255), "steel_l": (140, 146, 154, 255),
    "brass_d": (88, 61, 28, 255), "brass": (150, 110, 50, 255), "brass_l": (196, 156, 82, 255),
    "brass_xl": (232, 198, 118, 255), "red_d": (72, 16, 15, 255), "red": (154, 30, 26, 255),
    "red_l": (219, 64, 43, 255), "amber": (207, 146, 54, 255), "green_l": (110, 196, 96, 255),
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


# Retarder top: pair of brass brake shoes across a dark steel base.
top = canvas("iron_d")
rect(top, 0, 0, 15, 15, "black")
rect(top, 1, 1, 14, 14, "iron")
rect(top, 2, 2, 13, 13, "steel")
for x0, x1 in ((3, 6), (9, 12)):
    rect(top, x0, 2, x1, 13, "brass_d")
    rect(top, x0, 3, x1, 3, "brass_l")
    rect(top, x0, 12, x1, 12, "brass_d")
    rect(top, x0, 6, x1, 9, "brass")
    rect(top, x0, 7, x1, 8, "brass_xl")
rect(top, 7, 4, 8, 11, "red_d")
rect(top, 7, 5, 8, 10, "red")
px(top, 7, 6, "red_l")
for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
    rivet(top, x, y)
save(top, "retarder_top.png")

side = canvas("iron_d")
rect(side, 0, 0, 15, 15, "black")
rect(side, 1, 1, 14, 14, "iron")
rect(side, 1, 1, 14, 2, "brass_d")
rect(side, 1, 2, 14, 2, "brass")
rect(side, 1, 3, 14, 3, "brass_l")
rect(side, 3, 6, 12, 11, "iron_d")
rect(side, 4, 7, 11, 10, "red_d")
rect(side, 4, 8, 11, 9, "red")
for x in range(4, 12, 2):
    rect(side, x, 7, x, 10, "red_d")
for x, y in ((2, 5), (13, 5), (2, 12), (13, 12)):
    rivet(side, x, y)
rect(side, 1, 14, 14, 14, "iron_d")
rect(side, 1, 15, 14, 15, "black")
save(side, "retarder_side.png")

# Automatic uncoupler: brass-capped steel post.
post_side = canvas("iron_d")
rect(post_side, 0, 0, 15, 15, "black")
rect(post_side, 1, 1, 14, 14, "iron")
rect(post_side, 1, 1, 14, 2, "brass_d")
rect(post_side, 1, 2, 14, 2, "brass")
rect(post_side, 1, 3, 14, 3, "brass_l")
rect(post_side, 4, 4, 11, 13, "iron_d")
rect(post_side, 5, 5, 10, 12, "iron")
rect(post_side, 5, 8, 10, 9, "brass_d")
rect(post_side, 6, 8, 9, 8, "brass_xl")
rect(post_side, 6, 11, 9, 11, "black")
px(post_side, 7, 11, "red_l")
for x, y in ((2, 5), (13, 5), (2, 12), (13, 12)):
    rivet(post_side, x, y)
rect(post_side, 1, 14, 14, 14, "iron_d")
rect(post_side, 1, 15, 14, 15, "black")
save(post_side, "automatic_uncoupler_side.png")

post_top = canvas("iron_d")
rect(post_top, 0, 0, 15, 15, "black")
rect(post_top, 1, 1, 14, 14, "brass_d")
rect(post_top, 2, 2, 13, 13, "brass")
rect(post_top, 2, 2, 13, 2, "brass_l")
rect(post_top, 2, 2, 2, 13, "brass_l")
rect(post_top, 2, 13, 13, 13, "brass_d")
rect(post_top, 13, 2, 13, 13, "brass_d")
rect(post_top, 5, 5, 10, 10, "iron_d")
rect(post_top, 6, 6, 9, 9, "black")
rect(post_top, 7, 6, 8, 9, "brass_xl")
rect(post_top, 6, 7, 9, 8, "brass_l")
for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
    rivet(post_top, x, y)
save(post_top, "automatic_uncoupler_top.png")

front = post_side
save(front, "automatic_uncoupler_front.png")
print("yard textures written")
