/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomOutputStream;

/** The X-ray file handed to the page. */
final class XrayFile {

  private XrayFile() {}

  /** Adds file meta information to a DICOM file that lacks it (as some CD files do). */
  static void withFileMeta(Path file) throws IOException {
    Attributes ds;
    String ts;
    try (DicomInputStream in = new DicomInputStream(file.toFile())) {
      if (in.readFileMetaInformation() != null) {
        return;
      }
      ts = in.getTransferSyntax();
      ds = in.readDataset();
    }
    Path tmp = Files.createTempFile(file.getParent(), "meta-", ".dcm");
    try (DicomOutputStream out = new DicomOutputStream(tmp.toFile())) {
      out.writeDataset(ds.createFileMetaInformation(ts), ds);
    }
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
  }
}
