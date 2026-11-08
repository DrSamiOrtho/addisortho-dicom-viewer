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

/** What can go wrong while receiving, each with a plain message for the user. */
public enum ReceiveProblem {
  DISK_FULL("receive.problem.disk.full"),
  FOLDER_NOT_WRITABLE("receive.problem.folder"),
  FILE_TOO_LARGE("receive.problem.too.large"),
  NOT_DICOM("receive.problem.not.dicom"),
  CONFLICT("receive.problem.conflict"),
  SENDER_REJECTED("receive.problem.sender");

  private final String messageKey;

  ReceiveProblem(String messageKey) {
    this.messageKey = messageKey;
  }

  public String messageKey() {
    return messageKey;
  }
}
