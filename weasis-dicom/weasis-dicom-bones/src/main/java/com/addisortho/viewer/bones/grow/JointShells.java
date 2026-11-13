/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

import com.addisortho.viewer.bones.grow.AutoSplit.Options;
import com.addisortho.viewer.bones.grow.AutoSplit.Piece;
import com.addisortho.viewer.bones.grow.AutoSplit.Result;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * A second look at each bone found, for the joints the dense read at 300 HU keeps shut and whose
 * line is too shallow and too wide for the joint lines of {@link AutoSplit}: on a wrist CT of 0.5
 * mm with a standard kernel the third metacarpal came out as one with the capitate, the fourth and
 * fifth with the hamate (12 Nov 2026).
 *
 * <p>The bone's dense shell is looked at above one level after another. Where it falls into two
 * parts that face each other across a short gap, the bone is flooded from the two, brightest voxels
 * first, so the two sides meet along the darkest surface between them. The cut is kept when both
 * sides are large enough to be a bone and the face they share is small for the size of the smaller
 * side: two bones meeting end to end, not one bone cut through its middle or along its length. Each
 * side is then looked at again from the same level.
 *
 * <p>Known limits: the numbers were set on five CTs of adults (wrist on two kernels, ankle on two
 * slice distances, elbow, pelvis). A growth plate has two facing plates too. A fragment under the
 * fragment size of {@link Options#fragmentMm3()} that the find joined to its bone comes off again
 * where it faces the bone like this. Finger joints on a standard kernel stay shut: their shells do
 * not face each other closely enough.
 */
public final class JointShells {

  /** The levels, in HU, above which the dense shell is looked at, lowest first. */
  static final int[] LEVELS_HU = {400, 500, 600, 700, 800, 900, 1000};

  /** A part of the shell under this volume, in mm3, is not a bone's shell. */
  static final double MIN_SHELL_MM3 = 40;

  /** Neither side of a cut may be smaller than this, in mm3: a distal phalanx is about 300. */
  static final double MIN_PART_MM3 = 150;

  /** Two shells face each other when voxels of one lie this near the other, in mm. */
  static final double GAP_MM = 3;

  /** How much of one shell must lie that near the other, in mm2. */
  static final double FACING_MM2 = 12;

  /**
   * Largest face two sides may share, as a multiple of the smaller side's volume to the power of
   * two thirds. Two blocks meeting on a whole face give 1, counted on voxel faces up to 1.7; a bone
   * cut through its middle gives about 3 and a long bone cut along its length 5 or more (the ulna
   * of an elbow CT, 12 Nov 2026).
   */
  static final double MAX_FACE = 2.0;

  /** Largest block holding a bone that is looked at: six bytes a voxel while it is. */
  static final long MAX_BOX_VOXELS = 32_000_000L;

  /** How many of the largest shell parts are tried against each other at one level. */
  private static final int MAX_SHELLS = 6;

  private static final short NO_VALUE = Short.MIN_VALUE;

  private JointShells() {}

  /**
   * @param found the bones as found
   * @param hu gives a reader of the CT
   * @return the bones with those cut that hold a joint; the same result when none does
   */
  public static Result split(Result found, Supplier<HuSource> hu, Options o) {
    long start = System.nanoTime();
    Thread caller = Thread.currentThread();
    HuSource ct = hu.get();
    List<Piece> pieces = new ArrayList<>();
    boolean any = false;
    for (Piece p : found.pieces()) {
      List<BoxMask> parts = new ArrayList<>();
      cut(p.mask(), 0, ct, o, caller, parts);
      if (parts.size() == 1) {
        pieces.add(p);
        continue;
      }
      any = true;
      BoxMask home = home(parts, p.anchor());
      for (BoxMask part : parts) {
        pieces.add(
            part == home
                ? new Piece(part, p.anchor(), p.fragments())
                : new Piece(part, deepest(part, o, caller), 0));
      }
    }
    if (!any) {
      return found;
    }
    pieces.sort(Comparator.comparingLong((Piece p) -> p.mask().count()).reversed());
    return new Result(
        List.copyOf(pieces),
        found.fragments(),
        found.specks(),
        found.factor(),
        found.millis() + (System.nanoTime() - start) / 1_000_000,
        found.jointsSkipped());
  }

  /** The part holding the bone's anchor; the largest when none does. */
  private static BoxMask home(List<BoxMask> parts, int[] anchor) {
    BoxMask largest = parts.getFirst();
    for (BoxMask part : parts) {
      if (anchor != null && part.contains(anchor[0], anchor[1], anchor[2])) {
        return part;
      }
      if (part.count() > largest.count()) {
        largest = part;
      }
    }
    return largest;
  }

  /** One part of the shell above a level: its number in the labels, its voxels and its bounds. */
  private record Shell(int id, int voxels, int[] bounds) {}

  /** Adds to {@code out} the bone, or its sides when it is cut, each looked at again. */
  private static void cut(
      BoxMask bone, int fromLevel, HuSource ct, Options o, Thread caller, List<BoxMask> out) {
    double voxelMm3 = o.mmX() * o.mmY() * o.mmZ();
    VoxelBox box = bone.box();
    if (bone.count() * voxelMm3 < 2 * MIN_PART_MM3 || box.voxels() > MAX_BOX_VOXELS) {
      out.add(bone);
      return;
    }
    int nx = box.sizeX();
    int ny = box.sizeY();
    int nz = box.sizeZ();
    short[] value = values(bone, ct);
    int[] label = new int[value.length];
    for (int level = fromLevel; level < LEVELS_HU.length; level++) {
      RegionGrow.checkCancelled(caller);
      List<Shell> shells = shells(value, label, LEVELS_HU[level], nx, ny, nz);
      long shellVoxels = 0;
      for (Shell s : shells) {
        shellVoxels += s.voxels();
      }
      if (shellVoxels * voxelMm3 < 2 * MIN_SHELL_MM3) {
        break;
      }
      shells.removeIf(s -> s.voxels() * voxelMm3 < MIN_SHELL_MM3);
      shells.sort(Comparator.comparingInt(Shell::voxels).reversed());
      Shell[] pair = facingPair(shells, label, nx, ny, nz, o, caller);
      if (pair == null) {
        continue;
      }
      BoneSplit.Split split;
      try {
        split =
            AutoSplit.splitFrom(
                bone,
                mask(pair[0], label, box, nx, ny),
                mask(pair[1], label, box, nx, ny),
                o.mmX(),
                o.mmY(),
                o.mmZ(),
                ct);
      } catch (BoneSplit.SplitException e) {
        continue;
      }
      if (isJoint(split.kept(), split.cut(), o)) {
        cut(split.kept(), level, ct, o, caller, out);
        cut(split.cut(), level, ct, o, caller, out);
        return;
      }
    }
    out.add(bone);
  }

  /** The CT value of each voxel of the bone on its block, {@link #NO_VALUE} beside the bone. */
  private static short[] values(BoxMask bone, HuSource ct) {
    VoxelBox box = bone.box();
    int nx = box.sizeX();
    int ny = box.sizeY();
    int nz = box.sizeZ();
    short[] value = new short[nx * ny * nz];
    for (int z = 0, i = 0; z < nz; z++) {
      for (int y = 0; y < ny; y++) {
        for (int x = 0; x < nx; x++, i++) {
          double v =
              bone.bits().get(x, y, z)
                  ? ct.huAt(box.x0() + x, box.y0() + y, box.z0() + z)
                  : Double.NaN;
          value[i] =
              Double.isNaN(v)
                  ? NO_VALUE
                  : (short) Math.clamp(Math.round(v), NO_VALUE + 1, Short.MAX_VALUE);
        }
      }
    }
    return value;
  }

  /**
   * Numbers in {@code label} the parts of the bone at or above {@code level}, voxels touching by a
   * face, an edge or a corner being one part, and returns them.
   */
  private static List<Shell> shells(short[] value, int[] label, int level, int nx, int ny, int nz) {
    Arrays.fill(label, 0);
    List<Shell> shells = new ArrayList<>();
    int[] stack = new int[1 << 12];
    for (int start = 0; start < value.length; start++) {
      if (value[start] < level || label[start] != 0) {
        continue;
      }
      int id = shells.size() + 1;
      int voxels = 0;
      int[] b = {nx, -1, ny, -1, nz, -1};
      int top = 0;
      stack[top++] = start;
      label[start] = id;
      while (top > 0) {
        int i = stack[--top];
        int x = i % nx;
        int y = (i / nx) % ny;
        int z = i / (nx * ny);
        voxels++;
        b[0] = Math.min(b[0], x);
        b[1] = Math.max(b[1], x);
        b[2] = Math.min(b[2], y);
        b[3] = Math.max(b[3], y);
        b[4] = Math.min(b[4], z);
        b[5] = Math.max(b[5], z);
        for (int pz = Math.max(0, z - 1); pz <= Math.min(nz - 1, z + 1); pz++) {
          for (int py = Math.max(0, y - 1); py <= Math.min(ny - 1, y + 1); py++) {
            for (int px = Math.max(0, x - 1); px <= Math.min(nx - 1, x + 1); px++) {
              int j = (pz * ny + py) * nx + px;
              if (value[j] >= level && label[j] == 0) {
                label[j] = id;
                if (top == stack.length) {
                  stack = Arrays.copyOf(stack, 2 * top);
                }
                stack[top++] = j;
              }
            }
          }
        }
      }
      shells.add(new Shell(id, voxels, b));
    }
    return shells;
  }

  /**
   * Of the largest shells, the two with most of one lying within {@link #GAP_MM} of the other, the
   * larger first; null when no two face each other over {@link #FACING_MM2}.
   */
  private static Shell[] facingPair(
      List<Shell> shells, int[] label, int nx, int ny, int nz, Options o, Thread caller) {
    double voxelMm2 = Math.pow(o.mmX() * o.mmY() * o.mmZ(), 2.0 / 3);
    int n = Math.min(shells.size(), MAX_SHELLS);
    Shell[] best = null;
    double bestMm2 = FACING_MM2;
    for (int a = 0; a < n - 1; a++) {
      Shell from = shells.get(a);
      int[] reach = reach(from.bounds(), nx, ny, nz, o);
      float[] distance = null;
      for (int b = a + 1; b < n; b++) {
        Shell to = shells.get(b);
        if (!meet(reach, to.bounds())) {
          continue;
        }
        if (distance == null) {
          distance = distanceTo(from, label, reach, nx, ny, o, caller);
        }
        double mm2 = near(to, label, distance, reach, nx, ny) * voxelMm2;
        if (mm2 >= bestMm2) {
          bestMm2 = mm2;
          best = new Shell[] {from, to};
        }
      }
    }
    return best;
  }

  /** The bounds grown by the gap on each axis, within the block. */
  private static int[] reach(int[] b, int nx, int ny, int nz, Options o) {
    int rx = (int) Math.ceil(GAP_MM / o.mmX()) + 1;
    int ry = (int) Math.ceil(GAP_MM / o.mmY()) + 1;
    int rz = (int) Math.ceil(GAP_MM / o.mmZ()) + 1;
    return new int[] {
      Math.max(0, b[0] - rx),
      Math.min(nx - 1, b[1] + rx),
      Math.max(0, b[2] - ry),
      Math.min(ny - 1, b[3] + ry),
      Math.max(0, b[4] - rz),
      Math.min(nz - 1, b[5] + rz)
    };
  }

  private static boolean meet(int[] a, int[] b) {
    return a[0] <= b[1]
        && b[0] <= a[1]
        && a[2] <= b[3]
        && b[2] <= a[3]
        && a[4] <= b[5]
        && b[4] <= a[5];
  }

  /** Squared distance in mm2 to the shell, over the block {@code reach}. */
  private static float[] distanceTo(
      Shell shell, int[] label, int[] reach, int nx, int ny, Options o, Thread caller) {
    int sx = reach[1] - reach[0] + 1;
    int sy = reach[3] - reach[2] + 1;
    int sz = reach[5] - reach[4] + 1;
    float[] g = new float[sx * sy * sz];
    for (int z = 0, i = 0; z < sz; z++) {
      for (int y = 0; y < sy; y++) {
        int row = ((reach[4] + z) * ny + reach[2] + y) * nx + reach[0];
        for (int x = 0; x < sx; x++, i++) {
          g[i] = label[row + x] == shell.id() ? 0 : AutoSplit.INF;
        }
      }
    }
    AutoSplit.squaredDistance(g, sx, sy, sz, o.mmX(), o.mmY(), o.mmZ(), caller);
    return g;
  }

  /** How many voxels of the shell lie within the gap, by the distances over {@code reach}. */
  private static int near(Shell shell, int[] label, float[] distance, int[] reach, int nx, int ny) {
    int sx = reach[1] - reach[0] + 1;
    int sy = reach[3] - reach[2] + 1;
    int[] b = shell.bounds();
    double limit = GAP_MM * GAP_MM + 1e-6;
    int count = 0;
    for (int z = Math.max(reach[4], b[4]); z <= Math.min(reach[5], b[5]); z++) {
      for (int y = Math.max(reach[2], b[2]); y <= Math.min(reach[3], b[3]); y++) {
        for (int x = Math.max(reach[0], b[0]); x <= Math.min(reach[1], b[1]); x++) {
          if (label[(z * ny + y) * nx + x] == shell.id()
              && distance[((z - reach[4]) * sy + y - reach[2]) * sx + x - reach[0]] <= limit) {
            count++;
          }
        }
      }
    }
    return count;
  }

  /** The voxels of a shell as a mask on the CT grid. */
  private static BoxMask mask(Shell shell, int[] label, VoxelBox box, int nx, int ny) {
    int[] b = shell.bounds();
    BitVolume bits = new BitVolume(b[1] - b[0] + 1, b[3] - b[2] + 1, b[5] - b[4] + 1);
    for (int z = b[4]; z <= b[5]; z++) {
      for (int y = b[2]; y <= b[3]; y++) {
        for (int x = b[0]; x <= b[1]; x++) {
          if (label[(z * ny + y) * nx + x] == shell.id()) {
            bits.set(x - b[0], y - b[2], z - b[4]);
          }
        }
      }
    }
    return new BoxMask(
        new VoxelBox(
            box.x0() + b[0],
            box.x0() + b[1],
            box.y0() + b[2],
            box.y0() + b[3],
            box.z0() + b[4],
            box.z0() + b[5]),
        bits);
  }

  /**
   * Whether two sides of a cut are two bones at a joint: both large enough, and the face they share
   * no more than {@link #MAX_FACE} times the smaller side's own size (its volume to the power of
   * two thirds).
   */
  static boolean isJoint(BoxMask kept, BoxMask cut, Options o) {
    double voxelMm3 = o.mmX() * o.mmY() * o.mmZ();
    double smallerMm3 = Math.min(kept.count(), cut.count()) * voxelMm3;
    return smallerMm3 >= MIN_PART_MM3
        && sharedFaceMm2(kept, cut, o) <= MAX_FACE * Math.pow(smallerMm3, 2.0 / 3);
  }

  /** The area of the voxel faces where the two masks meet, in mm2. */
  static double sharedFaceMm2(BoxMask a, BoxMask b, Options o) {
    VoxelBox box = b.box();
    long acrossX = 0;
    long acrossY = 0;
    long acrossZ = 0;
    for (int z = box.z0(); z <= box.z1(); z++) {
      for (int y = box.y0(); y <= box.y1(); y++) {
        for (int x = box.x0(); x <= box.x1(); x++) {
          if (!b.contains(x, y, z)) {
            continue;
          }
          acrossX += (a.contains(x - 1, y, z) ? 1 : 0) + (a.contains(x + 1, y, z) ? 1 : 0);
          acrossY += (a.contains(x, y - 1, z) ? 1 : 0) + (a.contains(x, y + 1, z) ? 1 : 0);
          acrossZ += (a.contains(x, y, z - 1) ? 1 : 0) + (a.contains(x, y, z + 1) ? 1 : 0);
        }
      }
    }
    return acrossX * o.mmY() * o.mmZ() + acrossY * o.mmX() * o.mmZ() + acrossZ * o.mmX() * o.mmY();
  }

  /** The voxel of the mask deepest under its surface, on the CT grid. */
  static int[] deepest(BoxMask mask, Options o, Thread caller) {
    VoxelBox box = mask.box();
    int nx = box.sizeX() + 2;
    int ny = box.sizeY() + 2;
    int nz = box.sizeZ() + 2;
    float[] g = new float[nx * ny * nz];
    for (int z = 0; z < box.sizeZ(); z++) {
      for (int y = 0; y < box.sizeY(); y++) {
        for (int x = 0; x < box.sizeX(); x++) {
          if (mask.bits().get(x, y, z)) {
            g[((z + 1) * ny + y + 1) * nx + x + 1] = AutoSplit.INF;
          }
        }
      }
    }
    AutoSplit.squaredDistance(g, nx, ny, nz, o.mmX(), o.mmY(), o.mmZ(), caller);
    int best = -1;
    for (int i = 0; i < g.length; i++) {
      if (g[i] > 0 && (best < 0 || g[i] > g[best])) {
        best = i;
      }
    }
    return new int[] {
      box.x0() + best % nx - 1, box.y0() + (best / nx) % ny - 1, box.z0() + best / (nx * ny) - 1
    };
  }
}
