#!/usr/bin/env python3
"""Addis Ortho: build a made-up MRI study on a CD image (ISO 9660 + Joliet) with a DICOMDIR.

  make_cd.py WORK_DIR ISO_PATH

Three series shaped like the low-field lumbar study that showed black on Windows: a 256x256 localizer,
a 512x432 sagittal and fifteen 432x512 axials, all 12 bits stored in 16, uncompressed. No patient data.
Prints the SHA-256 and size of every file on the disc, for comparing with the viewer's cached copies.
"""

import hashlib
import sys
from pathlib import Path

import pycdlib
from pydicom.dataset import Dataset, FileMetaDataset
from pydicom.fileset import FileSet
from pydicom.uid import ExplicitVRLittleEndian

sys.path.insert(0, str(Path(__file__).parent))
from make_phantom import pixels  # noqa: E402

ROOT = "1.2.826.0.1.3680043.8.498.79"
MR = "1.2.840.10008.5.1.4.1.1.4"


def image(series_no, desc, n, rows, cols, center, width, top):
    sop = f"{ROOT}.{series_no}.{n}"
    meta = FileMetaDataset()
    meta.MediaStorageSOPClassUID = MR
    meta.MediaStorageSOPInstanceUID = sop
    meta.TransferSyntaxUID = ExplicitVRLittleEndian
    ds = Dataset()
    ds.file_meta = meta
    ds.SpecificCharacterSet = "ISO_IR 100"
    ds.SOPClassUID = MR
    ds.SOPInstanceUID = sop
    ds.StudyDate, ds.StudyTime, ds.AccessionNumber = "20000101", "120000", ""
    ds.Modality, ds.Manufacturer = "MR", "MADE-UP PHANTOM"
    ds.StudyDescription, ds.SeriesDescription = "PHANTOM^CD", desc
    ds.PatientName, ds.PatientID, ds.PatientBirthDate, ds.PatientSex = "PHANTOM^CD", "PHANTOM-CD", "20000101", "O"
    ds.SliceThickness, ds.MagneticFieldStrength = "4", "0.3425"
    ds.StudyInstanceUID = f"{ROOT}.0"
    ds.SeriesInstanceUID = f"{ROOT}.{series_no}"
    ds.StudyID, ds.SeriesNumber, ds.InstanceNumber = "1", series_no, n
    ds.FrameOfReferenceUID = f"{ROOT}.99"
    ds.ImagePositionPatient = [-150.0, -150.0, float(n * 4)]
    ds.ImageOrientationPatient = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0]
    ds.SamplesPerPixel, ds.PhotometricInterpretation = 1, "MONOCHROME2"
    ds.Rows, ds.Columns, ds.PixelSpacing = rows, cols, ["1.5625", "1.5625"]
    ds.BitsAllocated, ds.BitsStored, ds.HighBit, ds.PixelRepresentation = 16, 12, 11, 0
    ds.WindowCenter, ds.WindowWidth = center, width
    ds.PixelData = pixels(rows, cols, top)
    return ds


def main(work, iso_path):
    work = Path(work)
    fs = FileSet()
    fs.ID = "PHANTOMCD"
    for n in range(1, 4):
        fs.add(image(1, "localizer", n, 256, 256, "293", "658", 733))
    for n in range(1, 6):
        fs.add(image(2, "t2_tse_sag", n, 512, 432, "702", "1370.1", 1387))
    for n in range(1, 16):
        fs.add(image(5, "t2_tse_tra", n, 432, 512, "702", "1370.1", 1387))
    out = work / "cd"
    fs.write(out)

    iso = pycdlib.PyCdlib()
    iso.new(interchange_level=3, joliet=3, vol_ident="PHANTOMCD")
    made = set()
    for f in sorted(p for p in out.rglob("*") if p.is_file()):
        rel = f.relative_to(out).parts
        for i in range(1, len(rel)):
            d = "/".join(rel[:i])
            if d not in made:
                iso.add_directory("/" + d.upper(), joliet_path="/" + d)
                made.add(d)
        name = "/".join(rel)
        iso.add_file(str(f), "/" + name.upper() + ";1", joliet_path="/" + name)
        h = hashlib.sha256(f.read_bytes()).hexdigest()
        print(f"DISCFILE {f.stat().st_size} {h} {name}")
    iso.write(iso_path)
    iso.close()


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
