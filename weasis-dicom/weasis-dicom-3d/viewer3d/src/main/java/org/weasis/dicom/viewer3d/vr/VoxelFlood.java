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

import java.util.Optional;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * The structure under a picked voxel: a 6-connected region grown over the shown voxels whose value
 * reaches the renderer's visible threshold, so that one click selects what one sees. The same
 * algorithm as the bone select of the Addis Ortho bones bundle, on the kept mask's grid.
 *
 * <p>With a separation depth {@code k}, a neighbour joined to the structure by thin bridges is
 * split off: the region, holes filled slice by slice, is eroded by {@code k} voxels, which cuts the
 * bridges; the cores then grow back inside the region all at once, each voxel going to the core
 * that reaches it first, and the seeded core's share is the result.
 */
public final class VoxelFlood {

  /**
   * @param region the selected voxels, on the grid of the kept mask
   * @param voxels how many
   * @param separated whether the separation applied (it is skipped when erosion leaves no core)
   * @param millis time taken
   */
  public record Result(VoxelBits region, long voxels, boolean separated, long millis) {}

  /** Box of the grid, bounds included. */
  record Box(int x0, int x1, int y0, int y1, int z0, int z1) {
    Box grow(int margin, VoxelBits v) {
      return new Box(
          Math.max(0, x0 - margin),
          Math.min(v.sizeX() - 1, x1 + margin),
          Math.max(0, y0 - margin),
          Math.min(v.sizeY() - 1, y1 + margin),
          Math.max(0, z0 - margin),
          Math.min(v.sizeZ() - 1, z1 + margin));
    }
  }

  private static final int[][] NEIGHBOURS = {
    {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
  };

  /** How far from the clicked voxel a voxel above the threshold is looked for. */
  private static final int SEED_SEARCH = 3;

  private VoxelFlood() {}

  /**
   * Grows the structure of {@code seed}.
   *
   * @param kept the voxels shown; the region stays inside them
   * @param sampler the volume values
   * @param threshold lowest value taken into the region
   * @param seed the picked grid voxel; the nearest shown voxel above the threshold within a few
   *     voxels is taken when it is below
   * @param separation erosion depth of the separation, 0 for none
   * @return empty when no voxel near the seed is above the threshold
   */
  public static Optional<Result> grow(
      VolumeSpace space,
      VoxelBits kept,
      VoxelSampler sampler,
      double threshold,
      Vector3i seed,
      int separation) {
    long start = System.nanoTime();
    Vector3i s = findSeed(space, kept, sampler, threshold, seed);
    if (s == null) {
      return Optional.empty();
    }
    VoxelBits tested = new VoxelBits(kept.sizeX(), kept.sizeY(), kept.sizeZ());
    VoxelBits grown = new VoxelBits(kept.sizeX(), kept.sizeY(), kept.sizeZ());
    flood(space, kept, sampler, threshold, s, tested, grown);
    boolean separated = false;
    VoxelBits region = grown;
    if (separation > 0) {
      VoxelBits split = separate(grown, s, separation);
      if (split != null) {
        region = split;
        separated = true;
      }
    }
    return Optional.of(
        new Result(region, region.count(), separated, (System.nanoTime() - start) / 1_000_000));
  }

  /** The diff removing every shown voxel outside the region. */
  public static MaskDiff keepOnly(VoxelBits kept, VoxelBits region) {
    return combine(kept, region, false);
  }

  /** The diff removing every shown voxel of the region. */
  public static MaskDiff remove(VoxelBits kept, VoxelBits region) {
    return combine(kept, region, true);
  }

  private static MaskDiff combine(VoxelBits kept, VoxelBits region, boolean inside) {
    MaskDiff.Builder b = new MaskDiff.Builder(kept.sizeZ());
    for (int z = 0; z < kept.sizeZ(); z++) {
      long[] k = kept.sliceWords(z);
      long[] r = region.sliceWords(z);
      long[] out = new long[k.length];
      for (int w = 0; w < k.length; w++) {
        out[w] = inside ? k[w] & r[w] : k[w] & ~r[w];
      }
      MaskDiff.addRunsOfSlice(b, z, out, kept.sliceBits());
    }
    return b.build();
  }

  private static double valueOfGrid(VolumeSpace space, VoxelSampler sampler, int x, int y, int z) {
    Vector3d t = space.texCoordOfGrid(x, y, z, new Vector3d());
    Vector3d v = space.volumeOfTexCoord(t, new Vector3d());
    return sampler.valueAt((int) Math.round(v.x), (int) Math.round(v.y), (int) Math.round(v.z));
  }

  private static boolean qualifies(
      VolumeSpace space,
      VoxelBits kept,
      VoxelSampler sampler,
      double threshold,
      int x,
      int y,
      int z) {
    return kept.get(x, y, z) && valueOfGrid(space, sampler, x, y, z) >= threshold;
  }

  private static Vector3i findSeed(
      VolumeSpace space, VoxelBits kept, VoxelSampler sampler, double threshold, Vector3i seed) {
    if (inside(kept, seed.x, seed.y, seed.z)
        && qualifies(space, kept, sampler, threshold, seed.x, seed.y, seed.z)) {
      return new Vector3i(seed);
    }
    Vector3i best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (int dz = -SEED_SEARCH; dz <= SEED_SEARCH; dz++) {
      for (int dy = -SEED_SEARCH; dy <= SEED_SEARCH; dy++) {
        for (int dx = -SEED_SEARCH; dx <= SEED_SEARCH; dx++) {
          int d = dx * dx + dy * dy + dz * dz;
          int x = seed.x + dx;
          int y = seed.y + dy;
          int z = seed.z + dz;
          if (d < bestDistance
              && inside(kept, x, y, z)
              && qualifies(space, kept, sampler, threshold, x, y, z)) {
            best = new Vector3i(x, y, z);
            bestDistance = d;
          }
        }
      }
    }
    return best;
  }

  private static boolean inside(VoxelBits v, int x, int y, int z) {
    return x >= 0 && y >= 0 && z >= 0 && x < v.sizeX() && y < v.sizeY() && z < v.sizeZ();
  }

  /** Breadth-first flood over the qualifying voxels; each voxel is sampled at most once. */
  private static void flood(
      VolumeSpace space,
      VoxelBits kept,
      VoxelSampler sampler,
      double threshold,
      Vector3i seed,
      VoxelBits tested,
      VoxelBits grown) {
    int sx = kept.sizeX();
    int sy = kept.sizeY();
    IntQueue queue = new IntQueue(1 << 16);
    tested.set(seed.x, seed.y, seed.z);
    grown.set(seed.x, seed.y, seed.z);
    queue.add(seed.x + sx * (seed.y + sy * seed.z));
    while (!queue.isEmpty()) {
      int i = queue.poll();
      int x = i % sx;
      int rest = i / sx;
      int y = rest % sy;
      int z = rest / sy;
      for (int[] n : NEIGHBOURS) {
        int nx = x + n[0];
        int ny = y + n[1];
        int nz = z + n[2];
        if (inside(kept, nx, ny, nz)
            && tested.testAndSet(nx, ny, nz)
            && qualifies(space, kept, sampler, threshold, nx, ny, nz)) {
          grown.set(nx, ny, nz);
          queue.add(nx + sx * (ny + sy * nz));
        }
      }
    }
  }

  /** Bounds of the set voxels, or {@code null} when none. */
  static Box bounds(VoxelBits v) {
    int x0 = Integer.MAX_VALUE;
    int x1 = -1;
    int y0 = Integer.MAX_VALUE;
    int y1 = -1;
    int z0 = Integer.MAX_VALUE;
    int z1 = -1;
    for (int z = 0; z < v.sizeZ(); z++) {
      if (v.countSlice(z) == 0) {
        continue;
      }
      z0 = Math.min(z0, z);
      z1 = Math.max(z1, z);
      for (int y = 0; y < v.sizeY(); y++) {
        for (int x = 0; x < v.sizeX(); x++) {
          if (v.get(x, y, z)) {
            x0 = Math.min(x0, x);
            x1 = Math.max(x1, x);
            y0 = Math.min(y0, y);
            y1 = Math.max(y1, y);
          }
        }
      }
    }
    return x1 < 0 ? null : new Box(x0, x1, y0, y1, z0, z1);
  }

  /**
   * The seeded part of {@code grown}, or {@code null} when erosion leaves nothing near the seed.
   */
  static VoxelBits separate(VoxelBits grown, Vector3i seed, int depth) {
    Box box = bounds(grown);
    if (box == null) {
      return null;
    }
    box = box.grow(1, grown);
    VoxelBits solid = fillHoles(grown, box);
    VoxelBits core = solid;
    for (int i = 0; i < depth; i++) {
      core = erode(core, box);
    }
    Vector3i start = core.get(seed.x, seed.y, seed.z) ? seed : nearestInside(solid, core, seed);
    if (start == null) {
      return null;
    }
    VoxelBits seeded = new VoxelBits(grown.sizeX(), grown.sizeY(), grown.sizeZ());
    floodBits(core, start, seeded);
    return regrow(solid, core, seeded, box);
  }

  /** Sets every voxel of a slice that is not connected to the box edge outside the mask. */
  static VoxelBits fillHoles(VoxelBits mask, Box box) {
    VoxelBits filled = new VoxelBits(mask.sizeX(), mask.sizeY(), mask.sizeZ());
    int bw = box.x1 - box.x0 + 1;
    int bh = box.y1 - box.y0 + 1;
    for (int z = box.z0; z <= box.z1; z++) {
      if (mask.countSlice(z) == 0) {
        continue;
      }
      boolean[] outside = new boolean[bw * bh];
      IntQueue queue = new IntQueue(2 * (bw + bh));
      for (int x = 0; x < bw; x++) {
        reach(mask, box, outside, queue, x, 0, z, bw);
        reach(mask, box, outside, queue, x, bh - 1, z, bw);
      }
      for (int y = 0; y < bh; y++) {
        reach(mask, box, outside, queue, 0, y, z, bw);
        reach(mask, box, outside, queue, bw - 1, y, z, bw);
      }
      while (!queue.isEmpty()) {
        int i = queue.poll();
        int x = i % bw;
        int y = i / bw;
        if (x > 0) {
          reach(mask, box, outside, queue, x - 1, y, z, bw);
        }
        if (x < bw - 1) {
          reach(mask, box, outside, queue, x + 1, y, z, bw);
        }
        if (y > 0) {
          reach(mask, box, outside, queue, x, y - 1, z, bw);
        }
        if (y < bh - 1) {
          reach(mask, box, outside, queue, x, y + 1, z, bw);
        }
      }
      for (int y = 0; y < bh; y++) {
        for (int x = 0; x < bw; x++) {
          if (!outside[y * bw + x]) {
            filled.set(box.x0 + x, box.y0 + y, z);
          }
        }
      }
    }
    return filled;
  }

  private static void reach(
      VoxelBits mask, Box box, boolean[] outside, IntQueue queue, int x, int y, int z, int bw) {
    int i = y * bw + x;
    if (!outside[i] && !mask.get(box.x0 + x, box.y0 + y, z)) {
      outside[i] = true;
      queue.add(i);
    }
  }

  /** One step of 6-connected erosion inside the box; beyond the box counts as empty. */
  static VoxelBits erode(VoxelBits mask, Box box) {
    VoxelBits out = new VoxelBits(mask.sizeX(), mask.sizeY(), mask.sizeZ());
    for (int z = box.z0; z <= box.z1; z++) {
      if (mask.countSlice(z) == 0) {
        continue;
      }
      for (int y = box.y0; y <= box.y1; y++) {
        for (int x = box.x0; x <= box.x1; x++) {
          if (mask.get(x, y, z) && allNeighboursSet(mask, x, y, z)) {
            out.set(x, y, z);
          }
        }
      }
    }
    return out;
  }

  private static boolean allNeighboursSet(VoxelBits mask, int x, int y, int z) {
    for (int[] n : NEIGHBOURS) {
      int nx = x + n[0];
      int ny = y + n[1];
      int nz = z + n[2];
      if (!inside(mask, nx, ny, nz) || !mask.get(nx, ny, nz)) {
        return false;
      }
    }
    return true;
  }

  /** First voxel of {@code target} met by a flood through {@code within} from {@code start}. */
  private static Vector3i nearestInside(VoxelBits within, VoxelBits target, Vector3i start) {
    int sx = within.sizeX();
    int sy = within.sizeY();
    VoxelBits seen = new VoxelBits(sx, sy, within.sizeZ());
    IntQueue queue = new IntQueue(1 << 12);
    if (!inside(within, start.x, start.y, start.z)) {
      return null;
    }
    seen.set(start.x, start.y, start.z);
    queue.add(start.x + sx * (start.y + sy * start.z));
    while (!queue.isEmpty()) {
      int i = queue.poll();
      int x = i % sx;
      int y = (i / sx) % sy;
      int z = i / sx / sy;
      if (target.get(x, y, z)) {
        return new Vector3i(x, y, z);
      }
      for (int[] n : NEIGHBOURS) {
        int nx = x + n[0];
        int ny = y + n[1];
        int nz = z + n[2];
        if (inside(within, nx, ny, nz) && within.get(nx, ny, nz) && seen.testAndSet(nx, ny, nz)) {
          queue.add(nx + sx * (ny + sy * nz));
        }
      }
    }
    return null;
  }

  /** 6-connected flood of the set voxels of {@code mask} from {@code start} into {@code marks}. */
  private static void floodBits(VoxelBits mask, Vector3i start, VoxelBits marks) {
    int sx = mask.sizeX();
    int sy = mask.sizeY();
    IntQueue queue = new IntQueue(1 << 12);
    marks.set(start.x, start.y, start.z);
    queue.add(start.x + sx * (start.y + sy * start.z));
    while (!queue.isEmpty()) {
      int i = queue.poll();
      int x = i % sx;
      int y = (i / sx) % sy;
      int z = i / sx / sy;
      for (int[] n : NEIGHBOURS) {
        int nx = x + n[0];
        int ny = y + n[1];
        int nz = z + n[2];
        if (inside(mask, nx, ny, nz) && mask.get(nx, ny, nz) && marks.testAndSet(nx, ny, nz)) {
          queue.add(nx + sx * (ny + sy * nz));
        }
      }
    }
  }

  /**
   * Grows every core back inside {@code solid} at the same pace, breadth first from all of them; a
   * voxel belongs to the first core that reaches it. Returns the share of {@code seeded}.
   */
  private static VoxelBits regrow(VoxelBits solid, VoxelBits core, VoxelBits seeded, Box box) {
    int sx = solid.sizeX();
    int sy = solid.sizeY();
    VoxelBits reached = new VoxelBits(sx, sy, solid.sizeZ());
    VoxelBits mine = new VoxelBits(sx, sy, solid.sizeZ());
    IntQueue queue = new IntQueue(1 << 16);
    for (int z = box.z0; z <= box.z1; z++) {
      if (core.countSlice(z) == 0) {
        continue;
      }
      for (int y = box.y0; y <= box.y1; y++) {
        for (int x = box.x0; x <= box.x1; x++) {
          if (core.get(x, y, z)) {
            reached.set(x, y, z);
            if (seeded.get(x, y, z)) {
              mine.set(x, y, z);
            }
            queue.add(x + sx * (y + sy * z));
          }
        }
      }
    }
    while (!queue.isEmpty()) {
      int i = queue.poll();
      int x = i % sx;
      int y = (i / sx) % sy;
      int z = i / sx / sy;
      boolean own = mine.get(x, y, z);
      for (int[] n : NEIGHBOURS) {
        int nx = x + n[0];
        int ny = y + n[1];
        int nz = z + n[2];
        if (inside(solid, nx, ny, nz) && solid.get(nx, ny, nz) && reached.testAndSet(nx, ny, nz)) {
          if (own) {
            mine.set(nx, ny, nz);
          }
          queue.add(nx + sx * (ny + sy * nz));
        }
      }
    }
    return mine;
  }

  /** A growable first-in first-out queue of ints. */
  static final class IntQueue {
    private int[] items;
    private int head;
    private int size;

    IntQueue(int capacity) {
      items = new int[Math.max(16, capacity)];
    }

    boolean isEmpty() {
      return size == 0;
    }

    void add(int value) {
      if (size == items.length) {
        int[] grown = new int[Math.toIntExact(Math.min(Integer.MAX_VALUE - 8L, 2L * items.length))];
        int tail = items.length - head;
        System.arraycopy(items, head, grown, 0, tail);
        System.arraycopy(items, 0, grown, tail, head);
        items = grown;
        head = 0;
      }
      items[(head + size) % items.length] = value;
      size++;
    }

    int poll() {
      int value = items[head];
      head = (head + 1) % items.length;
      size--;
      return value;
    }
  }
}
