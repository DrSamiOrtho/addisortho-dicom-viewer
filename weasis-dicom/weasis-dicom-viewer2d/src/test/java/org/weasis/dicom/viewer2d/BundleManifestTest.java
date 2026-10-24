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
package org.weasis.dicom.viewer2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the OSGi manifest bnd writes for this bundle before the tests run. The launcher exports
 * org.slf4j from the application class path without a version (framework.system.packages.extra in
 * base.json), which OSGi reads as 0.0.0, and no bundle exports it: an import with a version range
 * can never be met, the bundle stays unresolved and no DICOM series opens in the 2D viewer, nor in
 * MPR or 3D, which need this bundle. The parent pom strips the version ("org.slf4j;version=!"); a
 * module that replaces Import-Package must keep that clause. This happened once, when the video
 * export embedded JCodec.
 */
class BundleManifestTest {

  private static final Path MANIFEST = Path.of("target", "classes", "META-INF", "MANIFEST.MF");
  private static final Path LAUNCHER_CONFIG =
      Path.of("..", "..", "weasis-launcher", "conf", "base.json");

  private static Manifest manifest() throws IOException {
    try (InputStream in = Files.newInputStream(MANIFEST)) {
      return new Manifest(in);
    }
  }

  /** Splits a manifest header at the commas outside quotes. */
  static List<String> clauses(String header) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (char c : header.toCharArray()) {
      if (c == '"') {
        quoted = !quoted;
      }
      if (c == ',' && !quoted) {
        out.add(cur.toString().trim());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    if (!cur.isEmpty()) {
      out.add(cur.toString().trim());
    }
    return out;
  }

  @Test
  void slf4jIsImportedWithoutVersionAsTheLauncherExportsIt() throws IOException {
    String launcher = Files.readString(LAUNCHER_CONFIG);
    assertTrue(
        Pattern.compile("[\\s,\"]org\\.slf4j[,\"]").matcher(launcher).find(),
        "the launcher exports org.slf4j");
    String imports = manifest().getMainAttributes().getValue("Import-Package");
    List<String> slf4j =
        clauses(imports).stream().filter(c -> c.split(";")[0].startsWith("org.slf4j")).toList();
    assertEquals(List.of("org.slf4j"), slf4j, "an unversioned org.slf4j import");
  }

  @Test
  void theVideoEncoderIsInsideTheBundle() throws IOException {
    var attributes = manifest().getMainAttributes();
    assertTrue(
        clauses(attributes.getValue("Bundle-ClassPath")).contains("lib/jcodec-0.2.5.jar"),
        "Bundle-ClassPath");
    for (String clause : clauses(attributes.getValue("Import-Package"))) {
      assertTrue(!clause.startsWith("org.jcodec"), "JCodec is embedded, not imported: " + clause);
    }
  }
}
