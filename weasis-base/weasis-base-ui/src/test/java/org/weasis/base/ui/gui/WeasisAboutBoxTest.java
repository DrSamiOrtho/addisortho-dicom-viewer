/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.base.ui.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.core.api.util.ResourceUtil;

class WeasisAboutBoxTest {

  @TempDir Path resources;

  @Test
  void thirdPartyLicensesUrlPrefersTheLocalNoticeAndFallsBackToUpstream() throws IOException {
    ResourceUtil.setResourcePath(resources.toString());
    assertEquals(
        WeasisAboutBox.THIRD_PARTY_LICENSES_FALLBACK_URL,
        WeasisAboutBox.getThirdPartyLicensesUrl());

    Path notice = resources.resolve(WeasisAboutBox.THIRD_PARTY_LICENSES);
    Files.createDirectories(notice.getParent());
    Files.writeString(notice, "notice");
    assertEquals(notice, Path.of(URI.create(WeasisAboutBox.getThirdPartyLicensesUrl())));
  }
}
