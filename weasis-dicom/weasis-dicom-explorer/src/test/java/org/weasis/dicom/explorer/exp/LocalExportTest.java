/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.exp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalExportTest {

  @TempDir Path tmp;

  @Test
  void namesTheFolderAfterThePatient() {
    assertEquals(
        new File(tmp.toFile(), "DOE JOHN 12345"),
        LocalExport.patientFolder(tmp.toFile(), "DOE JOHN 12345"));
  }

  @Test
  void numbersTheFolderWhenThePatientWasExportedThereBefore() throws IOException {
    Files.createDirectory(tmp.resolve("DOE JOHN 12345"));
    Files.createDirectory(tmp.resolve("DOE JOHN 12345 (2)"));
    assertEquals(
        new File(tmp.toFile(), "DOE JOHN 12345 (3)"),
        LocalExport.patientFolder(tmp.toFile(), "DOE JOHN 12345"));
  }

  @Test
  void dropsCharactersAFolderNameCannotHold() {
    File dir = LocalExport.patientFolder(tmp.toFile(), "DOE/JOHN: 12345");
    assertEquals(tmp.toFile(), dir.getParentFile());
    assertEquals(-1, dir.getName().indexOf('/'));
    assertEquals(-1, dir.getName().indexOf(':'));
  }

  @Test
  void putsTheFirstNameFirst() {
    assertEquals("Abebe Kebede", LocalExport.fullName("Kebede^Abebe"));
    assertEquals("Abebe Kebede Tesfaye", LocalExport.fullName("Tesfaye^Abebe^Kebede"));
    assertEquals("Dr Abebe Kebede Jr", LocalExport.fullName("Kebede^Abebe^^Dr^Jr"));
    assertEquals("Abebe Kebede", LocalExport.fullName("Kebede^Abebe=\u30a2\u30d9\u30d9"));
  }

  @Test
  void keepsANameTypedAsOneText() {
    assertEquals("ABEBE KEBEDE TESFAYE", LocalExport.fullName("ABEBE  KEBEDE TESFAYE "));
    assertEquals("", LocalExport.fullName(null));
    assertEquals("", LocalExport.fullName("^"));
  }
}
