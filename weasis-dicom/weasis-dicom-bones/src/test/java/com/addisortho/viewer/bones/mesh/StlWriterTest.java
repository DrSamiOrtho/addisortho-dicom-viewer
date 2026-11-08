/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StlWriterTest {

  /** A tetrahedron far from the origin, as LPS coordinates of a real CT are. */
  private static TriangleMesh tetrahedron() {
    double[] v = {
      -100, 50, -800, //
      -90, 50, -800, //
      -100, 60, -800, //
      -100, 50, -790
    };
    int[] t = {0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3};
    return new TriangleMesh(v, t);
  }

  private static byte[] bytes(TriangleMesh mesh) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    StlWriter.write(mesh, StlWriter.BONE_HEADER, out);
    return out.toByteArray();
  }

  @Test
  void writesHeaderCountAndFiftyBytesPerTriangle() throws IOException {
    TriangleMesh mesh = tetrahedron();

    byte[] stl = bytes(mesh);

    assertEquals(84 + 50 * 4, stl.length);
    String header = new String(stl, 0, 80, StandardCharsets.US_ASCII);
    assertEquals(
        "Addis Ortho DICOM Viewer bone surface, mm, DICOM LPS, automatic, not reviewed",
        header.stripTrailing());
    ByteBuffer b = ByteBuffer.wrap(stl).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(4, b.getInt(80));
  }

  @Test
  void triangleRecordsAreLittleEndianWithOutwardNormals() throws IOException {
    TriangleMesh mesh = tetrahedron();
    assertEquals(1000.0 / 6, mesh.signedVolume(), 1e-9);

    ByteBuffer b = ByteBuffer.wrap(bytes(mesh)).order(ByteOrder.LITTLE_ENDIAN);

    // First triangle (0, 2, 1) lies in z = -800 and faces down, away from vertex 3.
    int r = 84;
    assertArrayEquals(new float[] {0, 0, -1}, floats(b, r, 3));
    assertArrayEquals(
        new float[] {-100, 50, -800, -100, 60, -800, -90, 50, -800}, floats(b, r + 12, 9));
    assertEquals(0, b.getShort(r + 48));
    // Last triangle (1, 2, 3) faces away from the origin corner.
    float[] n = floats(b, 84 + 3 * 50, 3);
    float s = (float) (1 / Math.sqrt(3));
    assertArrayEquals(new float[] {s, s, s}, n, 1e-6f);
  }

  @Test
  void writesThroughATemporaryFile(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("bone.stl");
    Files.writeString(file, "old");

    StlWriter.write(tetrahedron(), StlWriter.BONE_HEADER, file);

    assertEquals(84 + 50 * 4, Files.size(file));
    try (var files = Files.list(dir)) {
      assertEquals(1, files.count(), "temporary file left behind");
    }
  }

  @Test
  void rejectsBadHeaders() {
    TriangleMesh mesh = tetrahedron();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    char[] longText = new char[81];
    Arrays.fill(longText, 'x');
    assertThrows(
        IllegalArgumentException.class, () -> StlWriter.write(mesh, new String(longText), out));
    assertThrows(IllegalArgumentException.class, () -> StlWriter.write(mesh, "solid bone", out));
  }

  private static float[] floats(ByteBuffer b, int offset, int count) {
    float[] f = new float[count];
    for (int n = 0; n < count; n++) {
      f[n] = b.getFloat(offset + 4 * n);
    }
    return f;
  }
}
