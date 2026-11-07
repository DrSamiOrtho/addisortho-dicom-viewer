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
package org.weasis.dicom.isowriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DiscLabelTest {

  @Test
  void noPatientIsDicom() {
    assertEquals("DICOM", IsoImageExport.isoVolumeId(null));
    assertEquals("DICOM", IsoImageExport.jolietVolumeId(null));
  }

  @Test
  void isoLabelKeepsOnlyIsoCharacters() {
    assertEquals("ABEBE_KEBEDE_123456", IsoImageExport.isoVolumeId("Abebe Kebede 123456"));
    assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", IsoImageExport.isoVolumeId("a".repeat(40)));
    assertEquals("DICOM", IsoImageExport.isoVolumeId("አበበ"));
  }

  @Test
  void jolietLabelIsSixteenCharacters() {
    assertEquals("Abebe Kebede 123", IsoImageExport.jolietVolumeId("Abebe Kebede 123456"));
    assertEquals("AB", IsoImageExport.jolietVolumeId("A:B?"));
    assertEquals("አበበ ከበደ", IsoImageExport.jolietVolumeId("አበበ ከበደ"));
  }
}
