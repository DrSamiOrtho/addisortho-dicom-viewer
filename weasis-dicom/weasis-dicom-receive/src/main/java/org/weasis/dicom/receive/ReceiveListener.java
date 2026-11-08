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

import org.weasis.dicom.explorer.library.StoredInstance;

/** Called on the threads of the network associations. */
public interface ReceiveListener {

  /** A new image is stored. A resent image that was already stored is not reported. */
  void onStored(StoredInstance instance);

  /**
   * @param detail what is safe to show and log, never patient data: the sender of a rejected
   *     association, otherwise empty
   */
  void onProblem(ReceiveProblem problem, String detail);
}
