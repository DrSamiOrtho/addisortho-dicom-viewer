/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The templating page's viewer build (build_viewer.sh): exactly the files its BUILD.txt lists, each
 * checked against its sha256 when loaded, kept in memory. Nothing else can be served.
 */
public final class PageFiles {

  static final String LIST = "BUILD.txt";
  private static final Pattern LINE = Pattern.compile("([0-9a-f]{64})  ([A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)?)");
  private static final Map<String, String> TYPES =
      Map.of(
          "html", "text/html; charset=utf-8",
          "js", "text/javascript; charset=utf-8",
          "json", "application/json",
          "wasm", "application/wasm",
          "png", "image/png",
          "txt", "text/plain; charset=utf-8",
          "md", "text/plain; charset=utf-8");

  private final Map<String, byte[]> files;
  private final String build;

  private PageFiles(Map<String, byte[]> files, String build) {
    this.files = files;
    this.build = build;
  }

  /** Loads the build from {@code base} (e.g. "templating-web/") on the class path. */
  public static PageFiles load(ClassLoader loader, String base) throws IOException {
    byte[] list = read(loader, base + LIST);
    Map<String, byte[]> files = new TreeMap<>();
    try (BufferedReader r =
        new BufferedReader(
            new InputStreamReader(
                new java.io.ByteArrayInputStream(list), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        var m = LINE.matcher(line);
        if (!m.matches() || m.group(2).contains("..") || type(m.group(2)) == null) {
          throw new IOException("Templating page: bad line in " + LIST + ": " + line);
        }
        byte[] data = read(loader, base + m.group(2));
        if (!sha256(data).equals(m.group(1))) {
          throw new IOException("Templating page: " + m.group(2) + " does not match " + LIST);
        }
        files.put(m.group(2), data);
      }
    }
    if (!files.containsKey("index.html") || !files.containsKey("library.json")) {
      throw new IOException("Templating page: index.html or library.json missing");
    }
    return new PageFiles(Map.copyOf(files), sha256(list).substring(0, 12));
  }

  /** The file's bytes, or null when the build has no such file. */
  public byte[] get(String name) {
    return files.get(name);
  }

  /** The start of the sha256 of BUILD.txt: which page build this is. */
  public String build() {
    return build;
  }

  static String type(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? null : TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  private static byte[] read(ClassLoader loader, String path) throws IOException {
    try (InputStream in = loader.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("Templating page: " + path + " is missing");
      }
      return in.readAllBytes();
    }
  }

  static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
