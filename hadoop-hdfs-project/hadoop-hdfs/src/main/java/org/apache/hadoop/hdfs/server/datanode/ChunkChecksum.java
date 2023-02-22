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

package org.apache.hadoop.hdfs.server.datanode;

/**
 * holder class that holds checksum bytes and the length in a block at which
 * the checksum bytes end
 * 
 * ex: length = 1023 and checksum is 4 bytes which is for 512 bytes, then
 *     the checksum applies for the last chunk, or bytes 512 - 1023
 */

public class ChunkChecksum {
  private final long dataLength;
  // can be null if not available
  private final byte[] checksum;

  public ChunkChecksum(long dataLength, byte[] checksum) {
    this.dataLength = dataLength;
    this.checksum = checksum;
  }

  public long getDataLength() {
    return dataLength;
  }

  public byte[] getChecksum() {
    return checksum;
  }
}