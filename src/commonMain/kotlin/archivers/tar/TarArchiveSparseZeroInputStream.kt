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

import com.soywiz.korio.stream.AsyncInputStream
import com.soywiz.korio.stream.SyncInputStream

class TarArchiveSparseZeroInputStream: SyncInputStream {
    override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        return 0
    }

    override fun close() {

    }

    override fun read(): Int {
        return 0
    }
}

class TarArchiveSparseZeroAsyncInputStream: AsyncInputStream {
    override suspend fun close() {

    }

    override suspend fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        return 0
    }

    override suspend fun read(): Int {
        return 0
    }
}