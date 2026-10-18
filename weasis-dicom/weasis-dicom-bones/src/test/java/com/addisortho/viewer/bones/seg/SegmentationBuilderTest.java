/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.seg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.addisortho.viewer.bones.grow.GrowOptions;
import com.addisortho.viewer.bones.grow.GrowProgress;
import com.addisortho.viewer.bones.grow.HuSource;
import com.addisortho.viewer.bones.grow.RegionGrow;
import com.addisortho.viewer.bones.grow.RegionGrow.Result;
import com.addisortho.viewer.bones.grow.VoxelBox;
import com.addisortho.viewer.bones.grow.VoxelMask;
import com.addisortho.viewer.bones.seg.SegmentationBuilder.Segment;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.core.ui.model.graphic.imp.seg.SegRegion;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomMediaIO;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.seg.SegBinaryMaskWorkaround;
import org.weasis.dicom.codec.seg.SegSpecialElement;

class SegmentationBuilderTest {

  private static final int COLUMNS = 37;
  private static final int ROWS = 41;
  private static final int SLICES = 23;
  private static final double SLICE_SPACING = 1.25;
  private static final String CT_SERIES = "1.2.826.0.1.3680043.10.1.2";
  private static final String FRAME_OF_REFERENCE = "1.2.826.0.1.3680043.10.1.3";

  @TempDir Path dir;

  /** A mask given by a predicate over a box of the grid. */
  private record TestMask(VoxelBox bounds, Inside inside) implements VoxelMask {
    interface Inside {
      boolean at(int x, int y, int z);
    }

    @Override
    public boolean contains(int x, int y, int z) {
      return bounds.contains(x, y, z) && inside.at(x, y, z);
    }

    @Override
    public boolean hasVoxelsInSlice(int z) {
      for (int y = bounds.y0(); y <= bounds.y1(); y++) {
        for (int x = bounds.x0(); x <= bounds.x1(); x++) {
          if (contains(x, y, z)) {
            return true;
          }
        }
      }
      return false;
    }
  }

  /** A ball of radius 6 voxels around (18, 20, 8): slices 2 to 14. */
  private static final VoxelMask BALL =
      new TestMask(
          new VoxelBox(0, COLUMNS - 1, 0, ROWS - 1, 0, SLICES - 1),
          (x, y, z) -> {
            int dx = x - 18;
            int dy = y - 20;
            int dz = z - 8;
            return dx * dx + dy * dy + dz * dz <= 36;
          });

  /** A block in the last columns, first rows and last slices: x 30 to 36, y 0 to 2, z 20 to 22. */
  private static final VoxelMask BLOCK =
      new TestMask(new VoxelBox(30, 36, 0, 2, 20, 22), (_, _, _) -> true);

  /** CT slices of a non-square grid far from the origin, listed top first to test the sorting. */
  private static CtGrid grid(double angleDegrees) {
    double a = Math.toRadians(angleDegrees);
    double[] orientation = {Math.cos(a), Math.sin(a), 0, -Math.sin(a), Math.cos(a), 0};
    List<CtGrid.Image> images = new ArrayList<>();
    for (int z = SLICES - 1; z >= 0; z--) {
      images.add(
          new CtGrid.Image(
              COLUMNS,
              ROWS,
              orientation,
              new double[] {0.8, 0.7},
              new double[] {-123.45, 67.89, -456.7 + z * SLICE_SPACING},
              UID.CTImageStorage,
              "1.2.826.0.1.3680043.10.1.4." + (z + 1),
              0));
    }
    return CtGrid.of(images).orElseThrow();
  }

  private static Attributes ctImage() {
    Attributes ct = new Attributes();
    ct.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 100");
    ct.setString(Tag.PatientName, VR.PN, "Phantom^Synthetic");
    ct.setString(Tag.PatientID, VR.LO, "SYN-0001");
    ct.setString(Tag.StudyInstanceUID, VR.UI, "1.2.826.0.1.3680043.10.1.1");
    ct.setString(Tag.SeriesInstanceUID, VR.UI, CT_SERIES);
    ct.setString(Tag.FrameOfReferenceUID, VR.UI, FRAME_OF_REFERENCE);
    ct.setString(Tag.StudyDescription, VR.LO, "Knee");
    return ct;
  }

  private static List<Segment> segments() {
    return List.of(
        new Segment(1, "Bone 1", new Color(230, 220, 180), BALL, "200 to 3000 HU"),
        new Segment(2, "Bone 2", new Color(80, 160, 240), BLOCK, null));
  }

  private Attributes writeAndRead(Attributes dataset) throws IOException {
    Path file = dir.resolve("seg.dcm");
    SegmentationBuilder.write(dataset, file);
    try (DicomInputStream in = new DicomInputStream(file.toFile())) {
      return in.readDataset();
    }
  }

  /** Bit of a pixel in the frames, as DICOM packs them: continuous, least significant bit first. */
  private static boolean bit(byte[] data, int frame, int x, int y) {
    long index = (long) frame * COLUMNS * ROWS + (long) y * COLUMNS + x;
    return ((data[(int) (index >>> 3)] >> (int) (index & 7)) & 1) != 0;
  }

  @Test
  void framesHoldExactlyTheSegmentVoxelsAfterAFileRoundTrip() throws IOException {
    CtGrid grid = grid(0);

    Attributes seg =
        writeAndRead(
            SegmentationBuilder.build(
                ctImage(), grid, segments(), "1.2.826.0.1.3680043.10.1.9", 1, "4.7.3", new Date()));

    // 13 slices of the ball, then 3 of the block.
    assertEquals(16, seg.getInt(Tag.NumberOfFrames, 0));
    byte[] data = seg.getBytes(Tag.PixelData);
    long bits = 16L * COLUMNS * ROWS;
    assertEquals(((bits + 7) / 8 + 1) / 2 * 2, data.length, "packed without padding, even length");
    Sequence frames = seg.getSequence(Tag.PerFrameFunctionalGroupsSequence);
    assertEquals(16, frames.size());
    for (int f = 0; f < 16; f++) {
      Attributes frame = frames.get(f);
      int segment =
          frame
              .getNestedDataset(Tag.SegmentIdentificationSequence)
              .getInt(Tag.ReferencedSegmentNumber, 0);
      int z = f < 13 ? f + 2 : f - 13 + 20;
      assertEquals(f < 13 ? 1 : 2, segment);
      assertArrayEquals(
          grid.slice(z).position(),
          frame.getNestedDataset(Tag.PlanePositionSequence).getDoubles(Tag.ImagePositionPatient));
      Attributes source =
          frame
              .getNestedDataset(Tag.DerivationImageSequence)
              .getNestedDataset(Tag.SourceImageSequence);
      assertEquals(grid.slice(z).sopInstanceUid(), source.getString(Tag.ReferencedSOPInstanceUID));
      VoxelMask mask = segment == 1 ? BALL : BLOCK;
      for (int y = 0; y < ROWS; y++) {
        for (int x = 0; x < COLUMNS; x++) {
          assertEquals(
              mask.contains(x, y, z), bit(data, f, x, y), "frame " + f + " " + x + "," + y);
        }
      }
    }
    for (long b = bits; b < 8L * data.length; b++) {
      assertEquals(0, (data[(int) (b >>> 3)] >> (int) (b & 7)) & 1, "trailing bit " + b);
    }
    assertTrue(SegBinaryMaskWorkaround.isAffected(seg), "37 columns: the codec's bit decoder runs");
  }

  @Test
  void headerFollowsTheSegmentationIod() throws IOException {
    Attributes seg =
        writeAndRead(
            SegmentationBuilder.build(
                ctImage(),
                grid(0),
                segments(),
                "1.2.826.0.1.3680043.10.1.9",
                3,
                "4.7.3",
                new Date()));

    assertEquals(UID.SegmentationStorage, seg.getString(Tag.SOPClassUID));
    assertEquals("SEG", seg.getString(Tag.Modality));
    assertEquals("BINARY", seg.getString(Tag.SegmentationType));
    assertEquals(1, seg.getInt(Tag.BitsAllocated, 0));
    assertEquals(1, seg.getInt(Tag.BitsStored, 0));
    assertEquals(0, seg.getInt(Tag.HighBit, -1));
    assertEquals(ROWS, seg.getInt(Tag.Rows, 0));
    assertEquals(COLUMNS, seg.getInt(Tag.Columns, 0));
    assertEquals(SegmentationBuilder.SERIES_DESCRIPTION, seg.getString(Tag.SeriesDescription));
    assertEquals(902, seg.getInt(Tag.SeriesNumber, 0));
    assertEquals(3, seg.getInt(Tag.InstanceNumber, 0));
    assertEquals("Addis Ortho", seg.getString(Tag.Manufacturer));
    assertEquals("BONES", seg.getString(Tag.ContentLabel));
    assertTrue(seg.contains(Tag.ContentCreatorName));
    assertFalse(seg.containsValue(Tag.ContentCreatorName));
    assertEquals("Phantom^Synthetic", seg.getString(Tag.PatientName));
    assertEquals("SYN-0001", seg.getString(Tag.PatientID));
    assertEquals("1.2.826.0.1.3680043.10.1.1", seg.getString(Tag.StudyInstanceUID));
    assertEquals(FRAME_OF_REFERENCE, seg.getString(Tag.FrameOfReferenceUID));
    assertEquals("1.2.826.0.1.3680043.10.1.9", seg.getString(Tag.SeriesInstanceUID));
    assertNotEquals(CT_SERIES, seg.getString(Tag.SeriesInstanceUID));
    assertEquals(
        CT_SERIES,
        seg.getNestedDataset(Tag.ReferencedSeriesSequence).getString(Tag.SeriesInstanceUID));
    assertEquals(
        16,
        seg.getNestedDataset(Tag.ReferencedSeriesSequence)
            .getSequence(Tag.ReferencedInstanceSequence)
            .size());

    Attributes first = seg.getSequence(Tag.SegmentSequence).getFirst();
    assertEquals(1, first.getInt(Tag.SegmentNumber, 0));
    assertEquals("Bone 1", first.getString(Tag.SegmentLabel));
    assertEquals("SEMIAUTOMATIC", first.getString(Tag.SegmentAlgorithmType));
    assertEquals("Addis Ortho region grow", first.getString(Tag.SegmentAlgorithmName));
    Attributes category = first.getNestedDataset(Tag.SegmentedPropertyCategoryCodeSequence);
    assertEquals("T-D000A", category.getString(Tag.CodeValue));
    assertEquals("SRT", category.getString(Tag.CodingSchemeDesignator));
    Attributes type = first.getNestedDataset(Tag.SegmentedPropertyTypeCodeSequence);
    assertEquals("272673000", type.getString(Tag.CodeValue));
    assertEquals("SCT", type.getString(Tag.CodingSchemeDesignator));
    assertEquals(3, first.getInts(Tag.RecommendedDisplayCIELabValue).length);

    Attributes shared = seg.getNestedDataset(Tag.SharedFunctionalGroupsSequence);
    assertArrayEquals(
        new double[] {0.8, 0.7},
        shared.getNestedDataset(Tag.PixelMeasuresSequence).getDoubles(Tag.PixelSpacing));
    assertEquals(
        SLICE_SPACING,
        shared.getNestedDataset(Tag.PixelMeasuresSequence).getDouble(Tag.SliceThickness, 0),
        1e-9);
    assertEquals(2, seg.getSequence(Tag.DimensionIndexSequence).size());
  }

  @Test
  void codecReadsSegmentsAndFramePositions() throws IOException {
    for (double angle : new double[] {0, 30}) {
      Attributes seg =
          writeAndRead(
              SegmentationBuilder.build(
                  ctImage(), grid(angle), segments(), "1.2.3.4.5", 1, "4.7.3", new Date()));
      DicomMediaIO reader = mock(DicomMediaIO.class);
      when(reader.getDicomObject()).thenReturn(seg);
      DicomImageElement[] frames = new DicomImageElement[16];
      for (int i = 0; i < frames.length; i++) {
        frames[i] = mock(DicomImageElement.class);
      }
      when(reader.getMediaElement()).thenReturn(frames);
      DicomSeries ct = mock(DicomSeries.class);
      when(ct.getTagValue(TagD.get(Tag.FrameOfReferenceUID))).thenReturn(FRAME_OF_REFERENCE);

      SegSpecialElement element = new SegSpecialElement(reader);
      element.initContours(ct, List.of());

      assertTrue(element.isReady());
      Map<Integer, SegRegion<DicomImageElement>> regions = element.getSegAttributes();
      assertEquals(2, regions.size());
      assertEquals("Bone 1", regions.get(1).getLabel());
      assertEquals("Bone 2", regions.get(2).getLabel());
      Color c = regions.get(2).getColor();
      assertEquals(80, c.getRed(), 2);
      assertEquals(160, c.getGreen(), 2);
      assertEquals(240, c.getBlue(), 2);
      // Every frame is placed by its own position along the slice normal: 16 distinct slices.
      assertEquals(16, element.getPositionMap().size());
      assertEquals(SLICE_SPACING, element.getSliceSpacing(), 1e-6);
      Vector3d normal = element.getReferenceNormal();
      assertEquals(0, normal.distance(0, 0, 1), 1e-9);
      assertEquals(COLUMNS, element.getMaskWidth());
      assertEquals(ROWS, element.getMaskHeight());
      assertArrayEquals(new double[] {0.8, 0.7}, element.getMaskPixelSpacing());
      Vector3d row = element.getMaskRowDirection();
      assertEquals(Math.cos(Math.toRadians(angle)), row.x, 1e-9);
    }
  }

  @Test
  void grownBoneComesBackFrameByFrame() throws Exception {
    CtGrid grid = grid(0);
    // A 700 HU rod with a 50 HU marrow channel, slices 4 to 18, touching the last column.
    HuSource ct =
        (x, y, z) -> {
          if (z < 4 || z > 18) {
            return 0;
          }
          double d = Math.hypot(x - 30, y - 20);
          return d <= 2 ? 50 : d <= 6.5 ? 700 : 0;
        };
    Result bone =
        RegionGrow.grow(
            COLUMNS, ROWS, SLICES, ct, 35, 20, 10, null, GrowOptions.defaults(), GrowProgress.NONE);

    Attributes seg =
        writeAndRead(
            SegmentationBuilder.build(
                ctImage(),
                grid,
                List.of(new Segment(1, "Bone 1", Color.ORANGE, bone, null)),
                "1.2.3.4",
                1,
                "4.7.3",
                new Date()));

    assertEquals(15, seg.getInt(Tag.NumberOfFrames, 0));
    byte[] data = seg.getBytes(Tag.PixelData);
    for (int f = 0; f < 15; f++) {
      for (int y = 0; y < ROWS; y++) {
        for (int x = 0; x < COLUMNS; x++) {
          assertEquals(bone.contains(x, y, f + 4), bit(data, f, x, y));
        }
      }
    }
    assertTrue(bit(data, 0, 30, 20), "the marrow is filled");
    assertTrue(bit(data, 7, 36, 20), "the last column is kept");
  }

  @Test
  void slicesWithoutVoxelsHaveNoFrame() {
    CtGrid grid = grid(0);
    VoxelMask twoSlices =
        new TestMask(new VoxelBox(0, 36, 0, 40, 0, 22), (x, y, z) -> (z == 4 || z == 9) && x == y);

    List<SegmentationBuilder.Frame> frames =
        SegmentationBuilder.frames(
            grid, List.of(new Segment(1, "Bone 1", Color.WHITE, twoSlices, null)));

    assertEquals(List.of(4, 9), frames.stream().map(SegmentationBuilder.Frame::z).toList());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SegmentationBuilder.build(
                ctImage(),
                grid,
                List.of(
                    new Segment(
                        1,
                        "Empty",
                        Color.WHITE,
                        new TestMask(new VoxelBox(0, 1, 0, 1, 0, 1), (_, _, _) -> false),
                        null)),
                "1.2.3",
                1,
                "4.7.3",
                new Date()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SegmentationBuilder.frames(
                grid,
                List.of(
                    new Segment(1, "A", Color.WHITE, twoSlices, null),
                    new Segment(1, "B", Color.WHITE, twoSlices, null))));
  }

  @Test
  void bitsArePackedLeastSignificantFirstAcrossRowsAndFrames() {
    CtGrid grid = grid(0);
    // Last pixel of row 0 and first pixel of row 1 share a byte: bits 36 and 37.
    VoxelMask two =
        new TestMask(
            new VoxelBox(0, 36, 0, 1, 5, 6),
            (x, y, z) ->
                (z == 5 && ((x == 36 && y == 0) || (x == 0 && y == 1)))
                    || z == 6 && x == 0 && y == 0);

    byte[] data =
        SegmentationBuilder.pack(
            grid,
            SegmentationBuilder.frames(grid, List.of(new Segment(1, "B", Color.WHITE, two, null))));

    assertEquals(0b0011_0000, data[4] & 0xFF);
    // Frame 2 starts at bit 37 * 41 = 1517: byte 189, bit 5.
    assertEquals(1 << 5, data[189] & 0xFF);
    int set = 0;
    for (byte b : data) {
      set += Integer.bitCount(b & 0xFF);
    }
    assertEquals(3, set);
  }

  @Test
  void gridSortsSlicesAndMapsPositions() {
    CtGrid grid = grid(30);

    assertEquals(SLICE_SPACING, grid.sliceSpacing(), 1e-9);
    assertEquals(0.8 * 0.7 * SLICE_SPACING, grid.voxelVolumeMm3(), 1e-12);
    assertEquals(-456.7, grid.slice(0).position()[2], 1e-9);
    double[] p = grid.voxelToLps(12, 7, 5);
    assertArrayEquals(new int[] {12, 7, 5}, grid.nearestVoxel(p).orElseThrow());
    double[] farAbove = {p[0], p[1], p[2] + 40};
    assertFalse(grid.nearestVoxel(farAbove).isPresent());
    VoxelBox box =
        grid(0)
            .boxOf(
                new double[] {-123.45 + 0.7 * 2, 67.89, -500},
                new double[] {0, 67.89 + 0.8 * 3, -456.7 + 2 * SLICE_SPACING})
            .orElseThrow();
    assertEquals(new VoxelBox(2, 36, 0, 3, 0, 2), box);
    assertNull(
        CtGrid.of(
                List.of(
                    new CtGrid.Image(
                        4,
                        4,
                        new double[] {1, 0, 0, 0, 1, 0},
                        new double[] {1, 1},
                        new double[] {0, 0, 0},
                        "1",
                        "a",
                        0),
                    new CtGrid.Image(
                        5,
                        4,
                        new double[] {1, 0, 0, 0, 1, 0},
                        new double[] {1, 1},
                        new double[] {0, 0, 1},
                        "1",
                        "b",
                        0)))
            .orElse(null),
        "images of different sizes are not one grid");
  }
}
