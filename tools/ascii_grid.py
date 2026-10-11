"""ascii_grid.py — statue photo -> ASCII grid for Aero Launcher's background (app/src/main/assets/*.txt).
Use: python3 tools/ascii_grid.py photo.webp app/src/main/assets/eris.txt [columns] [warm|blue]
Output: "cols rows", then `rows` lines of characters, then `rows` lines of brightness digits 0-9.
The launcher draws it itself (any screen size, OLED black, with a little shimmer).
  warm = cream/beige marble on any background (default)   blue = white plaster on a bluish-grey studio backdrop"""
import json, math, subprocess, sys

SRC, OUT = sys.argv[1], sys.argv[2]
SCOLS = int(sys.argv[3]) if len(sys.argv) > 3 else 100
MODE = sys.argv[4] if len(sys.argv) > 4 else "warm"

info = json.loads(subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                  "stream=width,height", "-of", "json", SRC], capture_output=True).stdout)["streams"][0]
SW, SH = info["width"], info["height"]
raw = subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-i", SRC, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                     capture_output=True, stdin=subprocess.DEVNULL).stdout
N = SW * SH
R, G, B = raw[0::3], raw[1::3], raw[2::3]
L = [0.2126 * R[i] + 0.7152 * G[i] + 0.0722 * B[i] for i in range(N)]

def blur(ch, r):
    out = [0.0] * N; tmp = [0.0] * N
    for y in range(SH):
        row = ch[y * SW:(y + 1) * SW]; acc = [0.0]
        for v in row: acc.append(acc[-1] + v)
        for x in range(SW):
            a, b = max(0, x - r), min(SW - 1, x + r); tmp[y * SW + x] = (acc[b + 1] - acc[a]) / (b - a + 1)
    for x in range(SW):
        col = tmp[x::SW]; acc = [0.0]
        for v in col: acc.append(acc[-1] + v)
        for y in range(SH):
            a, b = max(0, y - r), min(SH - 1, y + r); out[y * SW + x] = (acc[b + 1] - acc[a]) / (b - a + 1)
    return out

# ---------- 1. which pixels are statue ----------
if MODE == "blue":       # bluish studio grey around a neutral/warm statue
    BR, BB, BLm = blur(list(R), 2), blur(list(B), 2), blur(L, 2)
    mask = bytearray(0 if (BB[i] - BR[i] >= 2.5 and 140 < BLm[i] < 215) else 1 for i in range(N))
else:                    # cream marble: light, warm (red > blue), not green, not brick red
    mask = bytearray(1 if (L[i] > 105 and R[i] >= G[i] >= B[i] - 4 and 8 <= R[i] - B[i] <= 75 and R[i] - G[i] <= 40)
                     else 0 for i in range(N))

def morph(m, r, grow):   # grow=True: dilate, False: erode (square r)
    out = bytearray(N)
    for y in range(SH):
        for x in range(SW):
            ys, xs = range(max(0, y - r), min(SH, y + r + 1)), range(max(0, x - r), min(SW, x + r + 1))
            vals = (m[yy * SW + xx] for yy in ys for xx in xs)
            out[y * SW + x] = 1 if (any(vals) if grow else all(vals)) else 0
    return out
def largest(m):
    seen = bytearray(N); best = []
    for s in range(N):
        if not m[s] or seen[s]: continue
        comp = [s]; seen[s] = 1; k = 0
        while k < len(comp):
            i = comp[k]; k += 1; x, y = i % SW, i // SW
            for j in ((i + 1) if x + 1 < SW else -1, (i - 1) if x else -1, (i + SW) if y + 1 < SH else -1, (i - SW) if y else -1):
                if j >= 0 and m[j] and not seen[j]: seen[j] = 1; comp.append(j)
        if len(comp) > len(best): best = comp
    out = bytearray(N)
    for i in best: out[i] = 1
    return out
def fill_holes(m):       # background = what the border reaches without crossing the statue
    out = bytearray(b"\x01") * N; stack = [i for i in range(N) if (i % SW in (0, SW - 1) or i // SW in (0, SH - 1)) and not m[i]]
    for i in stack: out[i] = 0
    while stack:
        i = stack.pop(); x, y = i % SW, i // SW
        for j in ((i + 1) if x + 1 < SW else -1, (i - 1) if x else -1, (i + SW) if y + 1 < SH else -1, (i - SW) if y else -1):
            if j >= 0 and out[j] and not m[j]: out[j] = 0; stack.append(j)
    return out
mask = morph(morph(mask, 1, False), 1, True)              # opening: cuts thin bridges to the background
mask = largest(mask)
mask = morph(morph(mask, 3, True), 3, False)              # closing: fills cracks in the shadowed folds
mask = fill_holes(mask)

# ---------- 2. crop to the statue, one cell = 1 column x 2 rows of "pixels" (characters are 1:2) ----------
xs = [i % SW for i in range(N) if mask[i]]; ys = [i // SW for i in range(N) if mask[i]]
x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
scols = SCOLS
srows = int(scols * (y1 - y0 + 1) / (x1 - x0 + 1) / 2)
LS = blur(L, 1); LB = blur(L, 4)
LD = [ls + 0.8 * (ls - lb) for ls, lb in zip(LS, LB)]      # sharpened light (folds, face)
cells = {}
for cy in range(srows):
    for cx in range(scols):
        sx0, sx1 = x0 + cx * (x1 - x0 + 1) / scols, x0 + (cx + 1) * (x1 - x0 + 1) / scols
        sy0, sy1 = y0 + cy * (y1 - y0 + 1) / srows, y0 + (cy + 1) * (y1 - y0 + 1) / srows
        pts = [(int(sx0 + (sx1 - sx0) * (a + .5) / 3), int(sy0 + (sy1 - sy0) * (b + .5) / 4)) for a in range(3) for b in range(4)]
        idx = [min(SH - 1, y) * SW + min(SW - 1, x) for x, y in pts]
        inside = [i for i in idx if mask[i]]
        if len(inside) / len(idx) < 0.35: continue
        l = sum(LD[i] for i in inside) / len(inside)
        gx = sum(L[min(i + 1, N - 1)] - L[max(i - 1, 0)] for i in idx); gy = sum(L[min(i + SW, N - 1)] - L[max(i - SW, 0)] for i in idx)
        cells[cy, cx] = (len(inside) / len(idx), l, math.atan2(gy, gx))
sv = sorted(c[1] for c in cells.values()); P2, P98 = sv[int(len(sv) * .02)], sv[int(len(sv) * .98)]
RAMP = " .'`^\",:;Il!i><~+_-?][}{1)(|tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$"
def edge_char(a):
    a = (math.degrees(a) + 180) % 180
    return "|" if a < 22.5 or a >= 157.5 else "\\" if a < 67.5 else "-" if a < 112.5 else "/"
chars = [[" "] * scols for _ in range(srows)]; levels = [["0"] * scols for _ in range(srows)]
for (cy, cx), (cover, l, ang) in cells.items():
    t = min(1, max(0, (l - P2) / (P98 - P2))) ** 1.25
    chars[cy][cx] = edge_char(ang) if cover < 0.9 else RAMP[max(1, int(t * (len(RAMP) - 1)))]
    levels[cy][cx] = str(min(9, int(1 + t * 9)))
with open(OUT, "w") as f:
    f.write(f"{scols} {srows}\n")
    f.writelines("".join(r) + "\n" for r in chars)
    f.writelines("".join(r) + "\n" for r in levels)
print(f"{scols}x{srows} characters -> {OUT}")
