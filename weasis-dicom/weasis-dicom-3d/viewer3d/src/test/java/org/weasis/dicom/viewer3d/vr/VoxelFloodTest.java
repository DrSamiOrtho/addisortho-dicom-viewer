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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import org.weasis.dicom.viewer3d.vr.VoxelFlood.Result;

/** Keep only this and remove this on two cubes joined by a thin bridge. */
class VoxelFloodTest {

  private static final int N = 32;
  private static final VolumeSpace SPACE =
      VolumeSpace.ofVolume(new Vector3i(N, N, N), new Vector3d(1, 1, 1));

  /** Cube A at x 2..9, cube B at x 18..25, a 1-voxel bridge between them, all at y,z 10..17. */
  private static boolean solid(int x, int y, int z) {
    boolean inYZ = y >= 10 && y <= 17 && z >= 10 && z <= 17;
    boolean cubeA = x >= 2 && x <= 9 && inYZ;
    boolean cubeB = x >= 18 && x <= 25 && inYZ;
    boolean bridge = x >= 10 && x <= 17 && y == 13 && z == 13;
    return cubeA || cubeB || bridge;
  }

  /** The flood works on the mask grid, which is the texture here: indices are texture voxels. */
  private static Vector3i gridOf(int x, int y, int z) {
    return new Vector3i(x, y, z);
  }

  /** The texture field: the solid shapes, in texture voxel indices. */
  private static final VoxelSampler VOLUME =
      (x, y, z) -> {
        if (x < 0 || y < 0 || z < 0 || x >= N || y >= N || z >= N) {
          return Double.NaN;
        }
        return solid(x, y, z) ? 800 : -200;
      };

  /** Shown from 200 up, as a bone preset with a linear window. */
  private static final VoxelFlood.Visibility ABOVE_200 = (x, y, z, v) -> v >= 200;

  private static VoxelBits allKept() {
    VoxelBits kept = new VoxelBits(N, N, N);
    kept.fill(true);
    return kept;
  }

  @Test
  void withoutSeparationTheWholeConnectedStructureIsSelected() {
    Optional<Result> r = VoxelFlood.grow(SPACE, allKept(), VOLUME, ABOVE_200, gridOf(4, 12, 12), 0);
    assertTrue(r.isPresent());
    assertEquals(2 * 512 + 8, r.get().voxels());
    assertFalse(r.get().separated());
  }

  @Test
  void lightSeparationKeepsOnlyTheClickedCube() {
    Optional<Result> r = VoxelFlood.grow(SPACE, allKept(), VOLUME, ABOVE_200, gridOf(4, 12, 12), 1);
    assertTrue(r.isPresent());
    assertTrue(r.get().separated());
    long voxels = r.get().voxels();
    // Cube A whole, plus up to half of the bridge; cube B is not reached.
    assertTrue(voxels >= 512 && voxels <= 512 + 4, "voxels " + voxels);
    Vector3i b = gridOf(20, 12, 12);
    assertFalse(r.get().region().get(b.x, b.y, b.z));
    Vector3i a = gridOf(2, 10, 17);
    assertTrue(r.get().region().get(a.x, a.y, a.z), "the cube's thin edge grows back");
  }

  @Test
  void keepOnlyAndRemoveBuildComplementaryCuts() {
    VoxelBits kept = allKept();
    Result r = VoxelFlood.grow(SPACE, kept, VOLUME, ABOVE_200, gridOf(20, 12, 12), 1).orElseThrow();
    MaskDiff keep = VoxelFlood.keepOnly(kept, r.region());
    MaskDiff remove = VoxelFlood.remove(kept, r.region());
    assertEquals((long) N * N * N, keep.voxels() + remove.voxels());
    assertEquals(r.voxels(), remove.voxels());

    VoxelBits afterKeep = allKept();
    keep.applyTo(afterKeep);
    Vector3i inB = gridOf(20, 12, 12);
    Vector3i inA = gridOf(4, 12, 12);
    assertTrue(afterKeep.get(inB.x, inB.y, inB.z));
    assertFalse(afterKeep.get(inA.x, inA.y, inA.z));
    assertEquals(r.voxels(), afterKeep.count());
  }

  @Test
  void aClickBesideTheStructureSnapsToIt() {
    // Two voxels away from cube A, in air.
    Optional<Result> r = VoxelFlood.grow(SPACE, allKept(), VOLUME, ABOVE_200, gridOf(0, 12, 12), 0);
    assertTrue(r.isPresent());
    assertTrue(r.get().voxels() >= 512);
  }

  @Test
  void nothingNearTheClickSelectsNothing() {
    Optional<Result> r = VoxelFlood.grow(SPACE, allKept(), VOLUME, ABOVE_200, gridOf(28, 2, 2), 0);
    assertTrue(r.isEmpty());
  }

  @Test
  void theFloodStaysInsideTheShownVoxels() {
    VoxelBits kept = allKept();
    // The bridge is already cut away: the two cubes are separate without any separation.
    Vector3i bridge = gridOf(13, 13, 13);
    kept.clear(bridge.x, bridge.y, bridge.z);
    Result r = VoxelFlood.grow(SPACE, kept, VOLUME, ABOVE_200, gridOf(4, 12, 12), 0).orElseThrow();
    assertEquals(512 + 3, r.voxels());
  }

  @Test
  void visibleThresholdFollowsThePresetAndWindow() {
    // A LUT from -1000 with alpha rising from 0 at entry 1120 (120 HU) to full at 1500 (500 HU).
    int width = 4000;
    byte[] lut = new byte[width * 4];
    for (int i = 0; i < width; i++) {
      int hu = i - 1000;
      double a = Math.clamp((hu - 120) / 380.0, 0, 1);
      lut[i * 4 + 3] = (byte) Math.round(a * 255);
    }
    TransferFunction tf = new TransferFunction(lut, width, -1000 + width / 2.0, 1.0);
    double threshold = tf.visibleThreshold(0.02);
    assertEquals(120 + 0.02 * 380, threshold, 3.0);
    assertEquals(0.0, tf.opacity(0), 1e-6);
    assertEquals(1.0, tf.opacity(1200), 1e-6);
  }

  @Test
  void regrowStartsFromTheCoresSurfaceNotItsBulk() {
    // A solid 10x10x10 cube; its core eroded by one voxel is 8x8x8 = 512 voxels, of which only
    // the 512 - 6^3 = 296 on the surface touch a free voxel and start the front.
    VoxelBits solid = new VoxelBits(14, 14, 14);
    VoxelBits core = new VoxelBits(14, 14, 14);
    for (int z = 2; z < 12; z++) {
      for (int y = 2; y < 12; y++) {
        for (int x = 2; x < 12; x++) {
          solid.set(x, y, z);
          if (x > 2 && x < 11 && y > 2 && y < 11 && z > 2 && z < 11) {
            core.set(x, y, z);
          }
        }
      }
    }
    assertEquals(512, core.count());
    assertEquals(
        296, VoxelFlood.countRegrowSeeds(solid, core, new VoxelFlood.Box(0, 13, 0, 13, 0, 13)));
  }

  @Test
  void workingMemoryIsBudgetedBeforeAnythingIsAllocated() {
    VoxelBits kept = new VoxelBits(512, 512, 660);
    long flood = VoxelFlood.workingBytes(kept, 0);
    long separated = VoxelFlood.workingBytes(kept, 2);
    // 173M voxels: 21.6 MB per bit volume, 2 for the flood and 8 with the separation.
    assertEquals(2 * kept.bytes(), flood);
    assertEquals(8 * kept.bytes(), separated);
    assertTrue(separated < 200L << 20);
    assertDoesNotThrow(
        () -> VoxelFlood.checkBudget(separated, separated + VoxelFlood.HEADROOM_BYTES));
    VoxelFlood.BudgetException e =
        assertThrows(
            VoxelFlood.BudgetException.class,
            () -> VoxelFlood.checkBudget(separated, separated + VoxelFlood.HEADROOM_BYTES - 1));
    assertEquals(separated, e.neededBytes());
    assertTrue(e.getMessage().contains("MB"));
  }

  @Test
  void aFrontBeyondTheQueueCapIsRefusedNotGrownWithoutBound() {
    WorkingMemory budget = new WorkingMemory(1L << 20);
    VoxelFlood.IntQueue queue = new VoxelFlood.IntQueue(16, budget);
    for (int i = 0; i < 1000; i++) {
      queue.add(i);
    }
    for (int i = 0; i < 1000; i++) {
      assertEquals(i, queue.poll());
    }
    assertTrue(queue.isEmpty());
    // 1024 entries after the growth: 4 KB held, 6 KB at the peak of the last copy.
    assertEquals(4L * 1024, budget.used());
    assertEquals(4L * 1024 + 4L * 512, budget.peak());
    queue.free();
    assertEquals(0, budget.used());
    // A queue that would overrun the budget is refused as it grows, not after.
    WorkingMemory tiny = new WorkingMemory(4L * 64);
    VoxelFlood.IntQueue small = new VoxelFlood.IntQueue(16, tiny);
    assertThrows(
        VoxelFlood.BudgetException.class,
        () -> {
          for (int i = 0; i < 100; i++) {
            small.add(i);
          }
        });
    assertTrue(VoxelFlood.MAX_QUEUE_ENTRIES * 4L <= 256L << 20);
  }

  @Test
  void anInvisibleBandOfHigherValuesDoesNotJoinTwoStructures() {
    // Two cubes at 300 joined by a bridge at 900, with a preset opaque from 200 to 600 only (as
    // the Fat preset is opaque in a band): the bridge is shown nowhere, so the structures stay
    // apart even though its values are above any lower bound that shows the cubes.
    VoxelSampler banded =
        (x, y, z) -> {
          if (x < 0 || y < 0 || z < 0 || x >= N || y >= N || z >= N) {
            return Double.NaN;
          }
          boolean bridge = x >= 10 && x <= 17 && y == 13 && z == 13;
          return bridge ? 900 : solid(x, y, z) ? 300 : -200;
        };
    byte[] lut = new byte[2000 * 4];
    for (int i = 0; i < 2000; i++) {
      lut[i * 4 + 3] = (byte) (i >= 200 && i <= 600 ? 0xFF : 0);
    }
    TransferFunction band = new TransferFunction(lut, 2000, 1000, 1.0);
    VoxelFlood.Visibility shown = VoxelFlood.Visibility.ofOpacity(band, 0.02);
    Result r = VoxelFlood.grow(SPACE, allKept(), banded, shown, gridOf(4, 12, 12), 0).orElseThrow();
    assertEquals(512, r.voxels(), "cube A alone, the bridge is invisible");
    // A lower bound at the cubes' value would have taken the bridge and cube B along.
    Result bound =
        VoxelFlood.grow(SPACE, allKept(), banded, ABOVE_200, gridOf(4, 12, 12), 0).orElseThrow();
    assertEquals(2 * 512 + 8, bound.voxels());
  }

  @Test
  void lutShapesFollowTheShader() {
    byte[] lut = new byte[1000 * 4];
    for (int i = 0; i < 1000; i++) {
      lut[i * 4 + 3] = (byte) 0xFF;
    }
    double ww = 400;
    double wc = 100;
    TransferFunction sigmoid = new TransferFunction(lut, ww, wc, 1.0, TransferFunction.SIGMOID);
    // sigmoidLUT with nFactor -20: outRange / (1 + exp(-4 (v - wc) / ww)); half the range at wc.
    assertEquals(500, sigmoid.windowed(wc), 1e-9);
    assertEquals(1000 / (1 + Math.exp(-4 * 0.5)), sigmoid.windowed(wc + ww / 2), 1e-9);
    TransferFunction norm = new TransferFunction(lut, ww, wc, 1.0, TransferFunction.SIGMOID_NORM);
    assertEquals(0, norm.windowed(wc - ww / 2), 1e-9);
    assertEquals(1000, norm.windowed(wc + ww / 2), 1e-9);
    TransferFunction log = new TransferFunction(lut, ww, wc, 1.0, TransferFunction.LOG);
    assertEquals(0, log.windowed(wc - ww / 2), 1e-9);
    assertEquals(1000, log.windowed(wc + ww / 2), 1e-9);
    assertEquals(0, log.windowed(wc - 10 * ww), 0.0, "an undefined log shows nothing");
    TransferFunction inv = new TransferFunction(lut, ww, wc, 1.0, TransferFunction.LOG_INV);
    assertEquals(0, inv.windowed(wc - ww / 2), 1e-9);
    assertEquals(1000, inv.windowed(wc + ww / 2), 1e-9);
    TransferFunction linear = new TransferFunction(lut, ww, wc, 1.0);
    assertEquals(500, linear.windowed(wc), 1e-9);
    assertEquals(TransferFunction.LINEAR, linear.lutShape());
  }

  @Test
  void aSegmentationMaskBoundsTheSelectionAsItBoundsTheRendering() {
    // Mask include with a segment over cube A only: cube B and the bridge are not shown, so the
    // selection from A stops at the segment's edge even though the bridge joins the cubes.
    VolumeSpace space = SPACE;
    SegmentField overA = t -> t.x * N < 10 ? 1.0 : 0.0;
    VoxelFlood.Visibility shown =
        (x, y, z, v) -> {
          if (v < 200) {
            return false;
          }
          Vector3d t = space.texCoordOfTextureVoxel(x, y, z, new Vector3d());
          return !VolumePicker.SegMode.MASK_INCLUDE.masksAnatomy(overA.insideVisible(t));
        };
    Result r = VoxelFlood.grow(SPACE, allKept(), VOLUME, shown, gridOf(4, 12, 12), 0).orElseThrow();
    assertEquals(512, r.voxels());
    // Mask exclude with the same segment: a click on cube A finds nothing shown near it.
    VoxelFlood.Visibility excluded =
        (x, y, z, v) -> {
          if (v < 200) {
            return false;
          }
          Vector3d t = space.texCoordOfTextureVoxel(x, y, z, new Vector3d());
          return !VolumePicker.SegMode.MASK_EXCLUDE.masksAnatomy(overA.insideVisible(t));
        };
    assertTrue(VoxelFlood.grow(SPACE, allKept(), VOLUME, excluded, gridOf(4, 12, 12), 0).isEmpty());
    // From cube B the exclude mode grows B and the bridge up to the segment's edge.
    Result b =
        VoxelFlood.grow(SPACE, allKept(), VOLUME, excluded, gridOf(20, 12, 12), 0).orElseThrow();
    assertEquals(512 + 8, b.voxels());
  }

  @Test
  void aDenseSelectionHoldsTwoSliceQueuesAtMostNotOnePerSlice() {
    // A 512x512 grid, 8 slices, every voxel shown: the sweep fills the next slice's queue while
    // it drains the current one and frees the drained one, so the queues peak at two slices
    // (plus the growth copy), far from the one-queue-per-slice of a retained front.
    int w = 512;
    int h = 512;
    int d = 8;
    VoxelBits kept = new VoxelBits(w, h, d);
    kept.fill(true);
    VolumeSpace space = VolumeSpace.ofVolume(new Vector3i(w, h, d), new Vector3d(1, 1, 1));
    VoxelSampler dense = (x, y, z) -> 1000;
    WorkingMemory budget = new WorkingMemory(64L << 20);
    Result r =
        VoxelFlood.grow(
                space, kept, dense, (x, y, z, v) -> true, new Vector3i(256, 256, 3), 0, budget)
            .orElseThrow();
    assertEquals((long) w * h * d, r.voxels());
    long sliceQueue = 4L * w * h; // one int per voxel of a slice
    long bits = 2 * kept.bytes();
    // The slice being drained, the queue its own neighbours go to, the next slice's queue and
    // one growth copy: four slice queues is the ceiling, whatever the depth.
    assertTrue(r.peakBytes() <= bits + 4 * sliceQueue + (4L << 12), "peak " + r.peakBytes());
    assertTrue(r.peakBytes() < bits + (long) (d - 2) * sliceQueue, "no queue per slice retained");
    assertEquals(bits, budget.used(), "every queue was given back");
  }

  @Test
  void separationReleasesEveryQueueSoABudgetOfItsPeakSuffices() {
    WorkingMemory generous = new WorkingMemory(256L << 20);
    Result first =
        VoxelFlood.grow(SPACE, allKept(), VOLUME, ABOVE_200, gridOf(4, 12, 12), 2, generous)
            .orElseThrow();
    assertTrue(first.separated());
    // Nothing but the bit volumes and the sampler ceiling is still reserved when it is over.
    assertEquals(VoxelFlood.workingBytes(allKept(), 2), generous.used());
    // The same selection within a budget of exactly its peak live set fits: no reservation of a
    // dead queue (holes filling, the core search, the regrow) lingers to push it over.
    WorkingMemory exact = new WorkingMemory(first.peakBytes());
    Result again =
        VoxelFlood.grow(SPACE, allKept(), VOLUME, ABOVE_200, gridOf(4, 12, 12), 2, exact)
            .orElseThrow();
    assertEquals(first.voxels(), again.voxels());
    assertEquals(first.peakBytes(), again.peakBytes());
    assertTrue(
        new WorkingMemory(first.peakBytes() - 1).limit() < first.peakBytes()
            && assertThrowsBudget(first.peakBytes() - 1));
  }

  private static boolean assertThrowsBudget(long limit) {
    assertThrows(
        VoxelFlood.BudgetException.class,
        () ->
            VoxelFlood.grow(
                SPACE,
                allKept(),
                VOLUME,
                ABOVE_200,
                gridOf(4, 12, 12),
                2,
                new WorkingMemory(limit)));
    return true;
  }
}
