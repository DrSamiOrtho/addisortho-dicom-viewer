/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.core.ui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class AppShellTest {

  @Test
  void startsInStudiesAndKeepsReviewOnceSomethingOpened() {
    AppShell shell = new AppShell();
    shell.build(new JPanel());
    assertEquals(AppShell.Place.REVIEW, shell.current());
    shell.setHome(new JPanel());
    assertEquals(AppShell.Place.STUDIES, shell.current());

    shell.showViewer();
    shell.setHome(new JPanel());
    assertEquals(AppShell.Place.REVIEW, shell.current());
  }

  @Test
  void directOpenBeforeHomeStaysInReview() {
    AppShell shell = new AppShell();
    shell.showViewer();
    shell.build(new JPanel());
    shell.setHome(new JPanel());
    assertEquals(AppShell.Place.REVIEW, shell.current());
  }

  @Test
  void whenHomeRunsOnceTheHomeIsThere() {
    AppShell shell = new AppShell();
    AtomicInteger runs = new AtomicInteger();
    shell.whenHome(runs::incrementAndGet);
    assertEquals(0, runs.get());
    shell.setHome(new JPanel());
    assertEquals(1, runs.get());
    shell.whenHome(runs::incrementAndGet);
    assertEquals(2, runs.get());
    shell.setHome(new JPanel());
    assertEquals(2, runs.get());
  }
}
