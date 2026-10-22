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
 * The structure under a picked voxel: a 6-connected region grown over the voxels the renderer
 * shows, judged by their opacity through the preset and the window, so that one click selects what
 * one sees and an invisible band of values never joins two structures. The same algorithm as the
 * bone select of the Addis Ortho bones bundle, on the kept mask's grid.
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
  public record Result(
      VoxelBits region, long voxels, boolean separated, long millis, long peakBytes) {}

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

  /** A selection whose working memory would not fit. */
  public static final class BudgetException extends RuntimeException {
    private final long neededBytes;
    private final long availableBytes;

    BudgetException(long neededBytes, long availableBytes) {
      super(
          "The selection needs "
              + (neededBytes >> 20)
              + " MB of working memory, "
              + (availableBytes >> 20)
              + " MB are free");
      this.neededBytes = neededBytes;
      this.availableBytes = availableBytes;
    }

    public long neededBytes() {
      return neededBytes;
    }

    public long availableBytes() {
      return availableBytes;
    }
  }

  /** Most voxels a flood front may hold at once (256 MB); a selection beyond it is refused. */
  public static final int MAX_QUEUE_ENTRIES = 1 << 26;

  /** Heap left untouched by the working memory of a selection. */
  public static final long HEADROOM_BYTES = 64L << 20;

  private static final int[][] NEIGHBOURS = {
    {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
  };

  /** How far from the clicked voxel a shown voxel is looked for. */
  static final int SEED_SEARCH = 3;

  /**
   * Whether a texture voxel is shown by the renderer: the opacity of its value through the preset
   * and the window, in the LUT's shape, and whatever else the renderer applies to it (the
   * segmentation masks). A lower bound on the value would not do: the Fat preset, for one, is
   * opaque in a band and transparent above it.
   */
  @FunctionalInterface
  public interface Visibility {
    boolean visible(int x, int y, int z, double value);

    /** Most memory the predicate's own fields may take during the selection. */
    default long workingBytes() {
      return 0;
    }

    /** Visibility from the renderer's opacity alone. */
    static Visibility ofOpacity(TransferFunction transfer, double minOpacity) {
      return (x, y, z, value) -> transfer.opacity(value) >= minOpacity;
    }
  }

  private VoxelFlood() {}

  /**
   * Grows the structure of {@code seed}.
   *
   * @param kept the voxels shown; the region stays inside them
   * @param sampler the texture's values, see {@link TextureField}
   * @param visible which texture voxels the renderer shows, given their value
   * @param seed the picked grid voxel; the nearest shown voxel within a few voxels is taken when it
   *     is not shown itself
   * @param separation erosion depth of the separation, 0 for none
   * @return empty when no voxel near the seed is shown
   * @throws BudgetException when the working memory would not fit in the heap, before anything
   *     large is allocated, or when a flood front outgrows {@link #MAX_QUEUE_ENTRIES}
   */
  public static Optional<Result> grow(
      VolumeSpace space,
      VoxelBits kept,
      VoxelSampler sampler,
      Visibility visible,
      Vector3i seed,
      int separation) {
    return grow(space, kept, sampler, visible, seed, separation, WorkingMemory.ofFreeHeap());
  }

  /**
   * As {@link #grow(VolumeSpace, VoxelBits, VoxelSampler, Visibility, Vector3i, int)} within one
   * budget covering the bit volumes, every queue with its growth and the sampler's slice cache.
   */
  public static Optional<Result> grow(
      VolumeSpace space,
      VoxelBits kept,
      VoxelSampler sampler,
      Visibility visible,
      Vector3i seed,
      int separation,
      WorkingMemory budget) {
    return grow(space, kept, sampler, visible, seed, separation, SEED_SEARCH, budget);
  }

  /**
   * As {@link #grow(VolumeSpace, VoxelBits, VoxelSampler, Visibility, Vector3i, int,
   * WorkingMemory)}, looking for a shown voxel up to {@code seedSearch} grid voxels from the seed:
   * further for a click on a slice, which may land in the marrow, than for a picked surface.
   */
  public static Optional<Result> grow(
      VolumeSpace space,
      VoxelBits kept,
      VoxelSampler sampler,
      Visibility visible,
      Vector3i seed,
      int separation,
      int seedSearch,
      WorkingMemory budget) {
    long start = System.nanoTime();
    budget.reserve(reservationBytes(kept, separation, sampler, visible));
    Vector3i s = findSeed(space, kept, sampler, visible, seed, seedSearch);
    if (s == null) {
      return Optional.empty();
    }
    VoxelBits tested = new VoxelBits(kept.sizeX(), kept.sizeY(), kept.sizeZ());
    VoxelBits grown = new VoxelBits(kept.sizeX(), kept.sizeY(), kept.sizeZ());
    flood(space, kept, sampler, visible, s, tested, grown, budget);
    boolean separated = false;
    VoxelBits region = grown;
    if (separation > 0) {
      VoxelBits split = separate(grown, s, separation, budget);
      if (split != null) {
        region = split;
        separated = true;
      }
    }
    return Optional.of(
        new Result(
            region,
            region.count(),
            separated,
            (System.nanoTime() - start) / 1_000_000,
            budget.peak()));
  }

  /**
   * Working memory of a selection on the grid of {@code kept}: the bit volumes of the flood (2) and
   * of the separation (6: holes filled, two erosion buffers, seeded core, reached, mine), plus the
   * first queue.
   */
  public static long workingBytes(VoxelBits kept, int separation) {
    return kept.bytes() * (separation > 0 ? 8 : 2);
  }

  /**
   * What a selection reserves up front: the bit volumes, what the sampler may still allocate beyond
   * its resident caches, and the caches and scratch of the visibility's own fields. Queues are
   * reserved as they grow.
   */
  public static long reservationBytes(
      VoxelBits kept, int separation, VoxelSampler sampler, Visibility visible) {
    long samplerBytes = Math.max(0, sampler.workingBytes() - sampler.residentBytes());
    return workingBytes(kept, separation) + samplerBytes + visible.workingBytes();
  }

  /** Heap that a selection may still take: the maximum minus what is in use. */
  public static long availableBytes() {
    Runtime rt = Runtime.getRuntime();
    return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
  }

  /**
   * @throws BudgetException when {@code needed} plus the {@link #HEADROOM_BYTES} exceed {@code
   *     available}
   */
  public static void checkBudget(long needed, long available) {
    if (needed + HEADROOM_BYTES > available) {
      throw new BudgetException(needed, available);
    }
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

  /** Whether the grid voxel is shown: kept, and its texture voxel visible to the renderer. */
  private static boolean qualifies(
      VolumeSpace space,
      VoxelBits kept,
      VoxelSampler sampler,
      Visibility visible,
      int x,
      int y,
      int z) {
    if (!kept.get(x, y, z)) {
      return false;
    }
    Vector3d t = space.texCoordOfGrid(x, y, z, new Vector3d());
    Vector3i v = space.textureVoxelOf(t, new Vector3i());
    double value = sampler.valueAt(v.x, v.y, v.z);
    return !Double.isNaN(value) && visible.visible(v.x, v.y, v.z, value);
  }

  private static Vector3i findSeed(
      VolumeSpace space,
      VoxelBits kept,
      VoxelSampler sampler,
      Visibility visible,
      Vector3i seed,
      int radius) {
    if (inside(kept, seed.x, seed.y, seed.z)
        && qualifies(space, kept, sampler, visible, seed.x, seed.y, seed.z)) {
      return new Vector3i(seed);
    }
    Vector3i best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (int dz = -radius; dz <= radius; dz++) {
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dx = -radius; dx <= radius; dx++) {
          int d = dx * dx + dy * dy + dz * dz;
          int x = seed.x + dx;
          int y = seed.y + dy;
          int z = seed.z + dz;
          if (d < bestDistance
              && inside(kept, x, y, z)
              && qualifies(space, kept, sampler, visible, x, y, z)) {
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

  /**
   * Flood over the qualifying voxels; each voxel is sampled at most once. The front is kept slice
   * by slice and swept up and down the volume rather than taken in distance order: the field is
   * made on demand slice by slice (see {@link TextureField}), and a distance front crossing a wide
   * structure would touch hundreds of slices at once, while a sweep touches a slice and its two
   * neighbours. The region grown is the same connected component whatever the order.
   */
  private static void flood(
      VolumeSpace space,
      VoxelBits kept,
      VoxelSampler sampler,
      Visibility visible,
      Vector3i seed,
      VoxelBits tested,
      VoxelBits grown,
      WorkingMemory budget) {
    int sx = kept.sizeX();
    int sz = kept.sizeZ();
    IntQueue[] pending = new IntQueue[sz];
    tested.set(seed.x, seed.y, seed.z);
    grown.set(seed.x, seed.y, seed.z);
    pending[seed.z] = new IntQueue(1 << 12, budget);
    pending[seed.z].add(seed.x + sx * seed.y);
    boolean upwards = true;
    while (anyPending(pending)) {
      for (int step = 0; step < sz; step++) {
        int z = upwards ? step : sz - 1 - step;
        // Each queue of this slice is drained and freed; the voxels it adds within the slice go
        // to a new queue, drained in turn, so a slice is finished before the sweep moves on.
        IntQueue queue;
        while ((queue = pending[z]) != null) {
          pending[z] = null;
          try {
            drain(queue, z, space, kept, sampler, visible, tested, grown, pending, budget);
          } finally {
            queue.free();
          }
        }
      }
      upwards = !upwards;
    }
  }

  private static boolean anyPending(IntQueue[] pending) {
    for (IntQueue q : pending) {
      if (q != null) {
        return true;
      }
    }
    return false;
  }

  /** Drains one slice queue, adding the shown neighbours of its voxels to their slices' queues. */
  private static void drain(
      IntQueue queue,
      int z,
      VolumeSpace space,
      VoxelBits kept,
      VoxelSampler sampler,
      Visibility visible,
      VoxelBits tested,
      VoxelBits grown,
      IntQueue[] pending,
      WorkingMemory budget) {
    int sx = kept.sizeX();
    while (!queue.isEmpty()) {
      int i = queue.poll();
      int x = i % sx;
      int y = i / sx;
      for (int[] n : NEIGHBOURS) {
        int nx = x + n[0];
        int ny = y + n[1];
        int nz = z + n[2];
        if (inside(kept, nx, ny, nz)
            && tested.testAndSet(nx, ny, nz)
            && qualifies(space, kept, sampler, visible, nx, ny, nz)) {
          grown.set(nx, ny, nz);
          if (pending[nz] == null) {
            pending[nz] = new IntQueue(1 << 12, budget);
          }
          pending[nz].add(nx + sx * ny);
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
  static VoxelBits separate(VoxelBits grown, Vector3i seed, int depth, WorkingMemory budget) {
    Box box = bounds(grown);
    if (box == null) {
      return null;
    }
    box = box.grow(1, grown);
    VoxelBits solid = fillHoles(grown, box, budget);
    // Two buffers take turns over the erosion steps, whatever the depth.
    VoxelBits core = solid;
    VoxelBits spare = null;
    for (int i = 0; i < depth; i++) {
      VoxelBits target =
          spare != null ? spare : new VoxelBits(grown.sizeX(), grown.sizeY(), grown.sizeZ());
      erode(core, target, box);
      spare = core == solid ? null : core;
      core = target;
    }
    Vector3i start =
        core.get(seed.x, seed.y, seed.z) ? seed : nearestInside(solid, core, seed, budget);
    if (start == null) {
      return null;
    }
    VoxelBits seeded = new VoxelBits(grown.sizeX(), grown.sizeY(), grown.sizeZ());
    floodBits(core, start, seeded, budget);
    return regrow(solid, core, seeded, box, budget);
  }

  /** Sets every voxel of a slice that is not connected to the box edge outside the mask. */
  static VoxelBits fillHoles(VoxelBits mask, Box box, WorkingMemory budget) {
    VoxelBits filled = new VoxelBits(mask.sizeX(), mask.sizeY(), mask.sizeZ());
    int bw = box.x1 - box.x0 + 1;
    int bh = box.y1 - box.y0 + 1;
    for (int z = box.z0; z <= box.z1; z++) {
      if (mask.countSlice(z) == 0) {
        continue;
      }
      boolean[] outside = new boolean[bw * bh];
      IntQueue queue = new IntQueue(2 * (bw + bh), budget);
      try {
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
      } finally {
        queue.free();
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

  /** One step of 6-connected erosion inside the box into {@code out}; beyond the box is empty. */
  static VoxelBits erode(VoxelBits mask, VoxelBits out, Box box) {
    out.fill(false);
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
  private static Vector3i nearestInside(
      VoxelBits within, VoxelBits target, Vector3i start, WorkingMemory budget) {
    int sx = within.sizeX();
    int sy = within.sizeY();
    VoxelBits seen = new VoxelBits(sx, sy, within.sizeZ());
    if (!inside(within, start.x, start.y, start.z)) {
      return null;
    }
    IntQueue queue = new IntQueue(1 << 12, budget);
    try {
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
    } finally {
      queue.free();
    }
  }

  /** 6-connected flood of the set voxels of {@code mask} from {@code start} into {@code marks}. */
  private static void floodBits(
      VoxelBits mask, Vector3i start, VoxelBits marks, WorkingMemory budget) {
    int sx = mask.sizeX();
    int sy = mask.sizeY();
    IntQueue queue = new IntQueue(1 << 12, budget);
    try {
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
    } finally {
      queue.free();
    }
  }

  /**
   * Grows every core back inside {@code solid} at the same pace, breadth first from all of them; a
   * voxel belongs to the first core that reaches it. Returns the share of {@code seeded}.
   *
   * <p>Only the core voxels that touch a free voxel start the front, so the queue holds the cores'
   * surface, not their bulk: the inside of a core has nothing to grow into.
   */
  private static VoxelBits regrow(
      VoxelBits solid, VoxelBits core, VoxelBits seeded, Box box, WorkingMemory budget) {
    int sx = solid.sizeX();
    int sy = solid.sizeY();
    VoxelBits reached = new VoxelBits(sx, sy, solid.sizeZ());
    VoxelBits mine = new VoxelBits(sx, sy, solid.sizeZ());
    IntQueue queue = new IntQueue(1 << 16, budget);
    try {
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
              if (touchesFree(solid, core, x, y, z)) {
                queue.add(x + sx * (y + sy * z));
              }
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
          if (inside(solid, nx, ny, nz)
              && solid.get(nx, ny, nz)
              && reached.testAndSet(nx, ny, nz)) {
            if (own) {
              mine.set(nx, ny, nz);
            }
            queue.add(nx + sx * (ny + sy * nz));
          }
        }
      }
      return mine;
    } finally {
      queue.free();
    }
  }

  /** Whether a face neighbour of the voxel is in {@code solid} but not in {@code core}. */
  private static boolean touchesFree(VoxelBits solid, VoxelBits core, int x, int y, int z) {
    for (int[] n : NEIGHBOURS) {
      int nx = x + n[0];
      int ny = y + n[1];
      int nz = z + n[2];
      if (inside(solid, nx, ny, nz) && solid.get(nx, ny, nz) && !core.get(nx, ny, nz)) {
        return true;
      }
    }
    return false;
  }

  /** How many core voxels start the regrow front: those on the cores' surface. */
  static long countRegrowSeeds(VoxelBits solid, VoxelBits core, Box box) {
    long n = 0;
    for (int z = box.z0; z <= box.z1; z++) {
      for (int y = box.y0; y <= box.y1; y++) {
        for (int x = box.x0; x <= box.x1; x++) {
          if (core.get(x, y, z) && touchesFree(solid, core, x, y, z)) {
            n++;
          }
        }
      }
    }
    return n;
  }

  /**
   * A growable first-in first-out queue of ints whose storage, growth peaks included, is taken from
   * the budget and given back by {@link #free()}; refused beyond {@link #MAX_QUEUE_ENTRIES}.
   */
  static final class IntQueue {
    private final WorkingMemory budget;
    private int[] items;
    private int head;
    private int size;

    IntQueue(int capacity, WorkingMemory budget) {
      this.budget = budget;
      int n = Math.max(16, capacity);
      budget.reserve(4L * n);
      items = new int[n];
    }

    boolean isEmpty() {
      return size == 0;
    }

    void add(int value) {
      if (size == items.length) {
        if (items.length >= MAX_QUEUE_ENTRIES) {
          throw new BudgetException(8L * items.length, budget.limit());
        }
        int n = Math.toIntExact(Math.min(MAX_QUEUE_ENTRIES, 2L * items.length));
        // The old and the new array exist together while the entries are copied over.
        budget.reserve(4L * n);
        int[] grown = new int[n];
        int tail = items.length - head;
        System.arraycopy(items, head, grown, 0, tail);
        System.arraycopy(items, 0, grown, tail, head);
        budget.release(4L * items.length);
        items = grown;
        head = 0;
      }
      items[(head + size) % items.length] = value;
      size++;
    }

    /** Gives the storage back to the budget; the queue must be empty. */
    void free() {
      if (items != null) {
        budget.release(4L * items.length);
        items = null;
        size = 0;
        head = 0;
      }
    }

    int poll() {
      int value = items[head];
      head = (head + 1) % items.length;
      size--;
      return value;
    }
  }
}
