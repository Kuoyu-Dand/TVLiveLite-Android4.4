# -*- coding: utf-8 -*-
"""
生成 app 图标与 TV banner。

设计：DeepSeek 蓝底 + 反色（白）电视轮廓 + 右上角反色 lite。
所有图形都在 4 倍画布上绘制再 LANCZOS 缩到目标尺寸，保证 48px 小图标
的线条和 "lite" 字样不会糊成一团。

用法：python tools/gen_icons.py
"""
import io
import os
from PIL import Image, ImageDraw, ImageFont

RES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "app", "src", "main", "res")
WHITE = (255, 255, 255, 255)
FONT_BOLD = "C:/Windows/Fonts/segoeuib.ttf"
FONT_CN = "C:/Windows/Fonts/msyhbd.ttc"
FONT_CN_REG = "C:/Windows/Fonts/msyh.ttc"


def _gradient(w, h, top, bottom):
    """竖向渐变。top/bottom 为 (r,g,b)。"""
    g = Image.new("RGBA", (1, 256))
    gd = ImageDraw.Draw(g)
    for i in range(256):
        t = i / 255.0
        gd.line([(0, i), (1, i)], fill=(
            int(bottom[0] + (top[0] - bottom[0]) * (1 - t)),
            int(bottom[1] + (top[1] - bottom[1]) * (1 - t)),
            int(bottom[2] + (top[2] - bottom[2]) * (1 - t)), 255))
    return g.resize((w, h))


def draw_icon(S, plate_on=True):
    """S 为输出边长；内部按 4S 绘制。plate_on=False 时只画图形（给人 adaptive 图标当前景）。"""
    M = S * 4
    img = Image.new("RGBA", (M, M), (0, 0, 0, 0))

    # 蓝色圆角底（#627CFF -> #3F5AF0 的极轻微渐变，纯色在大图标上偏平）
    if plate_on:
        mask = Image.new("L", (M, M), 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            [M * 0.055, M * 0.055, M * 0.945, M * 0.945], radius=M * 0.235, fill=255)
        plate = Image.new("RGBA", (M, M), (0, 0, 0, 0))
        plate.paste(_gradient(M, M, (0x62, 0x7C, 0xFF), (0x3F, 0x5A, 0xF0)), (0, 0), mask)
        img = Image.alpha_composite(img, plate)
    d = ImageDraw.Draw(img)

    stroke = max(2, int(M * 0.037))
    sl, st, sr, sb = M * 0.135, M * 0.35, M * 0.735, M * 0.75

    # 电视屏幕（白色轮廓）
    d.rounded_rectangle([sl, st, sr, sb], radius=M * 0.052, outline=WHITE, width=stroke)

    # 播放三角
    cx, cy = (sl + sr) / 2, (st + sb) / 2
    tw, th = (sr - sl) * 0.28, (sb - st) * 0.46
    d.polygon([(cx - tw / 2, cy - th / 2), (cx - tw / 2, cy + th / 2), (cx + tw / 2, cy)],
              fill=WHITE)

    # 底座
    d.rounded_rectangle([M * 0.335, M * 0.795, M * 0.535, M * 0.795 + stroke * 0.8],
                        radius=stroke * 0.4, fill=WHITE)

    # 右上角 lite
    f = ImageFont.truetype(FONT_BOLD, int(M * 0.155))
    bb = d.textbbox((0, 0), "lite", font=f)
    d.text((M * 0.90 - (bb[2] - bb[0]) - bb[0], M * 0.285 - (bb[3] - bb[1]) - bb[1]),
           "lite", font=f, fill=WHITE)

    return img.resize((S, S), Image.LANCZOS)


def draw_foreground(S):
    """adaptive icon 前景层（108dp 画布）。内容缩到中心约 60%，
    否则系统按圆形/圆角方形裁切时，TV 的角会被切掉。"""
    big = draw_icon(S * 3, plate_on=False)
    bbox = big.getbbox()
    crop = big.crop(bbox)
    ratio = (S * 0.52) / max(crop.size)
    crop = crop.resize((max(1, int(crop.size[0] * ratio)),
                        max(1, int(crop.size[1] * ratio))), Image.LANCZOS)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(crop, ((S - crop.size[0]) // 2, (S - crop.size[1]) // 2), crop)
    return out


def draw_banner(W=320, H=180):
    """Android TV Leanback launcher 用的 banner，与图标保持同一套视觉。
    320x180 很窄，所以左边图形、右边两行文字，所有坐标按 4x 画布换算。"""
    K = 4
    M = (W * K, H * K)
    img = _gradient(M[0], M[1], (0x62, 0x7C, 0xFF), (0x33, 0x4C, 0xDC)).convert("RGB")
    d = ImageDraw.Draw(img)

    def px(v):
        return int(round(v * K))

    # 左侧电视
    stroke = px(5.2)
    sl, st, sw, sh = px(30), px(48), px(96), px(76)
    d.rounded_rectangle([sl, st, sl + sw, st + sh], radius=px(11), outline=WHITE, width=stroke)
    cx, cy = sl + sw / 2, st + sh / 2
    tw, th = sw * 0.30, sh * 0.52
    d.polygon([(cx - tw / 2, cy - th / 2), (cx - tw / 2, cy + th / 2), (cx + tw / 2, cy)],
              fill=WHITE)
    d.rounded_rectangle([sl + sw * 0.30, px(130), sl + sw * 0.70, px(130) + px(5.2)],
                        radius=px(2), fill=WHITE)

    # 右侧文字
    tx = px(142)
    f1 = ImageFont.truetype(FONT_CN, px(24))
    f2 = ImageFont.truetype(FONT_CN_REG, px(12))
    b1 = d.textbbox((0, 0), "电视直播", font=f1)
    d.text((tx, px(67) - b1[1]), "电视直播", font=f1, fill=WHITE)
    bw = b1[2] - b1[0]
    d.text((tx + bw + px(6), px(67) - b1[1] + px(5)), "Lite",
           font=ImageFont.truetype(FONT_CN, px(16)), fill=(255, 255, 255, 205))
    b2 = d.textbbox((0, 0), "IPTV · 节目单 · 4.4+", font=f2)
    d.text((tx, px(102) - b2[1]), "IPTV · 节目单 · 4.4+", font=f2, fill=(226, 232, 255))

    return img.resize((W, H), Image.LANCZOS)


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_bg" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""

BG_XML = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:angle="270"
        android:endColor="#3F5AF0"
        android:startColor="#627CFF" />
</shape>
"""


def _write(path, text):
    full = os.path.join(RES, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with io.open(full, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def main():
    for folder, size in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                         ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
        p = os.path.join(RES, folder)
        os.makedirs(p, exist_ok=True)
        icon = draw_icon(size)
        icon.save(os.path.join(p, "ic_launcher.png"), optimize=True)
        # 108dp 体系的前景层：48dp 图标对应 108dp 前景
        fg_size = int(round(size * 108 / 48.0))
        draw_foreground(fg_size).save(
            os.path.join(p, "ic_launcher_foreground.png"), optimize=True)
        print(folder, icon.size, "fg", fg_size)

    # Android 8.0+ 用自适应图标，否则系统会给 legacy 图标套一层白底
    _write("mipmap-anydpi-v26/ic_launcher.xml", ADAPTIVE_XML)
    _write("drawable/ic_launcher_bg.xml", BG_XML)
    print("adaptive icon xml written")

    banner_path = os.path.join(RES, "drawable-nodpi", "tv_banner.png")
    draw_banner().save(banner_path, optimize=True)
    print("banner 320x180", os.path.getsize(banner_path), "bytes")


if __name__ == "__main__":
    main()
