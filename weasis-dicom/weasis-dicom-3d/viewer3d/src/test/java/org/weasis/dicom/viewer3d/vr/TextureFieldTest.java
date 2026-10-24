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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import org.weasis.dicom.viewer3d.vr.VolumePicker.Hit;

/** The CPU copy of the uploaded field against the upload itself (VolumeBuilder's pipeline). */
class TextureFieldTest {

  /** A synthetic signed volume held as float slices. */
  private record Slices(int width, int height, float[][] data) implements TextureField.SliceSource {
    @Override
    public int depth() {
      return data.length;
    }

    @Override
    public float[] slice(int z) {
      return data[z].clone();
    }

    @Override
    public int unsignedOffset() {
      return 32768;
    }
  }

  private static Slices randomVolume(int w, int h, int d, long seed) {
    Random random = new Random(seed);
    float[][] data = new float[d][w * h];
    for (float[] slice : data) {
      for (int i = 0; i < slice.length; i++) {
        slice[i] = random.nextInt(4000) - 1000;
      }
    }
    return new Slices(w, h, data);
  }

  /** VolumeBuilder's pipeline: slices in texture order, shifted to unsigned, streamed. */
  private static float[][] upload(Slices source, int textureDepth, VolumeSmoother smoother) {
    List<short[]> out = new ArrayList<>();
    int n = source.width() * source.height();
    for (int texZ = 0; texZ < textureDepth; texZ++) {
      int sourceZ = VolumeSpace.sourceSliceOfTextureSlice(texZ, textureDepth, source.depth());
      float[] slice = source.slice(sourceZ);
      short[] unsigned = new short[n];
      for (int i = 0; i < n; i++) {
        unsigned[i] = (short) (Math.round(slice[i]) + 32768);
      }
      if (smoother == null) {
        out.add(unsigned);
      } else {
        out.addAll(smoother.push(unsigned));
      }
    }
    if (smoother != null) {
      out.addAll(smoother.finish());
    }
    float[][] result = new float[textureDepth][n];
    for (int z = 0; z < textureDepth; z++) {
      for (int i = 0; i < n; i++) {
        result[z][i] = (out.get(z)[i] & 0xFFFF) - 32768;
      }
    }
    return result;
  }

  @Test
  void smoothedFieldIsBitExactWithTheStreamedUpload() {
    Slices source = randomVolume(16, 12, 14, 1);
    VolumeSmoother streamed = new VolumeSmoother(16, 12, 0.8, 0.7);
    float[][] uploaded = upload(source, 14, streamed);
    TextureField field =
        new TextureField(source, new Vector3i(16, 12, 14), new VolumeSmoother(16, 12, 0.8, 0.7));
    for (int z = 0; z < 14; z++) {
      for (int y = 0; y < 12; y++) {
        for (int x = 0; x < 16; x++) {
          assertEquals(uploaded[z][y * 16 + x], field.valueAt(x, y, z), 0.0, x + "," + y + "," + z);
        }
      }
    }
  }

  @Test
  void unsmoothedFieldFollowsTheSliceConventionForAReducedDepth() {
    Slices source = randomVolume(8, 8, 12, 2);
    float[][] uploaded = upload(source, 5, null);
    TextureField field = new TextureField(source, new Vector3i(8, 8, 5), null);
    for (int z = 0; z < 5; z++) {
      for (int i = 0; i < 64; i++) {
        assertEquals(uploaded[z][i], field.valueAt(i % 8, i / 8, z), 0.0);
      }
    }
    // Texture slice 0 holds the last source slice taken, source 4 holds the first.
    assertEquals(
        source.data()[VolumeSpace.sourceSliceOfTextureSlice(0, 5, 12)][3], field.valueAt(3, 0, 0));
    assertEquals(source.data()[0][3], field.valueAt(3, 0, 4));
  }

  @Test
  void inPlaneScaleIsCentreAlignedBilinear() {
    float[] ramp = new float[16 * 16];
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        ramp[y * 16 + x] = 10 * x + y;
      }
    }
    Slices source = new Slices(16, 16, new float[][] {ramp});
    TextureField field = new TextureField(source, new Vector3i(8, 8, 1), null);
    // Texture voxel 3 covers source columns 6 and 7: its centre is at source 6.5.
    assertEquals(10 * 6.5 + 4.5, field.valueAt(3, 2, 0), 1e-4);
    assertEquals(0.5 * 10 + 0.5, field.valueAt(0, 0, 0), 1e-4);
  }

  @Test
  void pickingSeesTheSmoothedFieldNotTheRawSpike() {
    // A one-voxel spike of 3000 in front of a wall of 1000, in air at -1000; the smoothing of a
    // CT (sigma 0.6 in plane) brings the spike well below the 500 the preset shows from.
    int n = 24;
    float[][] data = new float[n][n * n];
    for (int z = 0; z < n; z++) {
      for (int i = 0; i < n * n; i++) {
        data[z][i] = z >= 18 ? 1000 : -1000; // the wall: texture slices 18 and beyond
      }
    }
    data[6][12 * n + 12] = 3000; // the spike, on the ray's path, in front of the wall
    Slices source = new Slices(n, n, data);
    Vector3i size = new Vector3i(n, n, n);
    VolumeSpace space = VolumeSpace.ofVolume(size, new Vector3d(1, 1, 1));
    TransferFunction opaqueAbove500 = TransferFunction.step(500, -1000, 4000);
    double voxel = 2.0 / n;

    // Source z is reversed in the texture, so build the field from a reversed source: the test
    // volume is defined directly in texture order to keep the numbers readable.
    TextureField.SliceSource texOrder =
        new TextureField.SliceSource() {
          public int width() {
            return n;
          }

          public int height() {
            return n;
          }

          public int depth() {
            return n;
          }

          public float[] slice(int z) {
            return data[n - 1 - z].clone();
          }

          public int unsignedOffset() {
            return 32768;
          }
        };
    TextureField raw = new TextureField(texOrder, size, null);
    TextureField smoothed =
        new TextureField(texOrder, size, TextureField.smootherOf(true, n, n, 1.0, 1.0));
    assertEquals(3000, raw.valueAt(12, 12, 6), 0.0);
    assertTrue(smoothed.valueAt(12, 12, 6) < 500, "the spike is smoothed away");

    // Through the centre column of texture voxel (12, 12): world voxel / 2 on x and y.
    Vector3d origin = new Vector3d(voxel / 2, voxel / 2, -3);
    Vector3d dir = new Vector3d(0, 0, 1);
    Optional<Hit> onRaw =
        new VolumePicker(space, raw, opaqueAbove500, t -> false)
            .pickRay(origin, dir, new Vector3d(0, 0, 0), new Vector3d(1, 1, 1));
    Optional<Hit> onSmoothed =
        new VolumePicker(space, smoothed, opaqueAbove500, t -> false)
            .pickRay(origin, dir, new Vector3d(0, 0, 0), new Vector3d(1, 1, 1));
    assertTrue(onRaw.isPresent() && onSmoothed.isPresent());
    double spikeDepth = 3 + (6.5 - n / 2.0) * voxel;
    double wallDepth = 3 + (18 - n / 2.0) * voxel;
    assertEquals(spikeDepth, onRaw.get().depth(), voxel, "the raw field stops at the spike");
    assertEquals(wallDepth, onSmoothed.get().depth(), voxel, "the uploaded field reaches the wall");
  }

  @Test
  void theBudgetCountsReconstructionTemporariesAndWhatIsAlreadyResident() {
    Slices source = randomVolume(16, 12, 10, 7);
    Vector3i size = new Vector3i(16, 12, 14);
    TextureField raw = new TextureField(source, size, null);
    TextureField smoothed = new TextureField(source, size, new VolumeSmoother(16, 12, 0.8, 0.7));
    long sliceBytes = 4L * 16 * 12;
    // The smoothed field makes an unsigned copy and the combined taps on the way to a slice.
    assertEquals(0, raw.reconstructionBytes());
    assertEquals(2L * 2 * 16 * 12, smoothed.reconstructionBytes());
    assertEquals(raw.workingBytes() + smoothed.reconstructionBytes(), smoothed.workingBytes());
    // Nothing is resident before the first read; the caches then count what they hold, and the
    // scaled slice, shared with its source here, is not counted twice.
    assertEquals(0, smoothed.residentBytes());
    smoothed.textureSlice(5);
    long resident = smoothed.residentBytes();
    assertTrue(resident >= sliceBytes * 3, "a texture slice, its filtered taps and sources");
    assertEquals(0, resident % sliceBytes);
    for (int z = 0; z < size.z; z++) {
      smoothed.textureSlice(z);
    }
    assertTrue(smoothed.residentBytes() > resident);
    assertTrue(
        smoothed.residentBytes() + smoothed.reconstructionBytes() <= smoothed.workingBytes());
  }
}
