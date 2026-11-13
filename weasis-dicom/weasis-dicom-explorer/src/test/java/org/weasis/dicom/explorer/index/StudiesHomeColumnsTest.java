/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class StudiesHomeColumnsTest {
  @Test
  void patientRecordNumberAndDateAreNeverDropped() {
    assertEquals(Set.of(0, 1, 2), StudiesHome.columnsFor(300));
  }

  @Test
  void columnsReturnByPriorityAsTheTableWidens() {
    assertEquals(Set.of(0, 1, 2), StudiesHome.columnsFor(600));
    assertEquals(Set.of(0, 1, 2, 3), StudiesHome.columnsFor(650));
    assertEquals(Set.of(0, 1, 2, 3, 4), StudiesHome.columnsFor(720));
    assertEquals(Set.of(0, 1, 2, 3, 4, 5), StudiesHome.columnsFor(800));
    assertEquals(Set.of(0, 1, 2, 3, 4, 5, 6), StudiesHome.columnsFor(1000));
  }
}
