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

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.AssociationHandler;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.AAssociateAC;
import org.dcm4che3.net.pdu.AAssociateRJ;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.dcm4che3.util.UIDUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.explorer.library.StoreNaming;
import org.weasis.dicom.explorer.library.StudyStore;

/**
 * The DICOM Storage SCP. It answers C-ECHO and C-STORE from the allowed senders only.
 *
 * <p>Nothing here logs a patient name, an MRN, or a storage path, which holds them. Exceptions from
 * the file system carry such paths, so only their class is logged, and they never go into a {@link
 * DicomServiceException}, which the dcm4che library logs and sends back to the machine.
 */
public class DicomReceiver {

  private static final Logger LOGGER = LoggerFactory.getLogger(DicomReceiver.class);

  private static final String ALL_INTERFACES = "0.0.0.0";
  private static final int REQUEST_TIMEOUT_MS = 30_000;
  private static final int IDLE_TIMEOUT_MS = 300_000;
  private static final int RELEASE_TIMEOUT_MS = 10_000;
  private static final int RESPONSE_TIMEOUT_MS = 30_000;
  private static final int WRITE_BUFFER = 64 * 1024;

  /**
   * Without the deflated syntax: its bytes would pass through the deflater of DicomOutputStream a
   * second time. Every sender must also offer Implicit VR Little Endian, so nothing is locked out.
   */
  private static final String[] TRANSFER_SYNTAXES = {
    UID.ExplicitVRLittleEndian,
    UID.ImplicitVRLittleEndian,
    UID.ExplicitVRBigEndian,
    UID.JPEGBaseline8Bit,
    UID.JPEGExtended12Bit,
    UID.JPEGLossless,
    UID.JPEGLosslessSV1,
    UID.JPEGLSLossless,
    UID.JPEGLSNearLossless,
    UID.JPEG2000Lossless,
    UID.JPEG2000,
    UID.RLELossless
  };

  private final ReceiveSettings settings;
  private final ReceiveListener listener;
  private final StudyStore store;

  private Device device;
  private ExecutorService executor;
  private ScheduledExecutorService scheduler;

  public DicomReceiver(ReceiveSettings settings, ReceiveListener listener) {
    this(
        settings,
        listener,
        new StudyStore(settings.storageFolder(), settings.includeNameInFileNames()));
  }

  public DicomReceiver(ReceiveSettings settings, ReceiveListener listener, StudyStore store) {
    this.settings = settings;
    this.listener = listener;
    this.store = store;
  }

  public StudyStore store() {
    return store;
  }

  public synchronized boolean isRunning() {
    return device != null;
  }

  public synchronized void start() throws ReceiveStartException {
    if (device != null) {
      return;
    }
    try {
      store.prepare();
    } catch (IOException | RuntimeException e) {
      LOGGER.warn("Storage folder is not usable: {}", e.getClass().getSimpleName());
      throw new ReceiveStartException("receive.start.folder", e);
    }

    Device newDevice = buildDevice();
    executor = Executors.newCachedThreadPool(daemonThreads("dicom-receive"));
    scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreads("dicom-receive-timer"));
    newDevice.setExecutor(executor);
    newDevice.setScheduledExecutor(scheduler);
    try {
      newDevice.bindConnections();
      device = newDevice;
    } catch (IOException | GeneralSecurityException | RuntimeException e) {
      shutdownExecutors();
      LOGGER.warn("Cannot listen on port {}: {}", settings.port(), e.getClass().getSimpleName());
      throw new ReceiveStartException(
          isPortInUse(e) ? "receive.start.port" : "receive.start.network", e);
    }
    LOGGER.info(
        "Receiving as AE title {} on port {}, {} allowed senders",
        settings.aeTitle(),
        settings.port(),
        settings.allowedSenders().size());
  }

  public synchronized void stop() {
    if (device == null) {
      return;
    }
    device.unbindConnections();
    for (Association association : device.listOpenAssociations()) {
      association.abort();
    }
    device = null;
    shutdownExecutors();
    LOGGER.info("Stopped receiving");
  }

  private void shutdownExecutors() {
    executor.shutdownNow();
    scheduler.shutdownNow();
  }

  private Device buildDevice() {
    Device newDevice = new Device("addisortho-receiver");
    Connection connection = new Connection();
    connection.setPort(settings.port());
    connection.setBindAddress(
        settings.listenAddress().isEmpty() ? ALL_INTERFACES : settings.listenAddress());
    connection.setHostname(
        settings.listenAddress().isEmpty() ? ALL_INTERFACES : settings.listenAddress());
    connection.setRequestTimeout(REQUEST_TIMEOUT_MS);
    connection.setIdleTimeout(IDLE_TIMEOUT_MS);
    connection.setReleaseTimeout(RELEASE_TIMEOUT_MS);
    connection.setResponseTimeout(RESPONSE_TIMEOUT_MS);
    newDevice.addConnection(connection);

    ApplicationEntity ae = new ApplicationEntity(settings.aeTitle());
    ae.setAssociationAcceptor(true);
    ae.setAssociationInitiator(false);
    ae.addConnection(connection);
    ae.addTransferCapability(
        new TransferCapability(null, "*", TransferCapability.Role.SCP, TRANSFER_SYNTAXES));
    newDevice.addApplicationEntity(ae);

    DicomServiceRegistry services = new DicomServiceRegistry();
    services.addDicomService(new BasicCEchoSCP());
    services.addDicomService(new StoreService());
    newDevice.setDimseRQHandler(services);
    newDevice.setAssociationHandler(new AllowListHandler());
    newDevice.setLimitOpenAssociations(settings.maxAssociations());
    return newDevice;
  }

  private boolean isAllowed(String callingAet, InetAddress remote) {
    return settings.allowedSenders().stream().anyMatch(s -> s.matches(callingAet, remote));
  }

  private static boolean isValidUid(String uid) {
    return uid != null && UIDUtils.isValid(uid);
  }

  private static boolean isPortInUse(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof BindException) {
        return true;
      }
    }
    return false;
  }

  private static ThreadFactory daemonThreads(String name) {
    return Thread.ofPlatform().name(name + "-", 0).daemon().factory();
  }

  private final class AllowListHandler extends AssociationHandler {

    @Override
    protected AAssociateAC negotiate(Association as, AAssociateRQ rq) throws IOException {
      InetAddress remote = as.getSocket().getInetAddress();
      if (!isAllowed(rq.getCallingAET(), remote)) {
        String sender =
            StoreNaming.safeForLog(rq.getCallingAET()) + " / " + remote.getHostAddress();
        LOGGER.warn("Rejected association from {}", sender);
        listener.onProblem(ReceiveProblem.SENDER_REJECTED, sender);
        throw new AAssociateRJ(
            AAssociateRJ.RESULT_REJECTED_PERMANENT,
            AAssociateRJ.SOURCE_SERVICE_USER,
            AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED);
      }
      return super.negotiate(as, rq);
    }
  }

  private final class StoreService extends BasicCStoreSCP {

    @Override
    protected void store(
        Association as, PresentationContext pc, Attributes rq, PDVInputStream data, Attributes rsp)
        throws IOException {
      String sopUid = rq.getString(Tag.AffectedSOPInstanceUID);
      String sopClass = rq.getString(Tag.AffectedSOPClassUID);
      if (!isValidUid(sopUid)) {
        throw refuseNotDicom("Invalid SOP Instance UID");
      }
      requireFreeSpace();
      Path temp = store.newTempFile();
      try {
        receiveToTemp(as, pc, data, sopUid, sopClass, temp);
        Attributes dataset = readAndCheck(temp, sopUid);
        commit(temp, dataset, sopUid);
      } catch (RuntimeException e) {
        // Without a response, the library would drop the whole association.
        LOGGER.error("Unexpected failure on image {}: {}", sopUid, e.getClass().getName());
        throw new DicomServiceException(Status.ProcessingFailure, "Unexpected failure");
      } finally {
        deleteQuietly(temp);
      }
    }

    private void requireFreeSpace() throws DicomServiceException {
      boolean enough;
      try {
        enough = store.hasFreeSpace();
      } catch (IOException e) {
        throw refuseFolderProblem(e);
      }
      if (!enough) {
        LOGGER.warn("Refused an image: the disk is nearly full");
        listener.onProblem(ReceiveProblem.DISK_FULL, "");
        throw new DicomServiceException(Status.OutOfResources, "Disk full");
      }
    }

    private void receiveToTemp(
        Association as,
        PresentationContext pc,
        PDVInputStream data,
        String sopUid,
        String sopClass,
        Path temp)
        throws IOException {
      Attributes fmi = as.createFileMetaInformation(sopUid, sopClass, pc.getTransferSyntax());
      try (OutputStream raw = openTemp(temp);
          DicomOutputStream out =
              new DicomOutputStream(
                  new GuardedOutput(
                      new BufferedOutputStream(raw, WRITE_BUFFER), settings.maxFileSizeBytes()),
                  UID.ExplicitVRLittleEndian)) {
        out.writeFileMetaInformation(fmi);
        data.copyTo(out);
      } catch (FileTooLargeException e) {
        LOGGER.warn("Refused image {}: above the size limit", sopUid);
        listener.onProblem(ReceiveProblem.FILE_TOO_LARGE, "");
        throw new DicomServiceException(Status.OutOfResources, "Above the size limit");
      } catch (LocalWriteException e) {
        throw refuseFolderProblem(e);
      }
    }

    private OutputStream openTemp(Path temp) throws DicomServiceException {
      try {
        return store.openTempFile(temp);
      } catch (IOException e) {
        throw refuseFolderProblem(e);
      }
    }

    /** A full disk and a lost folder both refuse with out of resources: the machine can resend. */
    private DicomServiceException refuseFolderProblem(IOException e) {
      boolean full = isDiskFull();
      LOGGER.warn("Cannot write an image: {}", e.getClass().getSimpleName());
      listener.onProblem(full ? ReceiveProblem.DISK_FULL : ReceiveProblem.FOLDER_NOT_WRITABLE, "");
      return new DicomServiceException(Status.OutOfResources, "Cannot store the image");
    }

    private boolean isDiskFull() {
      try {
        return !store.hasFreeSpace();
      } catch (IOException e) {
        return false;
      }
    }

    private Attributes readAndCheck(Path temp, String sopUid) throws DicomServiceException {
      Attributes dataset;
      try (DicomInputStream in = new DicomInputStream(temp.toFile())) {
        dataset = in.readDatasetUntilPixelData();
      } catch (IOException | RuntimeException | OutOfMemoryError e) {
        throw refuseNotDicom("Cannot parse the data set");
      }
      if (!sopUid.equals(dataset.getString(Tag.SOPInstanceUID))) {
        throw refuseNotDicom("SOP Instance UID missing or different from the request");
      }
      if (!isValidUid(dataset.getString(Tag.StudyInstanceUID))
          || !isValidUid(dataset.getString(Tag.SeriesInstanceUID))) {
        throw refuseNotDicom("Study or Series Instance UID missing");
      }
      return dataset;
    }

    private DicomServiceException refuseNotDicom(String reason) {
      LOGGER.warn("Refused an object: {}", reason);
      listener.onProblem(ReceiveProblem.NOT_DICOM, "");
      return new DicomServiceException(Status.CannotUnderstand, reason);
    }

    private void commit(Path temp, Attributes dataset, String sopUid) throws IOException {
      String studyUid = dataset.getString(Tag.StudyInstanceUID);
      String seriesUid = dataset.getString(Tag.SeriesInstanceUID);
      StudyStore.Commit commit;
      try {
        commit = store.commit(temp, dataset, studyUid, seriesUid, sopUid);
      } catch (IOException | RuntimeException e) {
        IOException cause = e instanceof IOException io ? io : new IOException();
        throw refuseFolderProblem(cause);
      }
      switch (commit.outcome()) {
        case STORED -> {
          LOGGER.debug("Stored image {} of study {}", sopUid, studyUid);
          listener.onStored(commit.instance());
        }
        case ALREADY_HELD ->
            LOGGER.info("Image {} was already stored, the first copy is kept", sopUid);
        case CONFLICT -> {
          LOGGER.warn("Image {} clashes with a stored image and was quarantined", sopUid);
          listener.onProblem(ReceiveProblem.CONFLICT, "");
          throw new DicomServiceException(
              Status.ProcessingFailure, "Conflicts with a stored image");
        }
      }
    }

    private void deleteQuietly(Path temp) {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException e) {
        LOGGER.debug("Temporary file not removed: {}", e.getClass().getSimpleName());
      }
    }
  }

  private static final class FileTooLargeException extends IOException {}

  private static final class LocalWriteException extends IOException {
    LocalWriteException(IOException cause) {
      super(cause.getClass().getSimpleName());
    }
  }

  /** Tells a failure to write here from a failure to read the network, and enforces the limit. */
  private static final class GuardedOutput extends FilterOutputStream {
    private final long limit;
    private long written;

    GuardedOutput(OutputStream out, long limit) {
      super(out);
      this.limit = limit;
    }

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      written += len;
      if (written > limit) {
        throw new FileTooLargeException();
      }
      try {
        out.write(b, off, len);
      } catch (IOException e) {
        throw new LocalWriteException(e);
      }
    }

    @Override
    public void flush() throws IOException {
      try {
        out.flush();
      } catch (IOException e) {
        throw new LocalWriteException(e);
      }
    }

    @Override
    public void close() throws IOException {
      try {
        out.close();
      } catch (IOException e) {
        throw new LocalWriteException(e);
      }
    }
  }
}
