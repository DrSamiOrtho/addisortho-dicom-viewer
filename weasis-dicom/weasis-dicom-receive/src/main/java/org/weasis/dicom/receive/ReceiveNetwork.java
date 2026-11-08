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

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the setup page needs to know about this computer's network. */
final class ReceiveNetwork {
  private static final int CONNECT_TIMEOUT_MS = 2000;

  private ReceiveNetwork() {}

  /** The addresses a scanner on the local network could reach this computer at. */
  static List<String> localAddresses() {
    List<String> out = new ArrayList<>();
    try {
      for (NetworkInterface card : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (card.isUp() && !card.isLoopback()) {
          for (InetAddress address : Collections.list(card.getInetAddresses())) {
            if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
              out.add(address.getHostAddress());
            }
          }
        }
      }
    } catch (SocketException e) {
      return List.of();
    }
    return out;
  }

  /**
   * Whether something on this computer accepts connections on the port. This proves the receiver is
   * listening; it cannot prove that a scanner is configured to send here, or that a firewall lets
   * it through.
   */
  static boolean isListening(String host, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
