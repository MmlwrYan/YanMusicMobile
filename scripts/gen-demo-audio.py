#!/usr/bin/env python3
"""生成随包内置的试听曲（24bit / 96kHz WAV）。

为什么需要它
------------
首版 APK 里没有任何音频资源，装上后「音乐库」是空的 —— 用户必须先自己找
文件导入才能看到播放链路工作。内置一段试听曲可以：

1. 让应用**装完即可播放**，UI 的播放/进度/音频参数面板立刻可验证；
2. 直接充当**高解析验证样本**（96kHz / 24bit 若被重采样，诊断页会读回 48000）；
3. 体积极小：纯正弦叠加，deflate 后可压缩到几百 KB。

内容
----
A 大三和弦的慢速 pad（A2 / E3 / A3 / C#4 / E4），左右声道微小失谐制造宽度，
1.2 秒淡入、2 秒淡出，整体留 ~6dB 余量。听感是「一个安静的长音」，不是噪声。

用法
----
    python scripts/gen-demo-audio.py

输出：`app/src/main/assets/demo/yanmusic-hires-demo.wav`（覆盖写）。
"""

from __future__ import annotations

import array
import math
import os
import struct
import sys

SAMPLE_RATE = 96_000
BIT_DEPTH = 24
CHANNELS = 2
DURATION_S = 8.0
PEAK = 0.42  # 留余量，避免叠加后削顶

FADE_IN_S = 1.2
FADE_OUT_S = 2.0

# (频率 Hz, 相对振幅)
PARTIALS = [
    (110.00, 0.30),  # A2
    (164.81, 0.22),  # E3
    (220.00, 0.26),  # A3
    (277.18, 0.16),  # C#4
    (329.63, 0.14),  # E4
]

MAX_24 = (1 << 23) - 1
MIN_24 = -(1 << 23)

OUT_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "demo", "yanmusic-hires-demo.wav",
)


def envelope(t: float, total: float) -> float:
    """淡入淡出包络（raised-cosine，无爆音）。"""
    if t < FADE_IN_S:
        x = t / FADE_IN_S
        return 0.5 - 0.5 * math.cos(math.pi * x)
    if t > total - FADE_OUT_S:
        x = (total - t) / FADE_OUT_S
        return 0.5 - 0.5 * math.cos(math.pi * x)
    return 1.0


def main() -> int:
    total = DURATION_S
    frames = int(SAMPLE_RATE * total)

    # 每个声道独立失谐：右声道 +0.12%，营造轻微宽度
    detune = (1.0, 1.0012)
    phase_offsets = (0.0, 0.35)

    data = array.array("b")  # 占位，实际用 bytearray 拼 24bit
    out = bytearray()

    amp_sum = sum(a for _, a in PARTIALS)
    norm = (PEAK / amp_sum) if amp_sum else 0.0

    two_pi = 2.0 * math.pi

    for n in range(frames):
        t = n / SAMPLE_RATE
        env = envelope(t, total)
        # 缓慢的呼吸感（0.21 Hz），避免完全静止
        breath = 0.88 + 0.12 * math.sin(two_pi * 0.21 * t)
        gain = norm * env * breath

        for ch in range(CHANNELS):
            s = 0.0
            d = detune[ch]
            po = phase_offsets[ch]
            for freq, a in PARTIALS:
                s += a * math.sin(two_pi * freq * d * t + po)
            v = int(round(s * gain * MAX_24))
            if v > MAX_24:
                v = MAX_24
            elif v < MIN_24:
                v = MIN_24
            # 24bit 小端三字节（负数取补码）
            if v < 0:
                v += 1 << 24
            out.append(v & 0xFF)
            out.append((v >> 8) & 0xFF)
            out.append((v >> 16) & 0xFF)

    del data

    block_align = CHANNELS * BIT_DEPTH // 8
    byte_rate = SAMPLE_RATE * block_align
    data_size = len(out)

    header = b"RIFF" + struct.pack("<I", 36 + data_size) + b"WAVE"
    header += b"fmt " + struct.pack(
        "<IHHIIHH",
        16,             # PCM 头长度
        1,              # PCM
        CHANNELS,
        SAMPLE_RATE,
        byte_rate,
        block_align,
        BIT_DEPTH,
    )
    header += b"data" + struct.pack("<I", data_size)

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "wb") as f:
        f.write(header)
        f.write(out)

    print(f"已生成：{OUT_PATH}")
    print(f"  规格：{SAMPLE_RATE} Hz / {BIT_DEPTH} bit / {CHANNELS}ch / {DURATION_S:.1f}s")
    print(f"  大小：{len(header) + data_size} 字节")
    return 0


if __name__ == "__main__":
    sys.exit(main())
