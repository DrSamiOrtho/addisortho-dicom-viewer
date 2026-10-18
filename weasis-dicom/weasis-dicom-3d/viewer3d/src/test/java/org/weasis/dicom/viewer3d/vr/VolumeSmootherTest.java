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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VolumeSmootherTest {

  private static final int SHIFT = 32768;

  /** Streams the slices through the filter as VolumeBuilder does and collects the result. */
  private static short[][] smooth(short[][] slices, int w, int h, double sxy, double sz) {
    VolumeSmoother smoother = new VolumeSmoother(w, h, sxy, sz);
    List<short[]> out = new ArrayList<>();
    for (short[] s : slices) {
      out.addAll(smoother.push(s.clone()));
    }
    out.addAll(smoother.finish());
    return out.toArray(short[][]::new);
  }

  /** Straightforward whole-volume reference: three separable passes in double, edges clamped. */
  private static short[][] reference(short[][] in, int w, int h, double sxy, double sz) {
    int d = in.length;
    float[] kxy = VolumeSmoother.gaussianKernel(sxy);
    float[] kz = VolumeSmoother.gaussianKernel(sz);
    double[][] a = new double[d][w * h];
    for (int z = 0; z < d; z++) {
      for (int i = 0; i < w * h; i++) {
        a[z][i] = in[z][i] & 0xFFFF;
      }
    }
    double[][] b = new double[d][w * h];
    int r = kxy.length / 2;
    for (int z = 0; z < d; z++) {
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
          double s = 0;
          for (int k = -r; k <= r; k++) {
            s += kxy[k + r] * a[z][y * w + Math.clamp(x + k, 0, w - 1)];
          }
          b[z][y * w + x] = s;
        }
      }
    }
    for (int z = 0; z < d; z++) {
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
          double s = 0;
          for (int k = -r; k <= r; k++) {
            s += kxy[k + r] * b[z][Math.clamp(y + k, 0, h - 1) * w + x];
          }
          a[z][y * w + x] = s;
        }
      }
    }
    short[][] out = new short[d][w * h];
    int rz = kz.length / 2;
    for (int z = 0; z < d; z++) {
      for (int i = 0; i < w * h; i++) {
        double s = 0;
        for (int k = -rz; k <= rz; k++) {
          s += kz[k + rz] * a[Math.clamp(z + k, 0, d - 1)][i];
        }
        out[z][i] = (short) Math.clamp(Math.round(s), 0, 0xFFFF);
      }
    }
    return out;
  }

  private static short[][] filled(int w, int h, int d, int value) {
    short[][] v = new short[d][w * h];
    for (short[] s : v) {
      java.util.Arrays.fill(s, (short) value);
    }
    return v;
  }

  private static void assertClose(short[][] expected, short[][] actual, int tolerance) {
    assertEquals(expected.length, actual.length, "slice count");
    int worst = 0;
    for (int z = 0; z < expected.length; z++) {
      for (int i = 0; i < expected[z].length; i++) {
        worst = Math.max(worst, Math.abs((expected[z][i] & 0xFFFF) - (actual[z][i] & 0xFFFF)));
      }
    }
    assertTrue(worst <= tolerance, "largest difference " + worst);
  }

  @Test
  void kernelIsNormalisedSymmetricAndHasTheRequestedSigma() {
    for (double sigma : new double[] {0.6, 0.8, 1.0, 1.5}) {
      float[] k = VolumeSmoother.gaussianKernel(sigma);
      int r = k.length / 2;
      double sum = 0;
      double variance = 0;
      for (int i = -r; i <= r; i++) {
        sum += k[i + r];
        variance += k[i + r] * i * i;
        assertEquals(k[i + r], k[r - i], 1e-7);
      }
      assertEquals(Math.ceil(3 * sigma), r);
      assertEquals(1.0, sum, 1e-6);
      // Truncation at 3 sigma and sampling keep the discrete variance within 3 % of sigma².
      assertEquals(sigma * sigma, variance, 0.03 * sigma * sigma, "sigma " + sigma);
    }
    assertArrayEquals(new float[] {1f}, VolumeSmoother.gaussianKernel(0));
  }

  @Test
  void impulseResponseIsTheProductOfTheThreeKernels() {
    int w = 15;
    int h = 13;
    int d = 11;
    int background = 1000;
    int amplitude = 50000;
    short[][] v = filled(w, h, d, background);
    v[5][6 * w + 7] = (short) (background + amplitude);
    double sxy = 0.8;
    double sz = 1.2;
    short[][] out = smooth(v, w, h, sxy, sz);

    float[] kxy = VolumeSmoother.gaussianKernel(sxy);
    float[] kz = VolumeSmoother.gaussianKernel(sz);
    int r = kxy.length / 2;
    int rz = kz.length / 2;
    double total = 0;
    for (int z = 0; z < d; z++) {
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
          int dx = x - 7;
          int dy = y - 6;
          int dz = z - 5;
          double weight =
              Math.abs(dx) <= r && Math.abs(dy) <= r && Math.abs(dz) <= rz
                  ? (double) kxy[dx + r] * kxy[dy + r] * kz[dz + rz]
                  : 0;
          int got = (out[z][y * w + x] & 0xFFFF) - background;
          assertEquals(amplitude * weight, got, 1.0, "at " + x + "," + y + "," + z);
          total += got;
        }
      }
    }
    // The energy of the impulse is kept, up to the rounding of each voxel.
    assertEquals(amplitude, total, w * h * d * 0.5);
  }

  @Test
  void impulseResponseHasTheRequestedSigmaOnEachAxis() {
    int n = 21;
    double sxy = 1.0;
    double sz = 1.5;
    short[][] v = filled(n, n, n, 0);
    v[10][10 * n + 10] = (short) 60000;
    short[][] out = smooth(v, n, n, sxy, sz);
    double sum = 0;
    double vx = 0;
    double vy = 0;
    double vz = 0;
    for (int z = 0; z < n; z++) {
      for (int y = 0; y < n; y++) {
        for (int x = 0; x < n; x++) {
          double m = out[z][y * n + x] & 0xFFFF;
          sum += m;
          vx += m * (x - 10) * (x - 10);
          vy += m * (y - 10) * (y - 10);
          vz += m * (z - 10) * (z - 10);
        }
      }
    }
    double finalSum = sum;
    double varX = vx / finalSum;
    double varY = vy / finalSum;
    double varZ = vz / finalSum;
    assertAll(
        () -> assertEquals(sxy * sxy, varX, 0.03 * sxy * sxy),
        () -> assertEquals(sxy * sxy, varY, 0.03 * sxy * sxy),
        () -> assertEquals(sz * sz, varZ, 0.03 * sz * sz));
  }

  @Test
  void streamedSlabsEqualTheWholeVolumeFilter() {
    Random random = new Random(7);
    int w = 37;
    int h = 29;
    // Covers fewer slices than the kernel, exactly the kernel, and many more.
    for (int d : new int[] {1, 2, 3, 7, 26}) {
      short[][] v = new short[d][w * h];
      for (short[] s : v) {
        for (int i = 0; i < s.length; i++) {
          s[i] = (short) (SHIFT + random.nextInt(4000) - 1000);
        }
      }
      for (double[] sigma : new double[][] {{0.6, 0.6}, {0.8, 1.6}, {1.0, 0}}) {
        short[][] expected = reference(v, w, h, sigma[0], sigma[1]);
        assertClose(expected, smooth(v, w, h, sigma[0], sigma[1]), 1);
      }
    }
  }

  @Test
  void slicesComeOutInOrderOnceTheirSlabIsComplete() {
    int w = 8;
    int h = 8;
    VolumeSmoother smoother = new VolumeSmoother(w, h, 0.8, 0.8);
    int radius = smoother.getRadiusZ();
    assertEquals(3, radius);
    int emitted = 0;
    for (int z = 0; z < 10; z++) {
      emitted += smoother.push(new short[w * h]).size();
      assertEquals(Math.max(0, z + 1 - radius), emitted);
    }
    assertEquals(radius, smoother.finish().size());
  }

  @Test
  void signedShiftIsPreserved() {
    Random random = new Random(3);
    int w = 24;
    int h = 20;
    int d = 9;
    short[][] hu = new short[d][w * h];
    short[][] shifted = new short[d][w * h];
    short[][] otherShift = new short[d][w * h];
    for (int z = 0; z < d; z++) {
      for (int i = 0; i < w * h; i++) {
        int value = random.nextInt(3000) - 1024;
        hu[z][i] = (short) value;
        shifted[z][i] = (short) (value + SHIFT);
        otherShift[z][i] = (short) (value + 2000);
      }
    }
    short[][] a = smooth(shifted, w, h, 0.8, 0.6);
    short[][] b = smooth(otherShift, w, h, 0.8, 0.6);
    for (int z = 0; z < d; z++) {
      for (int i = 0; i < w * h; i++) {
        int fromShift = (a[z][i] & 0xFFFF) - SHIFT;
        int fromOther = (b[z][i] & 0xFFFF) - 2000;
        assertEquals(fromOther, fromShift, 1, "voxel " + i + " of slice " + z);
      }
    }
    // Air stays exactly air: -1000 HU everywhere is untouched.
    short[][] air = smooth(filled(w, h, d, SHIFT - 1000), w, h, 0.8, 0.6);
    for (short[] s : air) {
      for (short value : s) {
        assertEquals(SHIFT - 1000, value & 0xFFFF);
      }
    }
  }

  @Test
  void zeroSigmaLeavesTheVolumeUntouched() {
    Random random = new Random(11);
    short[][] v = new short[4][6 * 5];
    for (short[] s : v) {
      for (int i = 0; i < s.length; i++) {
        s[i] = (short) random.nextInt(0xFFFF);
      }
    }
    short[][] out = smooth(v, 6, 5, 0, 0);
    for (int z = 0; z < v.length; z++) {
      assertArrayEquals(v[z], out[z]);
    }
  }

  @Test
  void sigmaAcrossSlicesFollowsTheSpacingWithAFloor() {
    assertAll(
        () -> assertEquals(0.6, VolumeSmoother.sigmaZ(0.6, 0.84, 1.25), 1e-9),
        () -> assertEquals(1.6, VolumeSmoother.sigmaZ(0.8, 1.0, 0.5), 1e-9),
        () -> assertEquals(0.8, VolumeSmoother.sigmaZ(0.8, 1.0, 1.0), 1e-9),
        () -> assertEquals(0.0, VolumeSmoother.sigmaZ(0.0, 1.0, 1.0), 1e-9));
  }
}
