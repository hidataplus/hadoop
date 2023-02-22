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
package org.apache.hadoop.hdfs;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_HA_ALLOW_STALE_READ_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_HA_ALLOW_STALE_READ_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_HA_NAMENODE_ID_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_HTTPS_ADDRESS_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_HTTPS_BIND_HOST_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_HTTP_BIND_HOST_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_LIFELINE_RPC_ADDRESS_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_LIFELINE_RPC_BIND_HOST_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_RPC_BIND_HOST_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_SERVICE_RPC_ADDRESS_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_SERVICE_RPC_BIND_HOST_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_SHARED_EDITS_DIR_KEY;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ha.HAServiceProtocol.HAServiceState;
import org.apache.hadoop.hdfs.NameNodeProxiesClient.ProxyAndInfo;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.ha.AbstractNNFailoverProxyProvider;
import org.apache.hadoop.io.MultipleIOException;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.ipc.StandbyException;
import org.apache.hadoop.security.UserGroupInformation;

import org.apache.hadoop.thirdparty.com.google.common.base.Joiner;
import org.apache.hadoop.thirdparty.com.google.common.base.Preconditions;
import org.apache.hadoop.thirdparty.com.google.common.collect.Lists;
import org.slf4j.LoggerFactory;

@InterfaceAudience.Private
public class HAUtil {

  public static final org.slf4j.Logger LOG =
      LoggerFactory.getLogger(HAUtil.class.getName());

  private static final String[] HA_SPECIAL_INDEPENDENT_KEYS = new String[]{
    DFS_NAMENODE_RPC_ADDRESS_KEY,
    DFS_NAMENODE_RPC_BIND_HOST_KEY,
    DFS_NAMENODE_LIFELINE_RPC_ADDRESS_KEY,
    DFS_NAMENODE_LIFELINE_RPC_BIND_HOST_KEY,
    DFS_NAMENODE_SERVICE_RPC_ADDRESS_KEY,
    DFS_NAMENODE_SERVICE_RPC_BIND_HOST_KEY,
    DFS_NAMENODE_HTTP_ADDRESS_KEY,
    DFS_NAMENODE_HTTPS_ADDRESS_KEY,
    DFS_NAMENODE_HTTP_BIND_HOST_KEY,
    DFS_NAMENODE_HTTPS_BIND_HOST_KEY,
  };

  private HAUtil() { /* Hidden constructor */ }

  /**
   * Returns true if HA for namenode is configured for the given nameservice
   * 
   * @param conf Configuration
   * @param nsId nameservice, or null if no federated NS is configured
   * @return true if HA is configured in the configuration; else false.
   */
  public static boolean isHAEnabled(Configuration conf, String nsId) {
    Map<String, Map<String, InetSocketAddress>> addresses =
        DFSUtilClient.getHaNnRpcAddresses(conf);
    if (addresses == null) return false;
    Map<String, InetSocketAddress> nnMap = addresses.get(nsId);
    return nnMap != null && nnMap.size() > 1;
  }

  /**
   * Returns true if HA is using a shared edits directory.
   *
   * @param conf Configuration
   * @return true if HA config is using a shared edits dir, false otherwise.
   */
  public static boolean usesSharedEditsDir(Configuration conf) {
    return null != conf.get(DFS_NAMENODE_SHARED_EDITS_DIR_KEY);
  }

  /**
   * Get the namenode Id by matching the {@code addressKey}
   * with the the address of the local node.
   * 
   * If {@link DFSConfigKeys#DFS_HA_NAMENODE_ID_KEY} is not specifically
   * configured, this method determines the namenode Id by matching the local
   * node's address with the configured addresses. When a match is found, it
   * returns the namenode Id from the corresponding configuration key.
   * 
   * @param conf Configuration
   * @return namenode Id on success, null on failure.
   * @throws HadoopIllegalArgumentException on error
   */
  public static String getNameNodeId(Configuration conf, String nsId) {
    String namenodeId = conf.getTrimmed(DFS_HA_NAMENODE_ID_KEY);
    if (namenodeId != null) {
      return namenodeId;
    }
    
    String suffixes[] = DFSUtil.getSuffixIDs(conf, DFS_NAMENODE_RPC_ADDRESS_KEY,
        nsId, null, DFSUtil.LOCAL_ADDRESS_MATCHER);
    if (suffixes == null) {
      String msg = "Configuration " + DFS_NAMENODE_RPC_ADDRESS_KEY + 
          " must be suffixed with nameservice and namenode ID for HA " +
          "configuration.";
      throw new HadoopIllegalArgumentException(msg);
    }
    
    return suffixes[1];
  }

  /**
   * Similar to
   * {@link DFSUtil#getNameServiceIdFromAddress(Configuration, 
   * InetSocketAddress, String...)}
   */
  public static String getNameNodeIdFromAddress(final Configuration conf, 
      final InetSocketAddress address, String... keys) {
    // Configuration with a single namenode and no nameserviceId
    String[] ids = DFSUtil.getSuffixIDs(conf, address, keys);
    if (ids != null && ids.length > 1) {
      return ids[1];
    }
    return null;
  }
  
  /**
   * Get the NN ID of the other nodes in an HA setup.
   * 
   * @param conf the configuration of this node
   * @return a list of NN IDs of other nodes in this nameservice
   */
  public static List<String> getNameNodeIdOfOtherNodes(Configuration conf, String nsId) {
    Preconditions.checkArgument(nsId != null,
        "Could not determine namespace id. Please ensure that this " +
        "machine is one of the machines listed as a NN RPC address, " +
        "or configure " + DFSConfigKeys.DFS_NAMESERVICE_ID);
    
    Collection<String> nnIds = DFSUtilClient.getNameNodeIds(conf, nsId);
    String myNNId = conf.get(DFSConfigKeys.DFS_HA_NAMENODE_ID_KEY);
    Preconditions.checkArgument(nnIds != null,
        "Could not determine namenode ids in namespace '%s'. " +
        "Please configure " +
        DFSUtil.addKeySuffixes(DFSConfigKeys.DFS_HA_NAMENODES_KEY_PREFIX,
            nsId),
        nsId);
    Preconditions.checkArgument(nnIds.size() >= 2,
        "Expected at least 2 NameNodes in namespace '%s'. " +
          "Instead, got only %s (NN ids were '%s')",
          nsId, nnIds.size(), Joiner.on("','").join(nnIds));
    Preconditions.checkState(myNNId != null && !myNNId.isEmpty(),
        "Could not determine own NN ID in namespace '%s'. Please " +
        "ensure that this node is one of the machines listed as an " +
        "NN RPC address, or configure " + DFSConfigKeys.DFS_HA_NAMENODE_ID_KEY,
        nsId);

    ArrayList<String> namenodes = Lists.newArrayList(nnIds);
    namenodes.remove(myNNId);
    assert namenodes.size() >= 1;
    return namenodes;
  }

  /**
   * Given the configuration for this node, return a list of configurations
   * for the other nodes in an HA setup.
   * 
   * @param myConf the configuration of this node
   * @return a list of configuration of other nodes in an HA setup
   */
  public static List<Configuration> getConfForOtherNodes(
      Configuration myConf) {
    
    String nsId = DFSUtil.getNamenodeNameServiceId(myConf);
    List<String> otherNodes = getNameNodeIdOfOtherNodes(myConf, nsId);

    // Look up the address of the other NNs
    List<Configuration> confs = new ArrayList<Configuration>(otherNodes.size());
    myConf = new Configuration(myConf);
    // unset independent properties
    for (String idpKey : HA_SPECIAL_INDEPENDENT_KEYS) {
      myConf.unset(idpKey);
    }
    for (String nn : otherNodes) {
      Configuration confForOtherNode = new Configuration(myConf);
      NameNode.initializeGenericKeys(confForOtherNode, nsId, nn);
      confs.add(confForOtherNode);
    }
    return confs;
  }

  /**
   * This is used only by tests at the moment.
   * @return true if the NN should allow read operations while in standby mode.
   */
  public static boolean shouldAllowStandbyReads(Configuration conf) {
    return conf.getBoolean(DFS_HA_ALLOW_STALE_READ_KEY,
        DFS_HA_ALLOW_STALE_READ_DEFAULT);
  }
  
  public static void setAllowStandbyReads(Configuration conf, boolean val) {
    conf.setBoolean(DFS_HA_ALLOW_STALE_READ_KEY, val);
  }

  /**
   * Check whether logical URI is needed for the namenode and
   * the corresponding failover proxy provider in the config.
   *
   * @param conf Configuration
   * @param nameNodeUri The URI of namenode
   * @return true if logical URI is needed. false, if not needed.
   * @throws IOException most likely due to misconfiguration.
   */
  public static boolean useLogicalUri(Configuration conf, URI nameNodeUri) 
      throws IOException {
    // Create the proxy provider. Actual proxy is not created.
    AbstractNNFailoverProxyProvider<ClientProtocol> provider = NameNodeProxiesClient
        .createFailoverProxyProvider(conf, nameNodeUri, ClientProtocol.class,
            false, null);

    // No need to use logical URI since failover is not configured.
    if (provider == null) {
      return false;
    }
    // Check whether the failover proxy provider uses logical URI.
    return provider.useLogicalURI();
  }

  /**
   * Get the internet address of the currently-active NN. This should rarely be
   * used, since callers of this method who connect directly to the NN using the
   * resulting InetSocketAddress will not be able to connect to the active NN if
   * a failover were to occur after this method has been called.
   * 
   * @param fs the file system to get the active address of.
   * @return the internet address of the currently-active NN.
   * @throws IOException if an error occurs while resolving the active NN.
   */
  public static InetSocketAddress getAddressOfActive(FileSystem fs)
      throws IOException {
    InetSocketAddress inAddr = null;
    if (!(fs instanceof DistributedFileSystem)) {
      throw new IllegalArgumentException("FileSystem " + fs + " is not a DFS.");
    }
    // force client address resolution.
    fs.exists(new Path("/"));
    DistributedFileSystem dfs = (DistributedFileSystem) fs;
    Configuration dfsConf = dfs.getConf();
    URI dfsUri = dfs.getUri();
    String nsId = dfsUri.getHost();
    if (isHAEnabled(dfsConf, nsId)) {
      List<ClientProtocol> namenodes =
          getProxiesForAllNameNodesInNameservice(dfsConf, nsId);
      for (ClientProtocol proxy : namenodes) {
        try {
          if (proxy.getHAServiceState().equals(HAServiceState.ACTIVE)) {
            inAddr = RPC.getServerAddress(proxy);
          }
        } catch (Exception e) {
          //Ignore the exception while connecting to a namenode.
          LOG.debug("Error while connecting to namenode", e);
        }
      }
    } else {
      DFSClient dfsClient = dfs.getClient();
      inAddr = RPC.getServerAddress(dfsClient.getNamenode());
    }
    return inAddr;
  }
  
  /**
   * Get an RPC proxy for each NN in an HA nameservice. Used when a given RPC
   * call should be made on every NN in an HA nameservice, not just the active.
   * 
   * @param conf configuration
   * @param nsId the nameservice to get all of the proxies for.
   * @return a list of RPC proxies for each NN in the nameservice.
   * @throws IOException in the event of error.
   */
  public static List<ClientProtocol> getProxiesForAllNameNodesInNameservice(
      Configuration conf, String nsId) throws IOException {
    List<ProxyAndInfo<ClientProtocol>> proxies =
        getProxiesForAllNameNodesInNameservice(conf, nsId, ClientProtocol.class);

    List<ClientProtocol> namenodes = new ArrayList<ClientProtocol>(
        proxies.size());
    for (ProxyAndInfo<ClientProtocol> proxy : proxies) {
      namenodes.add(proxy.getProxy());
    }
    return namenodes;
  }

  /**
   * Get an RPC proxy for each NN in an HA nameservice. Used when a given RPC
   * call should be made on every NN in an HA nameservice, not just the active.
   *
   * @param conf configuration
   * @param nsId the nameservice to get all of the proxies for.
   * @param xface the protocol class.
   * @return a list of RPC proxies for each NN in the nameservice.
   * @throws IOException in the event of error.
   */
  public static <T> List<ProxyAndInfo<T>> getProxiesForAllNameNodesInNameservice(
      Configuration conf, String nsId, Class<T> xface) throws IOException {
    Map<String, InetSocketAddress> nnAddresses =
        DFSUtil.getRpcAddressesForNameserviceId(conf, nsId, null);
    
    List<ProxyAndInfo<T>> proxies = new ArrayList<ProxyAndInfo<T>>(
        nnAddresses.size());
    for (InetSocketAddress nnAddress : nnAddresses.values()) {
      ProxyAndInfo<T> proxyInfo = NameNodeProxies.createNonHAProxy(conf,
          nnAddress, xface,
          UserGroupInformation.getCurrentUser(), false);
      proxies.add(proxyInfo);
    }
    return proxies;
  }
  
  /**
   * Used to ensure that at least one of the given HA NNs is currently in the
   * active state..
   * 
   * @param namenodes list of RPC proxies for each NN to check.
   * @return true if at least one NN is active, false if all are in the standby state.
   * @throws IOException in the event of error.
   */
  public static boolean isAtLeastOneActive(List<ClientProtocol> namenodes)
      throws IOException {
    List<IOException> exceptions = new ArrayList<>();
    for (ClientProtocol namenode : namenodes) {
      try {
        namenode.getFileInfo("/");
        return true;
      } catch (RemoteException re) {
        IOException cause = re.unwrapRemoteException();
        if (cause instanceof StandbyException) {
          // This is expected to happen for a standby NN.
        } else {
          exceptions.add(re);
        }
      } catch (IOException ioe) {
        exceptions.add(ioe);
      }
    }
    if(!exceptions.isEmpty()){
      throw MultipleIOException.createIOException(exceptions);
    }
    return false;
  }
}
