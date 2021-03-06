/*
 *   Copyright 2010 MINT Working Group
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.nema.medical.mint.dcm2mint;

import org.dcm4che2.data.*;
import org.nema.medical.mint.datadictionary.LevelAttributes;
import org.nema.medical.mint.metadata.*;
import org.nema.medical.mint.utils.StudyUtils;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

import static org.nema.medical.mint.utils.Iter.iter;


/**
\brief Class used to build up a StudyMeta message from a set of DICOM instances.

To use this class, create a StudyMeta instance, a binary item vector, and a tag normalization map
(see the accumulateFile signature). Call accumulateFile() for each DICOM instance P10 file in the
study's dataset. This class will populate the passed-in parameters with the data from the P10
instances.


\par Example Usage
\code
Dcm2MetaBuilder::GroupElementTagSet final studyLevelTags = ... get study level attribute tags ...
Dcm2MetaBuilder::GroupElementTagSet final seriesLevelTags = ... get series-level attribute tags ...
Dcm2MetaBuilder builder(studyLevelTags, seriesLevelTags);

foreach (Path final& p10Path, ... list of DICOM P10 files ...)
{
   builder.accumulateFile(p10Path);
}

Dcm2MetaBuilder::MetaBinaryPair final data = builder.finish();

... Use data to produce on-disk representation ...
\endcode

\par Study/Series Summary-Level Tag Insertion Rules.
Tags can be organized, by request, to the study summary and series summary section for each
series. To do this, the caller provides the constructor with maps containing the tags for the
attributes that should be stored in the corresponding summary sections.
\par
When processing an instance, the attributes of the instance are checked against these two maps.
If the attribute's tag is found in one of the summary maps, this indicates the attribute is
a summary-level attribute. The attribute is inserted into the corresponding summary table and,
as a result, \e not into individual instance's attribute table. If the attribute already
existed in the specific summary table (i.e.: from a previous instance being processed), this
copy of the attribute is essentially discarded.
\par
This behavior may result in summary tags whose values differ between instances being discarded.
It may also result in attributes appearing to be present in all instances even if some instances
happened to not have the attributes. Both of these cases would be extremely rare and the current
behavior is deemed acceptable.

@author Uli Bubenheimer
*/
public final class Dcm2MetaBuilder {

    /**
     * Codes from org.dcm4che.data.VR.valueOf.
     * These codes are a 16-bit integer representation of the two 8-bit ASCII character bytes representing
     * the constant names.
     */
    private interface VRCodes {
        int UN_SIEMENS = 0x3F3F;
        int FD = 0x4644;
        int FL = 0x464c;
        int OB = 0x4f42;
        int OF = 0x4f46;
        int OW = 0x4f57;
        int SQ = 0x5351;
        int UN = 0x554E;
    }

    private static final Collection<String> JPEG_TRANSFER_SYNTAXES = new HashSet<String>(Arrays.asList(
            UID.JPEGBaseline1,
            UID.JPEGExtended24,
            UID.JPEGLosslessNonHierarchical14,
            UID.JPEGLossless,
            UID.JPEGLSLossless,
            UID.JPEGLSLossyNearLossless,
            UID.JPEG2000LosslessOnly,
            UID.JPEG2000,
            UID.JPEG2000Part2MulticomponentLosslessOnly,
            UID.JPEG2000Part2Multicomponent
    ));

    /**
   Create an instance of the class with the specified summary-level tag maps.

   The maps are used in subsequent calls to accumulateFile().

   @param studyLevelTags The caller must fill this in with tags that are considered to be part of
   the study-level & patient-level summary set. The builder will extract the associated tags from
   the instances passed into this function and place them in the resulting study's study-level
   summary tags section. See the note below for tag insertion rules.
   @param seriesLevelTags The caller must fill this in with tags that are considered to be part of
   the series-level set for each series. See the note below for tag insertion rules.

    The caller may set a StudyInstanceUID on the study to constrain processing.
   \note If a given attribute tag is present in both maps, the studyLevelTags map takes
   precedence.
    */
   public Dcm2MetaBuilder(
           final LevelAttributes studyLevelTags,
           final LevelAttributes seriesLevelTags,
           final MetaBinaryPair metaBinaryPair) {
       this.studyLevelTags = studyLevelTags;
       this.seriesLevelTags = seriesLevelTags;
       this.metaBinaryPair = metaBinaryPair;
   }

   /**
    * @return the minimum number of bytes from a DICOM binary tag required to trigger
    *   storing in an external binary file rather than inline in the MINT metadata.
    *   A value of 0 means to store all binary data in external files.
    */
   public int getBinaryInlineThreshold() {
       return binaryInlineThreshold;
   }

   /**
    * @param value the minimum number of bytes from a DICOM binary tag required to trigger
    *   storing in an external binary file rather than inline in the MINT metadata.
    *   A value of 0 means to store all binary data in external files.
    */
   public void setBinaryInlineThreshold(final int value) {
       binaryInlineThreshold = value;
   }

   public boolean isP10Aware() {
       return p10Aware;
   }

   public void setP10Aware(final boolean p10Aware) {
       this.p10Aware = p10Aware;
   }

   public static String extractStudyInstanceUID(final DicomObject dcmObj) {
       return dcmObj.getString(Tag.StudyInstanceUID);
   }

   /**
   Completes the build of the meta data and returns the results.

   Normalizing the duplicate attributes in each series greatly reduces the size of the meta data
   and improves parsing times significantly.

   This should be called only after all P10 instances for the study have been processed with
   accumulateFile().
    */
   public void finish() {
       StudyUtils.normalizeStudy(metaBinaryPair.getMetadata());
   }

     /**
     Accumulates the tags for the DICOM P10 instance specified by path into the overall study
     metadata.

     @param dcmPath The path to the DICOM P10 instance. All instances accumulated for a given
     StudyMeta must be part of the same study or an exception will be thrown.
     @throws RuntimeException The instance referred to by the path either doesn't have a study
     instance UID or its study instance UID is not the same as previously accumulated instances.
      */
     public void accumulateFile(final File dcmPath, final DicomObject dcmObj,
             final TransferSyntax transferSyntax) {
         final String dataStudyInstanceUID = extractStudyInstanceUID(dcmObj);
         if (dataStudyInstanceUID != null) {
             if (metaBinaryPair.getMetadata().getStudyInstanceUID() == null) {
                 metaBinaryPair.getMetadata().setStudyInstanceUID(dataStudyInstanceUID);
             }
             else if (!metaBinaryPair.getMetadata().getStudyInstanceUID().equals(dataStudyInstanceUID)) {
                 throw new RuntimeException(dcmPath + " -- study instance uid (" + dataStudyInstanceUID +
                         ") does not match current study (" + metaBinaryPair.getMetadata().getStudyInstanceUID() + ')');
             }
         }

         final String seriesInstanceUID = dcmObj.getString(Tag.SeriesInstanceUID);
         if (seriesInstanceUID == null) {
             throw new RuntimeException(dcmPath + " -- missing series instance uid");
         }

         Series series = metaBinaryPair.getMetadata().getSeries(seriesInstanceUID);
         if (series == null) {
             series = new Series();
             series.setSeriesInstanceUID(seriesInstanceUID);
             metaBinaryPair.getMetadata().putSeries(series);
         }

         final Instance instance = new Instance();
         instance.setTransferSyntaxUID(transferSyntax.uid());
         instance.setSOPInstanceUID(dcmObj.getString(Tag.SOPInstanceUID));
         series.putInstance(instance);

         // Now, iterate through all items in the object and store each appropriately.
         // This dispatches the Attribute storage to one of the study level, series level
         // or instance-level Attributes sets.
         if (p10Aware) {
             for (final DicomElement dcmElement: iter(dcmObj.fileMetaInfoIterator())) {
                 handleTopElems(dcmPath, dcmObj, series, instance, dcmElement, transferSyntax);
             }
         }
         for (final DicomElement dcmElement: iter(dcmObj.datasetIterator())) {
             handleTopElems(dcmPath, dcmObj, series, instance, dcmElement, transferSyntax);
         }
     }

    private void handleTopElems(final File dcmPath, final DicomObject dcmObj, final Series series,
            final Instance instance, final DicomElement dcmElement, final TransferSyntax transferSyntax) {
        final int tag = dcmElement.tag();
         if (studyLevelTags.containsTag(tag)) {
             if (metaBinaryPair.getMetadata().getAttribute(tag) == null) {
                 handleDICOMElement(dcmPath, dcmElement, dcmObj, metaBinaryPair.getMetadata(), emptyTagPath,
                                    transferSyntax);
             }
         }
         else if (seriesLevelTags.containsTag(tag)) {
             if (series.getAttribute(tag) == null) {
                 handleDICOMElement(dcmPath, dcmElement, dcmObj, series, emptyTagPath, transferSyntax);
             }
         }
         else {
             handleDICOMElement(dcmPath, dcmElement, dcmObj, instance, emptyTagPath, transferSyntax);
         }
    }

     private void handleDICOMElement(
             final File dcmPath,
             final DicomElement dcmElem,
             final DicomObject parentDcmObj,
             final AttributeContainer attrs,
             final int[] tagPath,
             final TransferSyntax transferSyntax) {
         final Store store = new Store(dcmPath, tagPath, dcmElem, parentDcmObj, transferSyntax);
         final VR vr = dcmElem.vr();
         if (vr == null) {
             throw new RuntimeException("Null VR");
         }

         final Attribute attr;

         switch (vr.code()) {
             case VRCodes.OW:
             case VRCodes.OB:
             case VRCodes.OF:
             case VRCodes.UN:
             case VRCodes.UN_SIEMENS:
                 //Non-sequence binary
                 attr = store.createBinary();
                 break;

             case VRCodes.SQ:
                 //Sequence
                 attr = store.createSequence();
                 break;

             case VRCodes.FD:
             case VRCodes.FL:
                 //Float
                 attr = store.createFloat();
                 break;

             default:
                 attr = store.createPlain();
         }

         //TODO revisit once MINT WG has defined attribute standardization in terms of endianness
         //For the time being, convert Study-level and Series-level FL/FD tags binary data to little endian
         final Attribute standardizedAttribute;
         if (attrs instanceof StudyMetadata || attrs instanceof Series) {
             standardizedAttribute = StudyUtils.standardizedAttribute(attr, transferSyntax.bigEndian());
         } else {
             standardizedAttribute = attr;
         }
         attrs.putAttribute(standardizedAttribute);
     }

     private final class Store {
         public Store(final File dcmPath, final int[] tagPath, final DicomElement elem,
                      final DicomObject parentDcmObj, final TransferSyntax transferSyntax) {
             this.dcmPath = dcmPath;
             this.tagPath = tagPath;
             this.elem = elem;
             this.parentDcmObj = parentDcmObj;
             this.transferSyntax = transferSyntax;
         }

         public Attribute createPlain() {
             assert elem != null;
             assert !elem.hasItems();
             final String strVal = getStringValue(elem, parentDcmObj.getSpecificCharacterSet());
             final Attribute attr = newAttr(elem);
             attr.setVal(strVal);
             return attr;
         }

         public Attribute createFloat() {
             final Attribute attr = createPlain();
             final byte[] binaryData = elem.getBytes();
             attr.setBytes(binaryData);
             return attr;
         }

         public Attribute createSequence() {
             assert elem != null;
             assert elem.hasDicomObjects();
             final Attribute attr = newAttr(elem);
             final int[] newTagPath = new int[tagPath.length + 2];
             System.arraycopy(tagPath, 0, newTagPath, 0, tagPath.length);
             newTagPath[tagPath.length] = elem.tag();
             for (int i = 0; i < elem.countItems(); ++i) {
                 newTagPath[tagPath.length + 1] = i;
                 final DicomObject dcmObj = elem.getDicomObject(i);
                 final Item newItem = new Item();
                 attr.addItem(newItem);
                 for (final DicomElement dcmElement: iter(dcmObj.datasetIterator())) {
                     handleDICOMElement(dcmPath, dcmElement, dcmObj, newItem, newTagPath, transferSyntax);
                 }
             }
             return attr;
         }

         public Attribute createBinary() {
             assert elem != null;

             final Attribute attr = newAttr(elem);
             assert attr != null;

             final int[] newTagPath = new int[tagPath.length + 1];
             System.arraycopy(tagPath, 0, newTagPath, 0, tagPath.length);
             newTagPath[tagPath.length] = elem.tag();

             final BinaryDcmData binaryDataStore = (BinaryDcmData) metaBinaryPair.getBinaryData();

             if (elem.tag() == Tag.PixelData) {
                 //Never inline pixel data - introduces pointless complexity for user,
                 //as this would hardly ever happen in any case. Pixel data is a special case anyway.
                 attr.setBid(binaryDataStore.size());
                 int numFrames = parentDcmObj.getInt(Tag.NumberOfFrames, 1);
                 if (numFrames == 0) {
                     //Be lenient if SOP instance creator did not care about frames
                     numFrames = 1;
                 }
                 attr.setFrameCount(numFrames);
                 if (elem.hasItems()) {
                     //Compressed/encapsulated data
                     assert elem.hasFragments();
                     assert elem.vr().equals(VR.OB);

                     //Find offset table if available
                     final byte[] basicOffsetTableBytes = elem.getFragment(0);
                     if (basicOffsetTableBytes.length != 0 && basicOffsetTableBytes.length != numFrames * 4) {
                         throw new RuntimeException("Encapsulated pixel data Basic Offset Table length of "
                         + basicOffsetTableBytes.length + " not matching declaration of " + numFrames + " frames");
                     }
                     final int[] basicOffsetTable;
                     if (basicOffsetTableBytes.length == 0) {
                         basicOffsetTable = null;
                     } else {
                         basicOffsetTable = new int[numFrames];
                         for (int i = 0, byteIdx = 0; i < numFrames; ++i, byteIdx += 4) {
                             //Assume Little-Endianness
                             basicOffsetTable[i] = littleEndianBytesToInt(basicOffsetTableBytes, byteIdx);
                         }
                     }

                     final String transferSyntaxUID = transferSyntax.uid();
                     if (transferSyntaxUID.equals(UID.MPEG2)
                             || transferSyntaxUID.equals(UID.MPEG2MainProfileHighLevel)) {
                         //DICOM standard says that in the case of MPEG the Basic Offset Table shall be null
                         assert basicOffsetTable == null;
                     }

                     final int numFragments = elem.countItems();
                     final boolean isJPEG = JPEG_TRANSFER_SYNTAXES.contains(transferSyntaxUID);
                     final boolean singleFragmentPerFrame;
                     if (numFragments == numFrames + 1) {
                         singleFragmentPerFrame = true;
                     } else if (isJPEG) {
                         //Single or multiple fragments per frame
                         singleFragmentPerFrame = false;
                     } else if (transferSyntaxUID.equals(UID.RLELossless)
                             || transferSyntaxUID.equals(UID.MPEG2)
                             || transferSyntaxUID.equals(UID.MPEG2MainProfileHighLevel)) {
                         throw new RuntimeException(
                                 "RLE and MPEG image encoding require exactly one fragment per frame");
                     } else {
                         throw new RuntimeException("Unsupported encapsulated transfer syntax: " + transferSyntaxUID);
                     }

                     int fragmentIdx = 1;
                     int fragmentByteIdx = 0;
                     int binarySize = 0;
                     for (int i = 0; i < numFrames; ++i) {
                         final int curFrameStartFragment = fragmentIdx;
                         boolean frameHasMoreFragments;
                         do {
                             final byte[] fragmentBytes = elem.getFragment(fragmentIdx++);
                             final int fragmentByteCnt = fragmentBytes.length;
                             binarySize += fragmentByteCnt;
                             fragmentByteIdx += fragmentByteCnt + 8;
                             if (!singleFragmentPerFrame && fragmentIdx + numFrames - i - 1 < numFragments) {
                                 if (i + 1 == numFrames) {
                                     frameHasMoreFragments = true;
                                 } else if (basicOffsetTable != null) {
                                     frameHasMoreFragments = (fragmentByteIdx < basicOffsetTable[i + 1]);
                                 } else {
                                     final byte[] nextFragmentBytes = elem.getFragment(fragmentIdx);
                                     if (nextFragmentBytes.length <= 1 || nextFragmentBytes[0] != 0xFF) {
                                         frameHasMoreFragments = true;
                                     } else {
                                         byte fragmentPeekByte = (byte) 0xFF;
                                         for (int fragmentPeekIdx = 1;
                                              fragmentPeekIdx < nextFragmentBytes.length
                                                      && (fragmentPeekByte = nextFragmentBytes[fragmentPeekIdx++]) == 0xFF;);
                                         switch (fragmentPeekByte) {
                                             case (byte) 0xD8: //SOI
                                             case (byte) 0x4F: //SOC (JPEG 2000)
                                                 frameHasMoreFragments = false;
                                                 break;
                                             default:
                                                 frameHasMoreFragments = true;
                                         }
                                     }
                                 }
                             } else {
                                 frameHasMoreFragments = false;
                             }
                         } while (frameHasMoreFragments);

                         binaryDataStore.addEncapsulated(dcmPath, newTagPath, curFrameStartFragment,
                                 fragmentIdx - curFrameStartFragment);
                     }
                     attr.setBinarySize(binarySize);
                 } else {
                     final byte[] binaryData = elem.getBytes();
                     attr.setBinarySize(binaryData.length);
                     final int rows = parentDcmObj.getInt(Tag.Rows, -1);
                     final int columns = parentDcmObj.getInt(Tag.Columns, -1);
                     final int bitsAllocated = parentDcmObj.getInt(Tag.BitsAllocated, -1);
                     final int samples = parentDcmObj.getInt(Tag.SamplesPerPixel, -1);
                     final int frameSize;
                     if (rows > 0 && columns > 0 && bitsAllocated > 0 && samples > 0) {
                         frameSize = rows * columns * samples * (bitsAllocated / 8);
                     } else {
                         throw new RuntimeException("Invalid image pixel macro attributes "
                                 + "(rows, columns, bits allocated, samples): ("
                                 + rows + ", " + columns + ", " + bitsAllocated + ", " + samples + ")");
                     }

                     int expectedLength = frameSize * numFrames;
                     //binaryData may have one byte added to get it to an even length for DICOM
                     expectedLength = (expectedLength & 1) == 1 ? expectedLength + 1 : expectedLength;
                     if (expectedLength != binaryData.length) {
                         throw new RuntimeException(
                                 "Image pixel data size " + binaryData.length + " does not correspond to expected size "
                                 + expectedLength + " based on image pixel macro attribute values: " +
                                 "(rows, columns, bits allocated, samples) = (" +
                                 rows + ", " + columns + ", " + bitsAllocated + ", " + samples + ")");
                     }
                     for (int i = 0; i < numFrames; ++i) {
                         binaryDataStore.addNative(dcmPath, newTagPath, i * frameSize, frameSize);
                     }
                 }
             } else {
                 if (elem.hasItems()) {
                     throw new RuntimeException("Cannot handle DICOM item fragments outside of PixelData tag");
                 }
                 final byte[] binaryData = elem.getBytes();
                 if (binaryData.length < binaryInlineThreshold) {
                     attr.setBytes(binaryData);
                 } else {
                     attr.setBid(binaryDataStore.size());
                     attr.setBinarySize(binaryData.length);
                     binaryDataStore.addNative(dcmPath, newTagPath, 0, binaryData.length);
                 }
             }
             return attr;
         }

         private final File dcmPath;
         private final int[] tagPath;
         private final DicomElement elem;
         private final DicomObject parentDcmObj;
         private final TransferSyntax transferSyntax;
     }

     private static boolean areNonValueFieldsEqual(final Attribute a, final DicomElement obj) {
         return a.getTag() == obj.tag() && obj.vr().toString().equals(a.getVr());
     }

     private static String getStringValue(final DicomElement elem, final SpecificCharacterSet charSet) {
         return elem.getValueAsString(charSet, 0);
     }

     private static boolean areEqual(final Attribute a, final DicomElement elem, final String value) {
         if (areNonValueFieldsEqual(a, elem)) {
             if (value == null) {
                 return a.getVal() == null;
             }
             return a.getVal() != null && a.getVal().equals(value);
         }
         return false;
     }

     private static Attribute newAttr(final DicomElement obj) {
         final Attribute attr = new Attribute();
         attr.setTag(obj.tag());
         attr.setVr(obj.vr().toString());
         return attr;
     }

    static int littleEndianBytesToInt(final byte[] bytes, final int index) {
        return bytes[index] & 0xFF | (bytes[index + 1] & 0xFF) << 8 |
                (bytes[index + 2] & 0xFF) << 16 | (bytes[index + 3] & 0xFF) << 24;
    }

     private static final int[] emptyTagPath = new int[0];

     private final LevelAttributes studyLevelTags;
     private final LevelAttributes seriesLevelTags;
     private final MetaBinaryPair metaBinaryPair;
     private int binaryInlineThreshold = 256;
     private boolean p10Aware = true;
}
