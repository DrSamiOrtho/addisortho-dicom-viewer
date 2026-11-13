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
import java.util.List;

/**
 * Hand edits of a bone's mask: a brush that paints bone-dense voxels into it or rubs voxels out of
 * it around a clicked voxel, and a smoothing of its surface.
 */
public final class MaskEdit {

  private MaskEdit() {}

  /**
   * The voxels of the brush around {@code centre}: an ellipsoid of {@code radius} voxels per axis.
   */
  public static BoxMask brush(int[] centre, double[] radius, VoxelBox grid) {
    int rx = (int) Math.ceil(radius[0]);
    int ry = (int) Math.ceil(radius[1]);
    int rz = (int) Math.ceil(radius[2]);
    VoxelBox block =
        new VoxelBox(
                centre[0] - rx, centre[0] + rx,
                centre[1] - ry, centre[1] + ry,
                centre[2] - rz, centre[2] + rz)
            .intersect(grid);
    if (block == null) {
      return null;
    }
    BitVolume bits = new BitVolume(block.sizeX(), block.sizeY(), block.sizeZ());
    for (int z = block.z0(); z <= block.z1(); z++) {
      double dz = (z - centre[2]) / radius[2];
      for (int y = block.y0(); y <= block.y1(); y++) {
        double dy = (y - centre[1]) / radius[1];
        for (int x = block.x0(); x <= block.x1(); x++) {
          double dx = (x - centre[0]) / radius[0];
          if (dx * dx + dy * dy + dz * dz <= 1.0) {
            bits.set(x - block.x0(), y - block.y0(), z - block.z0());
          }
        }
      }
    }
    return BoxMask.trimmed(block, bits);
  }

  /**
   * The voxels the brush passes over along {@code points}, in order: every voxel within the brush
   * of a point or of the straight line between two points that follow each other, so a stroke has
   * an even edge however fast it was drawn. {@code null} when the stroke is wholly outside the
   * grid.
   */
  public static BoxMask stroke(List<int[]> points, double[] radius, VoxelBox grid) {
    return stroke(points, radius, grid, null, 0);
  }

  /**
   * As {@link #stroke(List, double[], VoxelBox)}, kept to the slice it was drawn on when {@code
   * normal} is given: the brush is then a disc lying in the slice, {@code depth} thick on each side
   * of it.
   *
   * @param normal unit normal of the slice along the grid's axes in mm, or {@code null} for a ball
   * @param depth half the thickness of the disc, as a part of the brush's radius (at most 1)
   */
  public static BoxMask stroke(
      List<int[]> points, double[] radius, VoxelBox grid, double[] normal, double depth) {
    if (points.isEmpty()) {
      return null;
    }
    int[] lo = points.getFirst().clone();
    int[] hi = points.getFirst().clone();
    for (int[] p : points) {
      for (int i = 0; i < 3; i++) {
        lo[i] = Math.min(lo[i], p[i]);
        hi[i] = Math.max(hi[i], p[i]);
      }
    }
    VoxelBox block = around(lo, hi, radius).intersect(grid);
    if (block == null) {
      return null;
    }
    BitVolume bits = new BitVolume(block.sizeX(), block.sizeY(), block.sizeZ());
    int[] last = points.getFirst();
    for (int[] p : points) {
      capsule(bits, block, last, p, radius, normal, depth);
      last = p;
    }
    return BoxMask.trimmed(block, bits);
  }

  private static VoxelBox around(int[] lo, int[] hi, double[] radius) {
    int rx = (int) Math.ceil(radius[0]);
    int ry = (int) Math.ceil(radius[1]);
    int rz = (int) Math.ceil(radius[2]);
    return new VoxelBox(lo[0] - rx, hi[0] + rx, lo[1] - ry, hi[1] + ry, lo[2] - rz, hi[2] + rz);
  }

  /**
   * Sets the voxels within the brush of the line from {@code a} to {@code b}, one point when equal.
   */
  private static void capsule(
      BitVolume bits,
      VoxelBox block,
      int[] a,
      int[] b,
      double[] radius,
      double[] normal,
      double depth) {
    int[] lo = {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])};
    int[] hi = {Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
    VoxelBox part = around(lo, hi, radius).intersect(block);
    if (part == null) {
      return;
    }
    // In units of the brush's radius on each axis the brush is a ball of radius 1.
    double ux = (b[0] - a[0]) / radius[0];
    double uy = (b[1] - a[1]) / radius[1];
    double uz = (b[2] - a[2]) / radius[2];
    double length2 = ux * ux + uy * uy + uz * uz;
    for (int z = part.z0(); z <= part.z1(); z++) {
      double pz = (z - a[2]) / radius[2];
      for (int y = part.y0(); y <= part.y1(); y++) {
        double py = (y - a[1]) / radius[1];
        for (int x = part.x0(); x <= part.x1(); x++) {
          double px = (x - a[0]) / radius[0];
          double t =
              length2 == 0 ? 0 : Math.clamp((px * ux + py * uy + pz * uz) / length2, 0.0, 1.0);
          double dx = px - t * ux;
          double dy = py - t * uy;
          double dz = pz - t * uz;
          double off = normal == null ? 0 : dx * normal[0] + dy * normal[1] + dz * normal[2];
          if (dx * dx + dy * dy + dz * dz - off * off <= 1.0
              && (normal == null || Math.abs(off) <= depth)) {
            bits.set(x - block.x0(), y - block.y0(), z - block.z0());
          }
        }
      }
    }
  }

  /**
   * A bone cut in two.
   *
   * @param kept the larger part, with what the line did not reach
   * @param piece what came away
   */
  public record Cut(BoxMask kept, BoxMask piece) {}

  /** The most voxels of a bone's block a cut is worked out on. */
  static final long CUT_MAX_VOXELS = 32_000_000L;

  /**
   * Cuts {@code bone} along a line drawn on a slice: the line is carried straight through the bone,
   * at right angles to the slice, and where that surface parts the bone the larger part is kept and
   * the rest comes away as one piece. Parts of the bone the line does not reach stay with the kept
   * part, and so does a crumb under {@code minPieceVoxels}.
   *
   * @param points the voxels the line passes through, in order
   * @param mm the voxel size along x, y and z
   * @param normal unit normal of the slice along the grid's axes in mm
   * @return {@code null} when the line does not part the bone, or the bone's block is too large
   */
  public static Cut cut(
      BoxMask bone, List<int[]> points, double[] mm, double[] normal, long minPieceVoxels) {
    VoxelBox box = bone.box();
    if (points.size() < 2 || box.voxels() > CUT_MAX_VOXELS) {
      return null;
    }
    // Two axes of the slice, and the line in them.
    int least = Math.abs(normal[0]) <= Math.abs(normal[1]) ? 0 : 1;
    least = Math.abs(normal[least]) <= Math.abs(normal[2]) ? least : 2;
    double[] e1 = new double[3];
    e1[least] = 1;
    double along = normal[least];
    for (int i = 0; i < 3; i++) {
      e1[i] -= along * normal[i];
    }
    double length = Math.sqrt(e1[0] * e1[0] + e1[1] * e1[1] + e1[2] * e1[2]);
    for (int i = 0; i < 3; i++) {
      e1[i] /= length;
    }
    double[] e2 = {
      normal[1] * e1[2] - normal[2] * e1[1],
      normal[2] * e1[0] - normal[0] * e1[2],
      normal[0] * e1[1] - normal[1] * e1[0]
    };
    int[] origin = points.getFirst();
    double[] lineA = new double[points.size()];
    double[] lineB = new double[points.size()];
    int n = 0;
    for (int[] p : points) {
      double a = 0;
      double b = 0;
      for (int i = 0; i < 3; i++) {
        double d = (p[i] - origin[i]) * mm[i];
        a += d * e1[i];
        b += d * e2[i];
      }
      if (n == 0 || a != lineA[n - 1] || b != lineB[n - 1]) {
        lineA[n] = a;
        lineB[n] = b;
        n++;
      }
    }
    if (n < 2) {
      return null;
    }
    // Thick enough that no step from voxel to voxel crosses it.
    double half = 0.5 * Math.hypot(pitch(e1, mm), pitch(e2, mm));
    int sx = box.sizeX();
    int sy = box.sizeY();
    int sz = box.sizeZ();
    BitVolume wall = new BitVolume(sx, sy, sz);
    BitVolume open = bone.bits().copy();
    double[] da = {e1[0] * mm[0], e1[1] * mm[1], e1[2] * mm[2]};
    double[] db = {e2[0] * mm[0], e2[1] * mm[1], e2[2] * mm[2]};
    for (int z = 0; z < sz; z++) {
      for (int y = 0; y < sy; y++) {
        for (int x = 0; x < sx; x++) {
          if (!open.get(x, y, z)) {
            continue;
          }
          int vx = box.x0() + x - origin[0];
          int vy = box.y0() + y - origin[1];
          int vz = box.z0() + z - origin[2];
          double a = vx * da[0] + vy * da[1] + vz * da[2];
          double b = vx * db[0] + vy * db[1] + vz * db[2];
          if (nearLine(a, b, lineA, lineB, n, half)) {
            wall.set(x, y, z);
            open.clear(x, y, z);
          }
        }
      }
    }
    if (wall.isEmpty()) {
      return null;
    }
    // The parts beside the wall, each flooded from a voxel that touches it.
    int[] label = new int[sx * sy * sz];
    List<Long> sizes = new ArrayList<>();
    sizes.add(0L);
    int[] queue = new int[1024];
    for (int z = 0; z < sz; z++) {
      for (int y = 0; y < sy; y++) {
        for (int x = 0; x < sx; x++) {
          if (!wall.get(x, y, z)) {
            continue;
          }
          for (int[] step : STEPS) {
            int px = x + step[0];
            int py = y + step[1];
            int pz = z + step[2];
            if (px < 0 || py < 0 || pz < 0 || px >= sx || py >= sy || pz >= sz) {
              continue;
            }
            int at = (pz * sy + py) * sx + px;
            if (label[at] == 0 && open.get(px, py, pz)) {
              int id = sizes.size();
              int head = 0;
              int tail = 0;
              label[at] = id;
              queue[tail++] = at;
              while (head < tail) {
                int v = queue[head++];
                int cx = v % sx;
                int cy = (v / sx) % sy;
                int cz = v / (sx * sy);
                for (int[] s : STEPS) {
                  int qx = cx + s[0];
                  int qy = cy + s[1];
                  int qz = cz + s[2];
                  if (qx < 0 || qy < 0 || qz < 0 || qx >= sx || qy >= sy || qz >= sz) {
                    continue;
                  }
                  int q = (qz * sy + qy) * sx + qx;
                  if (label[q] == 0 && open.get(qx, qy, qz)) {
                    label[q] = id;
                    if (tail == queue.length) {
                      queue = Arrays.copyOf(queue, tail * 2);
                    }
                    queue[tail++] = q;
                  }
                }
              }
              sizes.add((long) tail);
            }
          }
        }
      }
    }
    int largest = 0;
    for (int id = 1; id < sizes.size(); id++) {
      if (largest == 0 || sizes.get(id) > sizes.get(largest)) {
        largest = id;
      }
    }
    BitVolume piece = new BitVolume(sx, sy, sz);
    boolean any = false;
    for (int at = 0; at < label.length; at++) {
      int id = label[at];
      if (id != 0 && id != largest && sizes.get(id) >= Math.max(1, minPieceVoxels)) {
        piece.set(at % sx, (at / sx) % sy, at / (sx * sy));
        any = true;
      }
    }
    if (!any) {
      return null;
    }
    // The wall goes to the part it lies against: twice, as it is up to two voxels thick.
    for (int round = 0; round < 2; round++) {
      BitVolume gained = piece.emptyCopy();
      for (int z = 0; z < sz; z++) {
        for (int y = 0; y < sy; y++) {
          for (int x = 0; x < sx; x++) {
            if (wall.get(x, y, z) && !piece.get(x, y, z) && besideMore(piece, open, x, y, z)) {
              gained.set(x, y, z);
            }
          }
        }
      }
      piece.or(gained);
    }
    BoxMask cut = BoxMask.trimmed(box, piece);
    BoxMask kept = bone.minusLocal(piece);
    return kept == null ? null : new Cut(kept, cut);
  }

  private static final int[][] STEPS = {
    {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
  };

  /** How far one step from voxel to voxel reaches along {@code e}, at most, in mm. */
  private static double pitch(double[] e, double[] mm) {
    return Math.abs(e[0]) * mm[0] + Math.abs(e[1]) * mm[1] + Math.abs(e[2]) * mm[2];
  }

  /** Whether the point lies within {@code half} of the line through the {@code n} points. */
  private static boolean nearLine(
      double a, double b, double[] lineA, double[] lineB, int n, double half) {
    double limit = half * half;
    for (int i = 1; i < n; i++) {
      double a0 = lineA[i - 1];
      double b0 = lineB[i - 1];
      double a1 = lineA[i];
      double b1 = lineB[i];
      if (a < Math.min(a0, a1) - half
          || a > Math.max(a0, a1) + half
          || b < Math.min(b0, b1) - half
          || b > Math.max(b0, b1) + half) {
        continue;
      }
      double ua = a1 - a0;
      double ub = b1 - b0;
      double t = Math.clamp(((a - a0) * ua + (b - b0) * ub) / (ua * ua + ub * ub), 0.0, 1.0);
      double ra = a - a0 - t * ua;
      double rb = b - b0 - t * ub;
      if (ra * ra + rb * rb <= limit) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether more of the voxels around a wall voxel belong to the piece than to the rest of the bone
   * ({@code open} is the bone without the wall).
   */
  private static boolean besideMore(BitVolume piece, BitVolume open, int x, int y, int z) {
    int in = 0;
    int out = 0;
    for (int dz = -1; dz <= 1; dz++) {
      for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
          int px = x + dx;
          int py = y + dy;
          int pz = z + dz;
          if (px < 0
              || py < 0
              || pz < 0
              || px >= piece.sizeX()
              || py >= piece.sizeY()
              || pz >= piece.sizeZ()) {
            continue;
          }
          if (piece.get(px, py, pz)) {
            in++;
          } else if (open.get(px, py, pz)) {
            out++;
          }
        }
      }
    }
    return in > out;
  }

  /**
   * The voxels of {@code brush} that look like bone on the CT: at or above {@code lowerHu}, and not
   * above {@code upperHu}. {@code null} when there is none.
   */
  public static BoxMask dense(BoxMask brush, HuSource hu, double lowerHu, double upperHu) {
    VoxelBox b = brush.box();
    BitVolume bits = new BitVolume(b.sizeX(), b.sizeY(), b.sizeZ());
    for (int z = b.z0(); z <= b.z1(); z++) {
      for (int y = b.y0(); y <= b.y1(); y++) {
        for (int x = b.x0(); x <= b.x1(); x++) {
          if (brush.contains(x, y, z)) {
            double v = hu.huAt(x, y, z);
            if (v >= lowerHu && v <= upperHu) {
              bits.set(x - b.x0(), y - b.y0(), z - b.z0());
            }
          }
        }
      }
    }
    return BoxMask.trimmed(b, bits);
  }

  /**
   * {@code anchor} when {@code mask} holds it, else a voxel well inside the mask's largest part:
   * the voxel a bone is known by after an edit took its old one away.
   */
  public static int[] anchorIn(BoxMask mask, int[] anchor) {
    return AutoSplit.anchorIn(mask, anchor);
  }

  /** {@code mask} with the voxels of {@code added}; the same instance when it has them all. */
  public static BoxMask plus(BoxMask mask, BoxMask added) {
    if (added == null || added.minus(List.of(mask)) == null) {
      return mask;
    }
    return BoxMask.union(List.of(mask, added));
  }

  /** A voxel is bone after a smoothing round when at least this many of its 27 neighbours are. */
  private static final int MAJORITY = 14;

  /**
   * {@code mask} with its surface smoothed by {@code rounds} majority rounds: a voxel is kept, or
   * added, when most of the 3 x 3 x 3 voxels around it are bone. Each round fills pits and takes
   * off spikes of a voxel and rounds off edges by a voxel; a plate two voxels thick stays. {@code
   * null} when nothing is left.
   *
   * @throws java.util.concurrent.CancellationException when {@code caller} is interrupted
   */
  public static BoxMask smooth(BoxMask mask, int rounds, Thread caller) {
    if (rounds <= 0) {
      return mask;
    }
    BitVolume bits = mask.bits().padded(rounds);
    for (int r = 0; r < rounds; r++) {
      bits = majority(bits, caller);
    }
    VoxelBox b = mask.box();
    VoxelBox block =
        new VoxelBox(
            b.x0() - rounds, b.x1() + rounds,
            b.y0() - rounds, b.y1() + rounds,
            b.z0() - rounds, b.z1() + rounds);
    return BoxMask.trimmed(block, bits);
  }

  /** One majority round, with the voxels beyond the volume counted as clear. */
  static BitVolume majority(BitVolume v, Thread caller) {
    int sx = v.sizeX();
    int sy = v.sizeY();
    int sz = v.sizeZ();
    BitVolume out = v.emptyCopy();
    int[] alongZ = new int[sx * sy]; // bone voxels at z - 1, z and z + 1
    int[] alongYz = new int[sx * sy]; // the same summed over y - 1, y and y + 1
    for (int z = 0; z < sz; z++) {
      RegionGrow.checkCancelled(caller);
      for (int y = 0; y < sy; y++) {
        for (int x = 0; x < sx; x++) {
          int n = v.get(x, y, z) ? 1 : 0;
          if (z > 0 && v.get(x, y, z - 1)) {
            n++;
          }
          if (z < sz - 1 && v.get(x, y, z + 1)) {
            n++;
          }
          alongZ[y * sx + x] = n;
        }
      }
      for (int y = 0; y < sy; y++) {
        for (int x = 0; x < sx; x++) {
          int n = alongZ[y * sx + x];
          if (y > 0) {
            n += alongZ[(y - 1) * sx + x];
          }
          if (y < sy - 1) {
            n += alongZ[(y + 1) * sx + x];
          }
          alongYz[y * sx + x] = n;
        }
      }
      for (int y = 0; y < sy; y++) {
        int row = y * sx;
        for (int x = 0; x < sx; x++) {
          int n = alongYz[row + x];
          if (x > 0) {
            n += alongYz[row + x - 1];
          }
          if (x < sx - 1) {
            n += alongYz[row + x + 1];
          }
          if (n >= MAJORITY) {
            out.set(x, y, z);
          }
        }
      }
    }
    return out;
  }
}
