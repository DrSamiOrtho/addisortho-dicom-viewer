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

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.DataWriter;
import org.dcm4che3.net.DataWriterAdapter;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSP;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;

/** An in-process Storage SCU, standing in for an X-ray machine. */
final class TestScu implements AutoCloseable {
  static final String SOP_CLASS = UID.SecondaryCaptureImageStorage;
  private static final String TS = UID.ExplicitVRLittleEndian;

  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final Association association;

  TestScu(String callingAet, String calledAet, int port) throws Exception {
    Device device = new Device("test-scu");
    Connection local = new Connection();
    device.addConnection(local);
    ApplicationEntity ae = new ApplicationEntity(callingAet);
    ae.addConnection(local);
    device.addApplicationEntity(ae);
    device.setExecutor(executor);
    device.setScheduledExecutor(scheduler);

    Connection remote = new Connection();
    remote.setHostname("127.0.0.1");
    remote.setPort(port);

    AAssociateRQ rq = new AAssociateRQ();
    rq.setCallingAET(callingAet);
    rq.setCalledAET(calledAet);
    rq.addPresentationContext(new PresentationContext(1, SOP_CLASS, TS));
    rq.addPresentationContext(new PresentationContext(3, UID.Verification, TS));
    try {
      association = ae.connect(local, remote, rq);
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  int store(Attributes dataset) throws Exception {
    return send(dataset.getString(Tag.SOPInstanceUID), new DataWriterAdapter(dataset));
  }

  /** Sends bytes as they are, as if they were a data set. */
  int storeRaw(String sopUid, byte[] bytes) throws Exception {
    return send(sopUid, (out, tsuid) -> out.write(bytes));
  }

  private int send(String sopUid, DataWriter writer) throws Exception {
    DimseRSP rsp = association.cstore(SOP_CLASS, sopUid, 0, writer, TS);
    rsp.next();
    return rsp.getCommand().getInt(Tag.Status, -1);
  }

  int echo() throws Exception {
    DimseRSP rsp = association.cecho();
    rsp.next();
    return rsp.getCommand().getInt(Tag.Status, -1);
  }

  @Override
  public void close() {
    try {
      if (association != null && association.isReadyForDataTransfer()) {
        association.waitForOutstandingRSP();
        association.release();
        association.waitForSocketClose();
      }
    } catch (IOException | InterruptedException e) {
      // The test is over.
    }
    executor.shutdownNow();
    scheduler.shutdownNow();
  }
}
