/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageFilesTest {

  @TempDir Path tmp;

  private PageFiles loadFrom(String list, String... files) throws IOException {
    Path dir = Files.createDirectories(tmp.resolve("web"));
    for (int i = 0; i < files.length; i += 2) {
      Files.writeString(dir.resolve(files[i]), files[i + 1]);
    }
    Files.writeString(dir.resolve(PageFiles.LIST), list);
    try (URLClassLoader cl = new URLClassLoader(new URL[] {tmp.toUri().toURL()}, null)) {
      return PageFiles.load(cl, "web/");
    }
  }

  private static String line(String name, String content) {
    return PageFiles.sha256(content.getBytes(StandardCharsets.UTF_8)) + "  " + name + "\n";
  }

  private static final String HTML = "<html lang=\"en\" data-mode=\"viewer\" data-release=\"r28\">";
  private static final String LIB = "{\"libraryId\":\"abc\",\"families\":[{\"range\":\"ao-cup\"}]}";
  private static final String REL = "release r28\nlibraryId abc\nranges ao-cup\n";

  /** A page with index.html, library.json and RELEASE.txt as given, each listed with its sha256. */
  private PageFiles page(String html, String lib, String rel) throws IOException {
    return loadFrom(
        line("index.html", html) + line("library.json", lib) + line(PageFiles.RELEASE, rel),
        "index.html", html, "library.json", lib, PageFiles.RELEASE, rel);
  }

  @Test
  void loadsExactlyTheListedFiles() throws IOException {
    PageFiles p =
        loadFrom(
            line("index.html", HTML) + line("library.json", LIB) + line(PageFiles.RELEASE, REL),
            "index.html", HTML, "library.json", LIB, PageFiles.RELEASE, REL, "extra.js", "alert(1)");
    assertNotNull(p.get("index.html"));
    assertNull(p.get("extra.js"));
    assertNull(p.get(PageFiles.LIST));
    assertEquals(12, p.build().length());
    assertEquals("r28", p.release());
  }

  @Test
  void refusesAChangedFile() {
    IOException e =
        assertThrows(
            IOException.class,
            () ->
                loadFrom(
                    line("index.html", "<p>") + line("library.json", "{}"),
                    "index.html", "<p>changed", "library.json", "{}"));
    assertTrue(e.getMessage().contains("index.html does not match"), e.getMessage());
  }

  @Test
  void refusesAPathOutOfTheFolderOrAnUnknownType() {
    String ok = line("index.html", "<p>") + line("library.json", "{}");
    assertThrows(
        IOException.class,
        () -> loadFrom(ok + line("../secret.txt", "x"), "index.html", "<p>", "library.json", "{}"));
    assertThrows(
        IOException.class,
        () -> loadFrom(ok + line("run.sh", "x"), "index.html", "<p>", "library.json", "{}", "run.sh", "x"));
    assertThrows(IOException.class, () -> loadFrom(line("index.html", "<p>"), "index.html", "<p>"));
  }

  @Test
  void refusesAPageWithoutAMatchingRelease() {
    // no RELEASE.txt at all: a page built before 23 Oct 2026
    IOException none =
        assertThrows(
            IOException.class,
            () -> loadFrom(line("index.html", HTML) + line("library.json", LIB), "index.html", HTML, "library.json", LIB));
    assertTrue(none.getMessage().contains("RELEASE.txt"), none.getMessage());
    // another maker's range, in RELEASE.txt or only in library.json
    assertThrows(IOException.class, () -> page(HTML, LIB, "release r28\nlibraryId abc\nranges zb-g7\n"));
    assertThrows(
        IOException.class,
        () ->
            page(
                HTML,
                "{\"libraryId\":\"abc\",\"families\":[{\"range\":\"ao-cup\"},{\"range\":\"jnj-corail\"}]}",
                REL));
    // a range RELEASE.txt names that the library does not have
    assertThrows(IOException.class, () -> page(HTML, LIB, "release r28\nlibraryId abc\nranges ao-cup ao-160\n"));
    // another library, another release on the page, a release not like r28
    assertThrows(IOException.class, () -> page(HTML, LIB, "release r28\nlibraryId xyz\nranges ao-cup\n"));
    assertThrows(IOException.class, () -> page(HTML.replace("r28", "r27"), LIB, REL));
    assertThrows(IOException.class, () -> page(HTML, LIB, REL.replace("r28", "28")));
  }

  @Test
  void theBundledPageIsComplete() throws IOException {
    PageFiles p = PageFiles.load(getClass().getClassLoader(), "templating-web/");
    for (String f : new String[] {"index.html", "app.js", "library.json", "vendor/openjpegwasm_decode.wasm"}) {
      assertNotNull(p.get(f), f);
    }
    String html = new String(p.get("index.html"), StandardCharsets.UTF_8);
    assertTrue(html.contains("<html lang=\"en\" data-mode=\"viewer\" data-release=\"" + p.release() + "\">"));
    String lib = new String(p.get("library.json"), StandardCharsets.UTF_8);
    assertTrue(lib.contains("\"libraryId\":\"ac4667dfef91\""));
    // the Addis Ortho ranges (23 Oct 2026, r28): the 160 stems with BB1, the CCK and tumour knees; no other maker
    for (String id : new String[] {"chunli-160-150", "chunli-bb1-b", "chunli-cck-femur", "chunli-dfr-femur"}) {
      assertTrue(lib.contains("\"id\":\"" + id + "\""), id);
    }
    // r33 (26 Oct 2026): the proximal femoral and proximal humeral replacements
    for (String id : new String[] {"chunli-pfr", "chunli-phr"}) {
      assertTrue(lib.contains("\"id\":\"" + id + "\""), id);
    }
    // r44 (31 Oct 2026): the live range files, with the bipolar and the sizes from Chunli's DWGs; no phone Quick link
    assertTrue(lib.contains("\"id\":\"chunli-bipolar\""));
    assertTrue(!html.contains("quick.html"));
    for (String range : new String[] {"jnj-", "zb-", "ldk-"}) {
      assertTrue(!lib.contains("\"range\":\"" + range), range);
    }
    assertEquals("r44", p.release());
  }
}
