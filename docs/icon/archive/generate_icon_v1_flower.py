"""从 icon_design.svg 生成 Android 启动图标各密度资源。

运行：
  cd <项目>/.workbuddy/tools
  .venv/Scripts/python.exe ../../docs/icon/generate_icon.py
依赖：resvg-py + pillow（已装在 .workbuddy/tools/.venv，走阿里云镜像源）
"""
import io
import os
import resvg_py
from PIL import Image, ImageDraw

SVG = os.path.join(os.path.dirname(__file__), "icon_design.svg")
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res")

# 自适应图标前景：108dp 基准画布；密度倍率 mdpi=1x
DENSITIES = {
    "mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0,
}


def render_svg(size_px: int) -> Image.Image:
    png = resvg_py.svg_to_bytes(svg_path=SVG, width=size_px, height=size_px)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def make_legacy(size_px: int) -> Image.Image:
    """传统圆形图标：浅色圆底 + 缩到 72% 的花朵（留白更足）."""
    canvas = Image.new("RGBA", (size_px, size_px), (0, 0, 0, 0))
    d = ImageDraw.Draw(canvas)
    # 浅色圆底（与 adaptive 背景一致的淡色）
    pad = max(1, int(size_px * 0.02))
    d.ellipse([pad, pad, size_px - pad, size_px - pad], fill=(247, 249, 253, 255))
    # SVG 内花朵本体（含自身留白）实际直径约占画布 46%，
    # 按图标 1.28 倍缩放后花朵直径约占圆 60%，留白充足又不失存在感
    fg_size = int(size_px * 1.28)
    fg = render_svg(fg_size)
    # 前景大于画布时，从中心裁剪同尺寸区域再合成
    if fg_size > size_px:
        crop = (fg_size - size_px) // 2
        fg = fg.crop((crop, crop, crop + size_px, crop + size_px))
        x = y = 0
    else:
        x = (size_px - fg_size) // 2
        y = (size_px - fg_size) // 2
    canvas.alpha_composite(fg, (x, y))
    # 裁成圆形
    mask = Image.new("L", (size_px, size_px), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size_px - 1, size_px - 1], fill=255)
    canvas.putalpha(mask)
    return canvas


def main():
    for name, scale in DENSITIES.items():
        # 自适应前景 108dp
        fg_size = round(108 * scale)
        fg = render_svg(fg_size)
        fg.save(os.path.join(OUT, f"mipmap-{name}", "ic_launcher_foreground.png"))
        # 传统圆图标 48dp
        legacy_size = round(48 * scale)
        legacy = make_legacy(legacy_size)
        legacy.save(os.path.join(OUT, f"mipmap-{name}", "ic_launcher.png"))
        legacy.save(os.path.join(OUT, f"mipmap-{name}", "ic_launcher_round.png"))
        print(f"{name}: foreground={fg_size}px, legacy={legacy_size}px")


if __name__ == "__main__":
    main()
