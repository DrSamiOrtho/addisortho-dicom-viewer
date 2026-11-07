#!/usr/bin/env python3
"""Addis Ortho: unpack an installed app's bundle/*.jar.xz into DEST and extract the OpenCV native library.

Prints the path of the native library.
"""

import lzma
import shutil
import sys
import zipfile
from pathlib import Path


def main(bundle, dest):
    dest = Path(dest)
    dest.mkdir(parents=True, exist_ok=True)
    for f in sorted(Path(bundle).iterdir()):
        if f.name.endswith(".jar.xz"):
            (dest / f.name[:-3]).write_bytes(lzma.decompress(f.read_bytes()))
        elif f.name.endswith(".jar"):
            shutil.copy(f, dest / f.name)
    native = next(dest.glob("weasis-opencv-core-*.jar"))
    with zipfile.ZipFile(native) as z:
        lib = next(n for n in z.namelist() if n.endswith((".dll", ".dylib", ".so")))
        out = dest / Path(lib).name
        out.write_bytes(z.read(lib))
    print(out)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
