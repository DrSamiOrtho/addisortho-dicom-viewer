/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MaskEditTest {

  private static final VoxelBox GRID = VoxelBox.of(40, 40, 40);

  /** A solid block as a mask. */
  private static BoxMask block(VoxelBox box) {
    BitVolume bits = new BitVolume(box.sizeX(), box.sizeY(), box.sizeZ());
    for (int z = 0; z < box.sizeZ(); z++) {
      for (int y = 0; y < box.sizeY(); y++) {
        for (int x = 0; x < box.sizeX(); x++) {
          bits.set(x, y, z);
        }
      }
    }
    return new BoxMask(box, bits);
  }

  @Test
  void brushIsAnEllipsoidClippedToTheGrid() {
    BoxMask round = MaskEdit.brush(new int[] {20, 20, 20}, new double[] {3, 3, 1.5}, GRID);
    assertTrue(round.contains(20, 20, 20));
    assertTrue(round.contains(23, 20, 20));
    assertFalse(round.contains(24, 20, 20));
    assertTrue(round.contains(20, 20, 21));
    assertFalse(round.contains(20, 20, 22), "flatter along z: thicker slices");
    assertTrue(round.contains(22, 22, 20));
    assertFalse(round.contains(23, 22, 20), "a corner of the box is outside the ellipsoid");

    BoxMask edge = MaskEdit.brush(new int[] {0, 0, 0}, new double[] {2, 2, 2}, GRID);
    assertEquals(new VoxelBox(0, 2, 0, 2, 0, 2), edge.box());
    assertNull(MaskEdit.brush(new int[] {-10, 0, 0}, new double[] {2, 2, 2}, GRID));
  }

  @Test
  void denseKeepsOnlyTheVoxelsInTheHuRange() {
    BoxMask brush = MaskEdit.brush(new int[] {10, 10, 10}, new double[] {2, 2, 2}, GRID);
    // Bone to the right of x = 10, metal at x = 12, soft tissue to the left.
    HuSource hu = (x, y, z) -> x == 12 ? 4000 : x >= 10 ? 400 : 40;
    BoxMask dense = MaskEdit.dense(brush, hu, 200, 3000);
    assertNotNull(dense);
    assertTrue(dense.contains(10, 10, 10));
    assertTrue(dense.contains(11, 10, 10));
    assertFalse(dense.contains(9, 10, 10), "soft tissue");
    assertFalse(dense.contains(12, 10, 10), "metal");
    assertNull(MaskEdit.dense(brush, (x, y, z) -> 40, 200, 3000), "nothing dense");
  }

  @Test
  void plusAddsOnlyWhatIsNew() {
    BoxMask bone = block(new VoxelBox(10, 19, 10, 19, 10, 19));
    assertSame(bone, MaskEdit.plus(bone, null));
    assertSame(bone, MaskEdit.plus(bone, block(new VoxelBox(12, 14, 12, 14, 12, 14))));
    BoxMask grown = MaskEdit.plus(bone, block(new VoxelBox(18, 22, 10, 12, 10, 12)));
    assertEquals(1000 + 3 * 3 * 3, grown.count());
    assertTrue(grown.contains(22, 12, 12));
    assertEquals(new VoxelBox(10, 22, 10, 19, 10, 19), grown.box());
  }

  @Test
  void rubbingOutWithTheBrushLeavesTheRest() {
    BoxMask bone = block(new VoxelBox(10, 19, 10, 19, 10, 19));
    BoxMask brush = MaskEdit.brush(new int[] {19, 15, 15}, new double[] {2, 2, 2}, GRID);
    BoxMask left = bone.minus(List.of(brush));
    assertFalse(left.contains(19, 15, 15));
    assertFalse(left.contains(18, 15, 15));
    assertFalse(left.contains(17, 15, 15), "the brush reaches two voxels");
    assertTrue(left.contains(16, 15, 15));
    assertTrue(left.contains(19, 10, 10), "the corner is beyond the brush");
    assertEquals(1000 - countInside(brush, bone), left.count());
  }

  private static long countInside(BoxMask brush, BoxMask bone) {
    long n = 0;
    VoxelBox b = brush.box();
    for (int z = b.z0(); z <= b.z1(); z++) {
      for (int y = b.y0(); y <= b.y1(); y++) {
        for (int x = b.x0(); x <= b.x1(); x++) {
          if (brush.contains(x, y, z) && bone.contains(x, y, z)) {
            n++;
          }
        }
      }
    }
    return n;
  }

  @Test
  void smoothingFillsANotchAndTakesOffASpike() {
    BoxMask bone = block(new VoxelBox(10, 19, 10, 19, 10, 19));
    BitVolume bits = bone.bits().copy();
    bits.clear(5, 5, 9); // a one-voxel notch in the top face
    bits.clear(5, 6, 9);
    BitVolume spiked = bits;
    BoxMask notched = new BoxMask(bone.box(), spiked);
    // A spike needs room outside the block: a mask whose box holds the spike.
    BoxMask withSpike =
        MaskEdit.plus(notched, block(new VoxelBox(14, 14, 14, 14, 20, 24))); // 1 x 1 x 5 spike

    BoxMask smooth = MaskEdit.smooth(withSpike, 1, Thread.currentThread());
    assertNotNull(smooth);
    assertTrue(smooth.contains(15, 15, 19), "the notch is filled");
    assertTrue(smooth.contains(15, 16, 19));
    assertFalse(smooth.contains(14, 14, 22), "the spike is gone");
    assertFalse(smooth.contains(14, 14, 20));
    assertTrue(smooth.contains(10, 15, 15), "the faces stay");
    assertTrue(smooth.contains(15, 15, 19));
    assertFalse(smooth.contains(10, 10, 10), "the edges and corners are rounded off");
    assertEquals(1000 - 104, smooth.count(), "the 8 corners and the 96 other edge voxels");
    assertEquals(bone.box(), smooth.box());

    BoxMask plate =
        MaskEdit.smooth(block(new VoxelBox(5, 6, 5, 30, 5, 30)), 1, Thread.currentThread());
    assertTrue(plate.contains(5, 15, 15), "a plate two voxels thick stays");
    assertTrue(plate.contains(6, 15, 15));
    assertNull(
        MaskEdit.smooth(block(new VoxelBox(5, 5, 5, 30, 5, 30)), 1, Thread.currentThread()),
        "a one-voxel sheet has too few bone neighbours everywhere");
    assertSame(bone, MaskEdit.smooth(bone, 0, Thread.currentThread()));
  }

  @Test
  void aStrokeLeavesNoGapBetweenPointsFarApart() {
    double[] r = {2, 2, 2};
    BoxMask line = MaskEdit.stroke(List.of(new int[] {5, 20, 20}, new int[] {30, 20, 20}), r, GRID);
    for (int x = 5; x <= 30; x++) {
      assertTrue(line.contains(x, 20, 20), "the line is whole at x = " + x);
    }
    assertTrue(line.contains(3, 20, 20), "a ball at the start");
    assertTrue(line.contains(32, 20, 20), "a ball at the end");
    assertFalse(line.contains(15, 23, 20), "no wider than the brush");
    assertTrue(line.contains(15, 22, 20));

    BoxMask one = MaskEdit.stroke(List.of(new int[] {10, 10, 10}), r, GRID);
    assertEquals(MaskEdit.brush(new int[] {10, 10, 10}, r, GRID).count(), one.count());
    assertNull(MaskEdit.stroke(List.of(new int[] {-20, -20, -20}), r, GRID));
  }

  /**
   * A stroke is every voxel within the brush of the line drawn, exactly: balls set along the line
   * left a scalloped edge, and a slanted line a ragged one.
   */
  @Test
  void aStrokeHasAnEvenEdgeAlongASlantedLine() {
    double[] r = {3, 3, 1.5};
    int[] a = {6, 8, 20};
    int[] b = {31, 19, 22};
    BoxMask line = MaskEdit.stroke(List.of(a, b), r, GRID);
    double ux = (b[0] - a[0]) / r[0];
    double uy = (b[1] - a[1]) / r[1];
    double uz = (b[2] - a[2]) / r[2];
    double length2 = ux * ux + uy * uy + uz * uz;
    for (int z = 14; z <= 28; z++) {
      for (int y = 0; y <= 30; y++) {
        for (int x = 0; x <= 39; x++) {
          double px = (x - a[0]) / r[0];
          double py = (y - a[1]) / r[1];
          double pz = (z - a[2]) / r[2];
          double t = Math.clamp((px * ux + py * uy + pz * uz) / length2, 0.0, 1.0);
          double d2 =
              (px - t * ux) * (px - t * ux)
                  + (py - t * uy) * (py - t * uy)
                  + (pz - t * uz) * (pz - t * uz);
          assertEquals(d2 <= 1.0, line.contains(x, y, z), "voxel " + x + ", " + y + ", " + z);
        }
      }
    }
    // Drawn through a point on the way, the same line gives the same voxels or more, never a gap.
    BoxMask bent = MaskEdit.stroke(List.of(a, new int[] {18, 13, 21}, b), r, GRID);
    assertTrue(bent.count() >= line.count() - line.count() / 50);
  }

  @Test
  void strokeOfThisSliceOnlyIsADiscInTheSlice() {
    double[] r = {3, 3, 3};
    double[] axial = {0, 0, 1};
    BoxMask disc = MaskEdit.stroke(List.of(new int[] {20, 20, 20}), r, GRID, axial, 0.6 / 3);
    assertNotNull(disc);
    assertEquals(20, disc.box().z0(), "one slice");
    assertEquals(20, disc.box().z1(), "one slice");
    assertTrue(disc.contains(23, 20, 20), "as wide as the brush in the slice");
    assertFalse(disc.contains(24, 20, 20));
    assertFalse(disc.contains(20, 20, 21), "the slice behind is not touched");

    // A sagittal slice: the disc lies in y and z.
    BoxMask side =
        MaskEdit.stroke(
            List.of(new int[] {20, 10, 20}, new int[] {20, 30, 20}),
            r,
            GRID,
            new double[] {1, 0, 0},
            0.6 / 3);
    assertEquals(20, side.box().x0());
    assertEquals(20, side.box().x1());
    assertTrue(side.contains(20, 20, 23));
    assertFalse(side.contains(21, 20, 20));

    // An oblique slice: no voxel further from it than one layer.
    double h = Math.sqrt(0.5);
    BoxMask oblique =
        MaskEdit.stroke(
            List.of(new int[] {20, 20, 20}), r, GRID, new double[] {h, 0, h}, 0.6 * 2 * h / 3);
    assertTrue(oblique.contains(21, 20, 19), "in the slice");
    assertFalse(oblique.contains(22, 20, 22), "two layers off the slice");
  }

  @Test
  void cutPartsABoneAlongALineDrawnOnASlice() {
    // A bar 30 long along x, 8 x 8 across; the line is drawn across it on the slice z = 20.
    BoxMask bar = block(new VoxelBox(5, 34, 16, 23, 16, 23));
    double[] mm = {1, 1, 1};
    double[] axial = {0, 0, 1};
    List<int[]> line = List.of(new int[] {25, 12, 20}, new int[] {25, 27, 20});
    MaskEdit.Cut cut = MaskEdit.cut(bar, line, mm, axial, 5);
    assertNotNull(cut, "a line right across the bar cuts it");
    assertEquals(bar.count(), cut.kept().count() + cut.piece().count(), "no voxel is lost");
    assertTrue(cut.kept().contains(10, 20, 20), "the longer side is kept");
    assertTrue(cut.piece().contains(30, 20, 20), "the shorter side comes away");
    assertTrue(cut.piece().contains(30, 17, 16), "through every slice of the bone");
    assertFalse(cut.piece().contains(23, 20, 20));
    assertFalse(cut.kept().contains(27, 20, 23));
    long expected = 9L * 8 * 8;
    assertTrue(
        Math.abs(cut.piece().count() - expected) <= 2L * 8 * 8,
        "the cut lies at the line, within the wall: " + cut.piece().count());
  }

  @Test
  void cutOnADiagonalLineLeavesNoBridge() {
    BoxMask slab = block(new VoxelBox(5, 34, 5, 34, 18, 21));
    List<int[]> line = List.of(new int[] {2, 2, 20}, new int[] {37, 37, 20});
    MaskEdit.Cut cut = MaskEdit.cut(slab, line, new double[] {1, 1, 1}, new double[] {0, 0, 1}, 5);
    assertNotNull(cut);
    assertEquals(slab.count(), cut.kept().count() + cut.piece().count());
    assertTrue(cut.kept().contains(30, 8, 20) != cut.piece().contains(30, 8, 20));
    assertTrue(
        cut.kept().contains(30, 8, 20) != cut.kept().contains(8, 30, 20),
        "the two corners are on different sides");
  }

  @Test
  void cutIsRefusedWhenTheLineDoesNotPartTheBone() {
    BoxMask bar = block(new VoxelBox(5, 34, 16, 23, 16, 23));
    double[] mm = {1, 1, 1};
    double[] axial = {0, 0, 1};
    // Half way across only.
    assertNull(
        MaskEdit.cut(bar, List.of(new int[] {25, 12, 20}, new int[] {25, 19, 20}), mm, axial, 5));
    // Beside the bone.
    assertNull(
        MaskEdit.cut(bar, List.of(new int[] {2, 2, 20}, new int[] {2, 30, 20}), mm, axial, 5));
    // One point is no line.
    assertNull(MaskEdit.cut(bar, List.of(new int[] {25, 20, 20}), mm, axial, 5));
    // A corner crumb under the smallest piece is no cut.
    assertNull(
        MaskEdit.cut(bar, List.of(new int[] {32, 14, 20}, new int[] {36, 18, 20}), mm, axial, 200));
  }

  @Test
  void cutLeavesAnIslandTheLineDoesNotReachWithTheKeptPart() {
    BoxMask bar = block(new VoxelBox(5, 34, 16, 23, 16, 23));
    BoxMask island = block(new VoxelBox(30, 33, 30, 33, 18, 21));
    BoxMask both = BoxMask.union(List.of(bar, island));
    List<int[]> line = List.of(new int[] {25, 12, 20}, new int[] {25, 27, 20});
    MaskEdit.Cut cut = MaskEdit.cut(both, line, new double[] {1, 1, 1}, new double[] {0, 0, 1}, 5);
    assertNotNull(cut);
    assertTrue(cut.kept().contains(31, 31, 20), "the island was not met by the line");
    assertTrue(cut.piece().contains(30, 20, 20));
  }

  @Test
  void cutFollowsTheVoxelSizeOnThickSlices() {
    // 3 mm slices: a line on a coronal slice (normal along y) cuts between slices cleanly.
    BoxMask bar = block(new VoxelBox(10, 17, 10, 17, 2, 31));
    List<int[]> line = List.of(new int[] {6, 13, 20}, new int[] {21, 13, 20});
    MaskEdit.Cut cut =
        MaskEdit.cut(bar, line, new double[] {0.5, 0.5, 3}, new double[] {0, 1, 0}, 5);
    assertNotNull(cut);
    assertEquals(bar.count(), cut.kept().count() + cut.piece().count());
    assertTrue(cut.kept().contains(13, 13, 5));
    assertTrue(cut.piece().contains(13, 13, 28));
  }
}
