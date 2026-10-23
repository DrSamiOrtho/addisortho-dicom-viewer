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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The templating page's viewer build (build_viewer.sh): exactly the files its BUILD.txt lists, each
 * checked against its sha256 when loaded, kept in memory. Nothing else can be served.
 *
 * <p>Its RELEASE.txt (23 Oct 2026) names the release (e.g. r28), the libraryId and the implant
 * ranges. The build is refused unless library.json and index.html agree with it and every range
 * is an Addis Ortho one (ao-*): the installer is a public download, so no other maker's
 * templates.
 */
public final class PageFiles {

  static final String LIST = "BUILD.txt";
  static final String RELEASE = "RELEASE.txt";
  private static final Pattern RELEASE_TEXT =
      Pattern.compile(
          "release (r[0-9]{1,4}[a-z]?)\nlibraryId ([A-Za-z0-9-]{1,40})\n"
              + "ranges (ao-[a-z0-9]{2,12}(?: ao-[a-z0-9]{2,12})*)\n");
  private static final Pattern FAMILY_RANGE = Pattern.compile("\"range\":\"([^\"]*)\"");
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
  private final String release;

  private PageFiles(Map<String, byte[]> files, String build, String release) {
    this.files = files;
    this.build = build;
    this.release = release;
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
    String release = checkRelease(files);
    return new PageFiles(Map.copyOf(files), sha256(list).substring(0, 12), release);
  }

  /** The release RELEASE.txt names, once library.json and index.html agree with it. */
  static String checkRelease(Map<String, byte[]> files) throws IOException {
    byte[] text = files.get(RELEASE);
    var m =
        text == null ? null : RELEASE_TEXT.matcher(new String(text, StandardCharsets.UTF_8));
    if (m == null || !m.matches()) {
      throw new IOException("Templating page: no " + RELEASE + " naming its release and Addis Ortho ranges");
    }
    String library = new String(files.get("library.json"), StandardCharsets.UTF_8);
    if (!library.contains("\"libraryId\":\"" + m.group(2) + "\"")) {
      throw new IOException("Templating page: library.json is not library " + m.group(2));
    }
    Set<String> ranges = new TreeSet<>(Arrays.asList(m.group(3).split(" ")));
    Set<String> used = new TreeSet<>();
    FAMILY_RANGE.matcher(library).results().forEach(r -> used.add(r.group(1)));
    if (!used.equals(ranges)) {
      throw new IOException(
          "Templating page: library.json has ranges " + used + ", " + RELEASE + " " + ranges);
    }
    String html = new String(files.get("index.html"), StandardCharsets.UTF_8);
    if (!html.contains(" data-release=\"" + m.group(1) + "\"")) {
      throw new IOException("Templating page: index.html is not release " + m.group(1));
    }
    return m.group(1);
  }

  /** The file's bytes, or null when the build has no such file. */
  public byte[] get(String name) {
    return files.get(name);
  }

  /** The start of the sha256 of BUILD.txt: which page build this is. */
  public String build() {
    return build;
  }

  /** The page's release, e.g. r28. */
  public String release() {
    return release;
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
