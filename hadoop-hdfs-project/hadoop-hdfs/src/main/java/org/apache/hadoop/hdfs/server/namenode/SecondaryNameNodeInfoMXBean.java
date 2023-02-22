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
package org.apache.hadoop.hdfs.server.namenode;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

/**
 * JMX information of the secondary NameNode
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public interface SecondaryNameNodeInfoMXBean extends VersionInfoMXBean {
  /**
   * Gets the host and port colon separated.
   */
  public String getHostAndPort();

  /**
   * Gets if security is enabled.
   *
   * @return true, if security is enabled.
   */
  boolean isSecurityEnabled();

  /**
   * @return the timestamp of when the SNN starts
   */
  public long getStartTime();

  /**
   * @return the timestamp of the last checkpoint
   */
  public long getLastCheckpointTime();

  /**
   * @return the number of msec since the last checkpoint, or -1 if no
   * checkpoint has been done yet.
   */
  public long getLastCheckpointDeltaMs();

  /**
   * @return the directories that store the checkpoint images
   */
  public String[] getCheckpointDirectories();
  /**
   * @return the directories that store the edit logs
   */
  public String[] getCheckpointEditlogDirectories();
}
