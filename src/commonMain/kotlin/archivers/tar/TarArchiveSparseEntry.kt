/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package archivers.tar

import archivers.tar.TarConstants.SPARSELEN_GNU_SPARSE
import archivers.tar.TarConstants.SPARSE_HEADERS_IN_EXTENSION_HEADER

/**
 * This class represents a sparse entry in a Tar archive.
 *
 *
 *
 * The C structure for a sparse entry is:
 * <pre>
 * struct posix_header {
 * struct sparse sp[21]; // TarConstants.SPARSELEN_GNU_SPARSE     - offset 0
 * char isextended;      // TarConstants.ISEXTENDEDLEN_GNU_SPARSE - offset 504
 * };
</pre> *
 * Whereas, "struct sparse" is:
 * <pre>
 * struct sparse {
 * char offset[12];   // offset 0
 * char numbytes[12]; // offset 12
 * };
</pre> *
 *
 *
 * Each such struct describes a block of data that has actually been written to the archive. The offset describes
 * where in the extracted file the data is supposed to start and the numbytes provides the length of the block. When
 * extracting the entry the gaps between the sparse structs are equivalent to areas filled with zero bytes.
 */
class TarArchiveSparseEntry(headerBuf: ByteArray) {
    /** If an extension sparse header follows.  */
    val isExtended: Boolean

    /**
     * Obtains information about the configuration for the sparse entry.
     * @since 1.20
     * @return information about the configuration for the sparse entry.
     */
    val sparseHeaders: MutableList<TarArchiveStructSparse>

    /**
     * Construct an entry from an archive's header bytes. File is set
     * to null.
     *
     * @param headerBuf The header bytes from a tar archive entry.
     * @throws IOException on unknown format
     */
    init {
        var offset = 0
        sparseHeaders =
            TarUtil.readSparseStructs(headerBuf, 0, SPARSE_HEADERS_IN_EXTENSION_HEADER).toMutableList()
        offset += SPARSELEN_GNU_SPARSE
        isExtended = TarUtil.parseBoolean(headerBuf, offset)
    }
}

