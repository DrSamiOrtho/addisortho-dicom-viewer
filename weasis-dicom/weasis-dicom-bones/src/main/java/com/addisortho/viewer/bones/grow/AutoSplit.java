/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Splits all the bone of a CT into separate bones at once, without a click, so the Bones panel
 * opens with every bone already in its list.
 *
 * <ol>
 *   <li>The bone is read twice: at the bone threshold (200 HU), closed by one voxel and with the
 *       marrow filled, which is the bone each piece will hold; and at the dense threshold (300 HU),
 *       marrow filled, where the joint spaces are open because the subchondral plates are dense and
 *       the cartilage is not. A small joint (a finger, the carpus, the tarsus) is narrower than the
 *       scanner resolves: its line reads above the dense threshold, only darker than the plate on
 *       each side. Such voxels, a valley of {@link Options#jointHu()} or more between brighter bone
 *       on both sides within {@link Options#jointReachMm()}, are taken out of the dense read before
 *       the marrow is filled, so the fill puts back the ones a cortex encloses (a narrow marrow
 *       canal) and leaves the joint line open.
 *   <li>The distance of each dense voxel to the nearest non-bone voxel, in mm (a Euclidean distance
 *       transform, exact on the anisotropic grid).
 *   <li>A watershed on that distance: the voxels are taken deepest first and pieces meet where the
 *       bone is narrow. Two pieces stay apart only when the neck between them is thin both in mm
 *       and against the thickness of each piece, and each piece is large enough there: a joint
 *       touching at a point splits, the femoral neck or a narrow metaphysis does not.
 *   <li>Every voxel of the bone threshold goes to the piece that reaches it first.
 *   <li>Pieces under {@link Options#fragmentMm3()} are fragments: each joins the large piece it
 *       touches most, or the nearest within {@link Options#attachMm()} bone to bone, so a
 *       comminuted fracture gives one bone with its fragments and not dozens of entries. Specks are
 *       dropped.
 * </ol>
 *
 * <p>A large CT is worked on a coarser grid, to bound memory; each voxel then takes the piece of
 * its coarse voxel. Each axis is coarsened by its own whole factor, the finest spacing first, so a
 * scan with thick slices keeps the slices it has. Run off the event dispatch thread; interrupting
 * the thread cancels it.
 */
public final class AutoSplit {

  /** Whether a voxel of the CT grid is still shown in the 3D view, not cut away. */
  @FunctionalInterface
  public interface Shown {
    boolean at(int x, int y, int z);

    Shown ALL = (_, _, _) -> true;
  }

  /**
   * @param boneHu threshold of the bone each piece holds
   * @param denseHu threshold at which the pieces are told apart
   * @param mmX voxel size along the columns
   * @param mmY voxel size along the rows
   * @param mmZ distance between the slices
   * @param minBoneMm3 smallest part above a neck that can be a bone of its own
   * @param minDepthMm how much thicker than the neck each part must be to be a bone of its own
   * @param neckRatio thickest neck, as a part of the thickness of the thinner of the two parts
   * @param fragmentMm3 below this, a piece is a fragment of the bone it touches or lies near
   * @param attachMm farthest a loose fragment may lie from its bone
   * @param speckMm3 a loose piece below this, near no bone, is dropped
   * @param maxWorkVoxels largest grid worked on; a larger CT is worked on a coarser grid
   * @param jointHu how much darker than the bone on both sides a dense voxel must be to count as a
   *     joint line; 0 leaves the dense read as it is
   * @param jointReachMm how far on each side the brighter bone is looked for
   * @param jointMaxHu a voxel this bright or brighter is never a joint line
   * @param maxJointVoxels largest block holding the bone for which the joint lines are looked for:
   *     two bytes each while they are
   */
  public record Options(
      double boneHu,
      double denseHu,
      double mmX,
      double mmY,
      double mmZ,
      double minBoneMm3,
      double minDepthMm,
      double neckRatio,
      double fragmentMm3,
      double attachMm,
      double speckMm3,
      long maxWorkVoxels,
      double jointHu,
      double jointReachMm,
      double jointMaxHu,
      long maxJointVoxels) {

    public static Options of(double mmX, double mmY, double mmZ) {
      return new Options(
          GrowOptions.DEFAULT_LOWER_HU,
          300,
          mmX,
          mmY,
          mmZ,
          200,
          1.0,
          0.45,
          1000,
          10,
          30,
          16_000_000L,
          250,
          1.6,
          900,
          64_000_000L);
    }

    public Options withMaxWorkVoxels(long voxels) {
      return new Options(
          boneHu,
          denseHu,
          mmX,
          mmY,
          mmZ,
          minBoneMm3,
          minDepthMm,
          neckRatio,
          fragmentMm3,
          attachMm,
          speckMm3,
          voxels,
          jointHu,
          jointReachMm,
          jointMaxHu,
          maxJointVoxels);
    }

    /** The same with another valley depth for the joint lines; 0 looks for none. */
    public Options withJointHu(double hu) {
      return new Options(
          boneHu,
          denseHu,
          mmX,
          mmY,
          mmZ,
          minBoneMm3,
          minDepthMm,
          neckRatio,
          fragmentMm3,
          attachMm,
          speckMm3,
          maxWorkVoxels,
          hu,
          jointReachMm,
          jointMaxHu,
          maxJointVoxels);
    }

    /** The same with another size under which a piece is a fragment of its neighbour. */
    public Options withFragmentMm3(double mm3) {
      return new Options(
          boneHu,
          denseHu,
          mmX,
          mmY,
          mmZ,
          minBoneMm3,
          minDepthMm,
          neckRatio,
          mm3,
          attachMm,
          speckMm3,
          maxWorkVoxels,
          jointHu,
          jointReachMm,
          jointMaxHu,
          maxJointVoxels);
    }
  }

  /**
   * A bone found.
   *
   * @param mask its voxels, on the CT grid
   * @param anchor its deepest voxel, on the CT grid
   * @param fragments fragments joined to it
   */
  public record Piece(BoxMask mask, int[] anchor, int fragments) {}

  /**
   * @param pieces the bones, largest first
   * @param fragments fragments joined to a bone
   * @param specks loose specks dropped
   * @param factor coarsening of the grid worked on, 1 for the CT's own; the largest of the three
   *     axes' factors
   */
  public record Result(List<Piece> pieces, int fragments, int specks, int factor, long millis) {}

  private static final float INF = 1e12f;

  private AutoSplit() {}

  /**
   * @param box the block of the CT grid to search, such as the 3D view's crop box
   * @param hu gives a reader of the CT for each thread
   */
  public static Result split(VoxelBox box, Supplier<HuSource> hu, Shown shown, Options o) {
    long start = System.nanoTime();
    Thread caller = Thread.currentThread();
    int sx = box.sizeX();
    int sy = box.sizeY();
    int sz = box.sizeZ();
    BitVolume atBone = new BitVolume(sx, sy, sz);
    BitVolume atDense = new BitVolume(sx, sy, sz);
    // One slice per task: a slice's bits share no word with another slice's.
    IntStream.range(0, sz)
        .parallel()
        .forEach(
            k -> {
              RegionGrow.checkCancelled(caller);
              HuSource ct = hu.get();
              int gz = box.z0() + k;
              for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                  int gx = box.x0() + x;
                  int gy = box.y0() + y;
                  if (!shown.at(gx, gy, gz)) {
                    continue;
                  }
                  double v = ct.huAt(gx, gy, gz);
                  if (v >= o.boneHu()) {
                    atBone.set(x, y, k);
                    if (v >= o.denseHu()) {
                      atDense.set(x, y, k);
                    }
                  }
                }
              }
            });
    BitVolume solid =
        RegionGrow.fillHoles(RegionGrow.close(atBone, 1, GrowProgress.NONE, caller), caller);
    VoxelBox tight = solid.bounds();
    if (tight == null) {
      return new Result(List.of(), 0, 0, 1, millis(start));
    }
    BitVolume dense = RegionGrow.fillHoles(atDense, caller);
    dense.and(solid);
    solid = solid.crop(tight);
    dense = dense.crop(tight);
    VoxelBox block =
        new VoxelBox(
            box.x0() + tight.x0(),
            box.x0() + tight.x1(),
            box.y0() + tight.y0(),
            box.y0() + tight.y1(),
            box.z0() + tight.z0(),
            box.z0() + tight.z1());
    BitVolume joints = null;
    if (o.jointHu() > 0 && tight.voxels() <= o.maxJointVoxels()) {
      joints = openJointLines(dense, solid, block, hu, shown, o, caller);
    }
    return new Work(solid, dense, joints, block, o, caller).run(start);
  }

  /** The 13 directions of a voxel's 26 neighbours, one of each opposite pair. */
  private static final int[][] HALF_NEIGHBOURS = {
    {1, 0, 0},
    {0, 1, 0},
    {0, 0, 1},
    {1, 1, 0},
    {1, -1, 0},
    {1, 0, 1},
    {1, 0, -1},
    {0, 1, 1},
    {0, 1, -1},
    {1, 1, 1},
    {1, 1, -1},
    {1, -1, 1},
    {1, -1, -1}
  };

  /** A value no CT holds, for the voxels outside the block or not shown. */
  private static final short NO_VALUE = Short.MIN_VALUE;

  /**
   * Clears from the dense bone the voxels of the joint lines. A voxel may be one when it is darker
   * by {@link Options#jointHu()} or more than a voxel within {@link Options#jointReachMm()} on each
   * of two opposite sides, and along no direction brighter than both sides: that is a weak spot in
   * a cortex, a valley along the cortex and a ridge across it. Such voxels also fill the narrow
   * marrow canal of a small bone and the gaps between its trabeculae, shut inside the bone, while a
   * joint line comes out at its rim. So the voxels are taken together over the whole bone, each
   * group of touching ones (by a face, an edge or a corner), and only a group that comes out of the
   * bone is cleared from the dense bone.
   *
   * @param dense the dense bone with its marrow filled, on the block holding the bone
   * @param solid the whole bone, on the same block
   * @param block that block, on the CT grid
   * @return the voxels cleared, on the same block
   */
  private static BitVolume openJointLines(
      BitVolume dense,
      BitVolume solid,
      VoxelBox block,
      Supplier<HuSource> hu,
      Shown shown,
      Options o,
      Thread caller) {
    int tx = block.sizeX();
    int ty = block.sizeY();
    int tz = block.sizeZ();
    short[] value = new short[tx * ty * tz];
    IntStream.range(0, tz)
        .parallel()
        .forEach(
            k -> {
              RegionGrow.checkCancelled(caller);
              HuSource ct = hu.get();
              int gz = block.z0() + k;
              int i = k * tx * ty;
              for (int y = 0; y < ty; y++) {
                int gy = block.y0() + y;
                for (int x = 0; x < tx; x++, i++) {
                  int gx = block.x0() + x;
                  double v = shown.at(gx, gy, gz) ? ct.huAt(gx, gy, gz) : Double.NaN;
                  value[i] =
                      Double.isNaN(v)
                          ? NO_VALUE
                          : (short) Math.clamp(Math.round(v), NO_VALUE + 1, Short.MAX_VALUE);
                }
              }
            });
    int[] steps = new int[HALF_NEIGHBOURS.length];
    for (int d = 0; d < steps.length; d++) {
      int[] n = HALF_NEIGHBOURS[d];
      double mm = Math.sqrt(sq(n[0] * o.mmX()) + sq(n[1] * o.mmY()) + sq(n[2] * o.mmZ()));
      steps[d] = Math.max(1, (int) Math.round(o.jointReachMm() / mm));
    }
    // For each direction, the directions square to it, as bits.
    int[] square = new int[HALF_NEIGHBOURS.length];
    for (int d = 0; d < square.length; d++) {
      for (int e = 0; e < square.length; e++) {
        if (Math.abs(cosine(HALF_NEIGHBOURS[d], HALF_NEIGHBOURS[e], o)) <= JOINT_SQUARE) {
          square[d] |= 1 << e;
        }
      }
    }
    int depth = (int) Math.ceil(o.jointHu());
    int ridge = (int) Math.ceil(o.jointHu() * JOINT_RIDGE);
    int cap = (int) Math.ceil(o.jointMaxHu());
    // The valley voxels of each direction on their own, so a sheet can be told from a speck.
    BitVolume[] across = new BitVolume[HALF_NEIGHBOURS.length];
    for (int d = 0; d < across.length; d++) {
      across[d] = new BitVolume(tx, ty, tz);
    }
    // One slice per task: a slice's bits share no word with another slice's.
    IntStream.range(0, tz)
        .parallel()
        .forEach(
            z -> {
              RegionGrow.checkCancelled(caller);
              for (int y = 0; y < ty; y++) {
                for (int x = 0; x < tx; x++) {
                  if (!solid.get(x, y, z)) {
                    continue;
                  }
                  int v = value[(z * ty + y) * tx + x];
                  if (v >= cap || v == NO_VALUE) {
                    continue;
                  }
                  int level = Math.max(v + depth, JOINT_PLATE_HU);
                  int along = 0;
                  for (int d = 0; d < steps.length; d++) {
                    int[] n = HALF_NEIGHBOURS[d];
                    if (reaches(value, tx, ty, tz, x, y, z, n[0], n[1], n[2], steps[d], level, true)
                        && reaches(
                            value, tx, ty, tz, x, y, z, -n[0], -n[1], -n[2], steps[d], level,
                            true)) {
                      along |= 1 << d;
                    }
                  }
                  if (along == 0 || isRidge(value, tx, ty, tz, x, y, z, v - ridge, steps)) {
                    continue;
                  }
                  // A sheet is a valley across it and none along it; a canal is one all round.
                  for (int d = 0; d < steps.length; d++) {
                    if ((along & 1 << d) != 0 && (along & square[d]) == 0) {
                      across[d].set(x, y, z);
                    }
                  }
                }
              }
            });
    BitVolume valley = new BitVolume(tx, ty, tz);
    IntStream.range(0, tz)
        .parallel()
        .forEach(
            z -> {
              RegionGrow.checkCancelled(caller);
              for (BitVolume a : across) {
                if (a.countSlice(z) == 0) {
                  continue;
                }
                for (int y = 0; y < ty; y++) {
                  for (int x = 0; x < tx; x++) {
                    if (a.get(x, y, z)
                        && !valley.get(x, y, z)
                        && neighbours(a, x, y, z) >= JOINT_SHEET) {
                      valley.set(x, y, z);
                    }
                  }
                }
              }
            });
    // Each group of touching valley voxels, cleared from the dense bone when it comes out of it.
    BitVolume joints = new BitVolume(tx, ty, tz);
    int[] group = new int[1 << 12];
    for (int z0 = 0; z0 < tz; z0++) {
      RegionGrow.checkCancelled(caller);
      if (valley.countSlice(z0) == 0) {
        continue;
      }
      for (int y0 = 0; y0 < ty; y0++) {
        for (int x0 = 0; x0 < tx; x0++) {
          if (!valley.get(x0, y0, z0)) {
            continue;
          }
          int size = 0;
          boolean open = false;
          valley.clear(x0, y0, z0);
          group[size++] = (z0 * ty + y0) * tx + x0;
          for (int next = 0; next < size; next++) {
            int i = group[next];
            int x = i % tx;
            int y = (i / tx) % ty;
            int z = i / (tx * ty);
            // The edge of the block is not the outside: a bone cut by the first slice or by the
            // field of view shows its marrow canal there.
            open =
                open
                    || (x > 0 && !solid.get(x - 1, y, z))
                    || (x < tx - 1 && !solid.get(x + 1, y, z))
                    || (y > 0 && !solid.get(x, y - 1, z))
                    || (y < ty - 1 && !solid.get(x, y + 1, z))
                    || (z > 0 && !solid.get(x, y, z - 1))
                    || (z < tz - 1 && !solid.get(x, y, z + 1));
            for (int dz = -1; dz <= 1; dz++) {
              int pz = z + dz;
              if (pz < 0 || pz >= tz) {
                continue;
              }
              for (int dy = -1; dy <= 1; dy++) {
                int py = y + dy;
                if (py < 0 || py >= ty) {
                  continue;
                }
                for (int dx = -1; dx <= 1; dx++) {
                  int px = x + dx;
                  if (px >= 0 && px < tx && valley.get(px, py, pz)) {
                    valley.clear(px, py, pz);
                    if (size == group.length) {
                      group = Arrays.copyOf(group, 2 * size);
                    }
                    group[size++] = (pz * ty + py) * tx + px;
                  }
                }
              }
            }
          }
          if (open) {
            for (int n = 0; n < size; n++) {
              int i = group[n];
              int x = i % tx;
              int y = (i / tx) % ty;
              int z = i / (tx * ty);
              if (dense.get(x, y, z)) {
                dense.clear(x, y, z);
                joints.set(x, y, z);
              }
            }
          }
        }
      }
    }
    return joints;
  }

  /** How bright against both sides, as a part of the valley depth, makes a voxel a ridge. */
  private static final double JOINT_RIDGE = 0.6;

  /**
   * The bone on each side of a joint line is at least this bright, in HU: a subchondral plate. The
   * trabeculae on each side of a marrow gap seldom are.
   */
  private static final int JOINT_PLATE_HU = 600;

  /** Two directions are square to each other up to this cosine of their angle. */
  private static final double JOINT_SQUARE = 0.25;

  /**
   * Fewest of a voxel's 26 neighbours that are a valley along the same direction: a joint line is a
   * sheet, the gap between two trabeculae a speck or a thread.
   */
  private static final int JOINT_SHEET = 5;

  private static double sq(double v) {
    return v * v;
  }

  /** Cosine of the angle between two voxel steps, in mm. */
  private static double cosine(int[] a, int[] b, Options o) {
    double ax = a[0] * o.mmX();
    double ay = a[1] * o.mmY();
    double az = a[2] * o.mmZ();
    double bx = b[0] * o.mmX();
    double by = b[1] * o.mmY();
    double bz = b[2] * o.mmZ();
    return (ax * bx + ay * by + az * bz)
        / Math.sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz));
  }

  /** Whether, along one direction, a voxel at {@code level} or below lies on each side. */
  private static boolean isRidge(
      short[] value, int tx, int ty, int tz, int x, int y, int z, int level, int[] steps) {
    for (int d = 0; d < steps.length; d++) {
      int[] n = HALF_NEIGHBOURS[d];
      if (reaches(value, tx, ty, tz, x, y, z, n[0], n[1], n[2], steps[d], level, false)
          && reaches(value, tx, ty, tz, x, y, z, -n[0], -n[1], -n[2], steps[d], level, false)) {
        return true;
      }
    }
    return false;
  }

  /** How many of the 26 neighbours of a voxel are set. */
  private static int neighbours(BitVolume bits, int x, int y, int z) {
    int count = 0;
    for (int dz = -1; dz <= 1; dz++) {
      int pz = z + dz;
      if (pz < 0 || pz >= bits.sizeZ()) {
        continue;
      }
      for (int dy = -1; dy <= 1; dy++) {
        int py = y + dy;
        if (py < 0 || py >= bits.sizeY()) {
          continue;
        }
        for (int dx = -1; dx <= 1; dx++) {
          int px = x + dx;
          if ((dx != 0 || dy != 0 || dz != 0)
              && px >= 0
              && px < bits.sizeX()
              && bits.get(px, py, pz)) {
            count++;
          }
        }
      }
    }
    return count;
  }

  private static boolean reaches(
      short[] value,
      int tx,
      int ty,
      int tz,
      int x,
      int y,
      int z,
      int dx,
      int dy,
      int dz,
      int steps,
      int level,
      boolean above) {
    for (int s = 1; s <= steps; s++) {
      int px = x + s * dx;
      int py = y + s * dy;
      int pz = z + s * dz;
      if (px < 0 || py < 0 || pz < 0 || px >= tx || py >= ty || pz >= tz) {
        return !above;
      }
      int v = value[(pz * ty + py) * tx + px];
      if (above ? v >= level : v <= level) {
        return true;
      }
    }
    return false;
  }

  private static long millis(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  /**
   * How much each axis is coarsened so that the grid fits {@link Options#maxWorkVoxels()}: one step
   * at a time on the axes whose coarse voxel is smallest in mm, up to 4 each. An isotropic CT is
   * coarsened alike on all three, as before; a CT with 3 mm slices and 1 mm pixels is halved in the
   * plane only. One factor for all three (until 11 Nov 2026) made the slices 6 mm there, and a
   * joint one slice wide between two bones closed (review of 11 Nov 2026).
   */
  static int[] factors(int sx, int sy, int sz, Options o) {
    int[] f = {1, 1, 1};
    int[] size = {sx, sy, sz};
    double[] mm = {o.mmX(), o.mmY(), o.mmZ()};
    while (Work.coarse(sx, f[0]) * (long) Work.coarse(sy, f[1]) * Work.coarse(sz, f[2])
        > o.maxWorkVoxels()) {
      double least = Double.POSITIVE_INFINITY;
      for (int a = 0; a < 3; a++) {
        if (f[a] < 4 && size[a] > 1) {
          least = Math.min(least, mm[a] * f[a]);
        }
      }
      if (least == Double.POSITIVE_INFINITY) {
        break;
      }
      for (int a = 0; a < 3; a++) {
        if (f[a] < 4 && size[a] > 1 && mm[a] * f[a] <= least * 1.02) {
          f[a]++;
        }
      }
    }
    return f;
  }

  /** One split, on the block holding the bone. */
  private static final class Work {
    private final BitVolume solid;
    private final BitVolume dense;
    private final BitVolume joints;
    private final VoxelBox block;
    private final Options o;
    private final Thread caller;
    // the coarsening of each axis
    private final int fx;
    private final int fy;
    private final int fz;
    private final int wx;
    private final int wy;
    private final int wz;
    private final double fineMm3;

    /**
     * @param joints the voxels of the joint lines, already cleared from {@code dense}, or null
     */
    Work(
        BitVolume solid,
        BitVolume dense,
        BitVolume joints,
        VoxelBox block,
        Options o,
        Thread caller) {
      this.solid = solid;
      this.dense = dense;
      this.joints = joints;
      this.block = block;
      this.o = o;
      this.caller = caller;
      int[] f = factors(solid.sizeX(), solid.sizeY(), solid.sizeZ(), o);
      this.fx = f[0];
      this.fy = f[1];
      this.fz = f[2];
      this.wx = coarse(solid.sizeX(), fx);
      this.wy = coarse(solid.sizeY(), fy);
      this.wz = coarse(solid.sizeZ(), fz);
      this.fineMm3 = o.mmX() * o.mmY() * o.mmZ();
    }

    private static int coarse(int size, int factor) {
      return (size + factor - 1) / factor;
    }

    private int index(int x, int y, int z) {
      return (z * wy + y) * wx + x;
    }

    Result run(long start) {
      boolean[] solidW = new boolean[wx * wy * wz];
      float[] depth = depthOfDense(solidW);
      int[] label = watershed(depth);
      depth = null; // NOSONAR frees the largest array before the next steps
      int pieces = regrow(label, solidW);
      return pieces(label, pieces, start);
    }

    // ---- Coarse grid and distance ----

    /**
     * Distance in mm of each dense voxel of the working grid to the nearest voxel that is not; 0
     * outside. Fills {@code solidW} with the working grid's bone.
     */
    private float[] depthOfDense(boolean[] solidW) {
      float[] g = new float[wx * wy * wz];
      IntStream.range(0, wz)
          .parallel()
          .forEach(
              z -> {
                RegionGrow.checkCancelled(caller);
                for (int y = 0; y < wy; y++) {
                  for (int x = 0; x < wx; x++) {
                    int denseCount = 0;
                    int present = 0;
                    boolean any = false;
                    boolean joint = false;
                    for (int dz = 0; dz < fz; dz++) {
                      int pz = z * fz + dz;
                      if (pz >= solid.sizeZ()) {
                        continue;
                      }
                      for (int dy = 0; dy < fy; dy++) {
                        int py = y * fy + dy;
                        if (py >= solid.sizeY()) {
                          continue;
                        }
                        for (int dx = 0; dx < fx; dx++) {
                          int px = x * fx + dx;
                          if (px >= solid.sizeX()) {
                            continue;
                          }
                          present++;
                          any |= solid.get(px, py, pz);
                          if (dense.get(px, py, pz)) {
                            denseCount++;
                          }
                          joint |= joints != null && joints.get(px, py, pz);
                        }
                      }
                    }
                    int i = index(x, y, z);
                    solidW[i] = any;
                    // A coarse voxel is dense when at least half of it is, so a cortex one voxel
                    // thick stays bone. (More than half was tried on 11 Nov 2026: it opens a
                    // joint one voxel wide, but on a pelvis it cut thin bone into a sixth more
                    // pieces than the CT's own grid gives.) A joint line is thinner than a coarse
                    // voxel and would close: it opens the whole of each coarse voxel it crosses.
                    g[i] = present > 0 && !joint && 2 * denseCount >= present ? INF : 0;
                  }
                }
              });
      distanceTransform(g);
      for (int i = 0; i < g.length; i++) {
        g[i] = g[i] > 0 ? (float) Math.sqrt(g[i]) : 0;
      }
      return g;
    }

    /** Squared Euclidean distance in mm², in place: 0 stays 0, {@link #INF} gets its distance. */
    private void distanceTransform(float[] g) {
      double ux = o.mmX() * fx;
      double uy = o.mmY() * fy;
      double uz = o.mmZ() * fz;
      squaredDistance(g, wx, wy, wz, ux, uy, uz, caller);
      // The edge of the block counts as outside the bone: no voxel is farther than that.
      clampToEdges(g, ux, uy, uz);
    }

    private void clampToEdges(float[] g, double ux, double uy, double uz) {
      for (int z = 0; z < wz; z++) {
        double ez = Math.min(z + 1, wz - z) * uz;
        for (int y = 0; y < wy; y++) {
          double ey = Math.min(y + 1, wy - y) * uy;
          for (int x = 0; x < wx; x++) {
            int i = index(x, y, z);
            if (g[i] > 0) {
              double ex = Math.min(x + 1, wx - x) * ux;
              double e = Math.min(ex, Math.min(ey, ez));
              g[i] = (float) Math.min(g[i], e * e);
            }
          }
        }
      }
    }

    // ---- Watershed ----

    /** The piece of each dense voxel of the working grid, 1 and up; 0 elsewhere. */
    private int[] watershed(float[] depth) {
      // Deepest first, by steps of 0.05 mm.
      int levels = 1;
      for (float d : depth) {
        levels = Math.max(levels, (int) (d * 20) + 1);
      }
      int[] count = new int[levels + 1];
      int bone = 0;
      for (float d : depth) {
        if (d > 0) {
          count[levels - (int) (d * 20)]++;
          bone++;
        }
      }
      for (int i = 1; i <= levels; i++) {
        count[i] += count[i - 1];
      }
      int[] order = new int[bone];
      int[] next = new int[levels + 1];
      System.arraycopy(count, 0, next, 1, levels);
      for (int i = 0; i < depth.length; i++) {
        if (depth[i] > 0) {
          order[next[levels - (int) (depth[i] * 20)]++] = i;
        }
      }
      RegionGrow.checkCancelled(caller);

      int[] node = new int[depth.length];
      Arrays.fill(node, -1);
      Nodes nodes = new Nodes();
      int[] roots = new int[6];
      int[] kept = new int[6];
      double coarseMm3 = fineMm3 * fx * fy * fz;
      for (int n = 0; n < order.length; n++) {
        if ((n & 0xFFFF) == 0) {
          RegionGrow.checkCancelled(caller);
        }
        int i = order[n];
        float d = depth[i];
        int r = neighbourRoots(node, nodes, i, roots);
        if (r == 0) {
          node[i] = nodes.add(d, i);
          continue;
        }
        // Highest peak first: a part joins the first part kept that it may not stay apart from.
        sortByPeak(roots, r, nodes);
        int k = 0;
        for (int j = 0; j < r; j++) {
          int a = roots[j];
          int joinTo = -1;
          for (int m = 0; m < k; m++) {
            if (!apart(nodes, kept[m], a, d, coarseMm3)) {
              joinTo = kept[m];
              break;
            }
          }
          if (joinTo < 0) {
            kept[k++] = a;
          } else {
            nodes.union(joinTo, a);
          }
        }
        node[i] = kept[0];
        nodes.volume[kept[0]]++;
      }
      // Each voxel takes the piece of its root.
      int[] pieceOfRoot = new int[nodes.size];
      int pieces = 0;
      for (int i = 0; i < node.length; i++) {
        if (node[i] < 0) {
          node[i] = 0;
          continue;
        }
        int root = nodes.find(node[i]);
        if (pieceOfRoot[root] == 0) {
          pieceOfRoot[root] = ++pieces;
          peakOfPiece.put(pieces, nodes.peakVoxel[root]);
        }
        node[i] = pieceOfRoot[root];
      }
      return node;
    }

    /** Deepest voxel of each piece of the watershed, working grid index. */
    private final Map<Integer, Integer> peakOfPiece = new HashMap<>();

    /** Whether two parts meeting at depth {@code d} stay two bones. */
    private boolean apart(Nodes nodes, int a, int b, float d, double voxelMm3) {
      return standsAlone(nodes, a, d, voxelMm3) && standsAlone(nodes, b, d, voxelMm3);
    }

    private boolean standsAlone(Nodes nodes, int a, float d, double voxelMm3) {
      float peak = nodes.peak[a];
      return nodes.volume[a] * voxelMm3 >= o.minBoneMm3()
          && peak - d >= o.minDepthMm()
          && d <= o.neckRatio() * peak;
    }

    /** The distinct roots of the 6 neighbours already taken, in {@code roots}; their count. */
    private int neighbourRoots(int[] node, Nodes nodes, int i, int[] roots) {
      int x = i % wx;
      int y = (i / wx) % wy;
      int z = i / (wx * wy);
      int r = 0;
      for (int dir = 0; dir < 6; dir++) {
        int j = neighbour(x, y, z, dir);
        if (j < 0 || node[j] < 0) {
          continue;
        }
        int root = nodes.find(node[j]);
        boolean seen = false;
        for (int m = 0; m < r; m++) {
          seen |= roots[m] == root;
        }
        if (!seen) {
          roots[r++] = root;
        }
      }
      return r;
    }

    private int neighbour(int x, int y, int z, int dir) {
      return switch (dir) {
        case 0 -> x > 0 ? index(x - 1, y, z) : -1;
        case 1 -> x < wx - 1 ? index(x + 1, y, z) : -1;
        case 2 -> y > 0 ? index(x, y - 1, z) : -1;
        case 3 -> y < wy - 1 ? index(x, y + 1, z) : -1;
        case 4 -> z > 0 ? index(x, y, z - 1) : -1;
        default -> z < wz - 1 ? index(x, y, z + 1) : -1;
      };
    }

    private static void sortByPeak(int[] roots, int r, Nodes nodes) {
      for (int a = 1; a < r; a++) {
        int v = roots[a];
        int b = a - 1;
        while (b >= 0 && nodes.peak[roots[b]] < nodes.peak[v]) {
          roots[b + 1] = roots[b];
          b--;
        }
        roots[b + 1] = v;
      }
    }

    // ---- Regrow into the bone threshold ----

    /**
     * Gives every bone voxel of the working grid the piece that reaches it first; a part no piece
     * reaches becomes a piece of its own. Returns the number of pieces.
     */
    private int regrow(int[] label, boolean[] solidW) {
      IntQueue queue = new IntQueue(1 << 16);
      for (int i = 0; i < label.length; i++) {
        if (label[i] > 0) {
          queue.add(i);
        }
      }
      int pieces = 0;
      for (int l : label) {
        pieces = Math.max(pieces, l);
      }
      spread(label, solidW, queue);
      for (int i = 0; i < label.length; i++) {
        if (solidW[i] && label[i] == 0) {
          label[i] = ++pieces;
          queue.add(i);
          spread(label, solidW, queue);
        }
      }
      return pieces;
    }

    private void spread(int[] label, boolean[] solidW, IntQueue queue) {
      int visited = 0;
      while (!queue.isEmpty()) {
        if ((++visited & 0xFFFF) == 0) {
          RegionGrow.checkCancelled(caller);
        }
        int i = queue.poll();
        int x = i % wx;
        int y = (i / wx) % wy;
        int z = i / (wx * wy);
        for (int dir = 0; dir < 6; dir++) {
          int j = neighbour(x, y, z, dir);
          if (j >= 0 && solidW[j] && label[j] == 0) {
            label[j] = label[i];
            queue.add(j);
          }
        }
      }
    }

    // ---- Pieces, fragments and masks ----

    private Result pieces(int[] label, int count, long start) {
      // Voxels and extent of each piece on the CT's own grid.
      long[] voxels = new long[count + 1];
      int[][] extent = new int[count + 1][];
      for (int z = 0; z < solid.sizeZ(); z++) {
        RegionGrow.checkCancelled(caller);
        if (solid.countSlice(z) == 0) {
          continue;
        }
        for (int y = 0; y < solid.sizeY(); y++) {
          for (int x = 0; x < solid.sizeX(); x++) {
            if (solid.get(x, y, z)) {
              int l = label[index(x / fx, y / fy, z / fz)];
              voxels[l]++;
              extent[l] = grow(extent[l], x, y, z);
            }
          }
        }
      }
      Map<Long, Integer> contacts = contacts(label);
      Reach reach = null;
      int[] home = new int[count + 1];
      int fragments = 0;
      int specks = 0;
      boolean anyLarge = false;
      for (int l = 1; l <= count; l++) {
        anyLarge |= voxels[l] * fineMm3 >= o.fragmentMm3();
      }
      for (int l = 1; l <= count; l++) {
        if (voxels[l] == 0) {
          home[l] = -1;
        } else if (!anyLarge || voxels[l] * fineMm3 >= o.fragmentMm3()) {
          home[l] = l;
        } else {
          int to = mostTouched(l, contacts, voxels);
          if (to < 0) {
            if (reach == null) {
              reach = new Reach(label, voxels, count);
            }
            to = reach.nearestLarge(l);
          }
          if (to > 0) {
            home[l] = to;
            fragments++;
          } else if (voxels[l] * fineMm3 >= o.speckMm3()) {
            home[l] = l;
          } else {
            home[l] = -1;
            specks++;
          }
        }
      }
      List<Piece> pieces = new ArrayList<>();
      for (int l = 1; l <= count; l++) {
        if (home[l] != l) {
          continue;
        }
        int[] e = null;
        int joined = 0;
        for (int m = 1; m <= count; m++) {
          if (home[m] == l && extent[m] != null) {
            e = e == null ? extent[m].clone() : union(e, extent[m]);
            joined += m == l ? 0 : 1;
          }
        }
        pieces.add(new Piece(mask(label, home, l, e), anchor(l, e), joined));
      }
      pieces.sort(Comparator.comparingLong((Piece p) -> p.mask().count()).reversed());
      return new Result(
          List.copyOf(pieces), fragments, specks, Math.max(fx, Math.max(fy, fz)), millis(start));
    }

    /** Touching faces between two pieces, by the pair (smaller label, larger label). */
    private Map<Long, Integer> contacts(int[] label) {
      Map<Long, Integer> contacts = new HashMap<>();
      for (int z = 0; z < wz; z++) {
        for (int y = 0; y < wy; y++) {
          for (int x = 0; x < wx; x++) {
            int a = label[index(x, y, z)];
            if (a == 0) {
              continue;
            }
            for (int dir = 1; dir < 6; dir += 2) {
              int j = neighbour(x, y, z, dir);
              int b = j < 0 ? 0 : label[j];
              if (b != 0 && b != a) {
                long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
                contacts.merge(key, 1, Integer::sum);
              }
            }
          }
        }
      }
      return contacts;
    }

    private int mostTouched(int l, Map<Long, Integer> contacts, long[] voxels) {
      int best = -1;
      int bestFaces = 0;
      for (Map.Entry<Long, Integer> e : contacts.entrySet()) {
        int a = (int) (e.getKey() >>> 32);
        int b = (int) (long) e.getKey();
        int other = a == l ? b : b == l ? a : -1;
        if (other > 0 && voxels[other] * fineMm3 >= o.fragmentMm3() && e.getValue() > bestFaces) {
          best = other;
          bestFaces = e.getValue();
        }
      }
      return best;
    }

    /**
     * How far each piece lies from the nearest large piece, bone to bone: the distance in mm from
     * its nearest voxel to the nearest voxel of a large piece, on the working grid. Until 11 Nov
     * 2026 the distance was taken from the piece's centre to the large piece's bounding box, so
     * anything inside that box was "at" the bone: a calcification or a speck anywhere in the hollow
     * of a pelvis was joined to a pelvic bone and went into its surface, and where two boxes
     * overlapped the piece went to whichever came later in the list (review of 11 Nov 2026).
     */
    private final class Reach {
      private final int[] label;
      private final long[] voxels;
      private final int[] target;
      private final double ux = o.mmX() * fx;
      private final double uy = o.mmY() * fy;
      private final double uz = o.mmZ() * fz;
      // the squared mm from the voxel last asked to the large piece nearestTo gave for it
      private double foundMm2;

      Reach(int[] label, long[] voxels, int count) {
        this.label = label;
        this.voxels = voxels;
        float[] g = new float[label.length];
        for (int i = 0; i < g.length; i++) {
          g[i] = large(label[i]) ? 0 : INF;
        }
        squaredDistance(g, wx, wy, wz, ux, uy, uz, caller);
        double[] nearestMm2 = new double[count + 1];
        Arrays.fill(nearestMm2, Double.POSITIVE_INFINITY);
        for (int i = 0; i < g.length; i++) {
          int m = label[i];
          if (m > 0 && g[i] < nearestMm2[m]) {
            nearestMm2[m] = g[i];
          }
        }
        // Every voxel of a piece that lies at its least distance is asked which large piece is
        // that near, so two bones equally near different ends of a long fragment are both seen
        // (Astra, 11 Nov 2026). Each asks only the voxels at that distance from it, a shell and
        // not the cube round it. The nearer wins by the distance worked out afresh in full
        // precision; only pieces truly equally near are settled by size, then by order.
        target = new int[count + 1];
        Arrays.fill(target, -1);
        double[] targetMm2 = new double[count + 1];
        double reach2 = o.attachMm() * o.attachMm();
        for (int i = 0; i < g.length; i++) {
          int m = label[i];
          if (m > 0 && !large(m) && g[i] <= reach2 && g[i] <= nearestMm2[m] * (1 + 1e-6) + 1e-6) {
            int to = nearestTo(i, g[i], m);
            if (to > 0
                && (target[m] < 0
                    || nearer(foundMm2, targetMm2[m])
                    || (same(foundMm2, targetMm2[m]) && better(to, target[m])))) {
              target[m] = to;
              targetMm2[m] = foundMm2;
            }
          }
        }
      }

      private boolean large(int m) {
        return m > 0 && voxels[m] * fineMm3 >= o.fragmentMm3();
      }

      /** Of two large pieces equally near: the larger, then the earlier. */
      private boolean better(int a, int b) {
        return voxels[a] > voxels[b] || (voxels[a] == voxels[b] && a < b);
      }

      private static boolean same(double a, double b) {
        return Math.abs(a - b) <= 1e-9 * Math.max(1, Math.max(a, b));
      }

      private static boolean nearer(double a, double b) {
        return a < b && !same(a, b);
      }

      private static double lowest(double mm2) {
        return Math.sqrt(Math.max(0, mm2));
      }

      /** The large piece nearest piece {@code l}, within the reach; -1 when there is none. */
      int nearestLarge(int l) {
        return target[l];
      }

      /**
       * The large piece nearest working voxel {@code i}, which the transform put {@code mm2}
       * (squared mm, single precision) from one; its squared distance in full precision is left in
       * {@link #foundMm2}. For each row and slice offset only the columns that can complete that
       * distance are looked at: every column whose own squared distance lies within the transform's
       * rounding of the rest, which is one or two columns unless the columns are far finer than the
       * rows, and then as many as the rounding spans. Among what is found the nearest by the
       * recomputed distance wins.
       */
      private int nearestTo(int i, double mm2, int self) {
        int x = i % wx;
        int y = (i / wx) % wy;
        int z = i / (wx * wy);
        double tol = 1e-4 * Math.max(1, mm2);
        int ry = (int) Math.floor(Math.sqrt(mm2 + tol) / uy);
        int rz = (int) Math.floor(Math.sqrt(mm2 + tol) / uz);
        int best = -1;
        double bestMm2 = Double.POSITIVE_INFINITY;
        long looked = 0;
        for (int dz = -rz; dz <= rz; dz++) {
          RegionGrow.checkCancelled(caller);
          int pz = z + dz;
          if (pz < 0 || pz >= wz) {
            continue;
          }
          for (int dy = -ry; dy <= ry; dy++) {
            int py = y + dy;
            double rest = mm2 - sq(dy * uy) - sq(dz * uz);
            if (py < 0 || py >= wy || rest < -tol) {
              continue;
            }
            // Only offsets that stay inside the image: with columns far finer than the rows the
            // rounding spans a great many, and the count must not run past an int (Astra, 12 Nov
            // 2026).
            int widest = Math.max(x, wx - 1 - x);
            int from = (int) Math.min(widest + 1.0, Math.ceil(lowest(rest - tol) / ux - 1e-9));
            int to = (int) Math.min(widest, Math.floor(Math.sqrt(rest + tol) / ux + 1e-9));
            for (int dx = Math.max(0, from); dx <= to; dx++) {
              if ((++looked & 0xFFFF) == 0) {
                RegionGrow.checkCancelled(caller);
              }
              double d2 = sq(dx * ux) + sq(dy * uy) + sq(dz * uz);
              for (int side = -1; side <= 1; side += 2) {
                int px = x + side * dx;
                if (px >= 0 && px < wx) {
                  int m = label[index(px, py, pz)];
                  if (m != self
                      && large(m)
                      && (best < 0
                          || nearer(d2, bestMm2)
                          || (same(d2, bestMm2) && better(m, best)))) {
                    best = m;
                    bestMm2 = d2;
                  }
                }
                if (dx == 0) {
                  break;
                }
              }
            }
          }
        }
        foundMm2 = bestMm2;
        return best;
      }
    }

    private BoxMask mask(int[] label, int[] home, int l, int[] e) {
      BitVolume bits = new BitVolume(e[1] - e[0] + 1, e[3] - e[2] + 1, e[5] - e[4] + 1);
      for (int z = e[4]; z <= e[5]; z++) {
        for (int y = e[2]; y <= e[3]; y++) {
          for (int x = e[0]; x <= e[1]; x++) {
            if (solid.get(x, y, z)) {
              int m = label[index(x / fx, y / fy, z / fz)];
              if (m > 0 && home[m] == l) {
                bits.set(x - e[0], y - e[2], z - e[4]);
              }
            }
          }
        }
      }
      return new BoxMask(
          new VoxelBox(
              block.x0() + e[0],
              block.x0() + e[1],
              block.y0() + e[2],
              block.y0() + e[3],
              block.z0() + e[4],
              block.z0() + e[5]),
          bits);
    }

    /** The deepest voxel of the piece, else the centre of its extent, on the CT grid. */
    private int[] anchor(int l, int[] e) {
      Integer peak = peakOfPiece.get(l);
      int x;
      int y;
      int z;
      if (peak != null) {
        x = Math.min(solid.sizeX() - 1, (peak % wx) * fx + fx / 2);
        y = Math.min(solid.sizeY() - 1, ((peak / wx) % wy) * fy + fy / 2);
        z = Math.min(solid.sizeZ() - 1, (peak / (wx * wy)) * fz + fz / 2);
      } else {
        x = (e[0] + e[1]) / 2;
        y = (e[2] + e[3]) / 2;
        z = (e[4] + e[5]) / 2;
      }
      return new int[] {block.x0() + x, block.y0() + y, block.z0() + z};
    }

    private static int[] grow(int[] e, int x, int y, int z) {
      if (e == null) {
        return new int[] {x, x, y, y, z, z};
      }
      e[0] = Math.min(e[0], x);
      e[1] = Math.max(e[1], x);
      e[2] = Math.min(e[2], y);
      e[3] = Math.max(e[3], y);
      e[4] = Math.min(e[4], z);
      e[5] = Math.max(e[5], z);
      return e;
    }

    private static int[] union(int[] a, int[] b) {
      return new int[] {
        Math.min(a[0], b[0]),
        Math.max(a[1], b[1]),
        Math.min(a[2], b[2]),
        Math.max(a[3], b[3]),
        Math.min(a[4], b[4]),
        Math.max(a[5], b[5])
      };
    }
  }

  /**
   * Squared Euclidean distance in mm² to the nearest 0 of a grid, in place: 0 stays 0, {@link #INF}
   * gets its distance. Along x, then y, then z: each pass is the exact 1D transform of the one
   * before.
   */
  static void squaredDistance(
      float[] g, int nx, int ny, int nz, double ux, double uy, double uz, Thread caller) {
    int longest = Math.max(nx, Math.max(ny, nz));
    IntStream.range(0, nz)
        .parallel()
        .forEach(
            z -> {
              RegionGrow.checkCancelled(caller);
              Line line = new Line(longest);
              for (int y = 0; y < ny; y++) {
                int base = (z * ny + y) * nx;
                System.arraycopy(g, base, line.in, 0, nx);
                line.transform(nx, ux);
                System.arraycopy(line.out, 0, g, base, nx);
              }
              for (int x = 0; x < nx; x++) {
                for (int y = 0; y < ny; y++) {
                  line.in[y] = g[(z * ny + y) * nx + x];
                }
                line.transform(ny, uy);
                for (int y = 0; y < ny; y++) {
                  g[(z * ny + y) * nx + x] = line.out[y];
                }
              }
            });
    IntStream.range(0, ny)
        .parallel()
        .forEach(
            y -> {
              RegionGrow.checkCancelled(caller);
              Line line = new Line(longest);
              for (int x = 0; x < nx; x++) {
                for (int z = 0; z < nz; z++) {
                  line.in[z] = g[(z * ny + y) * nx + x];
                }
                line.transform(nz, uz);
                for (int z = 0; z < nz; z++) {
                  g[(z * ny + y) * nx + x] = line.out[z];
                }
              }
            });
  }

  /**
   * Splits one bone in two between two marks, one on each part: a watershed on the bone's distance
   * to its surface, flooded from the two marks deepest first, so the two parts meet at the
   * narrowest neck between the marks and not half-way. A part not connected to the second mark's
   * part stays with the first.
   *
   * @param keep the mark on the part that keeps the bone, CT grid voxel
   * @param cut the mark on the part split off, CT grid voxel
   * @throws BoneSplit.SplitException when a mark is not on the bone, or both marks are on one spot
   */
  public static BoneSplit.Split splitBetween(
      BoxMask bone, int[] keep, int[] cut, double mmX, double mmY, double mmZ)
      throws BoneSplit.SplitException {
    return splitBetween(bone, keep, cut, mmX, mmY, mmZ, null);
  }

  /**
   * Splits one bone in two between two marks. With the CT, the two parts are flooded from the marks
   * brightest first, so they meet along the darkest surface between the marks: a fracture line or a
   * joint space, which the bone mask had closed, even where the two parts press together over a
   * broad face with no neck. The marks stay where they were clicked and start flooding at once.
   * Without the CT, the flood follows the distance to the bone's surface: the narrowest neck.
   *
   * @param hu the CT, or {@code null} to cut at the narrowest neck
   */
  public static BoneSplit.Split splitBetween(
      BoxMask bone, int[] keep, int[] cut, double mmX, double mmY, double mmZ, HuSource hu)
      throws BoneSplit.SplitException {
    return flood(bone, keep, cut, null, null, null, mmX, mmY, mmZ, hu, null);
  }

  /**
   * Draws the border between two bones again, along the darkest surface between two clicks, one on
   * each.
   *
   * <p>Two answers are worked out. In the first each bone keeps for certain what lies more than
   * {@code bandMm} from the other and only the band along their contact is decided again, so
   * neither can lose its body to the other. In the second the clicks decide alone: each looks for
   * the cortex of its own bone (see {@link #climb}) and the two bones are flooded again from there,
   * as one. The second is taken only when both clicks found cortex, each bone keeps at least {@link
   * #WIDER_KEEPS} of itself, and either its border lies on a joint line where the first's does not
   * ({@link #WIDER_SHARE}, {@link #WIDER_GAIN}, as for a wide move after a find) or the first
   * leaves a bone less than that share of itself. Otherwise the first.
   *
   * <p>Until 12 Nov 2026 it was the band alone. On a foot CT the find had left a slice of the
   * cuboid, more than the band thick in places, with the fifth metatarsal: what the metatarsal
   * "kept for certain" then held cuboid cortex, the flood ran on along the cuboid from there, and
   * 34 of 40 pairs of clicks gave the metatarsal still more of the cuboid. The clicks alone put the
   * border on the joint there. On two scans that show no joint lines (1.25 mm slices with a
   * standard kernel, 3 mm with a soft one) the clicks alone moved borders about more than the band
   * did, with nothing to say they were better: hence the joint line as the condition.
   *
   * @param keepBone the bone clicked at {@code keep}, which keeps the other side
   * @param cutBone the bone clicked at {@code cut}
   * @return the new {@code keepBone} as {@code kept}, the new {@code cutBone} as {@code cut}
   */
  public static BoneSplit.Split splitBorder(
      BoxMask keepBone,
      BoxMask cutBone,
      int[] keep,
      int[] cut,
      double mmX,
      double mmY,
      double mmZ,
      HuSource hu,
      double bandMm)
      throws BoneSplit.SplitException {
    BoxMask union = BoxMask.union(List.of(keepBone, cutBone));
    BoxMask keepCore = farFrom(keepBone, cutBone, bandMm, mmX, mmY, mmZ);
    BoxMask cutCore = farFrom(cutBone, keepBone, bandMm, mmX, mmY, mmZ);
    BoneSplit.Split banded = null;
    BoneSplit.SplitException refused = null;
    try {
      banded = flood(union, keep, cut, keepCore, cutCore, cutBone, mmX, mmY, mmZ, hu, null);
    } catch (BoneSplit.SplitException e) {
      refused = e;
    }
    if (hu != null) {
      Options o = Options.of(mmX, mmY, mmZ);
      boolean[] climbed = new boolean[2];
      try {
        BoneSplit.Split s =
            flood(union, keep, cut, null, null, cutBone, mmX, mmY, mmZ, hu, climbed);
        if (climbed[0]
            && climbed[1]
            && s.kept().count() >= WIDER_KEEPS * keepBone.count()
            && s.cut().count() >= WIDER_KEEPS * cutBone.count()) {
          // The band's own answer can leave a bone nearly nothing: a small bone wholly within
          // the band of a brighter one has no body kept for it (an elbow CT, a piece of 3.5 cm3
          // left 15 mm3). Then the clicks' answer, which leaves both standing, is the better one.
          boolean bandGuts =
              banded == null
                  || banded.kept().count() < WIDER_KEEPS * keepBone.count()
                  || banded.cut().count() < WIDER_KEEPS * cutBone.count();
          double byClicks = jointShare(s.kept(), s.cut(), o, hu)[0];
          double byBand = banded == null ? 0 : jointShare(banded.kept(), banded.cut(), o, hu)[0];
          if (bandGuts || (byClicks >= WIDER_SHARE && byClicks >= byBand + WIDER_GAIN)) {
            return s;
          }
        }
      } catch (BoneSplit.SplitException e) {
        // the clicks alone leave one side empty: the band's answer
      }
    }
    if (banded == null) {
      throw refused;
    }
    return banded;
  }

  /**
   * How deep into each of two bones their border may move when it is drawn again after a find: the
   * first always, the wider ones only when they bring the border onto a joint line.
   */
  static final double[] REFINE_BANDS_MM = {2.5, 5, 8, 14};

  /** A border with this share of its faces on a joint line is a joint: no wider band is tried. */
  static final double JOINT_SHARE = 0.8;

  /**
   * A wider band is taken only when it leaves this share of the border on a joint line, and that
   * much more than the band before.
   */
  static final double WIDER_SHARE = 0.6;

  static final double WIDER_GAIN = 0.15;

  /**
   * Wider bands are tried only while less than this share of the narrowest band's border lies on a
   * joint line: a border half on a line is at the joint already, and a faint joint must not lose
   * the border to a stronger line further off. On the four CTs tried every border that a wider band
   * moved had started under 0.5, and ended smaller than it was; a wider band that makes the border
   * larger is refused as well (after Astra, 12 Nov 2026: the share alone is not enough to move a
   * border far).
   */
  static final double WIDER_FROM = 0.5;

  /**
   * No redraw may leave either bone less than this part of what the find gave it: a small bone must
   * not be swallowed by its neighbour (on an elbow CT a piece of 4 cm3 went almost whole to the
   * bone beside it), while a border moved onto a joint leaves both bones standing (the fifth
   * metatarsal kept half of a piece that had held a slice of the cuboid as well). Measured against
   * the bone as found, not as the pair before left it, so a bone with several neighbours cannot
   * lose that share to each in turn (Astra, 12 Nov 2026).
   */
  static final double WIDER_KEEPS = 0.3;

  /**
   * The narrowest band is applied without a joint line to show for it, so it may only tidy the
   * border: it is refused when it leaves either bone less than this part of what it had before this
   * pair. Before 12 Nov 2026 it was applied whatever it took, and a small bone lying mostly within
   * the band, with only a sliver further off, could go to its neighbour (Astra). On the four CTs
   * tried no narrowest band took more than 7 % of a bone.
   */
  static final double NARROW_KEEPS = 0.8;

  /**
   * The largest block in which a border is drawn again. Two bones that touch in two places far
   * apart (both ends of a tibia and fibula) are worked in one block round both; beyond this size
   * the border is left as the find drew it instead of asking for gigabytes (Astra, 12 Nov 2026).
   */
  static final long REFINE_MAX_VOXELS = 64_000_000L;

  /** CT values from here on are metal: nothing beside them says where a joint is. */
  static final double METAL_HU = 2500;

  /** How far each side of a border voxel brighter bone is looked for, as a joint line has. */
  static final double JOINT_LOOK_MM = 2.0;

  /**
   * Draws the border between every two bones that touch again, on the CT's own grid, along the
   * darkest surface between them: the joint. The find works on a coarser grid when the CT is large,
   * so its borders ran in steps of a coarse voxel, a millimetre or more, with specks of one bone
   * left in the other, and at a narrow joint (a tarsometatarsal joint) it could put the border
   * several millimetres off the joint, through the bone: on a foot CT a slice of the cuboid went
   * with the fifth metatarsal. In the coloured 3D picture that read as bones mixed into each other
   * (Sami, 12 Nov 2026).
   *
   * <p>For each pair the border is flooded again, brightest first, from what each bone keeps for
   * certain: what lies further from the other than a band, {@link #REFINE_BANDS_MM}. The narrowest
   * band is applied when it leaves both bones nearly whole ({@link #NARROW_KEEPS}). A border counts
   * as a joint when {@link #JOINT_SHARE} of its faces lie on a joint line, a voxel at least {@link
   * Options#jointHu()} darker than the bone of one of the two on one side of it and of the other on
   * the other side, within {@link #JOINT_LOOK_MM}. While it lies clearly off any line ({@link
   * #WIDER_FROM}), the next wider band is tried and taken only if it does clearly better ({@link
   * #WIDER_SHARE}, {@link #WIDER_GAIN}), makes the border no larger and leaves both bones standing
   * ({@link #WIDER_KEEPS}): a border may move far only onto a line the CT shows. On a scan that
   * shows no such lines (thick slices, a soft kernel: tried on a public trunk CT of 1 by 1 by 3 mm)
   * nothing moves further than the narrowest band. Every voxel stays in exactly one bone; none is
   * added or lost, and no bones are joined.
   *
   * <p>What this cannot do: the joint line is judged from brightness alone. A dark layer between
   * two bright ones inside one bone (a growth plate, a fracture line, layered dense bone) reads the
   * same, so a border moved here is a proposal to be looked at, not a proven joint. The pairs are
   * taken in order of size and once each: a contact that a later pair's redraw creates is not gone
   * over again.
   */
  public static Result refineBorders(Result found, Supplier<HuSource> hu, Options o) {
    long start = System.nanoTime();
    Thread caller = Thread.currentThread();
    HuSource ct = hu.get();
    List<BoxMask> masks = new ArrayList<>();
    for (Piece p : found.pieces()) {
      masks.add(p.mask());
    }
    int n = masks.size();
    long[] asFound = new long[n];
    for (int i = 0; i < n; i++) {
      asFound[i] = masks.get(i).count();
    }
    for (int i = 0; i < n; i++) {
      for (int j = i + 1; j < n; j++) {
        RegionGrow.checkCancelled(caller);
        VoxelBox touch = contact(masks.get(i), masks.get(j));
        if (touch == null) {
          continue;
        }
        BoxMask[] best = null;
        double bestShare = -1;
        double bestFaces = Double.MAX_VALUE;
        for (double band : REFINE_BANDS_MM) {
          BoxMask[] drawn = redrawn(masks.get(i), masks.get(j), touch, band, o, ct);
          if (drawn == null) {
            break; // one of them is thinner than the band here: no wider band either
          }
          double[] onLine = jointShare(drawn[0], drawn[1], o, ct);
          double share = o.jointHu() > 0 ? onLine[0] : 1;
          double faces = onLine[1];
          boolean narrowest = band == REFINE_BANDS_MM[0];
          // each still has its share of what the find gave it, whatever earlier pairs took
          boolean bothStay =
              drawn[0].count() >= WIDER_KEEPS * asFound[i]
                  && drawn[1].count() >= WIDER_KEEPS * asFound[j];
          if (narrowest) {
            if (bothStay
                && drawn[0].count() >= NARROW_KEEPS * masks.get(i).count()
                && drawn[1].count() >= NARROW_KEEPS * masks.get(j).count()) {
              best = drawn;
            }
            bestShare = share;
            bestFaces = faces;
            if (share >= WIDER_FROM || faces == 0) {
              break; // at a joint already, or apart: nothing to look for further off
            }
          } else if (bothStay
              && faces > 0
              && faces <= bestFaces
              && share >= WIDER_SHARE
              && share >= bestShare + WIDER_GAIN) {
            best = drawn;
            bestShare = share;
            bestFaces = faces;
          }
          if (bestShare >= JOINT_SHARE) {
            break;
          }
        }
        if (best != null) {
          masks.set(i, best[0]);
          masks.set(j, best[1]);
        }
      }
    }
    List<Piece> pieces = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Piece p = found.pieces().get(i);
      pieces.add(new Piece(masks.get(i), anchorIn(masks.get(i), p.anchor()), p.fragments()));
    }
    pieces.sort(Comparator.comparingLong((Piece p) -> p.mask().count()).reversed());
    return new Result(
        List.copyOf(pieces),
        found.fragments(),
        found.specks(),
        found.factor(),
        found.millis() + millis(start));
  }

  /**
   * The two bones with the band along their contact decided again, or null when one of them has
   * nothing further than the band from the other near the contact.
   */
  private static BoxMask[] redrawn(
      BoxMask wholeA, BoxMask wholeB, VoxelBox touch, double band, Options o, HuSource ct) {
    int padX = (int) Math.ceil((band + 2) / o.mmX());
    int padY = (int) Math.ceil((band + 2) / o.mmY());
    int padZ = (int) Math.ceil((band + 2) / o.mmZ());
    VoxelBox crop =
        new VoxelBox(
            touch.x0() - padX,
            touch.x1() + padX,
            touch.y0() - padY,
            touch.y1() + padY,
            touch.z0() - padZ,
            touch.z1() + padZ);
    if (crop.voxels() > REFINE_MAX_VOXELS) {
      return null;
    }
    BoxMask a = within(wholeA, crop);
    BoxMask b = within(wholeB, crop);
    if (a == null || b == null) {
      return null;
    }
    // Far from the whole of the other bone, not only from its part in the block: where the two
    // come near each other again just outside it, a voxel is not far (Astra, 12 Nov 2026).
    BoxMask coreA = farFrom(a, wholeB, band, o.mmX(), o.mmY(), o.mmZ());
    BoxMask coreB = farFrom(b, wholeA, band, o.mmX(), o.mmY(), o.mmZ());
    if (coreA == null || coreB == null) {
      return null;
    }
    try {
      BoneSplit.Split s =
          flood(
              BoxMask.union(List.of(a, b)),
              anyVoxel(coreA),
              anyVoxel(coreB),
              coreA,
              coreB,
              b,
              o.mmX(),
              o.mmY(),
              o.mmZ(),
              ct,
              null);
      return new BoxMask[] {rejoined(wholeA, a, s.kept()), rejoined(wholeB, b, s.cut())};
    } catch (BoneSplit.SplitException e) {
      return null;
    }
  }

  /**
   * How much of the border between two bones lies on a joint line: the share of the faces where a
   * voxel of one meets a voxel of the other at which one of the two is darker by {@link
   * Options#jointHu()} or more than the bone within {@link #JOINT_LOOK_MM} on both sides of it,
   * along one of the 13 directions: bone of the one on one side and of the other on the other side.
   * Returns the share and the number of faces; a share of 0 when the two do not touch.
   *
   * <p>Until 12 Nov 2026 any brighter CT on both sides counted, whatever it belonged to, so a third
   * bone, metal, or the bone's own texture beside the border could stand in for a joint (Astra).
   * Brightness is still all this sees: see {@link #refineBorders}.
   */
  static double[] jointShare(BoxMask a, BoxMask b, Options o, HuSource ct) {
    if (a.count() > b.count()) {
      return jointShare(b, a, o, ct);
    }
    int[] steps = new int[HALF_NEIGHBOURS.length];
    for (int d = 0; d < steps.length; d++) {
      int[] nb = HALF_NEIGHBOURS[d];
      double mm = Math.sqrt(sq(nb[0] * o.mmX()) + sq(nb[1] * o.mmY()) + sq(nb[2] * o.mmZ()));
      steps[d] = Math.max(1, (int) Math.round(JOINT_LOOK_MM / mm));
    }
    VoxelBox ab = a.box();
    VoxelBox bb = b.box();
    int x0 = Math.max(ab.x0(), bb.x0() - 1);
    int x1 = Math.min(ab.x1(), bb.x1() + 1);
    int y0 = Math.max(ab.y0(), bb.y0() - 1);
    int y1 = Math.min(ab.y1(), bb.y1() + 1);
    int z0 = Math.max(ab.z0(), bb.z0() - 1);
    int z1 = Math.min(ab.z1(), bb.z1() + 1);
    int[][] face = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    long faces = 0;
    long onLine = 0;
    Thread caller = Thread.currentThread();
    for (int z = z0; z <= z1; z++) {
      RegionGrow.checkCancelled(caller);
      if (!a.hasVoxelsInSlice(z)) {
        continue;
      }
      for (int y = y0; y <= y1; y++) {
        for (int x = x0; x <= x1; x++) {
          if (!a.contains(x, y, z)) {
            continue;
          }
          double own = Double.NaN;
          for (int[] f : face) {
            if (b.contains(x + f[0], y + f[1], z + f[2])) {
              if (Double.isNaN(own)) {
                own = valleyDepth(ct, a, b, x, y, z, steps);
              }
              faces++;
              if (own >= o.jointHu()
                  || valleyDepth(ct, a, b, x + f[0], y + f[1], z + f[2], steps) >= o.jointHu()) {
                onLine++;
              }
            }
          }
        }
      }
    }
    return new double[] {faces == 0 ? 0 : (double) onLine / faces, faces};
  }

  /**
   * How much darker a voxel is than the brightest bone of {@code a} on one side of it and of {@code
   * b} on the other, the best direction; 0 when there is no such direction. Values count up to
   * {@link #BRIGHT_HU}, and a direction with metal on it does not count.
   */
  private static double valleyDepth(
      HuSource ct, BoxMask a, BoxMask b, int x, int y, int z, int[] steps) {
    double here = value(ct, x, y, z);
    if (here >= METAL_HU) {
      return 0;
    }
    double[] peak = new double[2];
    int[] whose = new int[2];
    double best = 0;
    for (int d = 0; d < steps.length; d++) {
      int[] nb = HALF_NEIGHBOURS[d];
      boolean metal = false;
      for (int way = 0; way < 2 && !metal; way++) {
        int sign = way == 0 ? 1 : -1;
        peak[way] = Double.NEGATIVE_INFINITY;
        whose[way] = 0;
        for (int s = 1; s <= steps[d]; s++) {
          int px = x + sign * s * nb[0];
          int py = y + sign * s * nb[1];
          int pz = z + sign * s * nb[2];
          double v = value(ct, px, py, pz);
          if (v >= METAL_HU) {
            metal = true;
            break;
          }
          if (v > peak[way]) {
            int owner = a.contains(px, py, pz) ? 1 : b.contains(px, py, pz) ? 2 : 0;
            if (owner != 0) {
              peak[way] = v;
              whose[way] = owner;
            }
          }
        }
      }
      if (metal || whose[0] == 0 || whose[1] == 0 || whose[0] == whose[1]) {
        continue;
      }
      double low = Math.min(Math.min(peak[0], peak[1]), BRIGHT_HU);
      best = Math.max(best, low - Math.min(here, BRIGHT_HU));
    }
    return best;
  }

  /** {@code anchor} when the mask still holds it, else the voxel of the mask nearest to it. */
  static int[] anchorIn(BoxMask mask, int[] anchor) {
    if (anchor == null || mask.contains(anchor[0], anchor[1], anchor[2])) {
      return anchor;
    }
    VoxelBox box = mask.box();
    int[] best = anchor;
    long bestD = Long.MAX_VALUE;
    for (int z = 0; z < box.sizeZ(); z++) {
      long dz = box.z0() + z - anchor[2];
      if (dz * dz >= bestD || !mask.hasVoxelsInSlice(box.z0() + z)) {
        continue;
      }
      for (int y = 0; y < box.sizeY(); y++) {
        long dy = box.y0() + y - anchor[1];
        if (dz * dz + dy * dy >= bestD) {
          continue;
        }
        for (int x = 0; x < box.sizeX(); x++) {
          long dx = box.x0() + x - anchor[0];
          long d = dx * dx + dy * dy + dz * dz;
          if (d < bestD && mask.bits().get(x, y, z)) {
            bestD = d;
            best = new int[] {box.x0() + x, box.y0() + y, box.z0() + z};
          }
        }
      }
    }
    return best;
  }

  private static double value(HuSource ct, int x, int y, int z) {
    double v = ct.huAt(x, y, z);
    return Double.isNaN(v) ? -1000 : v;
  }

  /** The box round the voxels of the smaller mask that touch the other face to face, or null. */
  static VoxelBox contact(BoxMask a, BoxMask b) {
    if (a.count() > b.count()) {
      return contact(b, a);
    }
    VoxelBox ab = a.box();
    VoxelBox bb = b.box();
    int x0 = Math.max(ab.x0(), bb.x0() - 1);
    int x1 = Math.min(ab.x1(), bb.x1() + 1);
    int y0 = Math.max(ab.y0(), bb.y0() - 1);
    int y1 = Math.min(ab.y1(), bb.y1() + 1);
    int z0 = Math.max(ab.z0(), bb.z0() - 1);
    int z1 = Math.min(ab.z1(), bb.z1() + 1);
    int[] e = null;
    Thread caller = Thread.currentThread();
    for (int z = z0; z <= z1; z++) {
      RegionGrow.checkCancelled(caller);
      if (!a.hasVoxelsInSlice(z)) {
        continue;
      }
      for (int y = y0; y <= y1; y++) {
        for (int x = x0; x <= x1; x++) {
          if (a.contains(x, y, z)
              && (b.contains(x + 1, y, z)
                  || b.contains(x - 1, y, z)
                  || b.contains(x, y + 1, z)
                  || b.contains(x, y - 1, z)
                  || b.contains(x, y, z + 1)
                  || b.contains(x, y, z - 1))) {
            e = grow(e, x, y, z);
          }
        }
      }
    }
    return e == null ? null : new VoxelBox(e[0], e[1], e[2], e[3], e[4], e[5]);
  }

  private static int[] grow(int[] e, int x, int y, int z) {
    if (e == null) {
      return new int[] {x, x, y, y, z, z};
    }
    e[0] = Math.min(e[0], x);
    e[1] = Math.max(e[1], x);
    e[2] = Math.min(e[2], y);
    e[3] = Math.max(e[3], y);
    e[4] = Math.min(e[4], z);
    e[5] = Math.max(e[5], z);
    return e;
  }

  /** The part of {@code mask} inside {@code crop}, or null. */
  static BoxMask within(BoxMask mask, VoxelBox crop) {
    VoxelBox common = mask.box().intersect(crop);
    if (common == null) {
      return null;
    }
    VoxelBox b = mask.box();
    VoxelBox local =
        new VoxelBox(
            common.x0() - b.x0(),
            common.x1() - b.x0(),
            common.y0() - b.y0(),
            common.y1() - b.y0(),
            common.z0() - b.z0(),
            common.z1() - b.z0());
    return BoxMask.trimmed(common, mask.bits().crop(local));
  }

  private static int[] anyVoxel(BoxMask mask) {
    VoxelBox b = mask.box();
    for (int z = 0; z < b.sizeZ(); z++) {
      for (int y = 0; y < b.sizeY(); y++) {
        for (int x = 0; x < b.sizeX(); x++) {
          if (mask.bits().get(x, y, z)) {
            return new int[] {b.x0() + x, b.y0() + y, b.z0() + z};
          }
        }
      }
    }
    throw new IllegalArgumentException("empty mask");
  }

  /** {@code whole} with its part {@code was} replaced by {@code now}. */
  private static BoxMask rejoined(BoxMask whole, BoxMask was, BoxMask now) {
    BoxMask rest = whole.minus(List.of(was));
    return rest == null ? now : BoxMask.union(List.of(rest, now));
  }

  /** The voxels of {@code a} farther than {@code mm} from every voxel of {@code b}, or null. */
  static BoxMask farFrom(BoxMask a, BoxMask b, double mm, double mmX, double mmY, double mmZ) {
    VoxelBox box = a.box();
    int px = (int) Math.ceil(mm / mmX) + 1;
    int py = (int) Math.ceil(mm / mmY) + 1;
    int pz = (int) Math.ceil(mm / mmZ) + 1;
    int nx = box.sizeX() + 2 * px;
    int ny = box.sizeY() + 2 * py;
    int nz = box.sizeZ() + 2 * pz;
    float[] g = new float[cells(nx, ny, nz)];
    Arrays.fill(g, INF);
    VoxelBox bb = b.box();
    for (int z = 0; z < nz; z++) {
      int gz = box.z0() - pz + z;
      if (gz < bb.z0() || gz > bb.z1()) {
        continue;
      }
      for (int y = 0; y < ny; y++) {
        int gy = box.y0() - py + y;
        for (int x = 0; x < nx; x++) {
          int gx = box.x0() - px + x;
          if (b.contains(gx, gy, gz)) {
            g[(z * ny + y) * nx + x] = 0;
          }
        }
      }
    }
    squaredDistance(g, nx, ny, nz, mmX, mmY, mmZ, Thread.currentThread());
    BitVolume far = new BitVolume(box.sizeX(), box.sizeY(), box.sizeZ());
    double limit = mm * mm;
    for (int z = 0; z < box.sizeZ(); z++) {
      for (int y = 0; y < box.sizeY(); y++) {
        for (int x = 0; x < box.sizeX(); x++) {
          if (a.bits().get(x, y, z) && g[((z + pz) * ny + y + py) * nx + x + px] > limit) {
            far.set(x, y, z);
          }
        }
      }
    }
    return BoxMask.trimmed(box, far);
  }

  /**
   * @param cutOwner the voxels the flood does not reach stay with the kept side unless they are in
   *     this mask: then they go to the cut side, where they were. Null when one bone is split
   * @param climbed when not null: set to whether the keep mark and the cut mark each found the way
   *     to cortex that was given to its side
   */
  private static BoneSplit.Split flood(
      BoxMask bone,
      int[] keep,
      int[] cut,
      BoxMask keepSeeds,
      BoxMask cutSeeds,
      BoxMask cutOwner,
      double mmX,
      double mmY,
      double mmZ,
      HuSource hu,
      boolean[] climbed)
      throws BoneSplit.SplitException {
    Thread caller = Thread.currentThread();
    VoxelBox box = bone.box();
    // One clear voxel around the bone, so its faces on the block's edges are surface too.
    int nx = box.sizeX() + 2;
    int ny = box.sizeY() + 2;
    int nz = box.sizeZ() + 2;
    int[] a = nearestOnBone(bone, keep);
    int[] b = nearestOnBone(bone, cut);
    if (a == null || b == null) {
      throw new BoneSplit.SplitException(BoneSplit.SplitException.Reason.NOT_IN_BONE);
    }
    float[] g = new float[cells(nx, ny, nz)];
    for (int z = 0; z < box.sizeZ(); z++) {
      for (int y = 0; y < box.sizeY(); y++) {
        for (int x = 0; x < box.sizeX(); x++) {
          if (bone.bits().get(x, y, z)) {
            g[((z + 1) * ny + y + 1) * nx + x + 1] = INF;
          }
        }
      }
    }
    if (hu == null) {
      squaredDistance(g, nx, ny, nz, mmX, mmY, mmZ, caller);
      for (int i = 0; i < g.length; i++) {
        if (g[i] > 0) {
          g[i] = (float) Math.sqrt(g[i]); // depth in mm, flooded by steps of 0.05 mm
        }
      }
    } else {
      brightness(g, box, nx, ny, nz, hu);
    }
    int ia = ((a[2] - box.z0() + 1) * ny + a[1] - box.y0() + 1) * nx + a[0] - box.x0() + 1;
    int ib = ((b[2] - box.z0() + 1) * ny + b[1] - box.y0() + 1) * nx + b[0] - box.x0() + 1;
    if (ia == ib) {
      throw new BoneSplit.SplitException(BoneSplit.SplitException.Reason.NOT_SEPARABLE);
    }
    // Priority flood, highest first, in steps of 1/20 of the priority's unit.
    int levels = 1;
    for (float v : g) {
      if (v > 0) {
        levels = Math.max(levels, level(v) + 1);
      }
    }
    IntQueue[] buckets = new IntQueue[levels];
    byte[] side = new byte[g.length];
    side[ia] = 1;
    side[ib] = 2;
    // The marks start at once, whatever their own brightness: a click in the marrow must not wait
    // while the other part floods across the fracture.
    push(buckets, ia, levels - 1);
    push(buckets, ib, levels - 1);
    seed(keepSeeds, (byte) 1, box, nx, ny, side, buckets, levels - 1);
    seed(cutSeeds, (byte) 2, box, nx, ny, side, buckets, levels - 1);
    if (hu != null && (keepSeeds == null || cutSeeds == null)) {
      // Starting at once is not enough: from a click in the marrow everything beyond is reached
      // through that dark marrow, later than from a click on the other bone's cortex, so the other
      // side took this bone's cortex as well. On a foot CT, clicks in the marrow of the fifth
      // metatarsal and of the cuboid gave the metatarsal nothing at all; clicks on a slice land in
      // marrow more often than not (12 Nov 2026). A mark with no core of its own to start from
      // therefore first looks, brightest first, for the way to the cortex of its own bone; that
      // way is its side's, and from there the two sides compete as cortex against cortex.
      //
      // The first version gave a mark everything it looked at on the way, one mark after the
      // other, and Astra showed what that allowed (12 Nov 2026): where no cortex was in reach the
      // first mark took the whole bone before the second could move, and nothing kept a mark out
      // of the other bone. Now each mark looks on its own and nothing is given until it has ended
      // on cortex; then only the way there is given, not all that was looked at. Between two
      // bones a mark stays inside its own bone. Two ways that share a voxel (both marks in one
      // marrow space) are both dropped. A mark that finds no cortex starts as before, alone.
      //
      // What is left open: inside one mask nothing tells a mark which bone it is in, so where a
      // bone has no cortex towards its neighbour a way can end on the neighbour's cortex.
      int[] step6 = {1, -1, nx, -nx, nx * ny, -nx * ny};
      int cap = Math.max(2000, (int) Math.min(Integer.MAX_VALUE, bone.count() / 20));
      java.util.function.IntPredicate inCut =
          cutOwner == null
              ? null
              : i ->
                  cutOwner.contains(
                      box.x0() + i % nx - 1,
                      box.y0() + (i / nx) % ny - 1,
                      box.z0() + i / (nx * ny) - 1);
      int[] keepWay =
          keepSeeds != null
              ? null
              : climb(g, side, ia, inCut == null ? null : inCut.negate(), cap, step6, caller);
      int[] cutWay = cutSeeds != null ? null : climb(g, side, ib, inCut, cap, step6, caller);
      if (keepWay != null && cutWay != null && share(keepWay, cutWay)) {
        keepWay = null;
        cutWay = null;
      }
      take(keepWay, (byte) 1, side, buckets, levels - 1);
      take(cutWay, (byte) 2, side, buckets, levels - 1);
      if (climbed != null) {
        climbed[0] = keepWay != null;
        climbed[1] = cutWay != null;
      }
    }
    int[] step = {1, -1, nx, -nx, nx * ny, -nx * ny};
    long visited = 0;
    for (int lv = levels - 1; lv >= 0; ) {
      IntQueue q = buckets[lv];
      if (q == null || q.isEmpty()) {
        lv--;
        continue;
      }
      if ((++visited & 0xFFFF) == 0) {
        RegionGrow.checkCancelled(caller);
      }
      int i = q.poll();
      for (int s : step) {
        int j = i + s;
        if (g[j] > 0 && side[j] == 0) {
          side[j] = side[i];
          // A deeper voxel reached from a shallower one is taken at the current level.
          int l = Math.min(level(g[j]), lv);
          push(buckets, j, l);
        }
      }
    }
    BitVolume cutBits = new BitVolume(box.sizeX(), box.sizeY(), box.sizeZ());
    for (int z = 0; z < box.sizeZ(); z++) {
      for (int y = 0; y < box.sizeY(); y++) {
        for (int x = 0; x < box.sizeX(); x++) {
          byte s = side[((z + 1) * ny + y + 1) * nx + x + 1];
          if (s == 2
              || (s == 0
                  && cutOwner != null
                  && bone.bits().get(x, y, z)
                  && cutOwner.contains(box.x0() + x, box.y0() + y, box.z0() + z))) {
            cutBits.set(x, y, z);
          }
        }
      }
    }
    BitVolume keptBits = bone.bits().copy();
    keptBits.andNot(cutBits);
    BoxMask kept = BoxMask.trimmed(box, keptBits);
    BoxMask gone = BoxMask.trimmed(box, cutBits);
    if (kept == null || gone == null) {
      throw new BoneSplit.SplitException(BoneSplit.SplitException.Reason.NOT_SEPARABLE);
    }
    return new BoneSplit.Split(kept, gone, 0);
  }

  /**
   * The brightness at which a voxel is cortex for {@link #climb}: what the brightest 15 % of the
   * voxels {@code own} holds reach (of all the bone when it is null), and 500 HU at least.
   */
  private static float cortexLevel(float[] g, java.util.function.IntPredicate own) {
    int[] count = new int[level((float) ((BRIGHT_HU + 100) / 200)) + 2];
    long total = 0;
    for (int i = 0; i < g.length; i++) {
      if (g[i] > 0 && (own == null || own.test(i))) {
        count[Math.min(count.length - 1, level(g[i]))]++;
        total++;
      }
    }
    long above = 0;
    int lv = count.length - 1;
    while (lv > 0 && above + count[lv] < total * 0.15) {
      above += count[lv--];
    }
    return Math.max(lv / 20f, (float) ((500 + 100) / 200.0));
  }

  /**
   * The way from a mark to the cortex of its bone: brightest first from the mark, through voxels no
   * side has yet and, between two bones, only through those {@code own} holds, until a voxel of
   * cortex is met. Returns the voxels from that one back to the mark, or null when no cortex was
   * met within {@code cap} voxels. Changes nothing: see {@link #take}.
   */
  static int[] climb(
      float[] g,
      byte[] side,
      int from,
      java.util.function.IntPredicate own,
      int cap,
      int[] step,
      Thread caller) {
    float cortex = cortexLevel(g, own);
    java.util.PriorityQueue<Integer> open =
        new java.util.PriorityQueue<>((a, b) -> Float.compare(g[b], g[a]));
    // Where each voxel looked at was reached from: as large as the search, not as the block.
    java.util.Map<Integer, Integer> cameFrom = new java.util.HashMap<>();
    open.add(from);
    cameFrom.put(from, -1);
    int looked = 0;
    while (!open.isEmpty() && looked < cap) {
      int i = open.poll();
      if ((++looked & 0xFFF) == 0) {
        RegionGrow.checkCancelled(caller);
      }
      if (g[i] >= cortex) {
        List<Integer> way = new ArrayList<>();
        for (int at = i; at >= 0; at = cameFrom.get(at)) {
          way.add(at);
        }
        return way.stream().mapToInt(Integer::intValue).toArray();
      }
      for (int s : step) {
        int j = i + s;
        if (g[j] > 0 && side[j] == 0 && (own == null || own.test(j)) && !cameFrom.containsKey(j)) {
          cameFrom.put(j, i);
          open.add(j);
        }
      }
    }
    return null;
  }

  private static boolean share(int[] a, int[] b) {
    java.util.Set<Integer> seen = new java.util.HashSet<>();
    for (int i : a) {
      seen.add(i);
    }
    for (int i : b) {
      if (seen.contains(i)) {
        return true;
      }
    }
    return false;
  }

  /** Gives the voxels of a way to {@code who}, to start the flood with its mark. */
  private static void take(int[] way, byte who, byte[] side, IntQueue[] buckets, int top) {
    if (way == null) {
      return;
    }
    for (int i : way) {
      if (side[i] == 0) {
        side[i] = who;
        push(buckets, i, top);
      }
    }
  }

  private static int cells(int nx, int ny, int nz) {
    long n = (long) nx * ny * nz;
    if (n > Integer.MAX_VALUE - 8) {
      throw new IllegalArgumentException("block of " + n + " voxels is too large"); // NON-NLS
    }
    return (int) n;
  }

  /** CT values above this all flood together: cortex is cortex. */
  private static final double BRIGHT_HU = 1500;

  /**
   * Replaces the bone voxels of {@code g} (INF) with their brightness: the CT value of the voxel,
   * from -100 to {@link #BRIGHT_HU} HU, as 0.01 to 8 so that one flood step is 10 HU. The voxels
   * around the bone stay 0.
   */
  private static void brightness(float[] g, VoxelBox box, int nx, int ny, int nz, HuSource hu) {
    for (int z = 1; z < nz - 1; z++) {
      int gz = box.z0() + z - 1;
      for (int y = 1; y < ny - 1; y++) {
        int gy = box.y0() + y - 1;
        for (int x = 1; x < nx - 1; x++) {
          int i = (z * ny + y) * nx + x;
          if (g[i] <= 0) {
            continue;
          }
          int gx = box.x0() + x - 1;
          // The voxel's own value: a mean of the neighbours bridges a fracture gap one voxel
          // wide, their minimum makes a thin fragment as dark as the gap.
          double v = hu.huAt(gx, gy, gz);
          double value = Double.isNaN(v) ? 0 : v;
          double clamped = Math.max(-100, Math.min(BRIGHT_HU, value));
          g[i] = (float) Math.max(0.01, (clamped + 100) / 200);
        }
      }
    }
  }

  private static int level(float depth) {
    return (int) (depth * 20);
  }

  /** Gives the voxels of {@code seeds} to {@code who} before the flood, on the padded grid. */
  private static void seed(
      BoxMask seeds,
      byte who,
      VoxelBox box,
      int nx,
      int ny,
      byte[] side,
      IntQueue[] buckets,
      int level) {
    if (seeds == null) {
      return;
    }
    VoxelBox sb = seeds.box();
    for (int z = 0; z < sb.sizeZ(); z++) {
      for (int y = 0; y < sb.sizeY(); y++) {
        for (int x = 0; x < sb.sizeX(); x++) {
          if (seeds.bits().get(x, y, z)) {
            int i =
                ((sb.z0() + z - box.z0() + 1) * ny + sb.y0() + y - box.y0() + 1) * nx
                    + sb.x0()
                    + x
                    - box.x0()
                    + 1;
            if (side[i] == 0) {
              side[i] = who;
              push(buckets, i, level);
            }
          }
        }
      }
    }
  }

  private static void push(IntQueue[] buckets, int voxel, int level) {
    if (buckets[level] == null) {
      buckets[level] = new IntQueue(256);
    }
    buckets[level].add(voxel);
  }

  /** The voxel of the bone at or nearest the given one, within 3 voxels; {@code null} if none. */
  private static int[] nearestOnBone(BoxMask bone, int[] v) {
    if (bone.contains(v[0], v[1], v[2])) {
      return v;
    }
    int[] best = null;
    long bestD = Long.MAX_VALUE;
    int r = 3;
    for (int z = v[2] - r; z <= v[2] + r; z++) {
      for (int y = v[1] - r; y <= v[1] + r; y++) {
        for (int x = v[0] - r; x <= v[0] + r; x++) {
          long d =
              (long) (x - v[0]) * (x - v[0])
                  + (long) (y - v[1]) * (y - v[1])
                  + (long) (z - v[2]) * (z - v[2]);
          if (d < bestD && bone.contains(x, y, z)) {
            bestD = d;
            best = new int[] {x, y, z};
          }
        }
      }
    }
    return best;
  }

  /** The parts of the watershed: a union-find with each root's peak depth and voxel count. */
  private static final class Nodes {
    int[] parent = new int[1024];
    float[] peak = new float[1024];
    int[] peakVoxel = new int[1024];
    long[] volume = new long[1024];
    int size;

    int add(float depth, int voxel) {
      if (size == parent.length) {
        int n = 2 * size;
        parent = Arrays.copyOf(parent, n);
        peak = Arrays.copyOf(peak, n);
        peakVoxel = Arrays.copyOf(peakVoxel, n);
        volume = Arrays.copyOf(volume, n);
      }
      parent[size] = size;
      peak[size] = depth;
      peakVoxel[size] = voxel;
      volume[size] = 1;
      return size++;
    }

    int find(int a) {
      int root = a;
      while (parent[root] != root) {
        root = parent[root];
      }
      while (parent[a] != root) {
        int next = parent[a];
        parent[a] = root;
        a = next;
      }
      return root;
    }

    /** Joins {@code b} into {@code into}, which keeps its peak. */
    void union(int into, int b) {
      parent[b] = into;
      volume[into] += volume[b];
    }
  }

  /** The 1D squared distance transform of Felzenszwalb and Huttenlocher, on a line of voxels. */
  private static final class Line {
    final float[] in;
    final float[] out;
    private final int[] v;
    private final double[] z;

    Line(int n) {
      in = new float[n];
      out = new float[n];
      v = new int[n];
      z = new double[n + 1];
    }

    /** {@code out[q]} = min over p of ((q - p) × unit)² + {@code in[p]}. */
    void transform(int n, double unit) {
      int k = -1;
      for (int q = 0; q < n; q++) {
        if (in[q] >= INF) {
          continue;
        }
        double pq = q * unit;
        double s = 0;
        while (k >= 0) {
          double pp = v[k] * unit;
          s = ((in[q] + pq * pq) - (in[v[k]] + pp * pp)) / (2 * (pq - pp));
          if (s <= z[k]) {
            k--;
          } else {
            break;
          }
        }
        k++;
        v[k] = q;
        z[k] = k == 0 ? Double.NEGATIVE_INFINITY : s;
        z[k + 1] = Double.POSITIVE_INFINITY;
      }
      if (k < 0) {
        Arrays.fill(out, 0, n, INF);
        return;
      }
      int j = 0;
      for (int q = 0; q < n; q++) {
        double pq = q * unit;
        while (z[j + 1] < pq) {
          j++;
        }
        double d = pq - v[j] * unit;
        out[q] = (float) (d * d + in[v[j]]);
      }
    }
  }
}
