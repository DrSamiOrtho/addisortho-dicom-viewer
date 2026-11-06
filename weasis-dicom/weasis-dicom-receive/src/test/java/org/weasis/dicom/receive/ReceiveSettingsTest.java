/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.receive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ReceiveSettingsTest {

  private static ReceiveSettings with(String ae, int port, String listen, AllowedSender sender) {
    return new ReceiveSettings(
        true, ae, port, listen, List.of(sender), Path.of("/tmp/x"), 2, 2L * 1024 * 1024, false);
  }

  @Test
  void defaultsAreOffWithNobodyAllowed() {
    ReceiveSettings d = ReceiveSettings.defaults();
    assertFalse(d.enabled());
    assertEquals("AOVIEWER", d.aeTitle());
    assertEquals(11112, d.port());
    assertTrue(d.allowedSenders().isEmpty());
    assertTrue(d.firstError().isEmpty());
    assertEquals(d, ReceiveSettings.fromProperties(new Properties()));
  }

  @Test
  void settingsSurviveARoundTrip() {
    ReceiveSettings s =
        with("MY AE", 2000, "192.168.1.5", new AllowedSender("XRAY", "192.168.1.9"));
    assertEquals(s, ReceiveSettings.fromProperties(s.toProperties()));
  }

  @Test
  void invalidValuesAreFound() {
    AllowedSender ok = new AllowedSender("XRAY", "10.0.0.1");
    assertEquals(
        "receive.error.ae.title", with("WAY TOO LONG AE TITLE", 1, "", ok).firstError().orElse(""));
    assertEquals("receive.error.ae.title", with("BAD\\AE", 1, "", ok).firstError().orElse(""));
    assertEquals("receive.error.port", with("AE", 70000, "", ok).firstError().orElse(""));
    assertEquals(
        "receive.error.listen.address", with("AE", 1, "host.name", ok).firstError().orElse(""));
    assertEquals(
        "receive.error.sender",
        with("AE", 1, "", new AllowedSender("XRAY", "machine.local")).firstError().orElse(""));
  }

  @Test
  void aSenderMatchesOnBothAeTitleAndAddress() throws Exception {
    AllowedSender sender = new AllowedSender("XRAY", "10.0.0.1");
    InetAddress right = InetAddress.getByName("10.0.0.1");
    InetAddress wrong = InetAddress.getByName("10.0.0.2");
    assertTrue(sender.matches("XRAY", right));
    assertFalse(sender.matches("xray", right));
    assertFalse(sender.matches("XRAY", wrong));
  }

  @Test
  void aBrokenSenderInTheFileIsDropped() {
    Properties p = new Properties();
    p.setProperty("allowed.senders", "XRAY@10.0.0.1;nonsense;@1.1.1.1;CT@host.name");
    assertEquals(
        List.of(new AllowedSender("XRAY", "10.0.0.1")),
        ReceiveSettings.fromProperties(p).allowedSenders());
  }
}
