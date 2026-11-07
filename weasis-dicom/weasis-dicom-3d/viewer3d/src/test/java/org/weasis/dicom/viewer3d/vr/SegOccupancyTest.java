/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.dicom.viewer3d.vr;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.weasis.core.ui.model.graphic.imp.seg.SegRegion;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.seg.SegmentationVolume;
import org.weasis.opencv.seg.RegionAttributes;

/** Synthetic label volumes only. */
class SegOccupancyTest {

  /** The shader's surface level, 0.5 of the normalised byte. */
  private static final double SURFACE = 127.5;

  private static boolean isInside(ByteBuffer occupancy, int index) {
    return Byte.toUnsignedInt(occupancy.get(index)) > SURFACE;
  }

  @Test
  void visibleIdsFollowTheLutAlpha() {
    byte[] lut = {
      0,
      0,
      0,
      (byte) 255, // background: never visible, whatever its alpha
      10,
      20,
      30,
      (byte) 200, // id 1 visible
      10,
      20,
      30,
      0, // id 2 hidden
      0,
      0,
      0,
      1 // id 3 visible, even barely opaque
    };
    assertArrayEquals(new boolean[] {false, true, false, true}, SegOccupancy.visibleIds(lut));
    assertArrayEquals(new boolean[0], SegOccupancy.visibleIds(null));
  }

  @Test
  void opacityAndColourChangesKeepTheSameVisibility() {
    byte[] lut = {0, 0, 0, 0, 1, 2, 3, (byte) 255, 4, 5, 6, 0};
    byte[] recoloured = {0, 0, 0, 0, 90, 80, 70, 12, 4, 5, 6, 0};
    assertArrayEquals(SegOccupancy.visibleIds(lut), SegOccupancy.visibleIds(recoloured));
  }

  @Test
  void anyVisible() {
    assertAll(
        () -> assertFalse(SegOccupancy.anyVisible(new boolean[0])),
        () -> assertFalse(SegOccupancy.anyVisible(new boolean[] {false, false})),
        () -> assertTrue(SegOccupancy.anyVisible(new boolean[] {false, true})));
  }

  @Test
  void encodingPutsTheBoundaryOnTheSurfaceLevel() {
    assertAll(
        () -> assertTrue(SegOccupancy.encode(0.5) > SURFACE), // first voxel inside
        () -> assertTrue(SegOccupancy.encode(-0.5) < SURFACE), // first voxel outside
        () -> assertTrue(SegOccupancy.encode(3) > SegOccupancy.encode(1)),
        () -> assertEquals(255, SegOccupancy.encode(SegOccupancy.DISTANCE_RANGE)),
        () -> assertEquals(255, SegOccupancy.encode(1000)),
        () -> assertEquals(0, SegOccupancy.encode(-SegOccupancy.DISTANCE_RANGE)),
        () -> assertEquals(0, SegOccupancy.encode(-1000)));
  }

  @Test
  void insideTestMatchesTheVisibleLabelsAtVoxelCentres() {
    // 6 x 3 slice of byte labels: id 1 visible, id 2 hidden, 7 beyond the visibility table.
    byte[] ids = {
      0, 1, 1, 2, 7, 0,
      0, 1, 1, 1, 2, 0,
      1, 0, 2, 2, 1, 1
    };
    boolean[] visible = {false, true, false};
    ByteBuffer occupancy = ByteBuffer.allocate(ids.length);

    SegOccupancy.fill(ByteBuffer.wrap(ids), false, visible, occupancy, 6, 3, 1);

    for (int i = 0; i < ids.length; i++) {
      assertEquals(ids[i] == 1, isInside(occupancy, i), "voxel " + i);
    }
  }

  @Test
  void valueGrowsWithTheDistanceToTheBoundary() {
    // One slice, 40 voxels wide: inside on the left half.
    int width = 40;
    byte[] ids = new byte[width];
    for (int x = 0; x < 20; x++) {
      ids[x] = 1;
    }
    ByteBuffer occupancy = ByteBuffer.allocate(width);

    SegOccupancy.fill(
        ByteBuffer.wrap(ids), false, new boolean[] {false, true}, occupancy, width, 1, 1);

    int[] v = new int[width];
    for (int x = 0; x < width; x++) {
      v[x] = Byte.toUnsignedInt(occupancy.get(x));
    }
    assertAll(
        () -> assertEquals(SegOccupancy.encode(0.5), v[19]),
        () -> assertEquals(SegOccupancy.encode(-0.5), v[20]),
        () -> assertEquals(SegOccupancy.encode(5.5), v[14]),
        () -> assertEquals(SegOccupancy.encode(-5.5), v[25]),
        () -> assertEquals(255, v[0]), // beyond the clamped range
        () -> assertEquals(0, v[39]));
    for (int x = 1; x < width; x++) {
      assertTrue(v[x] <= v[x - 1], "monotonic at " + x);
    }
  }

  @Test
  void diagonalDistanceIsEuclidean() {
    // A single inside voxel in the middle of a 9 x 9 slice.
    byte[] ids = new byte[81];
    ids[40] = 1;
    ByteBuffer occupancy = ByteBuffer.allocate(81);

    SegOccupancy.fill(ByteBuffer.wrap(ids), false, new boolean[] {false, true}, occupancy, 9, 9, 1);

    // Voxel (6, 6) is (2, 2) away from the inside one.
    assertEquals(
        SegOccupancy.encode(0.5 - Math.sqrt(8)), Byte.toUnsignedInt(occupancy.get(6 * 9 + 6)));
    assertTrue(isInside(occupancy, 40)); // a one-voxel structure is kept
  }

  @Test
  void slicesWithoutABoundaryAreUniform() {
    byte[] ids = {1, 1, 1, 1, 0, 0, 0, 0};
    ByteBuffer occupancy = ByteBuffer.allocate(8);

    SegOccupancy.fill(ByteBuffer.wrap(ids), false, new boolean[] {false, true}, occupancy, 2, 2, 2);

    assertArrayEquals(
        new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255, 0, 0, 0, 0}, occupancy.array());
  }

  @Test
  void slicesAreIndependent() {
    // Distances are measured within each slice only: slice 1 is all outside, slice 0 has a
    // boundary, and slice 1 must not see slice 0's voxels.
    byte[] ids = {1, 0, 0, 0, 0, 0};
    ByteBuffer occupancy = ByteBuffer.allocate(6);

    SegOccupancy.fill(ByteBuffer.wrap(ids), false, new boolean[] {false, true}, occupancy, 3, 1, 2);

    assertAll(
        () -> assertTrue(isInside(occupancy, 0)),
        () -> assertEquals(SegOccupancy.encode(-0.5), Byte.toUnsignedInt(occupancy.get(1))),
        () -> assertEquals(0, Byte.toUnsignedInt(occupancy.get(3))));
  }

  @Test
  void unsignedShortLabelsInTheBufferOrder() {
    boolean[] visible = new boolean[40_001];
    visible[300] = true;
    visible[40_000] = true; // above Short.MAX_VALUE: must be read unsigned
    for (ByteOrder order : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
      ByteBuffer labels = ByteBuffer.allocate(8).order(order);
      labels.putShort((short) 300).putShort((short) 40_000).putShort((short) 1).putShort((short) 0);
      labels.flip();
      ByteBuffer occupancy = ByteBuffer.allocate(4);

      SegOccupancy.fill(labels, true, visible, occupancy, 4, 1, 1);

      assertAll(
          order.toString(),
          () -> assertTrue(isInside(occupancy, 0)),
          () -> assertTrue(isInside(occupancy, 1)),
          () -> assertFalse(isInside(occupancy, 2)),
          () -> assertFalse(isInside(occupancy, 3)));
    }
  }

  @Test
  void readsAndWritesFromTheCurrentPositionsAndAdvancesThem() {
    ByteBuffer labels = ByteBuffer.wrap(new byte[] {9, 9, 1, 0});
    labels.position(2);
    ByteBuffer occupancy = ByteBuffer.allocate(5);
    occupancy.position(3);

    SegOccupancy.fill(labels, false, new boolean[] {false, true}, occupancy, 2, 1, 1);

    assertAll(
        () -> assertEquals(4, labels.position()),
        () -> assertEquals(5, occupancy.position()),
        () -> assertEquals(0, occupancy.get(0)), // untouched
        () -> assertTrue(isInside(occupancy, 3)),
        () -> assertFalse(isInside(occupancy, 4)));
  }

  /** Same path as the GPU upload: storage IDs exported from a volume, visibility from its LUT. */
  @Test
  void volumeWithAHiddenSegmentAndAnOverlap() {
    SegRegion<DicomImageElement> femur = new SegRegion<>(1, "Femur", Color.YELLOW);
    SegRegion<DicomImageElement> tibia = new SegRegion<>(2, "Tibia", Color.CYAN);
    Map<Integer, RegionAttributes> attributes = new LinkedHashMap<>();
    attributes.put(1, femur);
    attributes.put(2, tibia);
    SegmentationVolume volume =
        new SegmentationVolume(
            3,
            1,
            1,
            new Vector3d(1, 1, 3),
            new Vector3d(),
            new Vector3d(1, 0, 0),
            new Vector3d(0, 1, 0),
            new Vector3d(0, 0, 1),
            attributes);
    volume.addLabel(0, 0, 0, 1); // femur only
    volume.addLabel(1, 0, 0, 1); // femur and tibia: an overlap combination ID
    volume.addLabel(1, 0, 0, 2);
    volume.addLabel(2, 0, 0, 2); // tibia only

    SegRegion<DicomImageElement> hiddenTibia = tibia.copy();
    hiddenTibia.setVisible(false);
    boolean[] visible =
        SegOccupancy.visibleIds(volume.buildSegmentColorLUT(Map.of(2, hiddenTibia)));

    ByteBuffer labels = ByteBuffer.allocateDirect(3).order(ByteOrder.nativeOrder());
    assertTrue(volume.exportSliceInto(0, labels));
    labels.flip();
    ByteBuffer occupancy = ByteBuffer.allocate(3);
    SegOccupancy.fill(labels, volume.isShortMode(), visible, occupancy, 3, 1, 1);

    // The overlap stays inside: one of its segments is visible.
    assertAll(
        () -> assertTrue(isInside(occupancy, 0)),
        () -> assertTrue(isInside(occupancy, 1)),
        () -> assertFalse(isInside(occupancy, 2)));
  }
}
