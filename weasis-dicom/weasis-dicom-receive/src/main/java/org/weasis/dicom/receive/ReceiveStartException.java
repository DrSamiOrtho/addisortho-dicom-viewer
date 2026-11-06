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

/** The receiver could not start, with the key of a plain message for the user. */
public class ReceiveStartException extends Exception {

  private final String messageKey;

  public ReceiveStartException(String messageKey, Throwable cause) {
    super(messageKey, cause);
    this.messageKey = messageKey;
  }

  public String messageKey() {
    return messageKey;
  }
}
