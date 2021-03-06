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

import org.nema.medical.mint.metadata.StudyMetadata;


/**
Type used to contain the result of the build.

This is returned by Dcm2MetaBuilder::finish().

@author Uli Bubenheimer
*/
public final class MetaBinaryPairImpl implements MetaBinaryPair {
    /** The study's metadata */
    public final StudyMetadata metadata = new StudyMetadata();

    /** The study's binary data */
    public BinaryData binaryData;

    @Override
    public StudyMetadata getMetadata() {
        return metadata;
    }

    @Override
    public BinaryData getBinaryData() {
        return binaryData;
    }

    public void setBinaryData(final BinaryData binaryData) {
        this.binaryData = binaryData;
    }
}
