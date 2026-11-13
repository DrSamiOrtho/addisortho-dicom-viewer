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
import com.addisortho.viewer.bones.grow.AutoSplit.Shown;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Gives the bones found their thin and weak bone. The bones are told apart on bone of 200 HU and
 * above, which leaves holes where the cortex is thin or the bone is osteopenic: the plate of the
 * iliac wing, a femoral head, the front of the sacrum. Here each bone grows, a voxel at a time and
 * every bone in turn, into the voxels beside it that the 3D view still shows and that read as weak
 * bone, {@value #REACH_MM} mm at most, and what the grown bone then encloses in a slice is filled.
 * Which voxel is whose bone is not changed: no bone loses a voxel and no two bones are joined.
 */
public final class WeakBone {

  /** Weakest bone taken in, in HU: the level the bone-only 3D view keeps weak bone from. */
  public static final double WEAK_HU = 150;

  /**
   * Farthest a bone grows into weak bone, in mm, measured along the weak bone: far enough to close
   * the thin plate of an iliac wing from its rim, 4 cm across.
   */
  public static final double REACH_MM = 20;

  private WeakBone() {}

  /** One bone as it grows: its voxels in a block wide enough for the growth. */
  private static final class Growing {
    final VoxelBox block;
    final BitVolume own;
    final BitVolume tested;
    IntList front = new IntList();

    Growing(BoxMask mask, VoxelBox within, int px, int py, int pz) {
      VoxelBox b = mask.box();
      block =
          new VoxelBox(b.x0() - px, b.x1() + px, b.y0() - py, b.y1() + py, b.z0() - pz, b.z1() + pz)
              .intersect(within);
      own = new BitVolume(block.sizeX(), block.sizeY(), block.sizeZ());
      tested = own.emptyCopy();
      for (int z = b.z0(); z <= b.z1(); z++) {
        for (int y = b.y0(); y <= b.y1(); y++) {
          for (int x = b.x0(); x <= b.x1(); x++) {
            if (mask.contains(x, y, z) && block.contains(x, y, z)) {
              own.set(x - block.x0(), y - block.y0(), z - block.z0());
              front.add(index(x, y, z));
            }
          }
        }
      }
    }

    int index(int x, int y, int z) {
      return ((z - block.z0()) * block.sizeY() + (y - block.y0())) * block.sizeX()
          + (x - block.x0());
    }

    boolean has(int x, int y, int z) {
      return block.contains(x, y, z) && own.get(x - block.x0(), y - block.y0(), z - block.z0());
    }
  }

  /** A growing list of ints. */
  private static final class IntList {
    int[] values = new int[256];
    int size;

    void add(int v) {
      if (size == values.length) {
        values = java.util.Arrays.copyOf(values, size * 2);
      }
      values[size++] = v;
    }
  }

  /**
   * The bones of {@code found} with the weak bone beside them, see the class.
   *
   * @param within the block of the CT grid that was searched
   * @throws java.util.concurrent.CancellationException when the calling thread is interrupted
   */
  public static Result grow(
      Result found, VoxelBox within, Supplier<HuSource> hu, Shown shown, Options o) {
    long start = System.nanoTime();
    Thread caller = Thread.currentThread();
    List<Piece> pieces = found.pieces();
    if (pieces.isEmpty()) {
      return found;
    }
    double step = Math.min(o.mmX(), o.mmY());
    int rounds = (int) Math.ceil(REACH_MM / step);
    int px = (int) Math.ceil(REACH_MM / o.mmX());
    int py = (int) Math.ceil(REACH_MM / o.mmY());
    int pz = (int) Math.ceil(REACH_MM / Math.abs(o.mmZ()));
    List<Growing> bones = new ArrayList<>();
    for (Piece p : pieces) {
      bones.add(new Growing(p.mask(), within, px, py, pz));
    }
    HuSource ct = hu.get();
    double acrossSlices = 0;
    for (int round = 1; round <= rounds; round++) {
      RegionGrow.checkCancelled(caller);
      // A step to the next slice is as long as the slices are apart: taken when the growth in the
      // slice has gone that far.
      acrossSlices += step;
      boolean slices = acrossSlices >= Math.abs(o.mmZ());
      if (slices) {
        acrossSlices -= Math.abs(o.mmZ());
      }
      boolean any = false;
      for (int i = 0; i < bones.size(); i++) {
        any |= grow(bones, i, slices, ct, shown);
      }
      if (!any && !slicesPending(acrossSlices, step, o, rounds - round)) {
        break;
      }
    }
    List<Piece> grown = new ArrayList<>();
    for (int i = 0; i < bones.size(); i++) {
      RegionGrow.checkCancelled(caller);
      Growing g = bones.get(i);
      BitVolume filled = RegionGrow.fillHoles(g.own, caller);
      takeBack(filled, g, bones, i, shown);
      Piece p = pieces.get(i);
      BoxMask mask = BoxMask.trimmed(g.block, filled);
      grown.add(mask == null ? p : new Piece(mask, p.anchor(), p.fragments()));
    }
    return new Result(
        List.copyOf(grown),
        found.fragments(),
        found.specks(),
        found.factor(),
        found.millis() + (System.nanoTime() - start) / 1_000_000,
        found.jointsSkipped());
  }

  /** Whether a round still to come steps to the next slice, so the growth is not over yet. */
  private static boolean slicesPending(double across, double step, Options o, int roundsLeft) {
    return across + roundsLeft * step >= Math.abs(o.mmZ());
  }

  private static final int[][] STEPS = {
    {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
  };

  /** One round of bone {@code i}: the weak bone beside its front joins it. */
  private static boolean grow(
      List<Growing> bones, int i, boolean slices, HuSource ct, Shown shown) {
    Growing g = bones.get(i);
    VoxelBox b = g.block;
    int sx = b.sizeX();
    int sy = b.sizeY();
    IntList next = new IntList();
    IntList stay = new IntList();
    int directions = slices ? 6 : 4;
    for (int f = 0; f < g.front.size; f++) {
      int at = g.front.values[f];
      int x = b.x0() + at % sx;
      int y = b.y0() + (at / sx) % sy;
      int z = b.z0() + at / (sx * sy);
      for (int d = 0; d < directions; d++) {
        int nx = x + STEPS[d][0];
        int ny = y + STEPS[d][1];
        int nz = z + STEPS[d][2];
        if (!b.contains(nx, ny, nz)) {
          continue;
        }
        int lx = nx - b.x0();
        int ly = ny - b.y0();
        int lz = nz - b.z0();
        if (g.own.get(lx, ly, lz) || g.tested.get(lx, ly, lz)) {
          continue;
        }
        g.tested.set(lx, ly, lz);
        if (!shown.at(nx, ny, nz)
            || !(ct.huAt(nx, ny, nz) >= WEAK_HU)
            || taken(bones, i, nx, ny, nz)) {
          continue;
        }
        g.own.set(lx, ly, lz);
        next.add(g.index(nx, ny, nz));
      }
      if (!slices) {
        stay.add(at); // its steps to the next slices are still to be taken
      }
    }
    boolean grew = next.size > 0;
    for (int k = 0; k < stay.size; k++) {
      next.add(stay.values[k]);
    }
    g.front = next;
    return grew;
  }

  private static boolean taken(List<Growing> bones, int except, int x, int y, int z) {
    for (int j = 0; j < bones.size(); j++) {
      if (j != except && bones.get(j).has(x, y, z)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Clears from what the fill added to a bone the voxels of another bone and the voxels the 3D view
   * does not show.
   */
  private static void takeBack(
      BitVolume filled, Growing g, List<Growing> bones, int i, Shown shown) {
    VoxelBox b = g.block;
    for (int z = 0; z < b.sizeZ(); z++) {
      for (int y = 0; y < b.sizeY(); y++) {
        for (int x = 0; x < b.sizeX(); x++) {
          if (filled.get(x, y, z) && !g.own.get(x, y, z)) {
            int gx = b.x0() + x;
            int gy = b.y0() + y;
            int gz = b.z0() + z;
            if (!shown.at(gx, gy, gz) || taken(bones, i, gx, gy, gz)) {
              filled.clear(x, y, z);
            }
          }
        }
      }
    }
  }
}
