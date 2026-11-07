/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import java.awt.Component;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiUtils;

/**
 * Opens a templating page in its own window, without the browser's tabs and address bar, using Edge
 * or Chrome (the browsers the page supports). Without either, it opens in the default browser.
 */
final class PageWindow {

  private static final Logger LOGGER = LoggerFactory.getLogger(PageWindow.class);

  private PageWindow() {}

  static void open(Component parent, URI page) {
    List<String> command =
        command(System.getProperty("os.name", ""), page, System.getenv(), Files::isExecutable);
    if (command != null) {
      try {
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        return;
      } catch (IOException e) {
        LOGGER.warn("Cannot open the page in its own window, using the default browser", e);
      }
    }
    GuiUtils.openInDefaultBrowser(parent, page);
  }

  /** The command for the page's window, or null when neither Edge nor Chrome is found. */
  static List<String> command(
      String osName, URI page, Map<String, String> env, Predicate<Path> executable) {
    for (String browser : candidates(osName, env)) {
      if (executable.test(Path.of(browser))) {
        return List.of(browser, "--app=" + page);
      }
    }
    return null;
  }

  private static List<String> candidates(String osName, Map<String, String> env) {
    String os = osName.toLowerCase(Locale.ROOT);
    List<String> found = new ArrayList<>();
    if (os.startsWith("windows")) {
      String edge = "\\Microsoft\\Edge\\Application\\msedge.exe";
      String chrome = "\\Google\\Chrome\\Application\\chrome.exe";
      for (String tail : List.of(edge, chrome)) {
        for (String root : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
          String dir = env.get(root);
          if (dir != null && !dir.isBlank()) {
            found.add(dir + tail);
          }
        }
      }
    } else if (os.contains("mac")) {
      found.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
      found.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
    }
    return found;
  }
}
