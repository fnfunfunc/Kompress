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

import com.soywiz.korio.stream.SyncInputStream
import kotlin.math.min


/**
 * A stream that limits reading from a wrapped stream to a given number of bytes.
 * @NotThreadSafe
 * @since 1.6
 */
class BoundedInputStream(private val `in`: SyncInputStream, size: Long) : SyncInputStream {

    /**
     * @return bytes remaining to read
     * @since 1.21
     */
    var bytesRemaining: Long
        private set

    /**
     * Creates the stream that will at most read the given amount of
     * bytes from the given stream.
     * @param in the stream to read from
     * @param size the maximum amount of bytes to read
     */
    init {
        bytesRemaining = size
    }

    override fun close() {
        // there isn't anything to close in this stream and the nested
        // stream is controlled externally
    }

    override fun read(): Int {
        if (bytesRemaining > 0) {
            --bytesRemaining
            return `in`.read()
        }
        return -1
    }

    override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        if (len == 0) {
            return 0
        }
        if (bytesRemaining == 0L) {
            return -1
        }
        var bytesToRead = len
        if (bytesToRead > bytesRemaining) {
            bytesToRead = bytesRemaining.toInt()
        }
        val bytesRead: Int = `in`.read(buffer, offset, bytesToRead)
        if (bytesRead >= 0) {
            bytesRemaining -= bytesRead.toLong()
        }
        return bytesRead
    }

    /**
     * @since 1.20
     */
    override fun skip(count: Int) {
        val bytesToSkip: Long = min(bytesRemaining, count.toLong())
        `in`.skip(bytesToSkip.toInt())
        bytesRemaining -= bytesToSkip
//        return bytesSkipped
    }
}

