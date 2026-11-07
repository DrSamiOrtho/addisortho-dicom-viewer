#!/usr/bin/env python3
"""Addis Ortho: write made-up DICOM files shaped like an old low-field MRI. No patient data.

Windows v1.0 showed black for a real 0.34T study that displays correctly on macOS. These files copy
that study's structure (12 bits stored in 16, uncompressed, large private block) with invented values,
so the behaviour can be tested on public CI.
"""

import math
import struct
import sys
from pathlib import Path

ROOT = "1.2.826.0.1.3680043.8.498.77"


def el(g, e, vr, val):
    if isinstance(val, str):
        val = val.encode("ascii")
    if len(val) % 2:
        val += b"\0" if vr in ("UI", "OB") else b" "
    if vr in ("OB", "OW", "UN"):
        return struct.pack("<HH2sHI", g, e, vr.encode(), 0, len(val)) + val
    return struct.pack("<HH2sH", g, e, vr.encode(), len(val)) + val


def us(v):
    return struct.pack("<H", v)


def pixels(rows, cols, top):
    out = bytearray()
    for r in range(rows):
        for c in range(cols):
            d = math.hypot((r - rows / 2) / (rows / 2), (c - cols / 2) / (cols / 2))
            v = 0 if d > 0.95 else int(top * (1 - d * 0.8) * (0.6 + 0.4 * c / cols))
            if abs(r - rows // 2) < 4:
                v = top
            out += us(min(v, top))
    return bytes(out)


def write(path, n, rows, cols, stored, high, center, width, top, private):
    sop = f"{ROOT}.{n}"
    body = b""
    body += el(0x0008, 0x0016, "UI", "1.2.840.10008.5.1.4.1.1.4")
    body += el(0x0008, 0x0018, "UI", sop)
    body += el(0x0008, 0x0060, "CS", "MR")
    body += el(0x0008, 0x0070, "LO", "MADE-UP PHANTOM")
    body += el(0x0008, 0x103E, "LO", path.stem)
    body += el(0x0010, 0x0010, "PN", "PHANTOM^TEST")
    body += el(0x0010, 0x0020, "LO", "PHANTOM-1")
    body += el(0x0018, 0x0050, "DS", "4")
    body += el(0x0018, 0x0087, "DS", "0.3425")
    body += el(0x0020, 0x000D, "UI", f"{ROOT}.0")
    body += el(0x0020, 0x000E, "UI", f"{ROOT}.0.{n}")
    body += el(0x0020, 0x0011, "IS", str(n))
    body += el(0x0020, 0x0013, "IS", "1")
    body += el(0x0028, 0x0002, "US", us(1))
    body += el(0x0028, 0x0004, "CS", "MONOCHROME2")
    body += el(0x0028, 0x0010, "US", us(rows))
    body += el(0x0028, 0x0011, "US", us(cols))
    body += el(0x0028, 0x0030, "DS", "1.5625\\1.5625")
    body += el(0x0028, 0x0100, "US", us(16))
    body += el(0x0028, 0x0101, "US", us(stored))
    body += el(0x0028, 0x0102, "US", us(high))
    body += el(0x0028, 0x0103, "US", us(0))
    body += el(0x0028, 0x0106, "US", us(0))
    body += el(0x0028, 0x0107, "US", us(top))
    body += el(0x0028, 0x1050, "DS", center)
    body += el(0x0028, 0x1051, "DS", width)
    body += el(0x0028, 0x1055, "LO", "Algo1")
    if private:
        body += el(0x0029, 0x0010, "LO", "MADE-UP PRIVATE")
        body += el(0x0029, 0x1010, "OB", bytes(9276))
        body += el(0x0029, 0x1020, "OB", bytes(63560))
    body += el(0x7FE0, 0x0010, "OW", pixels(rows, cols, top))

    meta = b""
    meta += el(0x0002, 0x0001, "OB", b"\0\1")
    meta += el(0x0002, 0x0002, "UI", "1.2.840.10008.5.1.4.1.1.4")
    meta += el(0x0002, 0x0003, "UI", sop)
    meta += el(0x0002, 0x0010, "UI", "1.2.840.10008.1.2.1")
    meta += el(0x0002, 0x0012, "UI", f"{ROOT}.9")
    group = el(0x0002, 0x0000, "UL", struct.pack("<I", len(meta)))
    path.write_bytes(bytes(128) + b"DICM" + group + meta + body)


def main(dest):
    d = Path(dest)
    d.mkdir(parents=True, exist_ok=True)
    write(d / "A_localizer_like.dcm", 1, 256, 256, 12, 11, "293", "658", 733, True)
    write(d / "B_t2_like.dcm", 2, 512, 432, 12, 11, "702", "1370.1", 1387, True)
    write(d / "C_no_private.dcm", 3, 256, 256, 12, 11, "293", "658", 733, False)
    write(d / "D_16bit_control.dcm", 4, 256, 256, 16, 15, "293", "658", 733, False)
    print(d)


if __name__ == "__main__":
    main(sys.argv[1])
