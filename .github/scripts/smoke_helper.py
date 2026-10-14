#!/usr/bin/env python3
"""Helper for .github/workflows/smoke-test.yml.

  smoke_helper.py sample DEST     copy pydicom's public DICOMDIR test set to DEST, print the DICOMDIR path
  smoke_helper.py extra DEST      copy a few small public CT/MR/NM test images to DEST, print DEST
  smoke_helper.py shot NAME [--from FILE]
                                  shrink a screenshot (FILE, or the screen on Windows) to a small JPEG and
                                  print it as base64 between =====SHOT NAME BEGIN/END===== lines
"""

import base64
import collections
import io
import os
import shutil
import sys
from pathlib import Path

MAX_BYTES = 60_000
TARGET_WIDTH = 1000


def sample(dest):
    import pydicom
    from pydicom.data import get_testdata_file

    src = Path(get_testdata_file("DICOMDIR")).parent
    dest = Path(dest)
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(src, dest)
    ds = pydicom.dcmread(dest / "DICOMDIR")
    kinds = collections.Counter(str(r.DirectoryRecordType) for r in ds.DirectoryRecordSequence)
    print(f"pydicom {pydicom.__version__} sample {src.name}: {dict(kinds)}", file=sys.stderr)
    print(dest / "DICOMDIR")


def extra(dest):
    from pydicom.data import get_testdata_file

    dest = Path(dest)
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    for name in ("CT_small.dcm", "MR_small.dcm", "JPEG2000.dcm"):
        shutil.copy(get_testdata_file(name), dest / name)
    print(dest)


def shot(name, src):
    from PIL import Image, ImageGrab, ImageStat

    img = Image.open(src) if src else ImageGrab.grab(all_screens=True)
    img = img.convert("RGB")
    width, height = img.size
    gray = img.convert("L")
    stat = ImageStat.Stat(gray)
    colors = len(img.resize((160, 100)).getcolors(maxcolors=16000) or [])
    print(
        f"shot {name}: {width}x{height}, mean luminance {stat.mean[0]:.1f}/255, "
        f"stddev {stat.stddev[0]:.1f}, distinct colours in 160x100 sample {colors}"
    )

    data = b""
    for target in (TARGET_WIDTH, 900, 800, 700):
        small = img.resize((target, max(1, round(height * target / width)))) if width > target else img
        for quality in (70, 60, 50, 40, 30, 22, 15):
            buf = io.BytesIO()
            small.save(buf, "JPEG", quality=quality, optimize=True)
            data = buf.getvalue()
            if len(data) <= MAX_BYTES:
                break
        if len(data) <= MAX_BYTES:
            break

    out_dir = Path(os.environ.get("SHOT_DIR", "shots"))
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / f"{name}.jpg").write_bytes(data)
    print(f"shot {name}: jpeg {small.size[0]}x{small.size[1]}, {len(data)} bytes")
    encoded = base64.b64encode(data).decode("ascii")
    print(f"=====SHOT {name} BEGIN=====")
    for i in range(0, len(encoded), 120):
        print(encoded[i : i + 120])
    print(f"=====SHOT {name} END=====", flush=True)


def main(argv):
    if len(argv) >= 3 and argv[1] == "sample":
        sample(argv[2])
    elif len(argv) >= 3 and argv[1] == "extra":
        extra(argv[2])
    elif len(argv) >= 3 and argv[1] == "shot":
        src = argv[argv.index("--from") + 1] if "--from" in argv else None
        shot(argv[2], src)
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
