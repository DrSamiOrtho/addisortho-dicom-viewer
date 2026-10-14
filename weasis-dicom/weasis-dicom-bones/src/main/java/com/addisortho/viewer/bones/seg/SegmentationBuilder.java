/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.seg;

import com.addisortho.viewer.bones.grow.VoxelBox;
import com.addisortho.viewer.bones.grow.VoxelMask;
import java.awt.Color;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.ElementDictionary;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.img.data.CIELab;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.util.UIDUtils;

/**
 * Builds a DICOM Segmentation (BINARY) of grown bones on the grid of their CT: one frame per CT
 * slice holding a voxel of a segment, segment after segment, each frame deriving from its CT image.
 * The patient and study are those of the CT, so the segmentation joins its study.
 */
public final class SegmentationBuilder {

  public static final String SERIES_DESCRIPTION =
      "Bones, region grow (semi-automatic, not reviewed)"; // NON-NLS
  public static final int SERIES_NUMBER = 902;
  public static final String CONTENT_LABEL = "BONES"; // NON-NLS
  public static final String MANUFACTURER = "Addis Ortho"; // NON-NLS
  public static final String MODEL_NAME = "Addis Ortho DICOM Viewer"; // NON-NLS
  public static final String ALGORITHM_NAME = "Addis Ortho region grow"; // NON-NLS
  public static final String ALGORITHM_TYPE = "SEMIAUTOMATIC"; // NON-NLS

  /** Copied from the source; written empty when absent, as the IOD requires them (type 2). */
  private static final int[] REQUIRED_TAGS = {
    Tag.PatientName,
    Tag.PatientID,
    Tag.PatientBirthDate,
    Tag.PatientSex,
    Tag.StudyDate,
    Tag.StudyTime,
    Tag.AccessionNumber,
    Tag.ReferringPhysicianName,
    Tag.StudyID
  };

  /** Copied from the source only when present. */
  private static final int[] OPTIONAL_TAGS = {Tag.IssuerOfPatientID, Tag.StudyDescription};

  /**
   * One segment of the segmentation.
   *
   * @param number SegmentNumber, from 1, unique in the segmentation
   * @param description SegmentDescription, or {@code null}
   */
  public record Segment(
      int number, String label, Color color, VoxelMask mask, String description) {}

  /** A frame of the segmentation: slice {@code z} of a segment. */
  record Frame(Segment segment, int z) {}

  private SegmentationBuilder() {}

  /**
   * Builds the dataset.
   *
   * @param source attributes of an image of the CT series (patient, study, series and frame of
   *     reference)
   * @param seriesUID SeriesInstanceUID; the same one when a segmentation is rewritten with one more
   *     segment
   * @param softwareVersion SoftwareVersions of the equipment
   * @param now creation date and time
   * @throws IllegalArgumentException when no segment holds a voxel or segment numbers repeat
   */
  public static Attributes build(
      Attributes source,
      CtGrid grid,
      List<Segment> segments,
      String seriesUID,
      int instanceNumber,
      String softwareVersion,
      Date now) {
    List<Frame> frames = frames(grid, segments);
    if (frames.isEmpty()) {
      throw new IllegalArgumentException("No segment holds a voxel");
    }
    Attributes attrs = new Attributes();
    // UTF-8, so that any patient name of the source and any segment label can be written
    attrs.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192"); // NON-NLS
    copyStrings(source, attrs);
    String studyUID = source.getString(Tag.StudyInstanceUID);
    attrs.setString(
        Tag.StudyInstanceUID, VR.UI, studyUID == null ? UIDUtils.createUID() : studyUID);
    attrs.setString(Tag.SOPClassUID, VR.UI, UID.SegmentationStorage);
    attrs.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
    attrs.setString(Tag.SeriesInstanceUID, VR.UI, seriesUID);
    attrs.setDate(Tag.InstanceCreationDateAndTime, now);

    // Segmentation Series, Frame of Reference, Enhanced General Equipment
    attrs.setString(Tag.Modality, VR.CS, "SEG"); // NON-NLS
    attrs.setInt(Tag.SeriesNumber, VR.IS, SERIES_NUMBER);
    attrs.setString(Tag.SeriesDescription, VR.LO, SERIES_DESCRIPTION);
    attrs.setDate(Tag.SeriesDateAndTime, now);
    String frameOfReference = source.getString(Tag.FrameOfReferenceUID);
    if (frameOfReference != null) {
      attrs.setString(Tag.FrameOfReferenceUID, VR.UI, frameOfReference);
    }
    attrs.setNull(Tag.PositionReferenceIndicator, VR.LO);
    attrs.setString(Tag.Manufacturer, VR.LO, MANUFACTURER);
    attrs.setString(Tag.ManufacturerModelName, VR.LO, MODEL_NAME);
    attrs.setString(Tag.DeviceSerialNumber, VR.LO, "none"); // NON-NLS
    attrs.setString(Tag.SoftwareVersions, VR.LO, softwareVersion);

    // General Image, Segmentation Image, Content Identification
    attrs.setInt(Tag.InstanceNumber, VR.IS, instanceNumber);
    attrs.setDate(Tag.ContentDateAndTime, now);
    attrs.setString(Tag.ImageType, VR.CS, "DERIVED", "PRIMARY"); // NON-NLS
    attrs.setString(Tag.ContentLabel, VR.CS, CONTENT_LABEL);
    attrs.setString(Tag.ContentDescription, VR.LO, SERIES_DESCRIPTION);
    attrs.setNull(Tag.ContentCreatorName, VR.PN);
    attrs.setString(Tag.SegmentationType, VR.CS, "BINARY"); // NON-NLS
    attrs.setString(Tag.SegmentsOverlap, VR.CS, "UNDEFINED"); // NON-NLS
    attrs.setString(Tag.LossyImageCompression, VR.CS, "00"); // NON-NLS
    addSegments(attrs, segments);

    // Image Pixel and Multi-frame
    attrs.setInt(Tag.SamplesPerPixel, VR.US, 1);
    attrs.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2"); // NON-NLS
    attrs.setInt(Tag.Rows, VR.US, grid.rows());
    attrs.setInt(Tag.Columns, VR.US, grid.columns());
    attrs.setInt(Tag.BitsAllocated, VR.US, 1);
    attrs.setInt(Tag.BitsStored, VR.US, 1);
    attrs.setInt(Tag.HighBit, VR.US, 0);
    attrs.setInt(Tag.PixelRepresentation, VR.US, 0);
    attrs.setInt(Tag.NumberOfFrames, VR.IS, frames.size());

    String dimensionUID = UIDUtils.createUID();
    addDimensions(attrs, dimensionUID);
    addSharedGroups(attrs, grid);
    addFrameGroups(attrs, grid, frames);
    addSeriesReference(attrs, source.getString(Tag.SeriesInstanceUID), grid, frames);
    attrs.setBytes(Tag.PixelData, VR.OB, pack(grid, frames));
    return attrs;
  }

  /** Writes the dataset as a DICOM file (Explicit VR Little Endian). */
  public static void write(Attributes dataset, Path file) throws IOException {
    try (DicomOutputStream dos =
        new DicomOutputStream(
            new BufferedOutputStream(Files.newOutputStream(file)), UID.ExplicitVRLittleEndian)) {
      dos.writeDataset(dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian), dataset);
    }
  }

  /** Frames in file order: the segments in the given order, the slices of each along the normal. */
  static List<Frame> frames(CtGrid grid, List<Segment> segments) {
    List<Frame> frames = new ArrayList<>();
    Map<Integer, Segment> numbers = new LinkedHashMap<>();
    for (Segment segment : segments) {
      if (numbers.put(segment.number(), segment) != null || segment.number() < 1) {
        throw new IllegalArgumentException("Invalid segment number " + segment.number());
      }
      VoxelBox b = segment.mask().bounds();
      for (int z = Math.max(0, b.z0()); z <= Math.min(grid.sliceCount() - 1, b.z1()); z++) {
        if (segment.mask().hasVoxelsInSlice(z)) {
          frames.add(new Frame(segment, z));
        }
      }
    }
    return frames;
  }

  /**
   * Packs the frames one bit per pixel, row after row, the first pixel in the least significant bit
   * of each byte and frames following each other without padding; one zero byte is added at the end
   * when the length is odd.
   */
  static byte[] pack(CtGrid grid, List<Frame> frames) {
    int columns = grid.columns();
    int rows = grid.rows();
    long framePixels = (long) columns * rows;
    long bits = framePixels * frames.size();
    long length = (bits + 7) / 8;
    if (length > Integer.MAX_VALUE - 9L) {
      throw new IllegalArgumentException("Segmentation too large: " + length + " bytes");
    }
    byte[] data = new byte[(int) (length + (length & 1))];
    for (int f = 0; f < frames.size(); f++) {
      Frame frame = frames.get(f);
      VoxelMask mask = frame.segment().mask();
      VoxelBox b = mask.bounds();
      long base = framePixels * f;
      for (int y = Math.max(0, b.y0()); y <= Math.min(rows - 1, b.y1()); y++) {
        for (int x = Math.max(0, b.x0()); x <= Math.min(columns - 1, b.x1()); x++) {
          if (mask.contains(x, y, frame.z())) {
            long bit = base + (long) y * columns + x;
            data[(int) (bit >>> 3)] |= (byte) (1 << (int) (bit & 7));
          }
        }
      }
    }
    return data;
  }

  // Decoded with the source's character set, re-encoded in UTF-8 when written.
  private static void copyStrings(Attributes source, Attributes target) {
    for (int tag : REQUIRED_TAGS) {
      VR vr = ElementDictionary.vrOf(tag, null);
      String[] values = source.getStrings(tag);
      if (values == null || values.length == 0) {
        target.setNull(tag, vr);
      } else {
        target.setString(tag, vr, values);
      }
    }
    for (int tag : OPTIONAL_TAGS) {
      String[] values = source.getStrings(tag);
      if (values != null && values.length > 0) {
        target.setString(tag, ElementDictionary.vrOf(tag, null), values);
      }
    }
  }

  private static void addSegments(Attributes attrs, List<Segment> segments) {
    Sequence seq = attrs.newSequence(Tag.SegmentSequence, segments.size());
    for (Segment segment : segments) {
      Attributes item = new Attributes();
      item.setInt(Tag.SegmentNumber, VR.US, segment.number());
      item.setString(Tag.SegmentLabel, VR.LO, segment.label());
      if (segment.description() != null) {
        item.setString(Tag.SegmentDescription, VR.ST, segment.description());
      }
      item.setString(Tag.SegmentAlgorithmType, VR.CS, ALGORITHM_TYPE);
      item.setString(Tag.SegmentAlgorithmName, VR.LO, ALGORITHM_NAME);
      item.newSequence(Tag.SegmentedPropertyCategoryCodeSequence, 1)
          .add(code("T-D000A", "SRT", "Anatomical Structure")); // NON-NLS
      item.newSequence(Tag.SegmentedPropertyTypeCodeSequence, 1)
          .add(code("272673000", "SCT", "Bone structure")); // NON-NLS
      item.setInt(Tag.RecommendedDisplayCIELabValue, VR.US, CIELab.rgbToDicomLab(segment.color()));
      seq.add(item);
    }
  }

  private static Attributes code(String value, String scheme, String meaning) {
    Attributes code = new Attributes(3);
    code.setString(Tag.CodeValue, VR.SH, value);
    code.setString(Tag.CodingSchemeDesignator, VR.SH, scheme);
    code.setString(Tag.CodeMeaning, VR.LO, meaning);
    return code;
  }

  // Frames are indexed by segment, then by position.
  private static void addDimensions(Attributes attrs, String dimensionUID) {
    Attributes organisation = new Attributes(1);
    organisation.setString(Tag.DimensionOrganizationUID, VR.UI, dimensionUID);
    attrs.newSequence(Tag.DimensionOrganizationSequence, 1).add(organisation);
    Sequence index = attrs.newSequence(Tag.DimensionIndexSequence, 2);
    index.add(
        dimension(
            dimensionUID,
            Tag.ReferencedSegmentNumber,
            Tag.SegmentIdentificationSequence,
            "Segment")); // NON-NLS
    index.add(
        dimension(
            dimensionUID,
            Tag.ImagePositionPatient,
            Tag.PlanePositionSequence,
            "Position")); // NON-NLS
  }

  private static Attributes dimension(String uid, int pointer, int group, String label) {
    Attributes item = new Attributes(4);
    item.setString(Tag.DimensionOrganizationUID, VR.UI, uid);
    item.setInt(Tag.DimensionIndexPointer, VR.AT, pointer);
    item.setInt(Tag.FunctionalGroupPointer, VR.AT, group);
    item.setString(Tag.DimensionDescriptionLabel, VR.LO, label);
    return item;
  }

  private static void addSharedGroups(Attributes attrs, CtGrid grid) {
    Attributes shared = new Attributes(2);
    Attributes orientation = new Attributes(1);
    double[] r = grid.rowDirection();
    double[] c = grid.columnDirection();
    orientation.setDouble(Tag.ImageOrientationPatient, VR.DS, r[0], r[1], r[2], c[0], c[1], c[2]);
    shared.newSequence(Tag.PlaneOrientationSequence, 1).add(orientation);
    Attributes measures = new Attributes(3);
    measures.setDouble(Tag.PixelSpacing, VR.DS, grid.rowSpacing(), grid.columnSpacing());
    double spacing = grid.sliceSpacing();
    measures.setDouble(Tag.SliceThickness, VR.DS, spacing);
    measures.setDouble(Tag.SpacingBetweenSlices, VR.DS, spacing);
    shared.newSequence(Tag.PixelMeasuresSequence, 1).add(measures);
    attrs.newSequence(Tag.SharedFunctionalGroupsSequence, 1).add(shared);
  }

  private static void addFrameGroups(Attributes attrs, CtGrid grid, List<Frame> frames) {
    Sequence seq = attrs.newSequence(Tag.PerFrameFunctionalGroupsSequence, frames.size());
    for (Frame frame : frames) {
      CtGrid.Slice slice = grid.slice(frame.z());
      Attributes item = new Attributes(4);

      Attributes content = new Attributes(1);
      content.setInt(Tag.DimensionIndexValues, VR.UL, frame.segment().number(), frame.z() + 1);
      item.newSequence(Tag.FrameContentSequence, 1).add(content);

      Attributes position = new Attributes(1);
      position.setDouble(Tag.ImagePositionPatient, VR.DS, slice.position());
      item.newSequence(Tag.PlanePositionSequence, 1).add(position);

      Attributes source = new Attributes(5);
      source.setString(Tag.ReferencedSOPClassUID, VR.UI, slice.sopClassUid());
      source.setString(Tag.ReferencedSOPInstanceUID, VR.UI, slice.sopInstanceUid());
      if (slice.frameNumber() > 0) {
        source.setInt(Tag.ReferencedFrameNumber, VR.IS, slice.frameNumber());
      }
      source
          .newSequence(Tag.PurposeOfReferenceCodeSequence, 1)
          .add(code("121322", "DCM", "Source image for image processing operation")); // NON-NLS
      source.setString(Tag.SpatialLocationsPreserved, VR.CS, "YES"); // NON-NLS
      Attributes derivation = new Attributes(2);
      derivation
          .newSequence(Tag.DerivationCodeSequence, 1)
          .add(code("113076", "DCM", "Segmentation")); // NON-NLS
      derivation.newSequence(Tag.SourceImageSequence, 1).add(source);
      item.newSequence(Tag.DerivationImageSequence, 1).add(derivation);

      Attributes identification = new Attributes(1);
      identification.setInt(Tag.ReferencedSegmentNumber, VR.US, frame.segment().number());
      item.newSequence(Tag.SegmentIdentificationSequence, 1).add(identification);
      seq.add(item);
    }
  }

  // Common Instance Reference: the CT series and the images the frames derive from.
  private static void addSeriesReference(
      Attributes attrs, String seriesUID, CtGrid grid, List<Frame> frames) {
    if (seriesUID == null) {
      return;
    }
    Map<String, String> instances = new LinkedHashMap<>();
    for (Frame frame : frames) {
      CtGrid.Slice slice = grid.slice(frame.z());
      instances.putIfAbsent(slice.sopInstanceUid(), slice.sopClassUid());
    }
    Attributes item = new Attributes(2);
    item.setString(Tag.SeriesInstanceUID, VR.UI, seriesUID);
    Sequence images = item.newSequence(Tag.ReferencedInstanceSequence, instances.size());
    instances.forEach(
        (instanceUID, classUID) -> {
          Attributes ref = new Attributes(2);
          ref.setString(Tag.ReferencedSOPClassUID, VR.UI, classUID);
          ref.setString(Tag.ReferencedSOPInstanceUID, VR.UI, instanceUID);
          images.add(ref);
        });
    attrs.newSequence(Tag.ReferencedSeriesSequence, 1).add(item);
  }
}
