/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class Make3dCommandTest {

  private static Make3dCommand cmd(String region, String label, boolean keep) {
    return new Make3dCommand(
        Path.of("/p/make_3d.sh"), Path.of("/t/ct"), Path.of("/t/out"), region, label, keep);
  }

  @Test
  void buildsThePipelineCommand() {
    assertEquals(
        List.of(
            "/bin/bash",
            "/p/make_3d.sh",
            "/t/ct",
            "--label",
            "HIP-261014-1530",
            "--region",
            "hip",
            "--series",
            "best",
            "--out-dicom",
            "/t/out",
            "--keep-identity"),
        cmd("hip", "HIP-261014-1530", true).arguments());
    assertFalse(cmd("hip", "HIP-1", false).arguments().contains("--keep-identity"));
  }

  @Test
  void theCaseCodeHoldsNoName() {
    assertEquals(
        "SPINE-261014-0905", Make3dCommand.codeFor("spine", LocalDateTime.of(2026, 10, 14, 9, 5)));
    assertThrows(IllegalArgumentException.class, () -> cmd("hip", "Abebe Kebede", true));
    assertThrows(IllegalArgumentException.class, () -> cmd("hip", "x; rm -rf /", true));
  }

  @Test
  void onlyRegionsTheFreeModelCovers() {
    assertThrows(IllegalArgumentException.class, () -> cmd("knee", "KNEE-1", true));
    assertTrue(Make3dCommand.REGIONS.containsAll(List.of("hip", "shoulder", "spine")));
  }
}
