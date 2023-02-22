/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

/**
 * Implementers of this interface provide a read API that writes to a
 * ByteBuffer, not a byte[].
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public interface ByteBufferReadable {
  /**
   * Reads up to buf.remaining() bytes into buf. Callers should use
   * buf.limit(..) to control the size of the desired read.
   * <p>
   * After a successful call, {@code buf.position()} will be advanced by the
   * number of bytes read and {@code buf.limit()} will be unchanged.
   * <p>
   * In the case of an exception, the state of the buffer (the contents of the
   * buffer, the {@code buf.position()}, the {@code buf.limit()}, etc.) is
   * undefined, and callers should be prepared to recover from this
   * eventuality.
   * <p>
   * Callers should use {@link StreamCapabilities#hasCapability(String)} with
   * {@link StreamCapabilities#READBYTEBUFFER} to check if the underlying
   * stream supports this interface, otherwise they might get a
   * {@link UnsupportedOperationException}.
   * <p>
   * Implementations should treat 0-length requests as legitimate, and must not
   * signal an error upon their receipt.
   *
   * @param buf
   *          the ByteBuffer to receive the results of the read operation.
   * @return the number of bytes read, possibly zero, or -1 if 
   *         reach end-of-stream
   * @throws IOException
   *           if there is some error performing the read
   */
  public int read(ByteBuffer buf) throws IOException;
}
