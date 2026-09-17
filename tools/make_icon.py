"""Generate the app icon and Play Store icon using only Python's standard library."""
from pathlib import Path
import math
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]

def png_chunk(kind, payload):
    return struct.pack('!I', len(payload)) + kind + payload + struct.pack('!I', zlib.crc32(kind + payload) & 0xffffffff)

def rounded_rect(x, y, left, top, right, bottom, radius):
    near_x = max(left + radius, min(x, right - radius))
    near_y = max(top + radius, min(y, bottom - radius))
    return (x - near_x) ** 2 + (y - near_y) ** 2 <= radius ** 2

def render(size, path):
    rows = []
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            u, v = (x + .5) / size, (y + .5) / size
            rgb = (16, 23, 34)
            if rounded_rect(u, v, .17, .24, .72, .71, .06): rgb = (72, 102, 130)
            if rounded_rect(u, v, .23, .19, .78, .66, .06): rgb = (100, 155, 179)
            if rounded_rect(u, v, .29, .30, .84, .77, .06): rgb = (225, 244, 235)
            if (u - .64) ** 2 + (v - .42) ** 2 < .065 ** 2: rgb = (247, 181, 99)
            if v > .54 and .29 < u < .84 and v < .77:
                hill = .65 - .25 * max(0, 1 - abs(u - .50) / .20)
                if v > hill: rgb = (24, 103, 115)
            row.extend((*rgb, 255))
        rows.append(bytes(row))
    raw = b''.join(rows)
    data = b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('!2I5B', size, size, 8, 6, 0, 0, 0))
    data += png_chunk(b'IDAT', zlib.compress(raw, 9)) + png_chunk(b'IEND', b'')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)

render(512, ROOT / 'publishing' / 'play_icon_512.png')
render(192, ROOT / 'app' / 'src' / 'main' / 'res' / 'drawable' / 'ic_launcher.png')
