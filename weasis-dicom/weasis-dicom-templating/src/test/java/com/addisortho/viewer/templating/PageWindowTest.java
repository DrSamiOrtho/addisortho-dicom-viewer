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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PageWindowTest {

  private static final URI PAGE = URI.create("http://127.0.0.1:47210/t/a/templating/quick.html");

  @Test
  void macPrefersEdgeThenChrome() {
    String chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
    assertEquals(
        List.of(chrome, "--app=" + PAGE),
        PageWindow.command("Mac OS X", PAGE, Map.of(), p -> p.toString().equals(chrome)));
  }

  @Test
  void neitherBrowserFallsBackToTheDefault() {
    assertNull(PageWindow.command("Mac OS X", PAGE, Map.of(), p -> false));
    assertNull(PageWindow.command("Linux", PAGE, Map.of(), p -> true));
  }

  @Test
  void windowsLooksInTheInstallFolders() {
    List<String> command =
        PageWindow.command("Windows 11", PAGE, Map.of("ProgramFiles(x86)", "C:\\PF86"), p -> true);
    assertEquals(
        List.of("C:\\PF86\\Microsoft\\Edge\\Application\\msedge.exe", "--app=" + PAGE), command);
  }
}
