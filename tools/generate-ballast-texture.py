"""Ballast texture: grey/brown crushed stone, no gravity block."""
from PIL import Image
import random

OUT = r"D:\DS_WORKSPACE\raildispatch\src\main\resources\assets\raildispatch\textures\block"
random.seed(20261020)

P = [
    (44, 42, 40, 255), (58, 55, 51, 255), (72, 68, 62, 255),
    (88, 83, 76, 255), (104, 98, 90, 255), (124, 117, 106, 255),
    (66, 61, 55, 255), (39, 37, 35, 255), (92, 84, 74, 255),
]

im = [[P[1] for _ in range(16)] for _ in range(16)]


def put(x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        im[y][x] = c


# Base mottling
for y in range(16):
    for x in range(16):
        im[y][x] = random.choice(P)

# Pebbles: small 2x1..3x2 clusters with highlight/shadow
for _ in range(42):
    w = random.choice([2, 2, 3, 3, 4])
    h = random.choice([1, 2, 2, 3])
    x = random.randint(0, 15)
    y = random.randint(0, 15)
    base = random.choice(P[2:6])
    dark = P[0]
    light = P[5]
    for yy in range(h):
        for xx in range(w):
            put(x + xx, y + yy, base)
    put(x, y, light)
    put(x + 1, y, light)
    put(x, y + h - 1, dark)
    put(x + w - 1, y + h - 1, dark)
    put(x + w - 1, y, P[4])

img = Image.new("RGBA", (16, 16))
for y in range(16):
    for x in range(16):
        img.putpixel((x, y), im[y][x])
img.save(OUT + r"\ballast.png")
print("ballast texture written")
