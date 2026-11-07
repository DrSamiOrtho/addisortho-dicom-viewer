/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
import java.nio.file.Path;
import org.dcm4che3.img.DicomImageAdapter;
import org.dcm4che3.img.DicomImageReadParam;
import org.dcm4che3.img.DicomImageReader;
import org.dcm4che3.img.DicomMetaData;
import org.dcm4che3.img.ImageRendering;
import org.dcm4che3.img.Transcoder;
import org.dcm4che3.img.stream.DicomFileInputStream;
import org.dcm4che3.img.stream.ImageDescriptor;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.weasis.opencv.data.PlanarImage;

/** The same decode steps as DicomMediaIO.getImageFragment, printing the pixel range at each step. */
public class Probe {
  static String range(PlanarImage img) {
    Mat m = img.toMat();
    Core.MinMaxLocResult r = Core.minMaxLoc(m);
    double[] mid = m.get(m.rows() / 2, m.cols() / 2);
    return img.width()
        + "x"
        + img.height()
        + " type="
        + img.type()
        + " min="
        + r.minVal
        + " max="
        + r.maxVal
        + " middle="
        + (mid == null ? "none" : mid[0]);
  }

  public static void main(String[] args) throws Exception {
    // The decoder library loads opencv_java itself from -Djava.library.path.
    System.out.println("os=" + System.getProperty("os.name") + " arch=" + System.getProperty("os.arch"));
    System.out.println("opencv=" + Core.VERSION);
    for (int i = 0; i < args.length; i++) {
      Path p = Path.of(args[i]).toAbsolutePath();
      String name = p.getFileName().toString();
      DicomImageReader reader = new DicomImageReader(Transcoder.dicomImageReaderSpi);
      try (DicomFileInputStream in = new DicomFileInputStream(p)) {
        reader.setInput(in);
        DicomMetaData meta = reader.getStreamMetadata();
        ImageDescriptor desc = meta.getImageDescriptor();
        DicomImageReadParam param = new DicomImageReadParam();
        param.setAllowFloatImageConversion(true);
        PlanarImage img = reader.getPlanarImage(0, param);
        System.out.println("RESULT " + name + " decoded: " + range(img));
        PlanarImage clean = ImageRendering.getImageWithoutEmbeddedOverlay(img, desc, 0);
        System.out.println("RESULT " + name + " no-overlay: " + range(clean));
        Core.MinMaxLocResult mm = new DicomImageAdapter(clean, desc, 0).getMinMax();
        System.out.println("RESULT " + name + " adapter: min=" + mm.minVal + " max=" + mm.maxVal);
      } catch (Throwable t) {
        System.out.println("RESULT " + name + " FAILED: " + t);
        t.printStackTrace(System.out);
      } finally {
        reader.dispose();
      }
    }
  }
}
