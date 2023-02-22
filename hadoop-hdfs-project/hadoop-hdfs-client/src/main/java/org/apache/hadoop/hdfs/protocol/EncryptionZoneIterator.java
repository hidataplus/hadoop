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

package org.apache.hadoop.hdfs.protocol;

import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.BatchedRemoteIterator;
import org.apache.hadoop.tracing.TraceScope;
import org.apache.hadoop.tracing.Tracer;

/**
 * EncryptionZoneIterator is a remote iterator that iterates over encryption
 * zones. It supports retrying in case of namenode failover.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class EncryptionZoneIterator
    extends BatchedRemoteIterator<Long, EncryptionZone> {

  private final ClientProtocol namenode;
  private final Tracer tracer;

  public EncryptionZoneIterator(ClientProtocol namenode, Tracer tracer) {
    super((long) 0);
    this.namenode = namenode;
    this.tracer = tracer;
  }

  @Override
  public BatchedEntries<EncryptionZone> makeRequest(Long prevId)
      throws IOException {
    try (TraceScope ignored = tracer.newScope("listEncryptionZones")) {
      return namenode.listEncryptionZones(prevId);
    }
  }

  @Override
  public Long elementToPrevKey(EncryptionZone entry) {
    return entry.getId();
  }
}
