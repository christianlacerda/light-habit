"""Just enough PNG to read `adb screencap` output: 8-bit RGB or RGBA, non-interlaced.

Standard library only, so the eval keeps its no-dependencies promise.
"""

from __future__ import annotations

import struct
import zlib
from pathlib import Path


class Image:
    def __init__(self, width: int, height: int, channels: int, rows: list[bytes]):
        self.width, self.height, self.channels, self.rows = width, height, channels, rows

    def luma(self, x: int, y: int) -> int:
        i = x * self.channels
        r, g, b = self.rows[y][i : i + 3]
        return (299 * r + 587 * g + 114 * b) // 1000


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    return a if pa <= pb and pa <= pc else b if pb <= pc else c


def read(path: Path | str) -> Image:
    data = Path(path).read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path} is not a PNG")
    pos, idat, header = 8, bytearray(), None
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos : pos + 8])
        chunk = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", chunk)
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"IEND":
            break
    width, height, depth, color, _, _, interlace = header
    if depth != 8 or color not in (2, 6) or interlace:
        raise ValueError(f"unsupported PNG (depth {depth}, colour type {color}, interlace {interlace})")
    bpp = 4 if color == 6 else 3
    stride = width * bpp
    raw = zlib.decompress(bytes(idat))
    rows, prev = [], bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        kind, line = raw[start], bytearray(raw[start + 1 : start + 1 + stride])
        if kind == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif kind == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif kind == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif kind == 4:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                up_left = prev[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + _paeth(left, prev[i], up_left)) & 0xFF
        rows.append(bytes(line))
        prev = line
    return Image(width, height, bpp, rows)
