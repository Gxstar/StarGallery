# -*- coding: utf-8 -*-
"""StarGallery v2 图标设计：候选方案 + 预览拼图。

运行（项目根目录）：
  .workbuddy/tools/.venv/Scripts/python.exe docs/icon/design_v2.py preview
  .workbuddy/tools/.venv/Scripts/python.exe docs/icon/design_v2.py build A

画布约定：108 x 108 viewBox，中心 (54,54)；自适应图标可见视口是中央 72dp 圆（半径 36）。
前景 SVG 不含背景色，背景由 ic_launcher_background 矢量提供。
"""
import io
import os
import sys

import resvg_py
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_RES = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "res"))
PREVIEW_DIR = os.path.join(HERE, "preview")


# ---------------------------------------------------------------- 基础形状


def star(cx, cy, r, k=0.18, k2=0.42):
    """四芒星：四个尖点 + 内凹贝塞尔（k/k2 越大腰部越饱满）。"""
    a, b = r * k, r * k2
    return (
        f"M{cx:.2f},{cy - r:.2f}"
        f"C{cx + a:.2f},{cy - b:.2f} {cx + b:.2f},{cy - a:.2f} {cx + r:.2f},{cy:.2f}"
        f"C{cx + b:.2f},{cy + a:.2f} {cx + a:.2f},{cy + b:.2f} {cx:.2f},{cy + r:.2f}"
        f"C{cx - a:.2f},{cy + b:.2f} {cx - b:.2f},{cy + a:.2f} {cx - r:.2f},{cy:.2f}"
        f"C{cx - b:.2f},{cy - a:.2f} {cx - a:.2f},{cy - b:.2f} {cx:.2f},{cy - r:.2f}Z"
    )


SHADOW = """
  <filter id="cardShadow" x="-45%" y="-45%" width="190%" height="190%">
    <feDropShadow dx="0" dy="1.5" stdDeviation="1.9" flood-color="#B9805A" flood-opacity="0.25"/>
  </filter>
  <filter id="starShadow" x="-40%" y="-40%" width="180%" height="180%">
    <feDropShadow dx="0" dy="0.9" stdDeviation="1.3" flood-color="#DB6B33" flood-opacity="0.30"/>
  </filter>
"""


def svg_wrap(body, defs=""):
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" '
        'width="432" height="432"><defs>' + defs + SHADOW + "</defs>" + body + "</svg>"
    )


def photo_card(cx, cy, side, rx, frame, rot, fill, cid, shadow=True, extra="", edge=None):
    """白边相纸 + 圆角相片内容（用 clipPath 裁切）。edge=发丝描边色，用于在浅底上勾出相纸边缘。"""
    ix = cx - side / 2 + frame
    iw = side - frame * 2
    irx = max(2.0, rx - frame)
    sh = ' filter="url(#cardShadow)"' if shadow else ""
    if edge:
        sh += f' stroke="{edge[0]}" stroke-opacity="{edge[1]}" stroke-width="{edge[2]}"'
    return f"""
  <g transform="rotate({rot} {cx} {cy})">
    <rect x="{cx - side / 2:.2f}" y="{cy - side / 2:.2f}" width="{side:.2f}" height="{side:.2f}"
          rx="{rx:.2f}" fill="#FFFFFF"{sh}/>
    <g clip-path="url(#{cid})">
      <rect x="{ix:.2f}" y="{ix:.2f}" width="{iw:.2f}" height="{iw:.2f}" rx="{irx:.2f}" fill="{fill}"/>
      {extra}
    </g>
  </g>"""


def clip_def(cid, cx, cy, side, rx, frame):
    ix = cx - side / 2 + frame
    iw = side - frame * 2
    irx = max(2.0, rx - frame)
    return (
        f'<clipPath id="{cid}"><rect x="{ix:.2f}" y="{ix:.2f}" width="{iw:.2f}" '
        f'height="{iw:.2f}" rx="{irx:.2f}"/></clipPath>'
    )


# ---------------------------------------------------------------- 方案 A：星芒相纸
# 一张微微倾斜的白边相纸，相片里是「蓝到橘」的天光，白色四芒星是画面主体。

A_CX, A_CY, A_SIDE = 54.0, 54.0, 42.0
A_K = A_SIDE / 38.4  # 以 38.4 为基准等比缩放内部元素
A_RX, A_FRAME = 9.6 * A_K, 2.5 * A_K


def _a(cx, cy, r):
    """把按 38.4 基准写的坐标换算到当前相纸尺寸。"""
    return star(54 + (cx - 54) * A_K, 54 + (cy - 54) * A_K, r * A_K)


SVG_A = svg_wrap(
    body=photo_card(
        A_CX, A_CY, A_SIDE, A_RX, A_FRAME, -6, "url(#skyA)", "clipA",
        edge=None,
        extra=(
            # 落日即四芒星
            f'<path d="{_a(54.0, 54.6, 8.9)}" fill="#FFFFFF" fill-opacity="0.99" '
            'filter="url(#starShadow)"/>'
            # 天空中的小星
            f'<path d="{_a(44.0, 43.0, 2.9)}" fill="#FFFFFF" fill-opacity="0.92"/>'
            f'<path d="{_a(65.2, 44.6, 1.9)}" fill="#FFFFFF" fill-opacity="0.75"/>'
        ),
    ),
    defs="""
  <linearGradient id="skyA" x1="0.02" y1="0" x2="0.30" y2="1">
    <stop offset="0" stop-color="#8FCDEF"/>
    <stop offset="0.26" stop-color="#CDE5F2"/>
    <stop offset="0.52" stop-color="#FFD3A6"/>
    <stop offset="1" stop-color="#FF9663"/>
  </linearGradient>
"""
    + clip_def("clipA", A_CX, A_CY, A_SIDE, A_RX, A_FRAME),
)

# ---------------------------------------------------------------- 方案 D：叠影相册
# 后一张冷色照片露出边缘，前一张白边暖色照片，星芒落在前景相片里。

SVG_D = svg_wrap(
    body="""
  <g transform="rotate(-13 48.8 50.0)">
    <rect x="32.5" y="33.7" width="32.5" height="32.5" rx="8.6"
          fill="url(#coolD)" filter="url(#cardShadow)"/>
  </g>
"""
    + photo_card(
        56.5, 57.5, 35.0, 9.0, 2.2, 6.5, "url(#warmD)", "clipD",
        extra=(
            f'<path d="{star(56.6, 56.0, 7.8)}" fill="#FFFFFF" fill-opacity="0.99"/>'
            f'<path d="{star(48.6, 49.2, 2.3)}" fill="#FFFFFF" fill-opacity="0.85"/>'
            f'<path d="{star(65.4, 61.6, 1.8)}" fill="#FFFFFF" fill-opacity="0.66"/>'
        ),
    ),
    defs="""
  <linearGradient id="coolD" x1="0" y1="0" x2="0.45" y2="1">
    <stop offset="0" stop-color="#84CFEE"/>
    <stop offset="1" stop-color="#49B3DA"/>
  </linearGradient>
  <linearGradient id="warmD" x1="0.10" y1="0" x2="0.55" y2="1">
    <stop offset="0" stop-color="#FFCE9C"/>
    <stop offset="1" stop-color="#FF9468"/>
  </linearGradient>
"""
    + clip_def("clipD", 56.5, 57.5, 35.0, 9.0, 2.2),
)

# ---------------------------------------------------------------- 方案 C：星窗
# 圆角相框描边，中间一颗大四芒星，右上角小星点缀。

SVG_C = svg_wrap(
    body=f"""
  <rect x="35.2" y="35.2" width="37.6" height="37.6" rx="12" fill="none"
        stroke="url(#frameC)" stroke-width="3.4"/>
  <path d="{star(53.6, 54.6, 12.9, 0.16, 0.41)}" fill="url(#starC)"/>
  <path d="{star(71.4, 37.8, 3.6, 0.18, 0.42)}" fill="#FFB65C"/>
""",
    defs="""
  <linearGradient id="frameC" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#FFD08A"/>
    <stop offset="1" stop-color="#F2873C"/>
  </linearGradient>
  <linearGradient id="starC" x1="0.15" y1="0" x2="0.8" y2="1">
    <stop offset="0" stop-color="#FFCE72"/>
    <stop offset="1" stop-color="#F0822A"/>
  </linearGradient>
""",
)

CANDS = {"A": SVG_A, "D": SVG_D, "C": SVG_C}

# ---------------------------------------------------------------- 渲染


def render(svg_text, size):
    png = resvg_py.svg_to_bytes(svg_string=svg_text, width=size, height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")


BG_TOP = (255, 250, 242, 255)
BG_BOTTOM = (255, 229, 201, 255)


def bg_tile(size):
    grad = Image.new("RGBA", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        grad.putpixel(
            (0, y),
            tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(4)),
        )
    return grad.resize((size, size))


def compose(svg_text, size):
    """渲染 108dp 画布，再裁出系统实际可见的中央 72dp 区域（自适应图标视口）。"""
    canvas = size * 108 // 72
    img = bg_tile(canvas)
    img.alpha_composite(render(svg_text, canvas), (0, 0))
    off = (canvas - size) // 2
    return img.crop((off, off, off + size, off + size))


def mask_round(img, radius_ratio):
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255
    )
    out = img.copy()
    out.putalpha(mask)
    return out


def mask_circle(img):
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def preview():
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    big, small, pad = 300, 96, 26
    cw = big * 3 + small + pad * 4
    ch = (big + pad) * len(CANDS) + pad + 40
    sheet = Image.new("RGBA", (cw, ch), (243, 243, 246, 255))
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.load_default(size=20)
    except Exception:
        font = None

    for ci, (name, svg_text) in enumerate(CANDS.items()):
        y = pad + ci * (big + pad) + 34
        base = compose(svg_text, big)
        tiles = [
            mask_circle(base),
            mask_round(base, 0.33),
            base,
            mask_circle(compose(svg_text, small)),
        ]
        x = pad
        for i, t in enumerate(tiles):
            sheet.alpha_composite(t, (x, y))
            x += (small if i == 3 else big) + pad
        if font:
            d.text((pad, y - 30), f"方案 {name}", fill=(40, 40, 45, 255), font=font)

    path = os.path.join(PREVIEW_DIR, "candidates.png")
    sheet.save(path)
    print("preview ->", path, sheet.size)
    for name, svg_text in CANDS.items():
        mask_circle(compose(svg_text, 432)).save(os.path.join(PREVIEW_DIR, f"{name}_circle.png"))


DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LEGACY_FACTOR = 1.61  # 传统圆形图标：前景相对 108dp 画布放大倍数


def circle_bg(size):
    """传统图标底：暖白圆形 + 竖向渐变。"""
    tile = bg_tile(size)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(tile, (0, 0), mask)
    return out


def make_legacy(svg_text, size):
    canvas = circle_bg(size)
    fg_size = max(1, round(size * LEGACY_FACTOR))
    fg = render(svg_text, fg_size)
    if fg_size > size:
        off = (fg_size - size) // 2
        fg = fg.crop((off, off, off + size, off + size))
        pos = (0, 0)
    else:
        pos = ((size - fg_size) // 2, (size - fg_size) // 2)
    canvas.alpha_composite(fg, pos)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    canvas.putalpha(mask)
    return canvas


def build(name):
    svg_text = CANDS[name]
    for dens, scale in DENSITIES.items():
        folder = os.path.join(OUT_RES, f"mipmap-{dens}")
        fg_px = round(108 * scale)
        render(svg_text, fg_px).save(os.path.join(folder, "ic_launcher_foreground.png"))
        legacy_px = round(48 * scale)
        legacy = make_legacy(svg_text, legacy_px)
        legacy.save(os.path.join(folder, "ic_launcher.png"))
        legacy.save(os.path.join(folder, "ic_launcher_round.png"))
        print(f"{dens}: foreground={fg_px}px, legacy={legacy_px}px")
    out_svg = os.path.join(HERE, f"icon_design_v2_{name}.svg")
    with open(out_svg, "w", encoding="utf-8") as f:
        f.write(svg_text)
    print("svg ->", out_svg)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "preview"
    if cmd == "build":
        build(sys.argv[2] if len(sys.argv) > 2 else "A")
    else:
        preview()
