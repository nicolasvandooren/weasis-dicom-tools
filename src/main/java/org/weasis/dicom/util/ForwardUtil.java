/*
 * Copyright (c) 2017-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.dcm4che6.codec.JPEGParser;
import org.dcm4che6.data.DataFragment;
import org.dcm4che6.data.DicomElement;
import org.dcm4che6.data.DicomObject;
import org.dcm4che6.data.Tag;
import org.dcm4che6.data.UID;
import org.dcm4che6.img.DicomImageReader;
import org.dcm4che6.img.DicomImageUtils;
import org.dcm4che6.img.DicomOutputData;
import org.dcm4che6.img.DicomTranscodeParam;
import org.dcm4che6.img.Transcoder;
import org.dcm4che6.img.data.TransferSyntaxType;
import org.dcm4che6.img.op.MaskArea;
import org.dcm4che6.img.stream.BytesWithImageDescriptor;
import org.dcm4che6.img.stream.ImageDescriptor;
import org.dcm4che6.img.stream.SeekableInMemoryByteChannel;
import org.dcm4che6.img.util.DicomObjectUtil;
import org.dcm4che6.io.DicomEncoding;
import org.dcm4che6.io.DicomInputStream;
import org.dcm4che6.io.DicomOutputStream;
import org.dcm4che6.net.Association;
import org.dcm4che6.net.Association.DataWriter;
import org.dcm4che6.net.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.param.AttributeEditor;
import org.weasis.dicom.param.AttributeEditorContext;
import org.weasis.dicom.param.AttributeEditorContext.Abort;
import org.weasis.dicom.param.DicomForwardDestination;
import org.weasis.dicom.param.ForwardDestination;
import org.weasis.dicom.param.ForwardDicomNode;
import org.weasis.dicom.util.ServiceUtil.ProgressStatus;
import org.weasis.dicom.web.DicomStowRS;
import org.weasis.dicom.web.Payload;
import org.weasis.dicom.web.WebForwardDestination;
import org.weasis.opencv.data.PlanarImage;

public class ForwardUtil {
  private static final String ERROR_WHEN_FORWARDING =
      "Error when forwarding to the final destination";
  private static final Logger LOGGER = LoggerFactory.getLogger(ForwardUtil.class);
  protected static final byte[] EMPTY_BYTES = {};

  public static final class Params {
    private final String iuid;
    private final String cuid;
    private final String tsuid;
    private final Byte pcid;
    private final InputStream data;
    private final Association as;

    public Params(String iuid, String cuid, Byte pcid, InputStream data, Association as) {
      super();
      this.iuid = iuid;
      this.cuid = cuid;
      this.pcid = pcid;
      this.tsuid = as.getTransferSyntax(pcid);
      this.as = as;
      this.data = data;
    }

    public Byte getPcid() {
      return pcid;
    }

    public String getIuid() {
      return iuid;
    }

    public String getCuid() {
      return cuid;
    }

    public String getTsuid() {
      return tsuid;
    }

    public Association getAs() {
      return as;
    }

    public InputStream getData() {
      return data;
    }
  }

  private static final class AbortException extends IllegalStateException {
    private static final long serialVersionUID = 3993065212756372490L;
    private final Abort abort;

    public AbortException(Abort abort, String s) {
      super(s);
      this.abort = abort;
    }

    public AbortException(Abort abort, String string, Exception e) {
      super(string, e);
      this.abort = abort;
    }

    @Override
    public String toString() {
      return getMessage();
    }

    public Abort getAbort() {
      return abort;
    }
  }

  private ForwardUtil() {}

  public static void storeMulitpleDestination(
      ForwardDicomNode fwdNode, List<ForwardDestination> destList, Params p) throws IOException {
    if (destList == null || destList.isEmpty()) {
      throw new IllegalStateException(
          "Cannot find the DICOM destination from " + fwdNode.toString());
    }
    // Exclude DICOMDIR
    if ("1.2.840.10008.1.3.10".equals(p.cuid)) {
      LOGGER.warn("Cannot send DICOMDIR {}", p.iuid);
      return;
    }

    if (destList.size() == 1) {
      storeOneDestination(fwdNode, destList.get(0), p);
    } else {
      List<ForwardDestination> destConList = new ArrayList<>();
      for (ForwardDestination fwDest : destList) {
        try {
          if (fwDest instanceof DicomForwardDestination) {
            prepareTransfer((DicomForwardDestination) fwDest, p.getCuid(), p.getTsuid());
          }
          destConList.add(fwDest);
        } catch (Exception e) {
          LOGGER.error("Cannot connect to the final destination", e);
        }
      }

      if (destConList.isEmpty()) {
        return;
      } else if (destConList.size() == 1) {
        storeOneDestination(fwdNode, destConList.get(0), p);
      } else {
        DicomObject dcm = DicomObject.newDicomObject();
        ForwardDestination fistDest = destConList.get(0);
        if (fistDest instanceof DicomForwardDestination) {
          transfer(fwdNode, (DicomForwardDestination) fistDest, dcm, p);
        } else if (fistDest instanceof WebForwardDestination) {
          transfer(fwdNode, (WebForwardDestination) fistDest, dcm, p);
        }
        if (!dcm.isEmpty()) {
          for (int i = 1; i < destConList.size(); i++) {
            ForwardDestination dest = destConList.get(i);
            if (dest instanceof DicomForwardDestination) {
              transferOther(fwdNode, (DicomForwardDestination) dest, dcm, p);
            } else if (dest instanceof WebForwardDestination) {
              transferOther(fwdNode, (WebForwardDestination) dest, dcm, p);
            }
          }
        }
      }
    }
  }

  public static void storeOneDestination(
      ForwardDicomNode fwdNode, ForwardDestination destination, Params p) throws IOException {
    if (destination instanceof DicomForwardDestination) {
      DicomForwardDestination dest = (DicomForwardDestination) destination;
      prepareTransfer(dest, p.getCuid(), p.getTsuid());
      transfer(fwdNode, dest, null, p);
    } else if (destination instanceof WebForwardDestination) {
      transfer(fwdNode, (WebForwardDestination) destination, null, p);
    }
  }

  public static synchronized StoreFromStreamSCU prepareTransfer(
      DicomForwardDestination destination, String cuid, String tsuid) throws IOException {
    String outTsuid =
        tsuid.equals(UID.RLELossless)
                || tsuid.equals(UID.ImplicitVRLittleEndian)
                || tsuid.equals(UID.ExplicitVRBigEndianRetired)
            ? UID.ExplicitVRLittleEndian
            : tsuid;
    StoreFromStreamSCU streamSCU = destination.getStreamSCU();
    if (streamSCU.getAssociation() == null || !streamSCU.getAssociation().isOpen()) {
      // Add Presentation Context for the association
      streamSCU.addData(cuid, outTsuid);
      if (!outTsuid.equals(UID.ExplicitVRLittleEndian)) {
        streamSCU.addData(cuid, UID.ExplicitVRLittleEndian);
      }
      streamSCU.open();
    } else {
      // Handle dynamically new SOPClassUID
      Stream<Byte> val = streamSCU.getAssociationRq().pcidsFor(cuid, outTsuid);
      boolean missingTsuid = val.findFirst().isEmpty();
      // Add Presentation Context for the association
      streamSCU.addData(cuid, outTsuid);
      if (!outTsuid.equals(UID.ExplicitVRLittleEndian)) {
        streamSCU.addData(cuid, UID.ExplicitVRLittleEndian);
      }
      if (missingTsuid) {
        streamSCU.close(true);
        streamSCU.open();
      }
    }
    return streamSCU;
  }

  public static void transfer(
      ForwardDicomNode sourceNode, DicomForwardDestination destination, DicomObject copy, Params p)
      throws IOException {
    StoreFromStreamSCU streamSCU = destination.getStreamSCU();
    String iuid = p.getIuid();
    try {
      if (!streamSCU.getAssociation().isOpen()) {
        throw new IllegalStateException("Association not ready for transfer.");
      }
      DataWriter dataWriter;
      String tsuid = p.getTsuid();

      Optional<Byte> pcid = selectTransferSyntax(streamSCU.getAssociation(), p);
      if (pcid.isEmpty()) {
        throw new IOException("The remote destination has no matching Presentation Context");
      }
      String supportedTsuid = streamSCU.getAssociation().getTransferSyntax(pcid.get());
      List<AttributeEditor> editors = destination.getDicomEditors();

      if (copy == null && editors.isEmpty() && supportedTsuid.equals(tsuid)) {
        dataWriter = (out, t) -> p.getData().transferTo(out);
      } else {
        AttributeEditorContext context = new AttributeEditorContext(destination);
        DicomObject data;
        try (DicomInputStream dis = new DicomInputStream(p.getData())) {
          data = dis.readDataSet();
          if (data == null) {
            throw new IllegalStateException("Cannot read DICOM dataset");
          }
        }
        if (copy != null) {
          DicomObjectUtil.copyDataset(data, copy);
        }

        if (!editors.isEmpty()) {
          DicomObject finalData = data;
          editors.forEach(e -> e.apply(finalData, context));
          iuid = data.getString(Tag.SOPInstanceUID).orElse(null);
        }

        if (context.getAbort() == Abort.FILE_EXCEPTION) {
          throw new AbortException(context.getAbort(), context.getAbortMessage());
        } else if (context.getAbort() == Abort.CONNECTION_EXCEPTION) {
          if (p.getAs() != null) {
            p.getAs().release();
          }
          throw new AbortException(
              context.getAbort(), "DICOM association abort: " + context.getAbortMessage());
        }
        BytesWithImageDescriptor desc = imageTranscode(data, tsuid, supportedTsuid, context);
        dataWriter = buildDataWriter(data, supportedTsuid, context, desc);
      }

      streamSCU.getAssociation().cstore(p.getCuid(), iuid, dataWriter, supportedTsuid);
      progressNotify(destination, iuid, p.getCuid(), false);
    } catch (AbortException e) {
      progressNotify(destination, iuid, p.getCuid(), true);
      if (e.getAbort() == Abort.CONNECTION_EXCEPTION) {
        throw e;
      }
    } catch (Exception e) {
      progressNotify(destination, iuid, p.getCuid(), true);
      LOGGER.error(ERROR_WHEN_FORWARDING, e);
    } finally {
      streamSCU.triggerCloseExecutor();
    }
  }

  public static void transferOther(
      ForwardDicomNode fwdNode, DicomForwardDestination destination, DicomObject copy, Params p)
      throws IOException {
    StoreFromStreamSCU streamSCU = destination.getStreamSCU();
    String iuid = p.getIuid();
    try {
      if (!streamSCU.getAssociation().isOpen()) {
        throw new IllegalStateException("Association not ready for transfer.");
      }

      DataWriter dataWriter;
      String tsuid = p.getTsuid();
      Optional<Byte> pcid = selectTransferSyntax(streamSCU.getAssociation(), p);
      if (pcid.isEmpty()) {
        throw new IOException("The remote destination has no matching Presentation Context");
      }
      String supportedTsuid = streamSCU.getAssociation().getTransferSyntax(pcid.get());
      List<AttributeEditor> editors = destination.getDicomEditors();
      if (editors.isEmpty() && supportedTsuid.equals(tsuid)) {
        dataWriter = (out, t) -> p.getData().transferTo(out);
      } else {
        AttributeEditorContext context = new AttributeEditorContext(destination);
        DicomObject dcm = DicomObject.newDicomObject();
        DicomObjectUtil.copyDataset(copy, dcm);
        if (!editors.isEmpty()) {
          editors.forEach(e -> e.apply(dcm, context));
          iuid = dcm.getString(Tag.SOPInstanceUID).orElse(null);
        }

        if (context.getAbort() == Abort.FILE_EXCEPTION) {
          throw new AbortException(context.getAbort(), context.getAbortMessage());
        } else if (context.getAbort() == Abort.CONNECTION_EXCEPTION) {
          throw new AbortException(
              context.getAbort(), "DICOM associtation abort. " + context.getAbortMessage());
        }
        BytesWithImageDescriptor desc = imageTranscode(dcm, tsuid, supportedTsuid, context);
        dataWriter = buildDataWriter(dcm, supportedTsuid, context, desc);
      }

      streamSCU.getAssociation().cstore(p.getCuid(), iuid, dataWriter, supportedTsuid);
      progressNotify(destination, iuid, p.getCuid(), false);
    } catch (AbortException e) {
      progressNotify(destination, iuid, p.getCuid(), true);
      if (e.getAbort() == Abort.CONNECTION_EXCEPTION) {
        throw e;
      }
    } catch (Exception e) {
      progressNotify(destination, iuid, p.getCuid(), true);
      LOGGER.error(ERROR_WHEN_FORWARDING, e);
    } finally {
      streamSCU.triggerCloseExecutor();
    }
  }

  private static DataWriter buildDataWriter(
      DicomObject data,
      String supportedTsuid,
      AttributeEditorContext context,
      BytesWithImageDescriptor desc)
      throws Exception {
    if (desc == null) {
      return (out, tsuid) -> {
        try (DicomOutputStream writer =
            new DicomOutputStream(out).withEncoding(DicomEncoding.of(tsuid))) {
          writer.writeDataSet(data);
        }
      };
    }

    DicomTranscodeParam tparams = new DicomTranscodeParam(supportedTsuid);
    DicomOutputData imgData = geDicomOutputData(tparams, desc, context);
    return (out, tsuid) -> {
      DicomObject dataSet = DicomObject.newDicomObject();
      for (DicomElement el : data) {
        if (el.tag() == Tag.PixelData) {
          break;
        }
        dataSet.add(el);
      }
      try (DicomOutputStream dos =
          new DicomOutputStream(out).withEncoding(DicomEncoding.of(supportedTsuid))) {
        if (DicomOutputData.isNativeSyntax(supportedTsuid)) {
          imgData.writRawImageData(dos, dataSet);
        } else {
          int[] jpegWriteParams =
              imgData.adaptTagsToCompressedImage(
                  dataSet,
                  imgData.getImages().get(0),
                  desc.getImageDescriptor(),
                  tparams.getWriteJpegParam());
          imgData.writCompressedImageData(dos, dataSet, jpegWriteParams);
        }
      } catch (Exception e) {
        LOGGER.error("Transcoding image data", e);
      }
    };
  }

  private static BytesWithImageDescriptor imageTranscode(
      DicomObject data, String originalTsuid, String supportedTsuid, AttributeEditorContext context)
      throws Exception {
    if ((Objects.nonNull(context.getMaskArea())
            && data.get(Tag.PixelData).isPresent()
            && !TransferSyntaxType.isLVideo(originalTsuid))
        || (!supportedTsuid.equals(originalTsuid)
            && TransferSyntaxType.forUID(originalTsuid) != TransferSyntaxType.NATIVE)) {
      Optional<DicomElement> pixdata = data.get(Tag.PixelData);
      ImageDescriptor imdDesc = new ImageDescriptor(data);
      ByteBuffer[] mfByteBuffer = new ByteBuffer[1];
      ArrayList<Integer> fragmentsPositions = new ArrayList<>();
      return new BytesWithImageDescriptor() {

        @Override
        public ImageDescriptor getImageDescriptor() {
          return imdDesc;
        }

        @Override
        public ByteBuffer getBytes(int frame) throws IOException {
          ImageDescriptor desc = getImageDescriptor();
          int bitsStored = desc.getBitsStored();
          if (pixdata.isEmpty() || bitsStored < 1) {
            return ByteBuffer.wrap(EMPTY_BYTES);
          } else {
            DicomElement pix = pixdata.get();
            List<DataFragment> fragments =
                pixdata.get().fragmentStream().collect(Collectors.toList());
            if (fragments.isEmpty()) {
              int frameLength =
                  desc.getPhotometricInterpretation()
                      .frameLength(
                          desc.getColumns(),
                          desc.getRows(),
                          desc.getSamples(),
                          desc.getBitsAllocated());
              if (mfByteBuffer[0] == null) {
                Optional<byte[]> bytes = DicomImageUtils.getByteData(pix);
                mfByteBuffer[0] = ByteBuffer.wrap(bytes.isEmpty() ? EMPTY_BYTES : bytes.get());
              }

              if (mfByteBuffer[0].limit() < frame * frameLength + frameLength) {
                throw new IOException("Frame out of the stream limit");
              }

              byte[] bytes = new byte[frameLength];
              mfByteBuffer[0].position(frame * frameLength);
              mfByteBuffer[0].get(bytes, 0, frameLength);
              return ByteBuffer.wrap(bytes);
            } else {
              int nbFragments = fragments.size();
              int numberOfFrame = desc.getFrames();
              if (numberOfFrame == 1) {
                int length = 0;
                for (int i = 0; i < nbFragments - 1; i++) {
                  DataFragment bulkData = fragments.get(i + 1);
                  length += bulkData.valueLength();
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream(length);
                for (int i = 0; i < nbFragments - 1; i++) {
                  DataFragment fragment = pix.getDataFragment(i + 1);
                  fragment.writeTo(out);
                }
                return ByteBuffer.wrap(out.toByteArray());
              } else {
                // Multi-frames where each frames can have multiple fragments.
                if (fragmentsPositions.isEmpty()) {
                  if (UID.RLELossless.equals(originalTsuid)) {
                    for (int i = 1; i < nbFragments; i++) {
                      fragmentsPositions.add(i);
                    }
                  } else {
                    for (int i = 1; i < nbFragments; i++) {
                      DataFragment bulkData = fragments.get(i);
                      try {
                        ByteArrayOutputStream out =
                            new ByteArrayOutputStream(bulkData.valueLength());
                        bulkData.writeTo(out);
                        SeekableInMemoryByteChannel channel =
                            new SeekableInMemoryByteChannel(out.toByteArray());
                        new JPEGParser(channel, bulkData.valueLength());
                        fragmentsPositions.add(i);
                      } catch (Exception e) {
                        // Not jpeg stream
                      }
                    }
                  }
                }

                if (fragmentsPositions.size() == numberOfFrame) {
                  int start = fragmentsPositions.get(frame);
                  int end =
                      (frame + 1) >= fragmentsPositions.size()
                          ? nbFragments
                          : fragmentsPositions.get(frame + 1);

                  int length = 0;
                  for (int i = 0; i < end - start; i++) {
                    DataFragment bulkData = fragments.get(start + i);
                    length += bulkData.valueLength();
                  }
                  ByteArrayOutputStream out = new ByteArrayOutputStream(length);
                  for (int i = 0; i < end - start; i++) {
                    DataFragment fragment = pix.getDataFragment(start + i);
                    fragment.writeTo(out);
                  }
                  return ByteBuffer.wrap(out.toByteArray());
                } else {
                  throw new IOException("Cannot match all the fragments to all the frames!");
                }
              }
            }
          }
        }

        @Override
        public String getTransferSyntax() {
          return originalTsuid;
        }

        @Override
        public DicomObject getPaletteColorLookupTable() {
          DicomObject dcm = DicomObject.newDicomObject();
          data.get(Tag.RedPaletteColorLookupTableDescriptor).ifPresent(dcm::add);
          data.get(Tag.GreenPaletteColorLookupTableDescriptor).ifPresent(dcm::add);
          data.get(Tag.BluePaletteColorLookupTableDescriptor).ifPresent(dcm::add);
          data.get(Tag.RedPaletteColorLookupTableData).ifPresent(dcm::add);
          data.get(Tag.GreenPaletteColorLookupTableData).ifPresent(dcm::add);
          data.get(Tag.BluePaletteColorLookupTableData).ifPresent(dcm::add);
          data.get(Tag.SegmentedRedPaletteColorLookupTableData).ifPresent(dcm::add);
          data.get(Tag.SegmentedGreenPaletteColorLookupTableData).ifPresent(dcm::add);
          data.get(Tag.SegmentedBluePaletteColorLookupTableData).ifPresent(dcm::add);
          return dcm;
        }
      };
    }
    return null;
  }

  private static DicomOutputData geDicomOutputData(
      DicomTranscodeParam tparams, BytesWithImageDescriptor desc, AttributeEditorContext context)
      throws IOException {
    try (DicomImageReader reader = new DicomImageReader(Transcoder.dicomImageReaderSpi)) {
      reader.setInput(desc);
      List<PlanarImage> images = new ArrayList<>();
      for (int i = 0; i < reader.getImageDescriptor().getFrames(); i++) {
        PlanarImage img = reader.getRawImage(i, tparams.getReadParam());
        if (context.getMaskArea() != null) {
          img = MaskArea.drawShape(img.toMat(), context.getMaskArea());
        }
        images.add(img);
      }
      return new DicomOutputData(images, desc.getImageDescriptor(), tparams.getOutputTsuid());
    }
  }

  public static void transfer(
      ForwardDicomNode fwdNode, WebForwardDestination destination, DicomObject copy, Params p) {
    String iuid = p.getIuid();
    try {
      List<AttributeEditor> editors = destination.getDicomEditors();
      DicomStowRS stow = destination.getStowrsSingleFile();
      String outputTsuid = p.getTsuid();
      boolean originalTsuid =
          !(UID.ImplicitVRLittleEndian.equals(outputTsuid)
              || UID.ExplicitVRBigEndianRetired.equals(outputTsuid));
      if (!originalTsuid) {
        outputTsuid = UID.ExplicitVRLittleEndian;
      }
      if (originalTsuid && copy == null && editors.isEmpty()) {
        DicomObject fmi =
            DicomObject.createFileMetaInformation(p.getCuid(), p.getIuid(), outputTsuid);
        try (InputStream stream = p.getData()) {
          stow.uploadDicom(stream, fmi);
        }
      } else {
        AttributeEditorContext context = new AttributeEditorContext(destination);
        try (DicomInputStream dis = new DicomInputStream(p.getData())) {
          DicomObject data = dis.readDataSet();
          if (copy != null) {
            DicomObjectUtil.copyDataset(data, copy);
          }
          if (!editors.isEmpty()) {
            editors.forEach(e -> e.apply(data, context));
            iuid = data.getString(Tag.SOPInstanceUID).orElse(null);
          }

          if (context.getAbort() == Abort.FILE_EXCEPTION) {
            throw new AbortException(context.getAbort(), context.getAbortMessage());
          } else if (context.getAbort() == Abort.CONNECTION_EXCEPTION) {
            if (p.getAs() != null) {
              p.getAs().release();
            }
            throw new AbortException(
                context.getAbort(), "STOW-RS abort: " + context.getAbortMessage());
          }
          if (UID.RLELossless.equals(outputTsuid)) { // Missing RLE writer
            outputTsuid = UID.ExplicitVRLittleEndian;
          }
          // Do not set original TSUID to avoid RLE transcoding when there is no mask to apply
          BytesWithImageDescriptor desc = imageTranscode(data, outputTsuid, outputTsuid, context);
          if (desc == null) {
            stow.uploadDicom(data, outputTsuid);
          } else {
            stow.uploadPayload(preparePlayload(data, outputTsuid, desc, context));
          }
        }
        progressNotify(destination, iuid, p.getCuid(), false);
      }
    } catch (AbortException e) {
      progressNotify(destination, iuid, p.getCuid(), true);
      if (e.getAbort() == Abort.CONNECTION_EXCEPTION) {
        throw e;
      }
    } catch (Exception e) {
      progressNotify(destination, iuid, p.getCuid(), true);
      LOGGER.error(ERROR_WHEN_FORWARDING, e);
    }
  }

  public static void transferOther(
      ForwardDicomNode fwdNode, WebForwardDestination destination, DicomObject copy, Params p) {
    String iuid = p.getIuid();
    try {
      List<AttributeEditor> editors = destination.getDicomEditors();
      DicomStowRS stow = destination.getStowrsSingleFile();
      String outputTsuid = p.getTsuid();
      if (UID.ImplicitVRLittleEndian.equals(outputTsuid)
          || UID.ExplicitVRBigEndianRetired.equals(outputTsuid)) {
        outputTsuid = UID.ExplicitVRLittleEndian;
      }
      if (editors.isEmpty()) {
        stow.uploadDicom(copy, outputTsuid);
      } else {
        AttributeEditorContext context = new AttributeEditorContext(destination);
        DicomObject dcm = DicomObject.newDicomObject();
        DicomObjectUtil.copyDataset(copy, dcm);
        editors.forEach(e -> e.apply(dcm, context));
        iuid = dcm.getString(Tag.SOPInstanceUID).orElse(null);

        if (context.getAbort() == Abort.FILE_EXCEPTION) {
          throw new AbortException(context.getAbort(), context.getAbortMessage());
        } else if (context.getAbort() == Abort.CONNECTION_EXCEPTION) {
          throw new AbortException(
              context.getAbort(), "DICOM associtation abort. " + context.getAbortMessage());
        }
        if (UID.RLELossless.equals(outputTsuid)) { // Missing RLE writer
          outputTsuid = UID.ExplicitVRLittleEndian;
        }
        BytesWithImageDescriptor desc = imageTranscode(dcm, outputTsuid, outputTsuid, context);
        if (desc == null) {
          stow.uploadDicom(dcm, outputTsuid);
        } else {
          stow.uploadPayload(preparePlayload(dcm, outputTsuid, desc, context));
        }
        progressNotify(destination, iuid, p.getCuid(), false);
      }
    } catch (AbortException e) {
      progressNotify(destination, iuid, p.getCuid(), true);
      if (e.getAbort() == Abort.CONNECTION_EXCEPTION) {
        throw e;
      }
    } catch (Exception e) {
      progressNotify(destination, iuid, p.getCuid(), true);
      LOGGER.error(ERROR_WHEN_FORWARDING, e);
    }
  }

  private static void progressNotify(
      ForwardDestination destination, String iuid, String cuid, boolean failed) {
    ServiceUtil.notifyProgession(
        destination.getState(),
        iuid,
        cuid,
        failed ? Status.ProcessingFailure : Status.Success,
        failed ? ProgressStatus.FAILED : ProgressStatus.COMPLETED,
        0);
  }

  public static Payload preparePlayload(
      DicomObject data,
      String outputTsuid,
      BytesWithImageDescriptor desc,
      AttributeEditorContext context)
      throws IOException {
    DicomTranscodeParam tparams = new DicomTranscodeParam(outputTsuid);
    DicomOutputData imgData = geDicomOutputData(tparams, desc, context);

    DicomObject dataSet = DicomObject.newDicomObject();
    for (DicomElement el : data) {
      if (el.tag() == Tag.PixelData) {
        break;
      }
      dataSet.add(el);
    }

    return new Payload() {
      @Override
      public long size() {
        return -1;
      }

      @Override
      public InputStream newInputStream() {
        DicomObject fmi = dataSet.createFileMetaInformation(outputTsuid);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DicomOutputStream dos = new DicomOutputStream(out)) {
          dos.writeFileMetaInformation(fmi).withEncoding(fmi);
          if (DicomOutputData.isNativeSyntax(outputTsuid)) {
            imgData.writRawImageData(dos, dataSet);
          } else {
            int[] jpegWriteParams =
                imgData.adaptTagsToCompressedImage(
                    dataSet,
                    imgData.getImages().get(0),
                    desc.getImageDescriptor(),
                    tparams.getWriteJpegParam());
            imgData.writCompressedImageData(dos, dataSet, jpegWriteParams);
          }
        } catch (IOException e) {
          LOGGER.error("Cannot write DICOM", e);
          return new ByteArrayInputStream(new byte[] {});
        }
        return new ByteArrayInputStream(out.toByteArray());
      }
    };
  }

  public static Optional<Byte> selectTransferSyntax(Association as, Params p) {
    if (as.getAaac().acceptedTransferSyntax(p.getPcid(), p.getTsuid())) {
      return Optional.of(p.getPcid());
    }

    Optional<Byte> res =
        as.getAarq()
            .pcidsFor(p.getCuid())
            .filter(b -> as.getAaac().acceptedTransferSyntax(b, p.getTsuid()))
            .findFirst();
    if (res.isPresent()) {
      return res;
    }
    return as.getAarq()
        .pcidsFor(p.getCuid())
        .filter(b -> as.getAaac().acceptedTransferSyntax(b, UID.ExplicitVRLittleEndian))
        .findFirst();
  }
}
