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

  @Test
  void loadsExactlyTheListedFiles() throws IOException {
    PageFiles p =
        loadFrom(
            line("index.html", "<p>") + line("library.json", "{}"),
            "index.html", "<p>", "library.json", "{}", "extra.js", "alert(1)");
    assertNotNull(p.get("index.html"));
    assertNull(p.get("extra.js"));
    assertNull(p.get(PageFiles.LIST));
    assertEquals(12, p.build().length());
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
  void theBundledPageIsComplete() throws IOException {
    PageFiles p = PageFiles.load(getClass().getClassLoader(), "templating-web/");
    for (String f : new String[] {"index.html", "app.js", "library.json", "vendor/openjpegwasm_decode.wasm"}) {
      assertNotNull(p.get(f), f);
    }
    String html = new String(p.get("index.html"), StandardCharsets.UTF_8);
    assertTrue(html.contains("<html lang=\"en\" data-mode=\"viewer\">"));
    String lib = new String(p.get("library.json"), StandardCharsets.UTF_8);
    assertTrue(lib.contains("\"libraryId\":\"656ef0f7cb39\""));
    for (String range : new String[] {"ao-130", "ao-160", "jnj-", "zb-"}) {
      assertTrue(!lib.contains("\"range\":\"" + range), range);
    }
  }
}
