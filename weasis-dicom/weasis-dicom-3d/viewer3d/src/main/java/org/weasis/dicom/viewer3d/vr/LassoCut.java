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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.List;
import java.util.stream.IntStream;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/**
 * The scalpel: removes the voxels whose projection through the current camera falls inside (or
 * outside) a closed area drawn over the view, through the whole depth of the volume.
 *
 * <p>Each grid row is projected by interpolating its two end points in clip space, which is exact
 * since a projective transform is affine in clip space, and a row whose projected segment misses
 * the area's bounding box is settled without visiting its voxels. The area itself is rasterised
 * once at the viewport's resolution, so the test per voxel is a bit lookup.
 */
public final class LassoCut {

  /** Which side of the area is cut away. */
  public enum Side {
    INSIDE,
    OUTSIDE
  }

  /**
   * @param diff the voxels to remove, none of which is already removed
   * @param millis time taken
   * @param rowsVisited rows whose voxels were projected one by one
   * @param rowsSkipped rows settled from their end points alone
   */
  public record Result(MaskDiff diff, long millis, long rowsVisited, long rowsSkipped) {}

  private static final double MIN_CLIP_W = 1e-9;

  private LassoCut() {}

  /**
   * Computes the cut.
   *
   * @param space the grid of {@code kept} and the volume's texture geometry
   * @param viewProjection the camera's projection times view matrix, the forward transform of the
   *     one the shaders invert
   * @param width viewport width in the pixels of {@code polygon}
   * @param height viewport height in the pixels of {@code polygon}
   * @param polygon the closed area, at least three points, in viewport pixels with y down
   * @param side the side of the area to cut away
   * @param kept the voxels currently shown; only those can be removed
   */
  public static Result compute(
      VolumeSpace space,
      Matrix4d viewProjection,
      int width,
      int height,
      List<? extends Point2D> polygon,
      Side side,
      VoxelBits kept) {
    if (polygon.size() < 3) {
      throw new IllegalArgumentException("A closed area needs at least three points");
    }
    long start = System.nanoTime();
    AreaRaster area = new AreaRaster(polygon, width, height);
    int gx = space.grid().x;
    int gy = space.grid().y;
    int gz = space.grid().z;
    boolean cutInside = side == Side.INSIDE;
    MaskDiff.Builder builder = new MaskDiff.Builder(gz);
    long[] visited = new long[gz];
    long[] skipped = new long[gz];
    Matrix4d vp = new Matrix4d(viewProjection);

    IntStream.range(0, gz)
        .parallel()
        .forEach(
            z -> {
              Vector3d tex = new Vector3d();
              Vector3d world = new Vector3d();
              Vector4d c0 = new Vector4d();
              Vector4d c1 = new Vector4d();
              long[] words = kept.sliceWords(z);
              for (int y = 0; y < gy; y++) {
                if (rowIsClear(words, y * gx, gx)) {
                  continue; // nothing left to remove in this row
                }
                clipOf(space, vp, 0, y, z, tex, world, c0);
                clipOf(space, vp, gx - 1, y, z, tex, world, c1);
                int rowStart = y * gx;
                if (c0.w > MIN_CLIP_W && c1.w > MIN_CLIP_W && area.segmentMisses(c0, c1)) {
                  skipped[z]++;
                  if (!cutInside) {
                    addKeptRuns(builder, z, words, rowStart, gx);
                  }
                  continue;
                }
                visited[z]++;
                double dx = c1.x - c0.x;
                double dy = c1.y - c0.y;
                double dw = c1.w - c0.w;
                double inv = gx > 1 ? 1.0 / (gx - 1) : 0.0;
                int runStart = -1;
                for (int x = 0; x < gx; x++) {
                  int i = rowStart + x;
                  boolean remove = false;
                  if ((words[i >>> 6] & (1L << i)) != 0) {
                    double f = x * inv;
                    double w = c0.w + dw * f;
                    boolean inside = false;
                    if (w > MIN_CLIP_W) {
                      double sx = ((c0.x + dx * f) / w + 1.0) * 0.5 * width;
                      double sy = (1.0 - (c0.y + dy * f) / w) * 0.5 * height;
                      inside = area.contains(sx, sy);
                    }
                    remove = inside == cutInside;
                  }
                  if (remove && runStart < 0) {
                    runStart = i;
                  } else if (!remove && runStart >= 0) {
                    builder.addRun(z, runStart, i - runStart);
                    runStart = -1;
                  }
                }
                if (runStart >= 0) {
                  builder.addRun(z, runStart, rowStart + gx - runStart);
                }
              }
            });
    long v = 0;
    long s = 0;
    for (int z = 0; z < gz; z++) {
      v += visited[z];
      s += skipped[z];
    }
    return new Result(builder.build(), (System.nanoTime() - start) / 1_000_000, v, s);
  }

  /** Clip-space position of the centre of a grid voxel. */
  private static void clipOf(
      VolumeSpace space,
      Matrix4d vp,
      int x,
      int y,
      int z,
      Vector3d tex,
      Vector3d world,
      Vector4d dst) {
    space.texCoordOfGrid(x, y, z, tex);
    space.worldOfTexCoord(tex, world);
    dst.set(world, 1.0);
    vp.transform(dst);
  }

  private static boolean rowIsClear(long[] words, int from, int length) {
    int to = from + length;
    int w0 = from >>> 6;
    int w1 = (to - 1) >>> 6;
    for (int w = w0; w <= w1; w++) {
      long bits = words[w];
      if (w == w0) {
        bits &= -1L << from;
      }
      if (w == w1) {
        bits &= -1L >>> (63 - ((to - 1) & 63));
      }
      if (bits != 0) {
        return false;
      }
    }
    return true;
  }

  /** Adds every run of set bits of the row as a run to remove. */
  private static void addKeptRuns(
      MaskDiff.Builder builder, int z, long[] words, int from, int length) {
    int runStart = -1;
    int to = from + length;
    for (int i = from; i < to; i++) {
      boolean set = (words[i >>> 6] & (1L << i)) != 0;
      if (set && runStart < 0) {
        runStart = i;
      } else if (!set && runStart >= 0) {
        builder.addRun(z, runStart, i - runStart);
        runStart = -1;
      }
    }
    if (runStart >= 0) {
      builder.addRun(z, runStart, to - runStart);
    }
  }

  /** The closed area as one bit per viewport pixel, with its bounding box. */
  static final class AreaRaster {
    private final int width;
    private final int height;
    private final byte[] bits;
    private final int scan;
    private final double minX;
    private final double maxX;
    private final double minY;
    private final double maxY;

    AreaRaster(List<? extends Point2D> polygon, int width, int height) {
      this.width = width;
      this.height = height;
      Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
      double x0 = Double.POSITIVE_INFINITY;
      double x1 = Double.NEGATIVE_INFINITY;
      double y0 = Double.POSITIVE_INFINITY;
      double y1 = Double.NEGATIVE_INFINITY;
      for (int i = 0; i < polygon.size(); i++) {
        Point2D p = polygon.get(i);
        if (i == 0) {
          path.moveTo(p.getX(), p.getY());
        } else {
          path.lineTo(p.getX(), p.getY());
        }
        x0 = Math.min(x0, p.getX());
        x1 = Math.max(x1, p.getX());
        y0 = Math.min(y0, p.getY());
        y1 = Math.max(y1, p.getY());
      }
      path.closePath();
      this.minX = x0;
      this.maxX = x1;
      this.minY = y0;
      this.maxY = y1;
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
      Graphics2D g = image.createGraphics();
      try {
        g.setColor(Color.WHITE);
        g.fill(path);
      } finally {
        g.dispose();
      }
      this.bits = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
      this.scan = (width + 7) / 8;
    }

    boolean contains(double sx, double sy) {
      if (sx < minX || sx > maxX || sy < minY || sy > maxY) {
        return false;
      }
      int ix = (int) Math.floor(sx);
      int iy = (int) Math.floor(sy);
      if (ix < 0 || iy < 0 || ix >= width || iy >= height) {
        return false;
      }
      return (bits[iy * scan + (ix >> 3)] & (0x80 >> (ix & 7))) != 0;
    }

    /** Whether the projected segment between two clip-space points misses the bounding box. */
    boolean segmentMisses(Vector4d c0, Vector4d c1) {
      double sx0 = (c0.x / c0.w + 1.0) * 0.5 * width;
      double sy0 = (1.0 - c0.y / c0.w) * 0.5 * height;
      double sx1 = (c1.x / c1.w + 1.0) * 0.5 * width;
      double sy1 = (1.0 - c1.y / c1.w) * 0.5 * height;
      return Math.max(sx0, sx1) < minX
          || Math.min(sx0, sx1) > maxX
          || Math.max(sy0, sy1) < minY
          || Math.min(sy0, sy1) > maxY;
    }
  }
}
