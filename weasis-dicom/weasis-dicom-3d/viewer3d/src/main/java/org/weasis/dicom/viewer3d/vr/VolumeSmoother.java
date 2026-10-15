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
import java.util.stream.IntStream;

/**
 * Separable 3D Gaussian pre-filter of a 16-bit unsigned volume, fed one axial slice at a time in
 * upload order so that only a slab of {@code 2 * radiusZ + 1} slices is ever held. Edges are
 * replicated, as the texture's {@code GL_CLAMP_TO_EDGE} does, so the result is identical to
 * filtering the whole volume at once. The weights sum to one, so a signed volume shifted into the
 * unsigned range keeps its shift.
 *
 * <p>Extra memory: {@code (2 * radiusZ + 2) * width * height} floats.
 */
public final class VolumeSmoother {

  /** Default in-plane sigma, in voxels. */
  public static final double SIGMA_XY = 0.6;

  /** Smallest sigma across slices, in slices, so the steps between slices are always softened. */
  public static final double MIN_SIGMA_Z = 0.6;

  private static final int PARALLEL_MIN_PIXELS = 64 * 64;

  private final int width;
  private final int height;
  private final float[] kernelXY;
  private final float[] kernelZ;
  private final float[][] ring;
  private final float[] rowPass;
  private int received;
  private int emitted;

  /**
   * @param sigmaXY in-plane standard deviation in voxels, 0 for none
   * @param sigmaZ standard deviation across slices in slices, 0 for none
   */
  public VolumeSmoother(int width, int height, double sigmaXY, double sigmaZ) {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("Empty slice");
    }
    this.width = width;
    this.height = height;
    this.kernelXY = gaussianKernel(sigmaXY);
    this.kernelZ = gaussianKernel(sigmaZ);
    this.ring = new float[kernelZ.length][];
    this.rowPass = new float[width * height];
  }

  /**
   * Sigma across slices for a given in-plane sigma, from the voxel spacing: the same physical
   * width, but never below {@link #MIN_SIGMA_Z}.
   */
  public static double sigmaZ(double sigmaXY, double spacingXY, double spacingZ) {
    if (sigmaXY <= 0) {
      return 0;
    }
    double physical = spacingZ > 0 ? sigmaXY * spacingXY / spacingZ : sigmaXY;
    return Math.max(physical, MIN_SIGMA_Z);
  }

  /** Normalised Gaussian weights over [-r, r] with r = ceil(3 sigma); {1} when sigma is 0. */
  public static float[] gaussianKernel(double sigma) {
    if (sigma <= 0) {
      return new float[] {1f};
    }
    int r = (int) Math.ceil(3 * sigma);
    double[] w = new double[2 * r + 1];
    double sum = 0;
    for (int i = -r; i <= r; i++) {
      w[i + r] = Math.exp(-(i * i) / (2 * sigma * sigma));
      sum += w[i + r];
    }
    float[] k = new float[w.length];
    for (int i = 0; i < w.length; i++) {
      k[i] = (float) (w[i] / sum);
    }
    return k;
  }

  public int getRadiusZ() {
    return kernelZ.length / 2;
  }

  /**
   * Adds the next slice ({@code width * height} unsigned values) and returns the filtered slices
   * that became complete: none while the first {@code radiusZ} slices arrive, then one each time.
   */
  public List<short[]> push(short[] slice) {
    if (slice.length != width * height) {
      throw new IllegalArgumentException("Slice size mismatch");
    }
    ring[received % ring.length] = filterInPlane(slice, ring[received % ring.length]);
    received++;
    List<short[]> out = new ArrayList<>(1);
    int radius = getRadiusZ();
    while (emitted + radius < received) {
      out.add(combine(emitted, received - 1));
      emitted++;
    }
    return out;
  }

  /** Returns the remaining filtered slices once the last one was pushed. */
  public List<short[]> finish() {
    List<short[]> out = new ArrayList<>(getRadiusZ());
    while (emitted < received) {
      out.add(combine(emitted, received - 1));
      emitted++;
    }
    return out;
  }

  /**
   * The in-plane pass alone, for a caller that keeps its own window of slices (see TextureField);
   * the result then goes through {@link #combineTaps}. Not thread-safe.
   */
  public float[] filterInPlane(short[] src) {
    if (src.length != width * height) {
      throw new IllegalArgumentException("Slice size mismatch");
    }
    return filterInPlane(src, null);
  }

  private float[] filterInPlane(short[] src, float[] reuse) {
    float[] dst = reuse != null ? reuse : new float[width * height];
    int r = kernelXY.length / 2;
    rows(
        y -> {
          int off = y * width;
          for (int x = 0; x < width; x++) {
            float s = 0;
            for (int k = -r; k <= r; k++) {
              int xx = Math.clamp(x + k, 0, width - 1);
              s += kernelXY[k + r] * (src[off + xx] & 0xFFFF);
            }
            rowPass[off + x] = s;
          }
        });
    rows(
        y -> {
          int off = y * width;
          for (int x = 0; x < width; x++) {
            float s = 0;
            for (int k = -r; k <= r; k++) {
              int yy = Math.clamp(y + k, 0, height - 1);
              s += kernelXY[k + r] * rowPass[yy * width + x];
            }
            dst[off + x] = s;
          }
        });
    return dst;
  }

  /** Slice {@code z} filtered across slices; {@code last} is the newest slice in the ring. */
  private short[] combine(int z, int last) {
    int r = getRadiusZ();
    float[][] taps = new float[kernelZ.length][];
    for (int k = -r; k <= r; k++) {
      int zz = Math.clamp(z + k, 0, last);
      taps[k + r] = ring[zz % ring.length];
    }
    return combineTaps(taps);
  }

  /**
   * The across-slices pass alone: {@code taps} are the in-plane filtered slices from {@code z -
   * radiusZ} to {@code z + radiusZ}, edge slices repeated as the streaming does.
   */
  public short[] combineTaps(float[][] taps) {
    if (taps.length != kernelZ.length) {
      throw new IllegalArgumentException("Expected " + kernelZ.length + " slices");
    }
    short[] out = new short[width * height];
    rows(
        y -> {
          int off = y * width;
          for (int i = off; i < off + width; i++) {
            float s = 0;
            for (int k = 0; k < taps.length; k++) {
              s += kernelZ[k] * taps[k][i];
            }
            out[i] = (short) Math.clamp(Math.round(s), 0, 0xFFFF);
          }
        });
    return out;
  }

  private void rows(java.util.function.IntConsumer row) {
    IntStream range = IntStream.range(0, height);
    if (width * height >= PARALLEL_MIN_PIXELS) {
      range = range.parallel();
    }
    range.forEach(row);
  }
}
