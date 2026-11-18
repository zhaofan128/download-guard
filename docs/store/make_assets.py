#!/usr/bin/env python3
"""
生成应用商店素材（图标 / 宣传图）。

为什么不用现有 ic_launcher.png：那张是真人照片拼贴，用作商店图标会涉及
肖像权，且与产品功能无关。这里用纯几何图形重绘：深色底 + 青色盾牌 +
下载箭头，和 App 内部的玻璃拟态配色一致。

输出：
    assets/icon-1024.png            源图
    assets/icon-512.png             商店图标（Google Play / 国内商店通用）
    assets/icon-round-512.png       圆形版本（部分商店要求）
    assets/feature-graphic-1024x500.png  宣传横幅（Google Play 必需，国内商店可选用）

依赖：Pillow（沙箱已装）。中文字体使用系统 Noto Sans CJK。
"""
from __future__ import annotations

import math
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "assets")
os.makedirs(OUT, exist_ok=True)

S = 4096              # 4x 超采样，最后缩到 1024
BG_TOP = (11, 18, 32)
BG_BOTTOM = (17, 24, 39)
TEAL = (94, 234, 212)
CYAN = (56, 189, 248)
INK = (11, 18, 32)


def vertical_gradient(size: tuple[int, int], top: tuple[int, int, int],
                      bottom: tuple[int, int, int]) -> Image.Image:
    w, h = size
    grad = Image.new("RGB", (1, h))
    px = grad.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    return grad.resize((w, h), Image.BILINEAR)


def rounded_mask(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return mask


def _quad(p0, p1, p2, steps: int = 28):
    """二次贝塞尔采样。盾牌下半部的收尖靠它，避免手算三角函数出错。"""
    out = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        out.append((
            u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
            u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1],
        ))
    return out


def shield_points(cx: float, top: float, width: float, bottom: float):
    """盾牌轮廓：上边切角、两肩直下、底部收尖。

    不用圆弧近似，全部是直线 + 二次贝塞尔，点序固定为顺时针，
    这样 PIL 的 polygon 填充不会因为自交而整块消失。
    """
    half = width / 2
    left, right = cx - half, cx + half
    shoulder = top + width * 0.52
    chamfer = width * 0.12

    pts = [(left + chamfer, top), (right - chamfer, top), (right, top + chamfer)]
    pts.append((right, shoulder))
    # 右侧肩部 → 底部尖端
    pts += _quad((right, shoulder), (right, bottom - width * 0.10), (cx, bottom))[1:]
    # 底部尖端 → 左侧肩部
    pts += _quad((cx, bottom), (left, bottom - width * 0.10), (left, shoulder))[1:]
    # 左侧肩部 → 左上切角，补上这个点才能和起点闭合出对称的斜角
    pts.append((left, top + chamfer))
    return pts


def draw_icon(size: int = 1024) -> Image.Image:
    canvas = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    bg = vertical_gradient((S, S), BG_TOP, BG_BOTTOM).convert("RGBA")
    canvas.paste(bg, (0, 0), rounded_mask(S, int(S * 0.22)))

    # 右上角青色光晕，避免整块死黑
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([S * 0.52, -S * 0.28, S * 1.18, S * 0.42], fill=(56, 189, 248, 70))
    glow = glow.filter(ImageFilter.GaussianBlur(S * 0.08))
    canvas.alpha_composite(Image.composite(glow, Image.new("RGBA", (S, S), (0, 0, 0, 0)),
                                           rounded_mask(S, int(S * 0.22))))

    # 盾牌渐变。mask 必须用 L 图层：在 RGBA 上 fill=255 会被解释成
    # (255,0,0,0)，alpha 为 0，polygon 等于没画（第一次就踩了这个坑）。
    shield_mask = Image.new("L", (S, S), 0)
    pts = shield_points(S / 2, S * 0.24, S * 0.46, S * 0.82)
    ImageDraw.Draw(shield_mask).polygon(pts, fill=255)
    grad = vertical_gradient((S, S), TEAL, CYAN).convert("RGBA")
    canvas.paste(grad, (0, 0), shield_mask)

    # 盾牌内负空间：下载箭头 + 托盘
    inner = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    idd = ImageDraw.Draw(inner)
    cx = S / 2
    stem_w = S * 0.058
    idd.rounded_rectangle(
        [cx - stem_w / 2, S * 0.36, cx + stem_w / 2, S * 0.58],
        radius=stem_w / 2, fill=INK
    )
    head_w = S * 0.165
    idd.polygon([
        (cx - head_w / 2, S * 0.545),
        (cx + head_w / 2, S * 0.545),
        (cx, S * 0.665),
    ], fill=INK)
    tray_w = S * 0.225
    idd.rounded_rectangle(
        [cx - tray_w / 2, S * 0.705, cx + tray_w / 2, S * 0.735],
        radius=S * 0.015, fill=INK
    )
    # 只在盾牌范围内挖空，边缘不会溢出
    inner.putalpha(Image.composite(inner.split()[3], Image.new("L", (S, S), 0), shield_mask))
    canvas.alpha_composite(inner)

    return canvas.resize((size, size), Image.LANCZOS)


def draw_feature() -> Image.Image:
    w, h = 1024, 500
    canvas = vertical_gradient((w, h), (9, 14, 26), (17, 24, 39)).convert("RGBA")

    # 右侧光带
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([w * 0.55, -h * 0.55, w * 1.25, h * 1.15], fill=(45, 212, 191, 46))
    gd.ellipse([w * 0.72, h * 0.35, w * 1.35, h * 1.5], fill=(56, 189, 248, 40))
    canvas.alpha_composite(glow.filter(ImageFilter.GaussianBlur(60)))

    # 左侧图标
    icon = draw_icon(1024).resize((210, 210), Image.LANCZOS)
    canvas.alpha_composite(icon, (72, 145))

    bold = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
    regular = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
    title = ImageFont.truetype(bold, 74)
    subtitle = ImageFont.truetype(regular, 29)
    footnote = ImageFont.truetype(regular, 22)

    d = ImageDraw.Draw(canvas)
    d.text((315, 168), "下载守护", font=title, fill=(241, 245, 249))
    d.text((318, 265), "多线程 · 断点续传 · 网页嗅探", font=subtitle, fill=(148, 233, 226))
    d.text((318, 308), "站点规则让网盘直链不再 403", font=subtitle, fill=(148, 163, 184))
    d.text((76, 418), "不收集数据 · 无广告 · 全部在本地完成", font=footnote, fill=(100, 116, 139))

    # 底部细线
    d.line([(76, 404), (948, 404)], fill=(45, 212, 191, 90), width=2)
    return canvas.convert("RGB")


def main() -> None:
    icon = draw_icon(1024)
    icon.save(os.path.join(OUT, "icon-1024.png"))
    icon.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, "icon-512.png"))

    # 圆形版：部分商店（如应用宝）要求圆形图标
    round_mask = Image.new("L", (1024, 1024), 0)
    ImageDraw.Draw(round_mask).ellipse([0, 0, 1023, 1023], fill=255)
    round_icon = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    round_icon.paste(icon, (0, 0), round_mask)
    round_icon.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, "icon-round-512.png"))

    draw_feature().save(os.path.join(OUT, "feature-graphic-1024x500.png"))

    for name in ("icon-1024.png", "icon-512.png", "icon-round-512.png",
                 "feature-graphic-1024x500.png"):
        path = os.path.join(OUT, name)
        with Image.open(path) as im:
            print(f"{name}\t{im.size[0]}x{im.size[1]}\t{os.path.getsize(path)} bytes")


if __name__ == "__main__":
    main()
