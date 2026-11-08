/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.send;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dcm4che3.net.Status;
import org.junit.jupiter.api.Test;

class SendDicomViewTest {

  @Test
  void onlyACompletedTransferCountsAsSent() {
    assertTrue(SendDicomView.delivered(Status.Success));
    assertFalse(SendDicomView.delivered(Status.Cancel));
    assertFalse(SendDicomView.delivered(Status.ProcessingFailure));
  }
}
