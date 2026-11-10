"""Software 3D renderer for Minecraft block JSON models + sprite preview sheet."""
from PIL import Image, ImageDraw
import json
import math
import os

ROOT = r"D:\DS_WORKSPACE\raildispatch\src\main\resources\assets\raildispatch"
BLOCK_TEX = os.path.join(ROOT, "textures", "block")
ITEM_TEX = os.path.join(ROOT, "textures", "item")
BLOCK_MOD = os.path.join(ROOT, "models", "block")


def load_tex(ref, tex_map):
    while ref.startswith("#"):
        ref = tex_map[ref[1:]]
    ns, rel = ref.split(":")
    path = os.path.join(ROOT, "textures", rel + ".png")
    return Image.open(path).convert("RGBA")


def rot_pt(pt, axis, angle_deg, origin):
    if angle_deg == 0:
        return pt
    rad = math.radians(angle_deg)
    c, s = math.cos(rad), math.sin(rad)
    x, y, z = pt[0] - origin[0], pt[1] - origin[1], pt[2] - origin[2]
    if axis == "x":
        ny, nz = y * c - z * s, y * s + z * c
        return (x + origin[0], ny + origin[1], nz + origin[2])
    elif axis == "y":
        nx, nz = x * c + z * s, -x * s + z * c
        return (nx + origin[0], y + origin[1], nz + origin[2])
    else:
        nx, ny = x * c - y * s, x * s + y * c
        return (nx + origin[0], ny + origin[1], z + origin[2])


def rot_global(pt, rx, ry, rz):
    # Matches JOML Quaternionf.rotationXYZ(rx, ry, rz): local v' = Rx(rx) * Ry(ry) * Rz(rz) * v
    p = pt
    p = rot_pt(p, "z", rz, (8, 8, 8))
    p = rot_pt(p, "y", ry, (8, 8, 8))
    p = rot_pt(p, "x", rx, (8, 8, 8))
    return p


def render_model(model_name, out_size=144):
    path = os.path.join(BLOCK_MOD, model_name + ".json")
    with open(path, "r", encoding="utf-8") as fh:
        model = json.load(fh)
    tex_map = model.get("textures", {})
    disp = model.get("display", {}).get("gui", {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]})
    rx, ry, rz = disp.get("rotation", [30, 225, 0])
    tx, ty, tz = disp.get("translation", [0, 0, 0])
    sx, sy, sz = disp.get("scale", [0.625, 0.625, 0.625])

    # Supersample 2x for clean edges
    SS = 2
    W = out_size * SS
    zbuf = [[-1e9] * W for _ in range(W)]
    cbuf = [[(0, 0, 0, 0)] * W for _ in range(W)]

    # Face definitions: (4 corner indices in (x0/x1, y0/y1, z0/z1), shade)
    face_defs = {
        "north": ([(1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0)], 0.82),
        "south": ([(0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)], 0.82),
        "west":  ([(0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0)], 0.65),
        "east":  ([(1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1)], 0.65),
        "up":    ([(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)], 1.00),
        "down":  ([(0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)], 0.50),
    }

    scale_px = (W / 16.0) * sx

    for elem in model.get("elements", []):
        x0, y0, z0 = elem["from"]
        x1, y1, z1 = elem["to"]
        xs, ys, zs = (x0, x1), (y0, y1), (z0, z1)
        rot_info = elem.get("rotation")

        for fname, fdata in elem.get("faces", {}).items():
            corners_idx, shade = face_defs[fname]
            pts3d = []
            for ix, iy, iz in corners_idx:
                p = (xs[ix], ys[iy], zs[iz])
                if rot_info:
                    p = rot_pt(p, rot_info["axis"], rot_info["angle"], rot_info["origin"])
                p = rot_global(p, rx, ry, rz)
                # Screen projection: X right, Y up -> screen y down, Z towards viewer
                px_s = W * 0.5 + (p[0] - 8.0) * scale_px + tx * (W / 16.0)
                py_s = W * 0.5 - (p[1] - 8.0) * scale_px - ty * (W / 16.0)
                pz_s = p[2]
                pts3d.append((px_s, py_s, pz_s))

            # Backface cull (2D signed area: clockwise in screen coords since Y is down)
            v1x, v1y = pts3d[1][0] - pts3d[0][0], pts3d[1][1] - pts3d[0][1]
            v2x, v2y = pts3d[2][0] - pts3d[0][0], pts3d[2][1] - pts3d[0][1]
            cross = v1x * v2y - v1y * v2x
            if cross <= 0:
                continue

            tex = load_tex(fdata["texture"], tex_map)
            tw, th = tex.size
            uv = fdata.get("uv", [0, 0, 16, 16])
            u0, v0, u1, v1 = uv[0] / 16.0, uv[1] / 16.0, uv[2] / 16.0, uv[3] / 16.0
            uv_pts = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
            uv_rot = fdata.get("rotation", 0) // 90
            if uv_rot:
                uv_pts = uv_pts[-uv_rot:] + uv_pts[:-uv_rot]

            # Rasterize 2 triangles: (0,1,2) and (0,2,3)
            for ia, ib, ic in ((0, 1, 2), (0, 2, 3)):
                A, B, C = pts3d[ia], pts3d[ib], pts3d[ic]
                ua, ub, uc = uv_pts[ia], uv_pts[ib], uv_pts[ic]
                minx = max(0, int(math.floor(min(A[0], B[0], C[0]))))
                maxx = min(W - 1, int(math.ceil(max(A[0], B[0], C[0]))))
                miny = max(0, int(math.floor(min(A[1], B[1], C[1]))))
                maxy = min(W - 1, int(math.ceil(max(A[1], B[1], C[1]))))
                denom = (B[1] - C[1]) * (A[0] - C[0]) + (C[0] - B[0]) * (A[1] - C[1])
                if abs(denom) < 1e-6:
                    continue
                for py in range(miny, maxy + 1):
                    cy_f = py + 0.5
                    for px in range(minx, maxx + 1):
                        cx_f = px + 0.5
                        w0 = ((B[1] - C[1]) * (cx_f - C[0]) + (C[0] - B[0]) * (cy_f - C[1])) / denom
                        w1 = ((C[1] - A[1]) * (cx_f - C[0]) + (A[0] - C[0]) * (cy_f - C[1])) / denom
                        w2 = 1.0 - w0 - w1
                        if w0 >= -1e-4 and w1 >= -1e-4 and w2 >= -1e-4:
                            z = w0 * A[2] + w1 * B[2] + w2 * C[2]
                            if z > zbuf[py][px]:
                                u = w0 * ua[0] + w1 * ub[0] + w2 * uc[0]
                                v = w0 * ua[1] + w1 * ub[1] + w2 * uc[1]
                                tx_i = max(0, min(tw - 1, int(u * tw)))
                                ty_i = max(0, min(th - 1, int(v * th)))
                                r, g, b, a = tex.getpixel((tx_i, ty_i))
                                if a > 0:
                                    zbuf[py][px] = z
                                    cbuf[py][px] = (int(r * shade), int(g * shade), int(b * shade), a)

    img = Image.new("RGBA", (W, W))
    img.putdata([cbuf[y][x] for y in range(W) for x in range(W)])
    return img.resize((out_size, out_size), Image.Resampling.BOX)


if __name__ == "__main__":
    models = [
        "autopilot_console",
        "autopilot_console_on",
        "dispatch_center",
        "yard_terminal",
        "retarder",
        "smart_retarder",
        "automatic_uncoupler",
        "address_plate",
    ]
    cell = 152
    cols = 4
    rows = 3
    sheet = Image.new("RGBA", (cols * cell, rows * cell), (24, 26, 30, 255))
    for idx, m in enumerate(models):
        im = render_model(m, 136)
        gx, gy = (idx % cols) * cell + 8, (idx // cols) * cell + 8
        sheet.alpha_composite(im, (gx, gy))

    # Row 3: item sprites & front textures magnified crisp
    items = [
        os.path.join(ITEM_TEX, "yard_terminal.png"),
        os.path.join(ITEM_TEX, "vehicle_tag.png"),
        os.path.join(ITEM_TEX, "address_tag.png"),
        os.path.join(BLOCK_TEX, "autopilot_console_front_on.png"),
    ]
    for idx, ip in enumerate(items):
        sp = Image.open(ip).convert("RGBA").resize((112, 112), Image.Resampling.NEAREST)
        gx = idx * cell + (cell - 112) // 2
        gy = 2 * cell + (cell - 112) // 2
        sheet.alpha_composite(sp, (gx, gy))

    out = r"D:\DS_WORKSPACE\raildispatch\build-plain\models-3d-preview.png"
    sheet.save(out)
    print("saved", out)
