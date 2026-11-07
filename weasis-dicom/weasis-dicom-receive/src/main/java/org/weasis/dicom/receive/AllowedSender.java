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

import java.net.InetAddress;
import java.util.Optional;

/** A device allowed to send images: its calling AE title and its IP address, both exact. */
public record AllowedSender(String aeTitle, String ipAddress) {

  private static final char SEPARATOR = '@';

  public boolean matches(String callingAet, InetAddress remote) {
    return aeTitle.equals(callingAet) && parseIp(ipAddress).filter(remote::equals).isPresent();
  }

  public boolean isValid() {
    return ReceiveSettings.isValidAeTitle(aeTitle) && parseIp(ipAddress).isPresent();
  }

  String encode() {
    return aeTitle + SEPARATOR + ipAddress;
  }

  static Optional<AllowedSender> decode(String text) {
    int index = text.lastIndexOf(SEPARATOR);
    if (index < 1) {
      return Optional.empty();
    }
    var sender =
        new AllowedSender(text.substring(0, index).trim(), text.substring(index + 1).trim());
    return sender.isValid() ? Optional.of(sender) : Optional.empty();
  }

  /** Accepts literal addresses only, so that no DNS lookup can happen here. */
  static Optional<InetAddress> parseIp(String text) {
    try {
      return text == null || text.isBlank()
          ? Optional.empty()
          : Optional.of(InetAddress.ofLiteral(text.trim()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
