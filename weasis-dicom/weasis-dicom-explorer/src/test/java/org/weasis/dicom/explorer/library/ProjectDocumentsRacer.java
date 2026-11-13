/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.library;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A second viewer process for {@link ProjectDocumentsTest}: waits for the start file, then puts one
 * document. Exits 0 when it kept it, 3 when it was refused as already kept, 1 on anything else.
 */
public final class ProjectDocumentsRacer {
  private ProjectDocumentsRacer() {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Path start = Path.of(args[4]);
    long until = System.currentTimeMillis() + 30_000;
    while (!Files.exists(start)) {
      if (System.currentTimeMillis() > until) {
        System.exit(1);
      }
      Thread.onSpinWait();
    }
    byte[] doc = ("{\"synthetic\":\"" + args[3] + "\"}").getBytes(StandardCharsets.UTF_8);
    try {
      new ProjectDocuments(root, "stitch").put(args[1], args[2], doc);
      System.exit(0);
    } catch (FileAlreadyExistsException e) {
      System.exit(3);
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    }
  }
}
