/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.core.ui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.weasis.opencv.op.ImageIOHandler;

/** Every picture format the export offers, encoded for real through the staged write. */
class SavedPictureTest {
  @TempDir Path tmp;

  @BeforeAll
  static void loadOpenCV() {
    assumeTrue(load(), "OpenCV native library unavailable");
  }

  private static boolean load() {
    Path modules = Paths.get(System.getProperty("user.dir")).getParent().resolve("weasis-opencv");
    try (Stream<Path> dirs = Files.list(modules)) {
      List<Path> libs =
          dirs.map(d -> d.resolve("target/classes/libopencv_java.dylib"))
              .filter(Files::isRegularFile)
              .toList();
      for (Path lib : libs) {
        try {
          System.load(lib.toAbsolutePath().toString());
          return true;
        } catch (Throwable wrongArchitecture) {
          // the next one may match
        }
      }
    } catch (IOException e) {
      return false;
    }
    return false;
  }

  @ParameterizedTest
  @ValueSource(strings = {"png", "jpg", "tif", "jp2", "jxl"})
  void aPictureIsSavedAndCanBeReadBack(String extension) throws IOException {
    Mat image = new Mat(32, 48, CvType.CV_8UC1, new Scalar(120));
    Path destination = tmp.resolve("Abebe MRN 00123 2026-01-15." + extension);

    SavedFile.write(
        destination,
        staged ->
            extension.equals("png")
                ? ImageIOHandler.writePNG(image, staged)
                : ImageIOHandler.writeImage(image, staged, new MatOfInt()));

    assertTrue(Files.size(destination) > 0);
    Mat back = Imgcodecs.imread(destination.toString(), Imgcodecs.IMREAD_UNCHANGED);
    assertFalse(back.empty(), extension);
    assertEquals(32, back.rows());
    assertEquals(48, back.cols());
    try (Stream<Path> left = Files.list(tmp)) {
      assertEquals(1, left.count(), "no temporary file is left");
    }
  }
}
