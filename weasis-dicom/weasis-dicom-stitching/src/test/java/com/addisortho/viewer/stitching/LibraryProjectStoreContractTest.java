/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import org.weasis.dicom.explorer.library.StudyStore;

/** The contract on the store the viewer uses: the study library's. */
class LibraryProjectStoreContractTest extends ProjectStoreContractTest {
  @Override
  ProjectStore open() {
    return new LibraryProjectStore(() -> new StudyStore(dir.resolve("library"), true));
  }
}
