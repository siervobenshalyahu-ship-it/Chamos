import math, random, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont
W, H = 720, 1280
OUT = sys.argv[1] if len(sys.argv) > 1 else "."

def grad(stops):
    im = Image.new("RGB", (W, H)); d = ImageDraw.Draw(im)
    for y in range(H):
        t = y / (H - 1)
        for i in range(len(stops) - 1):
            a, ca = stops[i]; b, cb = stops[i + 1]
            if a <= t <= b:
                u = (t - a) / (b - a)
                c = tuple(int(ca[k] + (cb[k] - ca[k]) * u) for k in range(3))
                d.line([(0, y), (W, y)], fill=c); break
    return im

def ridge(d, base, amp, col, seed, freq=0.012, step=6):
    rnd = random.Random(seed); ph = [rnd.uniform(0, 6.28) for _ in range(3)]
    pts = [(0, H)]
    for x in range(0, W + step, step):
        y = base - amp * (0.5 * math.sin(x * freq + ph[0]) + 0.3 * math.sin(x * freq * 2.3 + ph[1]) + 0.2 * math.sin(x * freq * 5 + ph[2]))
        pts.append((x, y))
    pts.append((W, H)); d.polygon(pts, fill=col)

def clouds(im, ys, alpha=150, seed=1):
    rnd = random.Random(seed); lay = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(lay)
    for y in ys:
        for _ in range(7):
            cx, cy = rnd.randint(0, W), y + rnd.randint(-20, 20); r = rnd.randint(40, 90)
            d.ellipse([cx - r * 1.8, cy - r * .5, cx + r * 1.8, cy + r * .5], fill=(255, 255, 255, alpha))
    lay = lay.filter(ImageFilter.GaussianBlur(14)); im.paste(lay, (0, 0), lay)

def sun(im, x, y, r, col=(255, 236, 170)):
    lay = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(lay)
    for k in range(5, 0, -1):
        d.ellipse([x - r * k, y - r * k, x + r * k, y + r * k], fill=col + (22,))
    d.ellipse([x - r, y - r, x + r, y + r], fill=col + (255,))
    lay = lay.filter(ImageFilter.GaussianBlur(3)); im.paste(lay, (0, 0), lay)

def palm(d, x, y, h, lean, col=(20, 70, 40)):
    tx, ty = x + lean, y - h
    d.line([(x, y), (x + lean * .5, y - h * .55), (tx, ty)], fill=(70, 50, 30), width=7)
    for a in (-160, -125, -90, -55, -20, 15, 200):
        ang = math.radians(a)
        ex, ey = tx + math.cos(ang) * h * .55, ty + math.sin(ang) * h * .38 + h * .12
        d.line([(tx, ty), ((tx + ex) / 2, (ty + ey) / 2 - 14), (ex, ey)], fill=col, width=6)

def save(im, n): im.save(f"{OUT}/chamos_fondo_{n}.webp", quality=72, method=6)

# 1 Salto Angel
im = grad([(0, (255, 214, 170)), (.35, (252, 168, 140)), (.6, (120, 160, 200)), (1, (60, 120, 130))]); d = ImageDraw.Draw(im)
sun(im, 520, 300, 38); clouds(im, [220, 420], 110, 2)
d.polygon([(130, 900), (150, 420), (190, 380), (460, 370), (520, 420), (560, 900)], fill=(52, 58, 70))
d.polygon([(150, 420), (190, 380), (210, 400), (170, 900), (130, 900)], fill=(70, 78, 90))
d.rectangle([330, 372, 350, 880], fill=(250, 252, 255)); d.rectangle([336, 372, 344, 880], fill=(255, 255, 255))
lay = Image.new("RGBA", (W, H), (0, 0, 0, 0)); ld = ImageDraw.Draw(lay)
ld.ellipse([230, 840, 450, 960], fill=(255, 255, 255, 170)); im.paste(lay.filter(ImageFilter.GaussianBlur(10)), (0, 0), lay.filter(ImageFilter.GaussianBlur(10)))
d = ImageDraw.Draw(im); ridge(d, 980, 60, (30, 96, 56), 3); ridge(d, 1080, 60, (22, 76, 44), 4); ridge(d, 1190, 50, (14, 56, 32), 5)
save(im, 1)

# 2 Roraima
im = grad([(0, (70, 60, 130)), (.35, (230, 110, 120)), (.6, (255, 190, 120)), (1, (60, 90, 70))]); d = ImageDraw.Draw(im)
sun(im, 360, 640, 46, (255, 225, 150))
d.polygon([(-20, 900), (40, 560), (60, 520), (330, 510), (350, 560), (400, 900)], fill=(86, 70, 100))
d.polygon([(300, 900), (360, 600), (380, 560), (700, 550), (730, 600), (760, 900)], fill=(70, 56, 92))
clouds(im, [700, 780, 860], 170, 7)
d = ImageDraw.Draw(im); ridge(d, 1010, 70, (46, 90, 62), 8); ridge(d, 1120, 60, (28, 66, 46), 9); ridge(d, 1220, 40, (16, 44, 30), 10)
save(im, 2)

# 3 Los Roques
im = grad([(0, (130, 205, 245)), (.42, (200, 238, 250)), (.43, (60, 200, 200)), (1, (20, 120, 160))]); d = ImageDraw.Draw(im)
sun(im, 560, 220, 40); clouds(im, [180, 330], 150, 11)
d.ellipse([-120, 640, 420, 720], fill=(246, 232, 190)); d.ellipse([430, 700, 860, 760], fill=(246, 232, 190))
for x, y, h, l in [(80, 700, 190, 30), (150, 690, 150, -25), (600, 745, 150, 25)]: palm(d, x, y, h, l)
d.polygon([(300, 900), (420, 900), (400, 830), (320, 830)], fill=(255, 255, 255)); d.polygon([(360, 700 + 100), (360, 640 + 130), (410, 830)], fill=(255, 255, 255))
d.line([(360, 740), (360, 905)], fill=(110, 80, 60), width=4); d.polygon([(362, 745), (362, 880), (425, 880)], fill=(255, 250, 240)); d.polygon([(358, 760), (358, 880), (310, 880)], fill=(255, 205, 80))
d.polygon([(290, 888), (430, 888), (410, 925), (310, 925)], fill=(190, 60, 50))
lay = Image.new("RGBA", (W, H), (0, 0, 0, 0)); ld = ImageDraw.Draw(lay)
for y in range(960, 1280, 26): ld.arc([-40, y, W + 40, y + 70], 200, 340, fill=(255, 255, 255, 120), width=4)
im.paste(lay, (0, 0), lay); save(im, 3)

# 4 El Avila
im = grad([(0, (30, 60, 130)), (.4, (250, 140, 100)), (.62, (255, 200, 130)), (1, (30, 40, 70))]); d = ImageDraw.Draw(im)
sun(im, 160, 640, 36, (255, 220, 150)); clouds(im, [250, 400], 90, 13)
ridge(d, 760, 190, (58, 96, 92), 14, .008); ridge(d, 860, 120, (36, 76, 72), 15, .01)
d.rectangle([0, 1000, W, H], fill=(24, 30, 52))
rnd = random.Random(16); x = 0
while x < W:
    bw = rnd.randint(36, 80); bh = rnd.randint(90, 330); top = 1000 - bh
    d.rectangle([x, top, x + bw, 1000], fill=(30, 38, 66))
    for wy in range(top + 12, 990, 22):
        for wx in range(x + 8, x + bw - 6, 14):
            if rnd.random() < .35: d.rectangle([wx, wy, wx + 5, wy + 8], fill=(255, 214, 120))
    x += bw + 4
d.rectangle([600, 560, 624, 1000], fill=(26, 32, 58)); d.rectangle([640, 600, 664, 1000], fill=(26, 32, 58))
ridge(d, 1160, 40, (16, 22, 40), 17); save(im, 4)

# 5 Medanos de Coro
im = grad([(0, (110, 170, 230)), (.5, (250, 220, 170)), (1, (240, 190, 120))]); d = ImageDraw.Draw(im)
sun(im, 520, 240, 44); clouds(im, [160, 320], 130, 18)
d.rectangle([0, 700, W, 760], fill=(80, 170, 200))
cols = [(238, 196, 128), (226, 170, 98), (206, 140, 78), (184, 112, 62)]
for i, c in enumerate(cols): ridge(d, 760 + i * 120, 90 - i * 12, c, 20 + i, .009 + i * .002, 4)
save(im, 5)

# 6 Teleferico Merida
im = grad([(0, (96, 150, 220)), (.5, (200, 224, 245)), (1, (240, 246, 252))]); d = ImageDraw.Draw(im)
clouds(im, [200, 460], 160, 22)
d.polygon([(-20, 760), (150, 330), (230, 470), (330, 260), (470, 560), (560, 400), (740, 760)], fill=(116, 134, 170))
d.polygon([(150, 330), (190, 400), (160, 420), (130, 380)], fill=(250, 252, 255)); d.polygon([(330, 260), (385, 360), (350, 350), (310, 380), (290, 320)], fill=(250, 252, 255)); d.polygon([(560, 400), (600, 470), (560, 460), (525, 480)], fill=(250, 252, 255))
ridge(d, 880, 90, (70, 110, 96), 23); 
d.line([(0, 650), (W, 960)], fill=(50, 50, 60), width=3)
d.rectangle([350, 780, 410, 830], fill=(210, 50, 50)); d.rectangle([358, 790, 402, 812], fill=(180, 220, 245)); d.line([(380, 780), (380, 774)], fill=(50, 50, 60), width=3)
for x in range(0, W, 46):
    y = 1010 + (x % 92) // 4; d.polygon([(x, y), (x + 22, y - 90), (x + 44, y)], fill=(20, 70, 50))
d.rectangle([0, 1040, W, H], fill=(20, 70, 50)); save(im, 6)

# 7 Comic antiguo
im = Image.new("RGB", (W, H), (244, 232, 196)); d = ImageDraw.Draw(im)
for row, y in enumerate(range(0, H, 28)):
    for x in range(-14 if row % 2 else 0, W, 28):
        r = 3 + 8 * (y / H); d.ellipse([x - r, y - r, x + r, y + r], fill=(222, 120, 100) if (x // 28 + row) % 3 else (110, 150, 196))
for cx, cy, rr, col in [(190, 330, 150, (255, 213, 74)), (520, 800, 170, (255, 255, 255))]:
    pts = []
    for i in range(32):
        a = i * math.pi / 16; r = rr if i % 2 == 0 else rr * .62; pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    d.polygon(pts, fill=col, outline=(30, 30, 30)); 
    d.line(pts + [pts[0]], fill=(30, 30, 30), width=6)
try: f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 78)
except Exception: f = ImageFont.load_default()
d.text((190, 330), "¡POW!", font=f, fill=(200, 30, 40), anchor="mm", stroke_width=5, stroke_fill=(30, 30, 30))
d.text((520, 800), "¡CHAMO!", font=f, fill=(30, 70, 160), anchor="mm", stroke_width=5, stroke_fill=(30, 30, 30))
d.rectangle([20, 20, W - 20, H - 20], outline=(30, 30, 30), width=8); save(im, 7)

# 8 Anime generico (original, pastel)
im = grad([(0, (140, 190, 255)), (.5, (255, 200, 230)), (1, (255, 236, 210))]); d = ImageDraw.Draw(im)
sun(im, 360, 420, 70, (255, 250, 230)); clouds(im, [300, 520, 760, 980], 200, 31)
rnd = random.Random(32)
for _ in range(60):
    x, y = rnd.randint(0, W), rnd.randint(0, H); s = rnd.randint(6, 13); a = rnd.uniform(0, 3)
    d.ellipse([x, y, x + s * 1.6, y + s], fill=(255, 190, 215)); 
d.line([(0, 1130), (W, 1060)], fill=(60, 60, 90), width=4)
for x in range(30, W, 120): d.line([(x, 1118 - x * .1), (x, 1190 - x * .1)], fill=(60, 60, 90), width=5)
save(im, 8)

# 9 Ciudad de heroes (original)
im = grad([(0, (14, 18, 48)), (.6, (60, 40, 110)), (1, (150, 70, 120))]); d = ImageDraw.Draw(im)
rnd = random.Random(40)
for _ in range(90): x, y = rnd.randint(0, W), rnd.randint(0, 700); d.ellipse([x, y, x + 2, y + 2], fill=(255, 255, 255))
d.ellipse([480, 130, 620, 270], fill=(255, 246, 214)); d.ellipse([510, 130, 650, 270], fill=(14, 18, 48))
lay = Image.new("RGBA", (W, H), (0, 0, 0, 0)); ld = ImageDraw.Draw(lay)
ld.polygon([(180, 1000), (150, 300), (330, 300)], fill=(255, 245, 190, 70)); im.paste(lay.filter(ImageFilter.GaussianBlur(6)), (0, 0), lay.filter(ImageFilter.GaussianBlur(6)))
d = ImageDraw.Draw(im); x = 0
while x < W:
    bw = rnd.randint(50, 100); bh = rnd.randint(220, 640); top = 1100 - bh
    d.rectangle([x, top, x + bw, H], fill=(18, 16, 40))
    for wy in range(top + 14, 1090, 26):
        for wx in range(x + 8, x + bw - 8, 16):
            if rnd.random() < .3: d.rectangle([wx, wy, wx + 6, wy + 10], fill=(255, 222, 130))
    x += bw + 3
d.ellipse([140, 270, 220, 330], outline=(255, 245, 190), width=0)
save(im, 9)
print("ok")
