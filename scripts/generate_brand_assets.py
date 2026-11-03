#!/usr/bin/env python3
"""生成 mytv 桌面图标与 Android TV banner：冷蓝黑磨砂卡 + 白色定制字标（y 用强调色）。

用法：python3 scripts/generate_brand_assets.py
字形与 res/drawable/wordmark_mytv.xml / docs/brand/*.svg 逐值一致。
"""

import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
SS = 4  # 超采样倍数，最后统一缩小以消除锯齿

# 字标网格：视口 278x98，描边 12，圆头圆角
WM_W, WM_H, WM_STROKE = 278.0, 98.0, 12.0
# 视觉重心：t 顶 y=6 到基线 y=66 的中点，用它对齐卡片中心（忽略 y 的下伸部）
WM_ANCHOR_Y = 36.0
ARCH_STEPS = 120  # 半圆拱的圆心线采样段数
WM_SS = 4         # 字标独立超采样，多边形笔画靠它消除锯齿
INK_ALPHA = 235  # @color/ui_wordmark = #EBFFFFFF
ACCENT = (175, 198, 255, INK_ALPHA)  # @color/accent #AFC6FF，只给 y 一个字母用

# 冷蓝黑：比纯黑多一点色温，避开 Apple TV 那种中性黑底 + 居中白字的构图
BG_TOP = (10, 14, 26)
BG_BOTTOM = (2, 3, 8)


def wordmark_image(width_px, weight=1.0, inks=None):
    """按字形本体宽度绘制 mytv 字标；weight 保留可调，默认维持描边 12。

    笔画全部用垂直矩形 + 两端圆帽自绘：Pillow 的粗线按主轴加宽，斜笔会变细。
    inks 可按字母覆盖颜色（如 {"y": 强调色}）。
    返回 (图层, 视觉重心距图层顶部的像素)。
    """
    pad = WM_STROKE * weight  # 圆头外溢余量，加粗时不贴边被裁
    k = width_px / WM_W * WM_SS
    r = WM_STROKE * weight * k / 2.0
    size = (int(math.ceil((WM_W + 2 * pad) * k)), int(math.ceil((WM_H + 2 * pad) * k)))
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    def P(x, y):
        return (x + pad) * k, (y + pad) * k

    def capsule(p1, p2, ink):
        (x1, y1), (x2, y2) = p1, p2
        dx, dy = x2 - x1, y2 - y1
        length = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / length * r, dx / length * r
        draw.polygon([(x1 + nx, y1 + ny), (x2 + nx, y2 + ny), (x2 - nx, y2 - ny), (x1 - nx, y1 - ny)], fill=ink)
        for cx, cy in (p1, p2):
            draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ink)

    def polyline(pts, ink):
        for a, b in zip(pts, pts[1:]):
            capsule(a, b, ink)

    def stroke(ink, *coords):
        # group translate(6,-24)：下面用的是已换算好的视口绝对坐标
        polyline([P(x, y) for x, y in zip(coords[::2], coords[1::2])], ink)

    def arch(ink, cx):
        # 拱按真实圆心线采样：半径 22、圆心 (cx,44)，与基线、x-height 双相切
        polyline([P(cx + 22 * math.cos(math.pi + math.pi * i / ARCH_STEPS),
                     44 + 22 * math.sin(math.pi + math.pi * i / ARCH_STEPS))
                 for i in range(ARCH_STEPS + 1)], ink)

    def ink_of(letter):
        return (inks or {}).get(letter, (255, 255, 255, INK_ALPHA))

    m, y, t, v = ink_of("m"), ink_of("y"), ink_of("t"), ink_of("v")
    stroke(m, 6, 22, 6, 66)                   # m 左竖笔
    arch(m, 28)
    arch(m, 72)                               # m 两只 r22 半圆拱
    stroke(m, 50, 44, 50, 66)                 # m 中竖笔
    stroke(m, 94, 44, 94, 66)                 # m 右竖笔
    stroke(y, 118, 22, 138, 66)               # y 左斜笔，止于交点
    stroke(y, 158, 22, 126.2, 92)             # y 右斜笔，穿过交点落到下伸部
    stroke(t, 195, 6, 195, 66)                # t 直杆，无收脚
    stroke(t, 182, 22, 208, 22)               # t 横杆，压在 x-height 线上
    stroke(v, 232, 22, 252, 66, 272, 22)      # v，尖角由圆帽自然柔化
    final = layer.resize((max(1, round(size[0] / WM_SS)), max(1, round(size[1] / WM_SS))), Image.LANCZOS)
    return final, (WM_ANCHOR_Y + pad) * (width_px / WM_W)


def rounded_mask(size, box, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(box, radius=radius, fill=255)
    return mask


def gradient_box(size_box, top, bottom):
    w, h = size_box
    strip = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        # 前 52% 走完上半段，之后压到底色，形成玻璃的厚度感
        f = (t / 0.52) * 0.55 if t <= 0.52 else 0.55 + ((t - 0.52) / 0.48) * 0.45
        strip.putpixel((0, y), tuple(int(round(top[i] + (bottom[i] - top[i]) * f)) for i in range(3)))
    return strip.resize((w, h), Image.BILINEAR)


def frosted(canvas, box, radius, grain_alpha, seed=7, hairline=True):
    """磨砂黑卡：渐变底 + 细颗粒 + 顶部柔光 + 1px 内描边。"""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    mask = rounded_mask(canvas.size, box, radius)

    grad = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    grad.paste(gradient_box((w, h), BG_TOP, BG_BOTTOM), (x0, y0))
    grad.putalpha(mask)
    canvas.alpha_composite(grad)

    # 细颗粒：磨砂玻璃的微小晶粒；密度与振幅都很低，否则随机像素会把 PNG 体积撑得很大
    random.seed(seed)
    grain = Image.new("L", (w, h), 0)
    gd = ImageDraw.Draw(grain)
    for _ in range(int(w * h * 0.006)):
        gd.point((random.randrange(w), random.randrange(h)), fill=random.randint(80, 255))
    grain = grain.filter(ImageFilter.GaussianBlur(1.4))
    white = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    white.putalpha(Image.composite(grain.point(lambda v: int(v * grain_alpha / 255)),
                                   Image.new("L", (w, h), 0),
                                   mask.crop(box)))
    canvas.alpha_composite(white, (x0, y0))

    # 顶部柔光，模拟玻璃反射
    sheen = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(sheen).ellipse([x0 - w * 0.3, y0 - h * 0.6, x1 + w * 0.3, y0 + h * 0.8],
                                  fill=(255, 255, 255, 22))
    sheen = sheen.filter(ImageFilter.GaussianBlur(h * 0.18))
    sheen.putalpha(Image.composite(sheen.split()[3], Image.new("L", canvas.size, 0), mask))
    canvas.alpha_composite(sheen)

    # 内描边：玻璃切口；banner 铺满整张会被桌面裁切，不描边
    if hairline:
        iw = max(1, int(round(min(w, h) * 0.007)))
        edge = Image.new("RGBA", canvas.size, (255, 255, 255, 0))
        ImageDraw.Draw(edge).rounded_rectangle([x0 + iw / 2, y0 + iw / 2, x1 - iw / 2, y1 - iw / 2],
                                               radius=max(0, radius - iw / 2),
                                               outline=(255, 255, 255, 30), width=iw)
        canvas.alpha_composite(edge)


def accent_glow(canvas, fx, fy, fr, alpha=38):
    """右上抹一层强调色光晕，亮度极低，只用来拉开与纯黑的区别。"""
    px = canvas.width
    r = px * fr
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse([px * fx - r, px * fy - r, px * fx + r, px * fy + r],
                                  fill=ACCENT[:3] + (alpha,))
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(r * 0.55)))


def centered_wordmark(canvas, ratio, weight=1.0, inks=None, cx_ratio=0.5, cy_ratio=0.5):
    """字标宽度按画布宽度等比取 ratio，按视觉重心对齐到 (cx_ratio, cy_ratio)。"""
    cw, ch = canvas.size
    wm, anchor = wordmark_image(cw * ratio, weight, inks)
    canvas.alpha_composite(wm, (int(cw * cx_ratio - wm.width / 2), int(ch * cy_ratio - anchor)))


def render_icon(target):
    px = target * SS
    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    # 平台规范：“图标均需直角，系统会自动截取圆角”，所以必须满幅不透明、不烘圆角不描边
    frosted(canvas, [0, 0, px, px], radius=0, grain_alpha=14, hairline=False)
    accent_glow(canvas, 0.76, 0.22, 0.30)
    centered_wordmark(canvas, ratio=0.62, inks={"y": ACCENT})
    return canvas.resize((target, target), Image.LANCZOS)


def render_banner(w, h):
    px_w, px_h = w * SS, h * SS
    canvas = Image.new("RGBA", (px_w, px_h), (0, 0, 0, 0))
    # banner 由系统桌面自行裁圆角，这里铺满不留透明边、不描边
    frosted(canvas, [0, 0, px_w, px_h], radius=0, grain_alpha=12, hairline=False)
    accent_glow(canvas, 0.82, 0.16, 0.34, alpha=34)
    # 字标占宽 55%，对齐芒果TV banner 的视觉重量（实测约 58%）；仍居中，留在安全区内
    centered_wordmark(canvas, ratio=0.55, inks={"y": ACCENT})
    return canvas.resize((w, h), Image.LANCZOS)


def assert_opaque(img, label):
    """Google Play 会因“图标没填满 / 带透明”直接驳回，这里兼做护栏。"""
    w, h = img.size
    for x, y in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        if img.getpixel((x, y))[3] != 255:
            raise AssertionError(f"{label} 角落 ({x},{y}) 不是不透明的")


def save(img, folder, name):
    path = os.path.join(RES, folder, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG", optimize=True)
    print("wrote", os.path.relpath(path, RES), img.size)


# 尺寸按官方 TV app icon 规范，不是手机的那套：
# icon 取 Google TV 规范档位（同时高于手机那套 48/72/96/144/192）
ICON_SIZES = [("mdpi", 90), ("hdpi", 135), ("xhdpi", 180), ("xxhdpi", 270), ("xxxhdpi", 360)]
STORE_ICON_SIZE = 512  # 上架用直角大图，不打包进 APK
# banner 照芒果TV TV版的实际做法：单张、不带密度限定、放 res/drawable/，尺寸 288x162。
# 那是同类国产 TV 桌面上验证过能满幅正常显示的样本；Google TV 模拟器会给 banner 加视差偏移，不能当校准源
BANNER_SIZE = (288, 162)


def main():
    for density, size in ICON_SIZES:
        icon = render_icon(size)
        assert_opaque(icon, f"icon-{density}")
        save(icon, f"mipmap-{density}", "ic_launcher.png")
    banner = render_banner(*BANNER_SIZE)
    assert_opaque(banner, "banner")
    save(banner, "drawable", "tv_banner.png")
    # 商店后台上传用的 512 直角大图，不进 APK
    store_path = os.path.join(ROOT, "docs/brand", f"icon-{STORE_ICON_SIZE}.png")
    render_icon(STORE_ICON_SIZE).save(store_path, "PNG", optimize=True)
    print("wrote", os.path.relpath(store_path, ROOT), (STORE_ICON_SIZE, STORE_ICON_SIZE))
    # 放大预览，肉眼校验字形与磨砂质感
    render_icon(512).save("/tmp/icon_preview.png", "PNG")
    render_banner(960, 540).save("/tmp/banner_preview.png", "PNG")


if __name__ == "__main__":
    main()
