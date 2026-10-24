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
 * voxels at or above a bone threshold, on a coarse grid of blocks where a block is bone when any of
 * its voxels is (so a thin cortex or a small fragment is never missed), grouped into connected
 * pieces. The scanner table (a thin, wide plate running the whole length of the scan, behind the
 * patient or in front of a prone one) and specks under a fixed size are left out, and the box of
 * the rest is widened by a margin. Every other piece counts, however small next to the main bone:
 * an avulsed fragment, a sesamoid or a scapula at the edge of the field stays in the box.
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

  /** ... and this share of the scan's length (the table runs through every slice). */
  static final double TABLE_LENGTH_SHARE = 0.9;

  /** Pieces smaller than this are specks (noise, a calcified vessel), in mm³. */
  static final double MIN_PIECE_MM3 = 30;

  /** The values of one texture slice, row by row, as the renderer has them. */
  @FunctionalInterface
  public interface SliceSource {
    float[] slice(int z);
  }

  /** One connected piece of the sample. */
  record Piece(int voxels, int[] min, int[] max) {}

  private BoneExtent() {}

  /**
   * @param sampler CT values of the texture voxels, in HU; a {@link TextureField} is read slice by
   *     slice
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
    return find(slicesOf(sampler, texture), texture, texelMm, thresholdHu, marginMm, cancelled);
  }

  /** The sampler read slice by slice: a {@link TextureField} gives its slices as they are. */
  static SliceSource slicesOf(VoxelSampler sampler, Vector3i texture) {
    if (sampler instanceof TextureField field) {
      return field::textureSlice;
    }
    return z -> {
      float[] slice = new float[texture.x * texture.y];
      for (int y = 0, i = 0; y < texture.y; y++) {
        for (int x = 0; x < texture.x; x++, i++) {
          slice[i] = (float) sampler.valueAt(x, y, z);
        }
      }
      return slice;
    };
  }

  /** As above, from whole slices. */
  public static Optional<CropBox> find(
      SliceSource slices,
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
    for (int tz = 0; tz < texture.z; tz++) {
      if (cancelled.getAsBoolean()) {
        return Optional.empty();
      }
      float[] slice = slices.slice(tz);
      int base = (tz / stride) * ny * nx;
      for (int ty = 0, i = 0; ty < texture.y; ty++) {
        int row = base + (ty / stride) * nx;
        for (int tx = 0; tx < texture.x; tx++, i++) {
          if (slice[i] >= thresholdHu) {
            bone[row + tx / stride] = true;
          }
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
    // Block bounds back in texture voxels, the last block cut at the texture's edge.
    int[] size = {texture.x, texture.y, texture.z};
    double[] mm = {texelMm.x, texelMm.y, texelMm.z};
    double[] lo = new double[3];
    double[] hi = new double[3];
    for (int a = 0; a < 3; a++) {
      double margin = mm[a] > 0 ? marginMm / mm[a] : 0;
      double first = (double) min[a] * stride;
      double end = Math.min((double) (max[a] + 1) * stride, size[a]);
      lo[a] = (first - margin) / size[a];
      hi[a] = (end + margin) / size[a];
    }
    return Optional.of(new CropBox(lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]));
  }

  /** The pieces that are bone: not the table, not specks. */
  static List<Piece> keep(List<Piece> pieces, int[] size, Vector3d sampleMm) {
    double blockMm3 = sampleMm.x * sampleMm.y * sampleMm.z;
    List<Piece> bones = new ArrayList<>();
    for (Piece p : pieces) {
      if (!isTable(p, size, sampleMm) && p.voxels() * blockMm3 >= MIN_PIECE_MM3) {
        bones.add(p);
      }
    }
    return bones;
  }

  /**
   * A thin, wide plate through the whole length of the scan: the table, behind a supine patient or
   * in front of a prone one. A scapula or a pelvic wing is thicker front to back or shorter.
   */
  static boolean isTable(Piece p, int[] size, Vector3d sampleMm) {
    double thickness = (p.max()[1] - p.min()[1] + 1) * sampleMm.y;
    double widthShare = (p.max()[0] - p.min()[0] + 1) / (double) size[0];
    double lengthShare = (p.max()[2] - p.min()[2] + 1) / (double) size[2];
    return thickness <= TABLE_THICKNESS_MM
        && widthShare >= TABLE_WIDTH_SHARE
        && lengthShare >= TABLE_LENGTH_SHARE;
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
