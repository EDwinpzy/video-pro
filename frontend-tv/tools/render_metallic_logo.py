# -*- coding: utf-8 -*-
"""将 20_金属_Metallic.svg 品牌标渲染为 App 各面资源（与 SVG 逐像素等价的实现）。

SVG 要素（200×200 画布）：
  - 圆角矩形 (36,54)-(164,146) r=18，线性渐变 userSpaceOnUse，gradientTransform=matrix(128 92 -92 128 0 0)
    渐变线起点 (0,0) → 终点 (128,92)，stops: 0:#8A8F98 .3:#E6E9EC .5:#9AA0A8 .7:#F2F4F6 1:#6E737C
  - 播放三角 (84,78)(84,122)(122,100)，填充 #1C1C1E
输出：drawable/my_tv_logo.png、mipmap-{xhdpi,xxxhdpi}/ic_launcher_fg.png、drawable/ic_banner.png
"""
import numpy as np
from PIL import Image

SS = 3  # 超采样倍率（抗锯齿）

STOPS = [
    (0.0, (0x8A, 0x8F, 0x98)),
    (0.3, (0xE6, 0xE9, 0xEC)),
    (0.5, (0x9A, 0xA0, 0xA8)),
    (0.7, (0xF2, 0xF4, 0xF6)),
    (1.0, (0x6E, 0x73, 0x7C)),
]
BLACK = np.array([0x1C, 0x1C, 0x1E], dtype=np.float64)


def gradient_color(xs, ys):
    """xs/ys 为 SVG 用户空间坐标（浮点数组），返回 (n,3) 颜色。"""
    t = (xs * 128.0 + ys * 92.0) / (128.0 ** 2 + 92.0 ** 2)
    t = np.clip(t, 0.0, 1.0)
    out = np.zeros((xs.size, 3))
    for (o0, c0), (o1, c1) in zip(STOPS[:-1], STOPS[1:]):
        seg = (t >= o0) & (t <= o1)
        k = np.zeros(xs.size)
        k[seg] = (t[seg] - o0) / (o1 - o0)
        for ch in range(3):
            out[seg, ch] = c0[ch] + (c1[ch] - c0[ch]) * k[seg]
    return out


def inside_round_rect(xs, ys):
    x0, y0, x1, y1, r = 36.0, 54.0, 164.0, 146.0, 18.0
    cx = np.clip(xs, x0 + r, x1 - r)
    cy = np.clip(ys, y0 + r, y1 - r)
    dx = xs - cx
    dy = ys - cy
    return (dx * dx + dy * dy) <= r * r


def inside_triangle(xs, ys):
    ax, ay = 84.0, 78.0
    bx, by = 84.0, 122.0
    cx, cy = 122.0, 100.0

    def side(px, py, qx, qy):
        return (px - qx) * (ys - qy) - (py - qy) * (xs - qx)

    d1 = side(ax, ay, bx, by)
    d2 = side(bx, by, cx, cy)
    d3 = side(cx, cy, ax, ay)
    neg = (d1 < 0) | (d2 < 0) | (d3 < 0)
    pos = (d1 > 0) | (d2 > 0) | (d3 > 0)
    return ~(neg & pos)


def render_logo(out_px):
    """渲染完整 200×200 画布（含原留白）到 out_px×out_px RGBA。"""
    hi = out_px * SS
    px = (np.arange(hi) + 0.5) / (hi / 200.0)
    gx, gy = np.meshgrid(px, px)
    xs, ys = gx.ravel(), gy.ravel()
    rgba = np.zeros((xs.size, 4))
    rect = inside_round_rect(xs, ys)
    rgba[rect, :3] = gradient_color(xs[rect], ys[rect])
    rgba[rect, 3] = 255.0
    tri = inside_triangle(xs, ys) & rect
    rgba[tri, :3] = BLACK
    img = Image.fromarray(rgba.reshape(hi, hi, 4).astype(np.uint8), "RGBA")
    return img.resize((out_px, out_px), Image.LANCZOS)


def paste_scaled(canvas, logo, box_px):
    """logo（200 画布）缩放到 box_px 居中贴到 canvas。"""
    im = logo.resize((box_px, box_px), Image.LANCZOS)
    w, h = canvas.size
    canvas.alpha_composite(im, ((w - box_px) // 2, (h - box_px) // 2))


def main():
    res = "app/src/main/res"

    # 启动屏 logo：200dp 画布 @4x
    logo800 = render_logo(800)
    logo800.save(f"{res}/drawable/my_tv_logo.png")

    # 桌面图标前景：logo 占画布 80%（圆角遮罩安全区 66.7%，图标角部最远点约 63%）
    for density, px in (("xhdpi", 216), ("xxxhdpi", 432)):
        canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        paste_scaled(canvas, logo800, round(px * 0.8))
        canvas.save(f"{res}/mipmap-{density}/ic_launcher_fg.png")

    # TV banner：640×360 纯黑 + logo 画布 300px 居中
    banner = Image.new("RGBA", (640, 360), (0, 0, 0, 255))
    paste_scaled(banner, logo800, 300)
    banner.convert("RGBA").save(f"{res}/drawable/ic_banner.png")

    # 预览图（黑底拼四宫格便于目检）
    prev = Image.new("RGBA", (820 * 2 + 30, 820 + 380 + 30), (20, 20, 22, 255))
    prev.alpha_composite(logo800, (10, 10))
    fg432 = Image.open(f"{res}/mipmap-xxxhdpi/ic_launcher_fg.png").resize((820, 820), Image.LANCZOS)
    prev.alpha_composite(fg432, (840, 10))
    prev.alpha_composite(banner.resize((1280, 720), Image.LANCZOS), (10, 850))
    prev.convert("RGB").save("tools/metallic_logo_preview.png")
    print("done")


if __name__ == "__main__":
    main()
