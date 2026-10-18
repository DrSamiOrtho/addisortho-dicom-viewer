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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * Finds the crop box that holds the bone of a CT, for the 3D view to open on the bone alone: the
 * voxels at or above a bone threshold, on a coarse sample of the volume, grouped into connected
 * pieces; the scanner table (a thin, wide plate behind the patient) and specks too small to be bone
 * are left out, and the box of the rest is widened by a margin.
 *
 * <p>Texture axes, as the crop box: x right to left, y front to back, z along the slices. The
 * result is a guess for display only and is always undoable; it changes no voxel.
 */
public final class BoneExtent {

  /** CT value from which a voxel counts as bone for the crop: cortical bone, above the table. */
  public static final double DEFAULT_THRESHOLD_HU = 300;

  /** Margin added on each side of the bone, in mm. */
  public static final double DEFAULT_MARGIN_MM = 8;

  /** Longest side of the coarse sample, in samples. */
  static final int SAMPLE_SIDE = 160;

  /** A piece thinner than this front to back may be the table. */
  static final double TABLE_THICKNESS_MM = 25;

  /** ... when it also spans this share of the width ... */
  static final double TABLE_WIDTH_SHARE = 0.4;

  /** ... and its centre lies behind this share of the depth (the table is behind the back). */
  static final double TABLE_BACK_SHARE = 0.5;

  /** Pieces smaller than this share of the largest piece are specks, not bone. */
  static final double MIN_PIECE_SHARE = 0.02;

  /** Pieces smaller than this are specks whatever the largest piece, in mm³. */
  static final double MIN_PIECE_MM3 = 300;

  /** One connected piece of the sample. */
  record Piece(int voxels, int[] min, int[] max) {}

  private BoneExtent() {}

  /**
   * @param sampler CT values of the texture voxels, in HU
   * @param texture size of the texture, in voxels
   * @param texelMm size of a texture voxel, in mm
   * @param thresholdHu bone threshold
   * @param marginMm margin around the bone
   * @param cancelled stops the search early, returning empty
   * @return the crop box of the bone, or empty when no bone is found
   */
  public static Optional<CropBox> find(
      VoxelSampler sampler,
      Vector3i texture,
      Vector3d texelMm,
      double thresholdHu,
      double marginMm,
      BooleanSupplier cancelled) {
    int maxSide = Math.max(texture.x, Math.max(texture.y, texture.z));
    int stride = Math.max(1, (int) Math.ceil(maxSide / (double) SAMPLE_SIDE));
    int nx = ceilDiv(texture.x, stride);
    int ny = ceilDiv(texture.y, stride);
    int nz = ceilDiv(texture.z, stride);
    boolean[] bone = new boolean[nx * ny * nz];
    for (int z = 0; z < nz; z++) {
      if (cancelled.getAsBoolean()) {
        return Optional.empty();
      }
      int tz = Math.min(texture.z - 1, z * stride + stride / 2);
      for (int y = 0; y < ny; y++) {
        int ty = Math.min(texture.y - 1, y * stride + stride / 2);
        for (int x = 0; x < nx; x++) {
          int tx = Math.min(texture.x - 1, x * stride + stride / 2);
          double v = sampler.valueAt(tx, ty, tz);
          bone[(z * ny + y) * nx + x] = v >= thresholdHu;
        }
      }
    }
    Vector3d sampleMm = new Vector3d(texelMm).mul(stride);
    List<Piece> pieces = pieces(bone, nx, ny, nz);
    List<Piece> kept = keep(pieces, new int[] {nx, ny, nz}, sampleMm);
    if (kept.isEmpty()) {
      return Optional.empty();
    }
    int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
    int[] max = {-1, -1, -1};
    for (Piece p : kept) {
      for (int a = 0; a < 3; a++) {
        min[a] = Math.min(min[a], p.min()[a]);
        max[a] = Math.max(max[a], p.max()[a]);
      }
    }
    double[] size = {nx, ny, nz};
    double[] mm = {sampleMm.x, sampleMm.y, sampleMm.z};
    double[] lo = new double[3];
    double[] hi = new double[3];
    for (int a = 0; a < 3; a++) {
      double margin = mm[a] > 0 ? marginMm / mm[a] : 0;
      lo[a] = (min[a] - margin) / size[a];
      hi[a] = (max[a] + 1 + margin) / size[a];
    }
    return Optional.of(new CropBox(lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]));
  }

  /** The pieces that are bone: not the table, not specks. */
  static List<Piece> keep(List<Piece> pieces, int[] size, Vector3d sampleMm) {
    double voxelMm3 = sampleMm.x * sampleMm.y * sampleMm.z;
    List<Piece> bones = new ArrayList<>();
    for (Piece p : pieces) {
      if (!isTable(p, size, sampleMm)) {
        bones.add(p);
      }
    }
    int largest = bones.stream().mapToInt(Piece::voxels).max().orElse(0);
    double minVoxels = Math.max(largest * MIN_PIECE_SHARE, MIN_PIECE_MM3 / voxelMm3);
    bones.removeIf(p -> p.voxels() < minVoxels);
    return bones;
  }

  static boolean isTable(Piece p, int[] size, Vector3d sampleMm) {
    double thickness = (p.max()[1] - p.min()[1] + 1) * sampleMm.y;
    double widthShare = (p.max()[0] - p.min()[0] + 1) / (double) size[0];
    double centreY = (p.min()[1] + p.max()[1] + 1) / 2.0 / size[1];
    return thickness <= TABLE_THICKNESS_MM
        && widthShare >= TABLE_WIDTH_SHARE
        && centreY >= TABLE_BACK_SHARE;
  }

  /** The 6-connected pieces of {@code bone}, with their size and bounds. */
  static List<Piece> pieces(boolean[] bone, int nx, int ny, int nz) {
    List<Piece> pieces = new ArrayList<>();
    boolean[] seen = new boolean[bone.length];
    int[] queue = new int[bone.length];
    int slice = nx * ny;
    for (int start = 0; start < bone.length; start++) {
      if (!bone[start] || seen[start]) {
        continue;
      }
      int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
      int[] max = {-1, -1, -1};
      int head = 0;
      int tail = 0;
      queue[tail++] = start;
      seen[start] = true;
      while (head < tail) {
        int i = queue[head++];
        int z = i / slice;
        int y = (i % slice) / nx;
        int x = i % nx;
        min[0] = Math.min(min[0], x);
        min[1] = Math.min(min[1], y);
        min[2] = Math.min(min[2], z);
        max[0] = Math.max(max[0], x);
        max[1] = Math.max(max[1], y);
        max[2] = Math.max(max[2], z);
        if (x > 0) {
          tail = visit(bone, seen, queue, tail, i - 1);
        }
        if (x < nx - 1) {
          tail = visit(bone, seen, queue, tail, i + 1);
        }
        if (y > 0) {
          tail = visit(bone, seen, queue, tail, i - nx);
        }
        if (y < ny - 1) {
          tail = visit(bone, seen, queue, tail, i + nx);
        }
        if (z > 0) {
          tail = visit(bone, seen, queue, tail, i - slice);
        }
        if (z < nz - 1) {
          tail = visit(bone, seen, queue, tail, i + slice);
        }
      }
      pieces.add(new Piece(tail, min, max));
    }
    return pieces;
  }

  private static int visit(boolean[] bone, boolean[] seen, int[] queue, int tail, int i) {
    if (bone[i] && !seen[i]) {
      seen[i] = true;
      queue[tail++] = i;
    }
    return tail;
  }

  private static int ceilDiv(int a, int b) {
    return (a + b - 1) / b;
  }
}
