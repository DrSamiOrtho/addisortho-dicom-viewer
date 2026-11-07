/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.mesh;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Binary STL: an 80-byte header, the triangle count, then per triangle its normal, three vertices
 * (32-bit floats, little-endian) and a zero attribute word.
 */
public final class StlWriter {

  /** Header text of the bone surfaces: units, coordinate system and review status. */
  public static final String BONE_HEADER =
      "Addis Ortho DICOM Viewer bone surface, mm, DICOM LPS, automatic, not reviewed";

  static final int HEADER_BYTES = 80;
  static final int TRIANGLE_BYTES = 50;

  private StlWriter() {}

  /**
   * Writes the file through a temporary sibling, so a failed or cancelled write leaves no partial
   * file under {@code file}'s name.
   *
   * @param header ASCII text, at most 80 characters
   */
  public static void write(TriangleMesh mesh, String header, Path file) throws IOException {
    Path dir = file.toAbsolutePath().getParent();
    Path tmp = Files.createTempFile(dir, ".stl-", ".part");
    try {
      try (OutputStream out = Files.newOutputStream(tmp)) {
        write(mesh, header, out);
      }
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  /**
   * @param header ASCII text, at most 80 characters, padded with spaces
   */
  public static void write(TriangleMesh mesh, String header, OutputStream out) throws IOException {
    byte[] text = header.getBytes(StandardCharsets.US_ASCII);
    if (text.length > HEADER_BYTES) {
      throw new IllegalArgumentException("STL header longer than 80 bytes");
    }
    // A binary STL must not start with "solid", which marks the ASCII format.
    if (header.regionMatches(true, 0, "solid", 0, 5)) {
      throw new IllegalArgumentException("Binary STL header must not start with \"solid\"");
    }
    BufferedOutputStream buffered = new BufferedOutputStream(out, 1 << 16);
    byte[] head = Arrays.copyOf(text, HEADER_BYTES);
    Arrays.fill(head, text.length, HEADER_BYTES, (byte) ' ');
    buffered.write(head);
    ByteBuffer record = ByteBuffer.allocate(TRIANGLE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    record.putInt(0, mesh.triangleCount());
    buffered.write(record.array(), 0, Integer.BYTES);
    double[] normal = new double[3];
    for (int t = 0; t < mesh.triangleCount(); t++) {
      record.clear();
      mesh.faceNormal(t, normal);
      putPoint(record, normal[0], normal[1], normal[2]);
      for (int c = 0; c < 3; c++) {
        int v = mesh.corner(t, c);
        putPoint(record, mesh.coordinate(v, 0), mesh.coordinate(v, 1), mesh.coordinate(v, 2));
      }
      record.putShort((short) 0);
      buffered.write(record.array());
    }
    buffered.flush();
  }

  private static void putPoint(ByteBuffer buffer, double x, double y, double z) {
    buffer.putFloat((float) x);
    buffer.putFloat((float) y);
    buffer.putFloat((float) z);
  }
}
