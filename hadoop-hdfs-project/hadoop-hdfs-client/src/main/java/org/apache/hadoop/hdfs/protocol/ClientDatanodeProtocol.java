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
import java.util.List;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.ReconfigurationTaskStatus;
import org.apache.hadoop.hdfs.client.BlockReportOptions;
import org.apache.hadoop.hdfs.client.HdfsClientConfigKeys;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenSelector;
import org.apache.hadoop.security.KerberosInfo;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenInfo;
import org.apache.hadoop.hdfs.server.datanode.DiskBalancerWorkStatus;

/** An client-datanode protocol for block recovery
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
@KerberosInfo(
    serverPrincipal = HdfsClientConfigKeys.DFS_DATANODE_KERBEROS_PRINCIPAL_KEY)
@TokenInfo(BlockTokenSelector.class)
public interface ClientDatanodeProtocol {
  /**
   * Until version 9, this class ClientDatanodeProtocol served as both
   * the client interface to the DN AND the RPC protocol used to
   * communicate with the NN.
   *
   * This class is used by both the DFSClient and the
   * DN server side to insulate from the protocol serialization.
   *
   * If you are adding/changing DN's interface then you need to
   * change both this class and ALSO related protocol buffer
   * wire protocol definition in ClientDatanodeProtocol.proto.
   *
   * For more details on protocol buffer wire protocol, please see
   * .../org/apache/hadoop/hdfs/protocolPB/overview.html
   *
   * The log of historical changes can be retrieved from the svn).
   * 9: Added deleteBlockPool method
   *
   * 9 is the last version id when this class was used for protocols
   *  serialization. DO not update this version any further.
   */
  long versionID = 9L;

  /** Return the visible length of a replica. */
  long getReplicaVisibleLength(ExtendedBlock b) throws IOException;

  /**
   * Refresh the list of federated namenodes from updated configuration
   * Adds new namenodes and stops the deleted namenodes.
   *
   * @throws IOException on error
   **/
  void refreshNamenodes() throws IOException;

  /**
   * Delete the block pool directory. If force is false it is deleted only if
   * it is empty, otherwise it is deleted along with its contents.
   *
   * @param bpid Blockpool id to be deleted.
   * @param force If false blockpool directory is deleted only if it is empty
   *          i.e. if it doesn't contain any block files, otherwise it is
   *          deleted along with its contents.
   * @throws IOException
   */
  void deleteBlockPool(String bpid, boolean force) throws IOException;

  /**
   * Retrieves the path names of the block file and metadata file stored on the
   * local file system.
   *
   * In order for this method to work, one of the following should be satisfied:
   * <ul>
   * <li>
   * The client user must be configured at the datanode to be able to use this
   * method.</li>
   * <li>
   * When security is enabled, kerberos authentication must be used to connect
   * to the datanode.</li>
   * </ul>
   *
   * @param block
   *          the specified block on the local datanode
   * @param token
   *          the block access token.
   * @return the BlockLocalPathInfo of a block
   * @throws IOException
   *           on error
   */
  BlockLocalPathInfo getBlockLocalPathInfo(ExtendedBlock block,
      Token<BlockTokenIdentifier> token) throws IOException;

  /**
   * Shuts down a datanode.
   *
   * @param forUpgrade If true, data node does extra prep work before shutting
   *          down. The work includes advising clients to wait and saving
   *          certain states for quick restart. This should only be used when
   *          the stored data will remain the same during upgrade/restart.
   * @throws IOException
   */
  void shutdownDatanode(boolean forUpgrade) throws IOException;

  /**
   * Evict clients that are writing to a datanode.
   *
   * @throws IOException
   */
  void evictWriters() throws IOException;

  /**
   * Obtains datanode info
   *
   * @return software/config version and uptime of the datanode
   */
  DatanodeLocalInfo getDatanodeInfo() throws IOException;

  /**
   * Asynchronously reload configuration on disk and apply changes.
   */
  void startReconfiguration() throws IOException;

  /**
   * Get the status of the previously issued reconfig task.
   * @see org.apache.hadoop.conf.ReconfigurationTaskStatus
   */
  ReconfigurationTaskStatus getReconfigurationStatus() throws IOException;

  /**
   * Get a list of allowed properties for reconfiguration.
   */
  List<String> listReconfigurableProperties() throws IOException;

  /**
   * Trigger a new block report.
   */
  void triggerBlockReport(BlockReportOptions options)
    throws IOException;

  /**
   * Get current value of the balancer bandwidth in bytes per second.
   *
   * @return balancer bandwidth
   */
  long getBalancerBandwidth() throws IOException;

  /**
   * Get volume report of datanode.
   */
  List<DatanodeVolumeInfo> getVolumeReport() throws IOException;

  /**
   * Submit a disk balancer plan for execution.
   */
  void submitDiskBalancerPlan(String planID, long planVersion, String planFile,
                              String planData, boolean skipDateCheck)
       throws IOException;

  /**
   * Cancel an executing plan.
   *
   * @param planID - A SHA-1 hash of the plan string.
   */
  void cancelDiskBalancePlan(String planID) throws IOException;


  /**
   * Gets the status of an executing diskbalancer Plan.
   */
  DiskBalancerWorkStatus queryDiskBalancerPlan() throws IOException;

  /**
   * Gets a run-time configuration value from running diskbalancer instance.
   * For example : Disk Balancer bandwidth of a running instance.
   *
   * @param key runtime configuration key
   * @return value of the key as a string.
   * @throws IOException - Throws if there is no such key
   */
  String getDiskBalancerSetting(String key) throws IOException;
}
