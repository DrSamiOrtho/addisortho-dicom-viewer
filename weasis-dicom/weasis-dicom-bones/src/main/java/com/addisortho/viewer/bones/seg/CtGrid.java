/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.seg;

import com.addisortho.viewer.bones.grow.VoxelBox;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The voxel grid of a CT series as acquired: one slice per image, {@code columns} by {@code rows}
 * pixels, every slice with the same orientation and pixel spacing. Voxel {@code (x, y, z)} is
 * column x and row y of slice z; slices are ordered along the normal (row direction cross column
 * direction). Positions are in mm, DICOM patient space (LPS).
 *
 * @param rowSpacing distance between rows, the first value of PixelSpacing
 * @param columnSpacing distance between columns, the second value of PixelSpacing
 */
public record CtGrid(
    int columns,
    int rows,
    double[] rowDirection,
    double[] columnDirection,
    double rowSpacing,
    double columnSpacing,
    List<Slice> slices) {

  /** Tolerance on orientation cosines and spacings when checking that the images form a grid. */
  private static final double TOLERANCE = 1e-3;

  /**
   * One image of the series.
   *
   * @param position ImagePositionPatient, centre of the first transmitted pixel
   * @param frameNumber frame of a multi-frame image, from 1; 0 for a single-frame image
   */
  public record Slice(
      double[] position, String sopClassUid, String sopInstanceUid, int frameNumber) {}

  /** The geometry of one image, as read from its attributes. */
  public record Image(
      int columns,
      int rows,
      double[] orientation,
      double[] pixelSpacing,
      double[] position,
      String sopClassUid,
      String sopInstanceUid,
      int frameNumber) {}

  public CtGrid {
    if (columns <= 0 || rows <= 0 || slices.isEmpty() || !(rowSpacing > 0 && columnSpacing > 0)) {
      throw new IllegalArgumentException("Empty CT grid");
    }
    rowDirection = rowDirection.clone();
    columnDirection = columnDirection.clone();
    slices = List.copyOf(slices);
  }

  /**
   * Builds the grid of a series, sorting its images along the normal.
   *
   * @return nothing when the images differ in size, orientation or spacing, lack geometry, or share
   *     their positions
   */
  public static Optional<CtGrid> of(List<Image> images) {
    if (images.isEmpty()) {
      return Optional.empty();
    }
    Image first = images.getFirst();
    if (!complete(first)) {
      return Optional.empty();
    }
    for (Image image : images) {
      if (!complete(image)
          || image.columns() != first.columns()
          || image.rows() != first.rows()
          || !close(image.orientation(), first.orientation())
          || !close(image.pixelSpacing(), first.pixelSpacing())) {
        return Optional.empty();
      }
    }
    double[] row = Arrays.copyOfRange(first.orientation(), 0, 3);
    double[] column = Arrays.copyOfRange(first.orientation(), 3, 6);
    double[] normal = cross(row, column);
    List<Slice> slices = new ArrayList<>(images.size());
    for (Image image : images) {
      slices.add(
          new Slice(
              image.position().clone(),
              image.sopClassUid(),
              image.sopInstanceUid(),
              image.frameNumber()));
    }
    slices.sort(Comparator.comparingDouble(s -> dot(s.position(), normal)));
    CtGrid grid =
        new CtGrid(
            first.columns(),
            first.rows(),
            row,
            column,
            first.pixelSpacing()[0],
            first.pixelSpacing()[1],
            slices);
    // Slices stacked at one position (a localiser, or a series holding two reconstructions).
    return grid.sliceSpacing() > TOLERANCE ? Optional.of(grid) : Optional.empty();
  }

  private static boolean complete(Image image) {
    return image.columns() > 0
        && image.rows() > 0
        && image.orientation() != null
        && image.orientation().length == 6
        && image.pixelSpacing() != null
        && image.pixelSpacing().length >= 2
        && image.pixelSpacing()[0] > 0
        && image.pixelSpacing()[1] > 0
        && image.position() != null
        && image.position().length == 3
        && image.sopInstanceUid() != null
        && image.sopClassUid() != null;
  }

  private static boolean close(double[] a, double[] b) {
    for (int i = 0; i < Math.min(a.length, b.length); i++) {
      if (Math.abs(a[i] - b[i]) > TOLERANCE) {
        return false;
      }
    }
    return true;
  }

  public int sliceCount() {
    return slices.size();
  }

  public Slice slice(int z) {
    return slices.get(z);
  }

  /** Unit normal of the slices, row direction cross column direction. */
  public double[] normal() {
    double[] n = cross(rowDirection, columnDirection);
    double length = Math.sqrt(dot(n, n));
    return new double[] {n[0] / length, n[1] / length, n[2] / length};
  }

  /** Median distance between consecutive slices; the slice thickness is not known here. */
  public double sliceSpacing() {
    if (slices.size() < 2) {
      return Math.min(rowSpacing, columnSpacing);
    }
    double[] n = normal();
    double[] gaps = new double[slices.size() - 1];
    for (int z = 1; z < slices.size(); z++) {
      gaps[z - 1] = dot(slices.get(z).position(), n) - dot(slices.get(z - 1).position(), n);
    }
    Arrays.sort(gaps);
    return gaps[gaps.length / 2];
  }

  public double voxelVolumeMm3() {
    return rowSpacing * columnSpacing * sliceSpacing();
  }

  /** Patient position of the centre of voxel {@code (x, y, z)}. */
  public double[] voxelToLps(double x, double y, int z) {
    double[] p = slices.get(z).position();
    double cx = x * columnSpacing;
    double ry = y * rowSpacing;
    return new double[] {
      p[0] + cx * rowDirection[0] + ry * columnDirection[0],
      p[1] + cx * rowDirection[1] + ry * columnDirection[1],
      p[2] + cx * rowDirection[2] + ry * columnDirection[2]
    };
  }

  /**
   * The voxel nearest to a patient position: the nearest slice along the normal, then the nearest
   * pixel of that slice.
   *
   * @return nothing when the position is more than half a voxel outside the grid
   */
  public Optional<int[]> nearestVoxel(double[] lps) {
    double[] n = normal();
    double along = dot(lps, n);
    int best = -1;
    double bestGap = Double.POSITIVE_INFINITY;
    for (int z = 0; z < slices.size(); z++) {
      double gap = Math.abs(dot(slices.get(z).position(), n) - along);
      if (gap < bestGap) {
        bestGap = gap;
        best = z;
      }
    }
    if (bestGap > Math.max(sliceSpacing(), 1e-6) / 2 + TOLERANCE) {
      return Optional.empty();
    }
    double[] d = sub(lps, slices.get(best).position());
    int x = (int) Math.round(dot(d, rowDirection) / columnSpacing);
    int y = (int) Math.round(dot(d, columnDirection) / rowSpacing);
    if (x < 0 || y < 0 || x >= columns || y >= rows) {
      return Optional.empty();
    }
    return Optional.of(new int[] {x, y, best});
  }

  /**
   * The voxels of the grid inside an axis-aligned box of patient space, as the smallest block of
   * the grid holding them: exact when the grid is aligned with the patient axes, a little larger
   * when it is oblique.
   *
   * @param min lowest x, y and z of the box, mm
   * @param max highest x, y and z of the box, mm
   * @return nothing when the box misses the grid
   */
  public Optional<VoxelBox> boxOf(double[] min, double[] max) {
    double[] n = normal();
    double[] origin = slices.getFirst().position();
    double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
    double[] hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
    for (int c = 0; c < 8; c++) {
      double[] corner = {
        (c & 1) == 0 ? min[0] : max[0],
        (c & 2) == 0 ? min[1] : max[1],
        (c & 4) == 0 ? min[2] : max[2]
      };
      double[] d = sub(corner, origin);
      double[] v = {
        dot(d, rowDirection) / columnSpacing, dot(d, columnDirection) / rowSpacing, dot(corner, n)
      };
      for (int a = 0; a < 3; a++) {
        lo[a] = Math.min(lo[a], v[a]);
        hi[a] = Math.max(hi[a], v[a]);
      }
    }
    int z0 = Integer.MAX_VALUE;
    int z1 = Integer.MIN_VALUE;
    for (int z = 0; z < slices.size(); z++) {
      double along = dot(slices.get(z).position(), n);
      if (along >= lo[2] - TOLERANCE && along <= hi[2] + TOLERANCE) {
        z0 = Math.min(z0, z);
        z1 = Math.max(z1, z);
      }
    }
    int x0 = Math.max(0, (int) Math.ceil(lo[0] - TOLERANCE));
    int x1 = Math.min(columns - 1, (int) Math.floor(hi[0] + TOLERANCE));
    int y0 = Math.max(0, (int) Math.ceil(lo[1] - TOLERANCE));
    int y1 = Math.min(rows - 1, (int) Math.floor(hi[1] + TOLERANCE));
    if (z0 > z1 || x0 > x1 || y0 > y1) {
      return Optional.empty();
    }
    return Optional.of(new VoxelBox(x0, x1, y0, y1, z0, z1));
  }

  static double dot(double[] a, double[] b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  }

  private static double[] sub(double[] a, double[] b) {
    return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
  }

  private static double[] cross(double[] a, double[] b) {
    return new double[] {
      a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
    };
  }
}
