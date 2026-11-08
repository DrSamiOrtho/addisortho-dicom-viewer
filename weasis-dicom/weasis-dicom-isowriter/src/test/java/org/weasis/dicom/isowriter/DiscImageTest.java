/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.isowriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscImageTest {
  @TempDir Path tmp;

  /** Set ao.export.sample to keep the image for a reader that is not this program. */
  @Test
  void makesADiscImageHoldingTheFolder() throws Exception {
    Path source = Files.createDirectories(tmp.resolve("disc/DICOM/ABC"));
    Files.write(source.resolve("IMG1"), new byte[4096]);
    Files.writeString(tmp.resolve("disc/README.HTM"), "synthetic");
    String keep = System.getProperty("ao.export.sample");
    File iso = (keep == null ? tmp : Path.of(keep)).resolve("media.iso").toFile();
    Files.createDirectories(iso.toPath().getParent());
    Files.deleteIfExists(iso.toPath());

    File made =
        IsoImageExport.makeISO(tmp.resolve("disc").toFile(), iso, "TEST", "TEST", true, true);

    assertNotNull(made);
    assertEquals(iso, made);
    assertTrue(iso.length() > 4096);
  }

  @Test
  void aDestinationThatCannotBeWrittenGivesNoImage() throws Exception {
    Path source = Files.createDirectories(tmp.resolve("disc"));
    Files.writeString(source.resolve("README.HTM"), "synthetic");
    Path blocked = Files.createFile(tmp.resolve("not-a-folder"));

    assertNull(
        IsoImageExport.makeISO(
            source.toFile(), blocked.resolve("x.iso").toFile(), "TEST", "TEST", true, true));
  }
}
