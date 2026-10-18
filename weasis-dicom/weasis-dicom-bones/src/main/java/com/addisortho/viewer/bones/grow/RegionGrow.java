/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

import com.addisortho.viewer.bones.grow.GrowProgress.Stage;
import java.util.concurrent.CancellationException;
import java.util.stream.IntStream;

/**
 * Semi-automatic bone selection: 3D seeded region growing on the CT between two HU bounds
 * (6-connected), inside an optional box of the grid, then optionally:
 *
 * <ol>
 *   <li>separation from a neighbour joined by thin bridges: the grown region with its slice holes
 *       filled is eroded by k voxels, which cuts the bridges into separate cores; the cores then
 *       grow back inside the filled region all at once, each voxel going to the core that reaches
 *       it first, and the voxels won by the core holding the seed are kept. A bridge is split
 *       half-way, and thin parts of the seeded bone that the erosion removed (an edge, a thin
 *       cortex) come back, which a dilation by k alone would lose;
 *   <li>a morphological closing, which seals small gaps of a thin cortex;
 *   <li>hole filling slice by slice, so the marrow cavity becomes part of the bone;
 *   <li>removal of the pieces smaller than a minimum, except the seeded one.
 * </ol>
 *
 * <p>Every mask is a {@link BitVolume} over the box only, one bit per voxel, and the CT is read
 * only where the region reaches. Separation comes before the closing: a closing would fill a joint
 * space narrower than twice its radius and weld the two bones that separation must split.
 */
public final class RegionGrow {

  /**
   * @param box the smallest block of the grid holding the selected voxels
   * @param mask the selected voxels, in {@code box} coordinates
   * @param seed the voxel the region grew from, in grid coordinates
   * @param seedHu CT value at {@code seed}
   * @param grownVoxels voxels reached by the grow alone, before the other steps
   * @param voxels voxels of {@code mask}
   * @param separated whether the separation step applied (it is skipped when the bone is thinner
   *     than twice the separation)
   * @param piecesDropped pieces removed for being smaller than the minimum
   */
  public record Result(
      VoxelBox box,
      BitVolume mask,
      int[] seed,
      double seedHu,
      long grownVoxels,
      long voxels,
      boolean separated,
      int piecesDropped)
      implements VoxelMask {

    @Override
    public boolean contains(int x, int y, int z) {
      return box.contains(x, y, z) && mask.get(x - box.x0(), y - box.y0(), z - box.z0());
    }

    @Override
    public boolean hasVoxelsInSlice(int z) {
      return z >= box.z0() && z <= box.z1() && mask.countSlice(z - box.z0()) > 0;
    }

    @Override
    public VoxelBox bounds() {
      return box;
    }

    public double volumeCm3(double voxelVolumeMm3) {
      return voxels * voxelVolumeMm3 / 1000.0;
    }
  }

  /** The seed cannot start a region. */
  public static final class SeedException extends Exception {

    /** Why the seed cannot start a region. */
    public enum Reason {
      OUTSIDE_BOX,
      OUT_OF_RANGE
    }

    private final Reason reason;
    private final double hu;

    SeedException(Reason reason, double hu) {
      super(reason + " (" + hu + " HU)");
      this.reason = reason;
      this.hu = hu;
    }

    public Reason reason() {
      return reason;
    }

    /** CT value at the seed, {@code NaN} outside the CT. */
    public double hu() {
      return hu;
    }
  }

  private static final int[][] NEIGHBOURS = {
    {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
  };

  private static final int CHECK_EVERY = 1 << 16;

  private RegionGrow() {}

  /**
   * Grows the region of the voxel {@code (x, y, z)} of a grid of {@code sizeX * sizeY * sizeZ}.
   *
   * @param crop the part of the grid to work in, or {@code null} for the whole grid
   * @throws SeedException when the seed is outside the box or no voxel near it is in range
   * @throws CancellationException when the thread is interrupted
   * @throws IllegalArgumentException when the box holds more than {@link Integer#MAX_VALUE} voxels
   */
  public static Result grow(
      int sizeX,
      int sizeY,
      int sizeZ,
      HuSource ct,
      int x,
      int y,
      int z,
      VoxelBox crop,
      GrowOptions options,
      GrowProgress progress)
      throws SeedException {
    Thread caller = Thread.currentThread();
    VoxelBox grid = VoxelBox.of(sizeX, sizeY, sizeZ);
    VoxelBox box = crop == null ? grid : crop.intersect(grid);
    double seedHu = grid.contains(x, y, z) ? ct.huAt(x, y, z) : Double.NaN;
    if (box == null || !box.contains(x, y, z)) {
      throw new SeedException(SeedException.Reason.OUTSIDE_BOX, seedHu);
    }
    if (box.voxels() > Integer.MAX_VALUE - 8L) {
      throw new IllegalArgumentException("Box too large: " + box.voxels() + " voxels");
    }
    int[] seed = findSeed(ct, box, new int[] {x, y, z}, options);
    if (seed == null) {
      throw new SeedException(SeedException.Reason.OUT_OF_RANGE, seedHu);
    }
    int[] local = {seed[0] - box.x0(), seed[1] - box.y0(), seed[2] - box.z0()};

    progress.update(Stage.GROWING, 0);
    BitVolume mask = new Flood(ct, box, options, progress, caller).run(local);
    long grownVoxels = mask.count();

    // The later steps work on the region's block only, with room for the closing to dilate.
    VoxelBox work = around(mask.bounds(), options.closingRadius() + 1, mask);
    mask = mask.crop(work);
    box = offset(box, work);
    local = new int[] {local[0] - work.x0(), local[1] - work.y0(), local[2] - work.z0()};

    boolean separated = false;
    if (options.separationVoxels() > 0) {
      progress.update(Stage.SEPARATING, 0);
      BitVolume split = separate(mask, local, options.separationVoxels(), progress, caller);
      if (split != null) {
        mask = split;
        separated = true;
      }
    }
    if (options.closingRadius() > 0) {
      progress.update(Stage.CLOSING, 0);
      mask = close(mask, options.closingRadius(), progress, caller);
    }
    if (options.fillHoles()) {
      progress.update(Stage.FILLING, 0);
      mask = fillHoles(mask, caller);
    }
    progress.update(Stage.PIECES, 0);
    int dropped = dropSmallPieces(mask, options.minPieceVoxels(), local, caller);
    progress.update(Stage.PIECES, 1);
    // Keep only the block around the bone, so the result costs memory for the bone alone.
    VoxelBox tight = mask.bounds();
    BitVolume trimmed = mask.crop(tight);
    return new Result(
        offset(box, tight),
        trimmed,
        seed,
        ct.huAt(seed[0], seed[1], seed[2]),
        grownVoxels,
        trimmed.count(),
        separated,
        dropped);
  }

  /** {@code inner}, given in the coordinates of {@code outer}, in the coordinates of the grid. */
  private static VoxelBox offset(VoxelBox outer, VoxelBox inner) {
    return new VoxelBox(
        outer.x0() + inner.x0(),
        outer.x0() + inner.x1(),
        outer.y0() + inner.y0(),
        outer.y0() + inner.y1(),
        outer.z0() + inner.z0(),
        outer.z0() + inner.z1());
  }

  /** {@code b} grown by {@code margin} on every side, within the volume {@code v}. */
  private static VoxelBox around(VoxelBox b, int margin, BitVolume v) {
    return new VoxelBox(
        Math.max(0, b.x0() - margin),
        Math.min(v.sizeX() - 1, b.x1() + margin),
        Math.max(0, b.y0() - margin),
        Math.min(v.sizeY() - 1, b.y1() + margin),
        Math.max(0, b.z0() - margin),
        Math.min(v.sizeZ() - 1, b.z1() + margin));
  }

  /** The seed itself when in range, else the nearest voxel in range within the search radius. */
  static int[] findSeed(HuSource ct, VoxelBox box, int[] seed, GrowOptions options) {
    if (options.inRange(ct.huAt(seed[0], seed[1], seed[2]))) {
      return seed;
    }
    int r = options.seedSearchVoxels();
    int[] best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (int dz = -r; dz <= r; dz++) {
      for (int dy = -r; dy <= r; dy++) {
        for (int dx = -r; dx <= r; dx++) {
          int d = dx * dx + dy * dy + dz * dz;
          int x = seed[0] + dx;
          int y = seed[1] + dy;
          int z = seed[2] + dz;
          if (d < bestDistance
              && d <= r * r
              && box.contains(x, y, z)
              && options.inRange(ct.huAt(x, y, z))) {
            best = new int[] {x, y, z};
            bestDistance = d;
          }
        }
      }
    }
    return best;
  }

  /** Breadth-first flood over the voxels in range; each voxel of the box is read at most once. */
  private static final class Flood {
    private final HuSource ct;
    private final VoxelBox box;
    private final GrowOptions options;
    private final GrowProgress progress;
    private final Thread caller;
    private final int sx;
    private final int sy;
    private final int sz;
    private final BitVolume tested;
    private final BitVolume grown;
    private final IntQueue queue = new IntQueue(1 << 16);

    Flood(HuSource ct, VoxelBox box, GrowOptions options, GrowProgress progress, Thread caller) {
      this.ct = ct;
      this.box = box;
      this.options = options;
      this.progress = progress;
      this.caller = caller;
      this.sx = box.sizeX();
      this.sy = box.sizeY();
      this.sz = box.sizeZ();
      this.tested = new BitVolume(sx, sy, sz);
      this.grown = tested.emptyCopy();
    }

    BitVolume run(int[] seed) {
      tested.set(seed[0], seed[1], seed[2]);
      grown.set(seed[0], seed[1], seed[2]);
      queue.add(seed[0] + sx * (seed[1] + sy * seed[2]));
      double total = box.voxels();
      long seen = 1;
      long polled = 0;
      while (!queue.isEmpty()) {
        int i = queue.poll();
        int x = i % sx;
        int rest = i / sx;
        int y = rest % sy;
        int z = rest / sy;
        if (++polled % CHECK_EVERY == 0) {
          checkCancelled(caller);
          progress.update(Stage.GROWING, seen / total);
        }
        for (int[] n : NEIGHBOURS) {
          int nx = x + n[0];
          int ny = y + n[1];
          int nz = z + n[2];
          if (nx >= 0
              && ny >= 0
              && nz >= 0
              && nx < sx
              && ny < sy
              && nz < sz
              && tested.testAndSet(nx, ny, nz)) {
            seen++;
            if (options.inRange(ct.huAt(box.x0() + nx, box.y0() + ny, box.z0() + nz))) {
              grown.set(nx, ny, nz);
              queue.add(nx + sx * (ny + sy * nz));
            }
          }
        }
      }
      return grown;
    }
  }

  /**
   * The seeded part of {@code grown}, or {@code null} when erosion leaves nothing near the seed.
   */
  static BitVolume separate(
      BitVolume grown, int[] seed, int depth, GrowProgress progress, Thread caller) {
    BitVolume solid = fillHoles(grown, caller);
    progress.update(Stage.SEPARATING, 0.25);
    BitVolume core = solid.morph(depth, false, true, null, caller);
    progress.update(Stage.SEPARATING, 0.5);
    int[] start =
        core.get(seed[0], seed[1], seed[2]) ? seed : nearestInside(solid, core, seed, caller);
    if (start == null) {
      return null;
    }
    BitVolume seeded = solid.emptyCopy();
    flood(core, start, seeded, false, caller);
    progress.update(Stage.SEPARATING, 0.75);
    return regrow(solid, core, seeded, caller);
  }

  /**
   * Grows every core back inside {@code solid} at the same pace, breadth first from all of them; a
   * voxel belongs to the first core that reaches it.
   *
   * @param seeded the voxels of the core whose share is returned
   * @return the share of {@code seeded}
   */
  private static BitVolume regrow(
      BitVolume solid, BitVolume core, BitVolume seeded, Thread caller) {
    int sx = solid.sizeX();
    int sy = solid.sizeY();
    int sz = solid.sizeZ();
    BitVolume reached = core.copy();
    BitVolume mine = seeded.copy();
    IntQueue queue = new IntQueue(1 << 16);
    for (int z = 0; z < sz; z++) {
      checkCancelled(caller);
      if (core.countSlice(z) == 0) {
        continue;
      }
      for (int y = 0; y < sy; y++) {
        for (int x = 0; x < sx; x++) {
          if (core.get(x, y, z) && touchesFree(solid, reached, x, y, z)) {
            queue.add(x + sx * (y + sy * z));
          }
        }
      }
    }
    long polled = 0;
    while (!queue.isEmpty()) {
      int i = queue.poll();
      int x = i % sx;
      int y = (i / sx) % sy;
      int z = i / sx / sy;
      if (++polled % CHECK_EVERY == 0) {
        checkCancelled(caller);
      }
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

  /** Whether a face neighbour of the voxel is in {@code solid} and not yet reached. */
  private static boolean touchesFree(BitVolume solid, BitVolume reached, int x, int y, int z) {
    for (int[] n : NEIGHBOURS) {
      int nx = x + n[0];
      int ny = y + n[1];
      int nz = z + n[2];
      if (inside(solid, nx, ny, nz) && solid.get(nx, ny, nz) && !reached.get(nx, ny, nz)) {
        return true;
      }
    }
    return false;
  }

  /** First voxel of {@code target} met by a flood through {@code within} from {@code start}. */
  private static int[] nearestInside(
      BitVolume within, BitVolume target, int[] start, Thread caller) {
    int sx = within.sizeX();
    int sy = within.sizeY();
    BitVolume seen = within.emptyCopy();
    IntQueue queue = new IntQueue(1 << 12);
    seen.set(start[0], start[1], start[2]);
    queue.add(start[0] + sx * (start[1] + sy * start[2]));
    long polled = 0;
    while (!queue.isEmpty()) {
      int i = queue.poll();
      int x = i % sx;
      int y = (i / sx) % sy;
      int z = i / sx / sy;
      if (++polled % CHECK_EVERY == 0) {
        checkCancelled(caller);
      }
      if (target.get(x, y, z)) {
        return new int[] {x, y, z};
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

  /**
   * 6-connected flood of the set voxels of {@code mask} from {@code start}, marking them in {@code
   * marks}; with {@code clear}, also clears them in {@code mask}.
   *
   * @return the voxels flooded
   */
  private static long flood(
      BitVolume mask, int[] start, BitVolume marks, boolean clear, Thread caller) {
    int sx = mask.sizeX();
    int sy = mask.sizeY();
    IntQueue queue = new IntQueue(1 << 12);
    marks.set(start[0], start[1], start[2]);
    if (clear) {
      mask.clear(start[0], start[1], start[2]);
    }
    queue.add(start[0] + sx * (start[1] + sy * start[2]));
    long count = 0;
    while (!queue.isEmpty()) {
      int i = queue.poll();
      int x = i % sx;
      int y = (i / sx) % sy;
      int z = i / sx / sy;
      if (++count % CHECK_EVERY == 0) {
        checkCancelled(caller);
      }
      for (int[] n : NEIGHBOURS) {
        int nx = x + n[0];
        int ny = y + n[1];
        int nz = z + n[2];
        if (inside(mask, nx, ny, nz) && mask.get(nx, ny, nz) && (clear || !marks.get(nx, ny, nz))) {
          marks.set(nx, ny, nz);
          if (clear) {
            mask.clear(nx, ny, nz);
          }
          queue.add(nx + sx * (ny + sy * nz));
        }
      }
    }
    return count;
  }

  private static boolean inside(BitVolume v, int x, int y, int z) {
    return x >= 0 && y >= 0 && z >= 0 && x < v.sizeX() && y < v.sizeY() && z < v.sizeZ();
  }

  /** Dilation then erosion; beyond the box counts as bone for the erosion, so edges stay. */
  static BitVolume close(BitVolume mask, int radius, GrowProgress progress, Thread caller) {
    BitVolume dilated = mask.morph(radius, true, false, null, caller);
    progress.update(Stage.CLOSING, 0.5);
    return dilated.morph(radius, false, true, null, caller);
  }

  /** Sets every voxel of a slice that is not connected to the slice edge outside the mask. */
  static BitVolume fillHoles(BitVolume mask, Thread caller) {
    BitVolume filled = mask.copy();
    int sx = mask.sizeX();
    int sy = mask.sizeY();
    IntStream.range(0, mask.sizeZ())
        .parallel()
        .forEach(
            z -> {
              checkCancelled(caller);
              if (mask.countSlice(z) > 0) {
                fillSlice(mask, filled, z, sx, sy);
              }
            });
    return filled;
  }

  private static void fillSlice(BitVolume mask, BitVolume filled, int z, int sx, int sy) {
    boolean[] outside = new boolean[sx * sy];
    IntQueue queue = new IntQueue(2 * (sx + sy));
    for (int x = 0; x < sx; x++) {
      reach(mask, outside, queue, x, 0, z, sx);
      reach(mask, outside, queue, x, sy - 1, z, sx);
    }
    for (int y = 0; y < sy; y++) {
      reach(mask, outside, queue, 0, y, z, sx);
      reach(mask, outside, queue, sx - 1, y, z, sx);
    }
    while (!queue.isEmpty()) {
      int i = queue.poll();
      int x = i % sx;
      int y = i / sx;
      if (x > 0) {
        reach(mask, outside, queue, x - 1, y, z, sx);
      }
      if (x < sx - 1) {
        reach(mask, outside, queue, x + 1, y, z, sx);
      }
      if (y > 0) {
        reach(mask, outside, queue, x, y - 1, z, sx);
      }
      if (y < sy - 1) {
        reach(mask, outside, queue, x, y + 1, z, sx);
      }
    }
    for (int y = 0; y < sy; y++) {
      for (int x = 0; x < sx; x++) {
        if (!outside[y * sx + x]) {
          filled.set(x, y, z);
        }
      }
    }
  }

  private static void reach(
      BitVolume mask, boolean[] outside, IntQueue queue, int x, int y, int z, int sx) {
    int i = y * sx + x;
    if (!outside[i] && !mask.get(x, y, z)) {
      outside[i] = true;
      queue.add(i);
    }
  }

  /**
   * Clears the pieces of fewer than {@code minVoxels} voxels, except the one holding the seed.
   * Nothing is cleared when the seed is not selected, as then no piece is known to be the bone.
   *
   * @return the pieces cleared
   */
  static int dropSmallPieces(BitVolume mask, long minVoxels, int[] seed, Thread caller) {
    if (minVoxels <= 1 || !mask.get(seed[0], seed[1], seed[2])) {
      return 0;
    }
    BitVolume seen = mask.emptyCopy();
    flood(mask, seed, seen, false, caller);
    int dropped = 0;
    for (int z = 0; z < mask.sizeZ(); z++) {
      checkCancelled(caller);
      if (mask.countSlice(z) == 0) {
        continue;
      }
      for (int y = 0; y < mask.sizeY(); y++) {
        for (int x = 0; x < mask.sizeX(); x++) {
          if (mask.get(x, y, z) && !seen.get(x, y, z)) {
            int[] start = {x, y, z};
            long size = flood(mask, start, seen, false, caller);
            if (size < minVoxels) {
              flood(mask, start, seen, true, caller);
              dropped++;
            }
          }
        }
      }
    }
    return dropped;
  }

  static void checkCancelled(Thread caller) {
    if (caller.isInterrupted()) {
      throw new CancellationException();
    }
  }
}
