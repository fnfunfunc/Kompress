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

package archivers.utils

import com.soywiz.korio.stream.AsyncOutputStream

class CountingAsyncOutputStream(private val outputStream: AsyncOutputStream): AsyncOutputStream {

    private var bytesWritten: Long = 0

    /**
     * Increments the counter of already written bytes.
     * Doesn't increment if the EOF has been hit (written == -1)
     *
     * @param written the number of bytes written
     */
    fun count(written: Long) {
        if (written != -1L) {
            bytesWritten += written
        }
    }

    /**
     * Returns the current number of bytes written to this stream.
     * @return the number of written bytes
     */
    fun getBytesWritten(): Long {
        return bytesWritten
    }


    override suspend fun write(buffer: ByteArray, offset: Int, len: Int) {
        outputStream.write(buffer, offset, len)
        count(len.toLong())
    }


    override suspend fun write(byte: Int) {
        outputStream.write(byte)
        count(1)
    }

    suspend fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override suspend fun close() {
        outputStream.close()
    }
}