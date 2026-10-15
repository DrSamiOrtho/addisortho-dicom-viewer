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

  /** The grid voxel of a volume voxel: the flood works on the grid, whose z is reversed. */
  private static Vector3i gridOf(int vx, int vy, int vz) {
    return SPACE.gridOfVolume(vx, vy, vz, new Vector3i());
  }

  private static final VoxelSampler VOLUME =
      (x, y, z) -> {
        if (x < 0 || y < 0 || z < 0 || x >= N || y >= N || z >= N) {
          return Double.NaN;
        }
        return solid(x, y, z) ? 800 : -200;
      };

  private static VoxelBits allKept() {
    VoxelBits kept = new VoxelBits(N, N, N);
    kept.fill(true);
    return kept;
  }

  @Test
  void withoutSeparationTheWholeConnectedStructureIsSelected() {
    Optional<Result> r = VoxelFlood.grow(SPACE, allKept(), VOLUME, 200, gridOf(4, 12, 12), 0);
    assertTrue(r.isPresent());
    assertEquals(2 * 512 + 8, r.get().voxels());
    assertFalse(r.get().separated());
  }

  @Test
  void lightSeparationKeepsOnlyTheClickedCube() {
    Optional<Result> r = VoxelFlood.grow(SPACE, allKept(), VOLUME, 200, gridOf(4, 12, 12), 1);
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
    Result r = VoxelFlood.grow(SPACE, kept, VOLUME, 200, gridOf(20, 12, 12), 1).orElseThrow();
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
    Optional<Result> r = VoxelFlood.grow(SPACE, allKept(), VOLUME, 200, gridOf(0, 12, 12), 0);
    assertTrue(r.isPresent());
    assertTrue(r.get().voxels() >= 512);
  }

  @Test
  void nothingNearTheClickSelectsNothing() {
    Optional<Result> r = VoxelFlood.grow(SPACE, allKept(), VOLUME, 200, gridOf(28, 2, 2), 0);
    assertTrue(r.isEmpty());
  }

  @Test
  void theFloodStaysInsideTheShownVoxels() {
    VoxelBits kept = allKept();
    // The bridge is already cut away: the two cubes are separate without any separation.
    Vector3i bridge = gridOf(13, 13, 13);
    kept.clear(bridge.x, bridge.y, bridge.z);
    Result r = VoxelFlood.grow(SPACE, kept, VOLUME, 200, gridOf(4, 12, 12), 0).orElseThrow();
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
    assertEquals(2 * kept.bytes() + (4L << 16), flood);
    assertEquals(8 * kept.bytes() + (4L << 16), separated);
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
    VoxelFlood.IntQueue queue = new VoxelFlood.IntQueue(16);
    for (int i = 0; i < 1000; i++) {
      queue.add(i);
    }
    for (int i = 0; i < 1000; i++) {
      assertEquals(i, queue.poll());
    }
    assertTrue(queue.isEmpty());
    assertTrue(VoxelFlood.MAX_QUEUE_ENTRIES * 4L <= 256L << 20);
  }
}
