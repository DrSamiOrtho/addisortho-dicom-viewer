/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.pipeline;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The command line of the owner's 3D bone pipeline (planning/make_3d.sh): segments the bones of one
 * CT series with TotalSegmentator on this computer, stores the results in the trial PACS and, with
 * --out-dicom, writes the same DICOM objects to a folder the viewer opens.
 */
public record Make3dCommand(
    Path script, Path ctFolder, Path outFolder, String region, String label, boolean keepName) {

  /**
   * The regions the free TotalSegmentator model covers (knee bones need a licence: not offered).
   */
  public static final List<String> REGIONS = List.of("hip", "shoulder", "spine");

  public Make3dCommand {
    Objects.requireNonNull(script);
    Objects.requireNonNull(ctFolder);
    Objects.requireNonNull(outFolder);
    if (!REGIONS.contains(region)) {
      throw new IllegalArgumentException("Unknown region: " + region);
    }
    if (label == null || !label.matches("[A-Za-z0-9_-]{1,40}")) {
      throw new IllegalArgumentException("The case code may only use letters, digits, - and _");
    }
  }

  /** A case code that never holds a name: the region and the time, e.g. HIP-261014-1530. */
  public static String codeFor(String region, LocalDateTime when) {
    return region.toUpperCase(java.util.Locale.ROOT)
        + "-"
        + when.format(DateTimeFormatter.ofPattern("yyMMdd-HHmm"));
  }

  public List<String> arguments() {
    List<String> a = new ArrayList<>();
    a.add("/bin/bash");
    a.add(script.toString());
    a.add(ctFolder.toString());
    a.add("--label");
    a.add(label);
    a.add("--region");
    a.add(region);
    a.add("--series");
    a.add("best");
    a.add("--out-dicom");
    a.add(outFolder.toString());
    if (keepName) {
      a.add("--keep-identity");
    }
    return a;
  }
}
