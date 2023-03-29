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

/**
 * This class represents struct sparse in a Tar archive.
 *
 *
 * Whereas, "struct sparse" is:
 * <pre>
 * struct sparse {
 * char offset[12];   // offset 0
 * char numbytes[12]; // offset 12
 * };
</pre> *
 */
data class TarArchiveStructSparse(val offset: Long, val numbytes: Long) {

    init {
        if (offset < 0) {
            throw IllegalArgumentException("offset must not be negative")
        }
        if (numbytes < 0) {
            throw IllegalArgumentException("numbytes must not be negative")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || other !is TarArchiveStructSparse) {
            return false
        }
        return offset == other.offset &&
                numbytes == other.numbytes
    }

    override fun hashCode(): Int {
        return utils.hashCode(arrayOf(offset, numbytes))
    }
}

