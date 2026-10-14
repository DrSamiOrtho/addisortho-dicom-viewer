/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.surface;

import com.addisortho.viewer.bones.mesh.GaussianFilter;
import com.addisortho.viewer.bones.mesh.ScalarGrid;
import com.addisortho.viewer.bones.surface.Progress.Stage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * The smoothed scalar field whose iso-surface is the bone, on an isotropic grid around the bone's
 * label.
 *
 * <p>With a CT, the field is the Gaussian-smoothed CT inside the bone label grown by {@link
 * SurfaceOptions#growVoxels()} samples (never into another segment) and a value far below the
 * threshold elsewhere: the surface then follows the bone edge seen on the CT at the threshold, not
 * the label's voxel blocks, and stops between two bones that touch. The CT is smoothed before it is
 * masked; smoothing the masked field instead would blur the outside value into the bone edge, a
 * millimetre away, and shrink the surface. Without a CT, the field is the smoothed label occupancy
 * (1 inside, 0 outside) and the surface sits at 0.5.
 */
public final class BoneField {

  /** Occupancy iso value of the field without CT. */
  static final double OCCUPANCY_ISO = 0.5;

  private final ScalarGrid grid;
  private final boolean fromCt;
  private final double iso;
  private final float outsideValue;

  private BoneField(ScalarGrid grid, boolean fromCt, double iso, float outsideValue) {
    this.grid = grid;
    this.fromCt = fromCt;
    this.iso = iso;
    this.outsideValue = outsideValue;
  }

  /**
   * @param bone box holding every voxel of the bone label
   * @param ct the CT, or {@code null} to build the occupancy field
   * @throws CancellationException when the thread is interrupted
   */
  public static BoneField build(
      LabelSampler labels, Box bone, HuSampler ct, SurfaceOptions options, Progress progress) {
    ScalarGrid grid = newGrid(bone, options);
    Thread caller = Thread.currentThread();
    byte[] label = sampleLabels(grid, labels, caller, progress);
    if (ct == null) {
      fillOccupancy(grid, label);
      progress.update(Stage.SMOOTHING_FIELD, 0);
      GaussianFilter.smooth(grid, options.sigmaMm());
      return new BoneField(grid, false, OCCUPANCY_ISO, 0);
    }
    float outside = (float) Math.min(-1000, options.thresholdHu() - 1000);
    sampleCt(grid, ct, outside, caller, progress);
    progress.update(Stage.SMOOTHING_FIELD, 0);
    GaussianFilter.smooth(grid, options.sigmaMm());
    mask(grid, label, ball(options.growVoxels()), outside, caller);
    return new BoneField(grid, true, options.thresholdHu(), outside);
  }

  public ScalarGrid grid() {
    return grid;
  }

  public double iso() {
    return iso;
  }

  /** {@code false} for the occupancy field built without CT. */
  public boolean fromCt() {
    return fromCt;
  }

  /** Field value outside the grown bone label. */
  public float outsideValue() {
    return outsideValue;
  }

  /**
   * An isotropic grid covering the bone plus room for growing, smoothing and one outside layer. The
   * spacing grows until the grid fits in {@link SurfaceOptions#maxVoxels()}.
   */
  static ScalarGrid newGrid(Box bone, SurfaceOptions options) {
    double s = options.spacingMm();
    for (int attempt = 0; ; attempt++) {
      double margin = (options.growVoxels() + 2) * s + 3 * Math.max(0, options.sigmaMm());
      Box box = bone.expand(margin);
      int nx = (int) Math.ceil((box.maxX() - box.minX()) / s) + 1;
      int ny = (int) Math.ceil((box.maxY() - box.minY()) / s) + 1;
      int nz = (int) Math.ceil((box.maxZ() - box.minZ()) / s) + 1;
      double count = (double) nx * ny * nz;
      if (count <= options.maxVoxels() || attempt > 20) {
        return new ScalarGrid(
            nx, ny, nz, new double[] {box.minX(), box.minY(), box.minZ()}, new double[] {s, s, s});
      }
      s *= Math.cbrt(count / options.maxVoxels()) * 1.01;
    }
  }

  private static byte[] sampleLabels(
      ScalarGrid grid, LabelSampler labels, Thread caller, Progress progress) {
    int nx = grid.sizeX();
    int ny = grid.sizeY();
    int nz = grid.sizeZ();
    byte[] label = new byte[nx * ny * nz];
    AtomicInteger done = new AtomicInteger();
    IntStream.range(0, nz)
        .parallel()
        .forEach(
            k -> {
              checkCancelled(caller);
              double z = grid.z(k);
              for (int j = 0; j < ny; j++) {
                double y = grid.y(j);
                int row = grid.index(0, j, k);
                for (int i = 0; i < nx; i++) {
                  int l = labels.labelAt(grid.x(i), y, z);
                  label[row + i] =
                      (byte) (l == LabelSampler.BONE || l == LabelSampler.OTHER ? l : 0);
                }
              }
              progress.update(Stage.FIELD, 0.5 * done.incrementAndGet() / nz);
            });
    return label;
  }

  private static void fillOccupancy(ScalarGrid grid, byte[] label) {
    for (int k = 0; k < grid.sizeZ(); k++) {
      for (int j = 0; j < grid.sizeY(); j++) {
        for (int i = 0; i < grid.sizeX(); i++) {
          grid.set(i, j, k, label[grid.index(i, j, k)] == LabelSampler.BONE ? 1 : 0);
        }
      }
    }
  }

  private static void sampleCt(
      ScalarGrid grid, HuSampler ct, float outside, Thread caller, Progress progress) {
    int nz = grid.sizeZ();
    AtomicInteger done = new AtomicInteger();
    IntStream.range(0, nz)
        .parallel()
        .forEach(
            k -> {
              checkCancelled(caller);
              double z = grid.z(k);
              for (int j = 0; j < grid.sizeY(); j++) {
                double y = grid.y(j);
                for (int i = 0; i < grid.sizeX(); i++) {
                  double hu = ct.huAt(grid.x(i), y, z);
                  grid.set(i, j, k, Double.isNaN(hu) ? outside : (float) hu);
                }
              }
              progress.update(Stage.FIELD, 0.5 + 0.5 * done.incrementAndGet() / nz);
            });
  }

  /** Sets {@code outside} wherever the sample is neither bone nor within the grown bone. */
  private static void mask(
      ScalarGrid grid, byte[] label, int[][] ball, float outside, Thread caller) {
    IntStream.range(0, grid.sizeZ())
        .parallel()
        .forEach(
            k -> {
              checkCancelled(caller);
              for (int j = 0; j < grid.sizeY(); j++) {
                for (int i = 0; i < grid.sizeX(); i++) {
                  int l = label[grid.index(i, j, k)];
                  boolean inside =
                      l == LabelSampler.BONE
                          || (l == LabelSampler.NONE && nearBone(grid, label, i, j, k, ball));
                  if (!inside) {
                    grid.set(i, j, k, outside);
                  }
                }
              }
            });
  }

  /** {@code true} when a bone sample lies within the ball offsets of (i, j, k). */
  private static boolean nearBone(
      ScalarGrid grid, byte[] label, int i, int j, int k, int[][] ball) {
    for (int[] o : ball) {
      int x = i + o[0];
      int y = j + o[1];
      int z = k + o[2];
      if (x >= 0
          && y >= 0
          && z >= 0
          && x < grid.sizeX()
          && y < grid.sizeY()
          && z < grid.sizeZ()
          && label[grid.index(x, y, z)] == LabelSampler.BONE) {
        return true;
      }
    }
    return false;
  }

  /** Offsets within {@code radius} samples (Euclidean), nearest first, excluding the centre. */
  static int[][] ball(int radius) {
    List<int[]> offsets = new ArrayList<>();
    for (int dz = -radius; dz <= radius; dz++) {
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dx = -radius; dx <= radius; dx++) {
          int d2 = dx * dx + dy * dy + dz * dz;
          if (d2 > 0 && d2 <= radius * radius) {
            offsets.add(new int[] {dx, dy, dz});
          }
        }
      }
    }
    offsets.sort((a, b) -> Integer.compare(norm2(a), norm2(b)));
    return offsets.toArray(new int[0][]);
  }

  private static int norm2(int[] o) {
    return o[0] * o[0] + o[1] * o[1] + o[2] * o[2];
  }

  static void checkCancelled(Thread caller) {
    if (caller.isInterrupted()) {
      throw new CancellationException();
    }
  }
}
