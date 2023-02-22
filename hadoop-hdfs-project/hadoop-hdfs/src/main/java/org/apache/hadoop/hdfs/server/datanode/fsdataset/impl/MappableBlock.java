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

package org.apache.hadoop.hdfs.server.datanode.fsdataset.impl;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hdfs.ExtendedBlockId;

import java.io.Closeable;

/**
 * Represents an HDFS block that is mapped by the DataNode.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public interface MappableBlock extends Closeable {

  /**
   * Get the number of bytes that have been cached.
   * @return the number of bytes that have been cached.
   */
  long getLength();

  /**
   * Get cache address if applicable.
   * Return -1 if not applicable.
   */
  long getAddress();

  /**
   * Get cached block's ExtendedBlockId.
   * @return cached block's ExtendedBlockId..
   */
  ExtendedBlockId getKey();
}
