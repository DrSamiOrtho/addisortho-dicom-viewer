/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.receive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class ReceiveNetworkTest {

  @Test
  void aPortThatIsListeningIsFoundAndOneThatIsNotIsNot() throws Exception {
    int port;
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = server.getLocalPort();
      assertTrue(ReceiveNetwork.isListening("127.0.0.1", port));
    }
    assertFalse(ReceiveNetwork.isListening("127.0.0.1", port));
  }

  @Test
  void onlyLocalNetworkAddressesAreOffered() {
    for (String address : ReceiveNetwork.localAddresses()) {
      assertFalse(address.startsWith("127."), address);
      assertTrue(address.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"), address);
    }
  }
}
