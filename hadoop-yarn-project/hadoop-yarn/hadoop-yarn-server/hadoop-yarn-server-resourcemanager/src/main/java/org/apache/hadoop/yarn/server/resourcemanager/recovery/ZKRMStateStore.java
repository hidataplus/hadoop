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

package org.apache.hadoop.yarn.server.resourcemanager.recovery;

import org.apache.hadoop.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.yarn.util.Clock;
import org.apache.hadoop.yarn.util.SystemClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.security.token.delegation.DelegationKey;
import org.apache.hadoop.util.ZKUtil;
import org.apache.hadoop.util.curator.ZKCuratorManager;
import org.apache.hadoop.util.curator.ZKCuratorManager.SafeTransaction;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ReservationId;
import org.apache.hadoop.yarn.conf.HAUtil;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.proto.YarnServerCommonProtos.VersionProto;
import org.apache.hadoop.yarn.proto.YarnServerResourceManagerRecoveryProtos.AMRMTokenSecretManagerStateProto;
import org.apache.hadoop.yarn.proto.YarnServerResourceManagerRecoveryProtos.ApplicationAttemptStateDataProto;
import org.apache.hadoop.yarn.proto.YarnServerResourceManagerRecoveryProtos.ApplicationStateDataProto;
import org.apache.hadoop.yarn.proto.YarnServerResourceManagerRecoveryProtos.EpochProto;
import org.apache.hadoop.yarn.proto.YarnProtos.ReservationAllocationStateProto;
import org.apache.hadoop.yarn.security.client.RMDelegationTokenIdentifier;
import org.apache.hadoop.yarn.server.records.Version;
import org.apache.hadoop.yarn.server.records.impl.pb.VersionPBImpl;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.AMRMTokenSecretManagerState;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.ApplicationAttemptStateData;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.ApplicationStateData;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.Epoch;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.RMDelegationTokenIdentifierData;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.impl.pb.AMRMTokenSecretManagerStatePBImpl;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.impl.pb.ApplicationAttemptStateDataPBImpl;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.impl.pb.ApplicationStateDataPBImpl;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.impl.pb.EpochPBImpl;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.auth.DigestAuthenticationProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link RMStateStore} implementation backed by ZooKeeper.
 *
 * The znode structure is as follows:
 * ROOT_DIR_PATH
 * |--- VERSION_INFO
 * |--- EPOCH_NODE
 * |--- RM_ZK_FENCING_LOCK
 * |--- RM_APP_ROOT
 * |     |----- HIERARCHIES
 * |     |        |----- 1
 * |     |        |      |----- (#ApplicationId barring last character)
 * |     |        |      |       |----- (#Last character of ApplicationId)
 * |     |        |      |       |       |----- (#ApplicationAttemptIds)
 * |     |        |      ....
 * |     |        |
 * |     |        |----- 2
 * |     |        |      |----- (#ApplicationId barring last 2 characters)
 * |     |        |      |       |----- (#Last 2 characters of ApplicationId)
 * |     |        |      |       |       |----- (#ApplicationAttemptIds)
 * |     |        |      ....
 * |     |        |
 * |     |        |----- 3
 * |     |        |      |----- (#ApplicationId barring last 3 characters)
 * |     |        |      |       |----- (#Last 3 characters of ApplicationId)
 * |     |        |      |       |       |----- (#ApplicationAttemptIds)
 * |     |        |      ....
 * |     |        |
 * |     |        |----- 4
 * |     |        |      |----- (#ApplicationId barring last 4 characters)
 * |     |        |      |       |----- (#Last 4 characters of ApplicationId)
 * |     |        |      |       |       |----- (#ApplicationAttemptIds)
 * |     |        |      ....
 * |     |        |
 * |     |----- (#ApplicationId1)
 * |     |        |----- (#ApplicationAttemptIds)
 * |     |
 * |     |----- (#ApplicationId2)
 * |     |       |----- (#ApplicationAttemptIds)
 * |     ....
 * |
 * |--- RM_DT_SECRET_MANAGER_ROOT
 *        |----- RM_DT_SEQUENTIAL_NUMBER_ZNODE_NAME
 *        |----- RM_DELEGATION_TOKENS_ROOT_ZNODE_NAME
 *        |       |----- 1
 *        |       |      |----- (#TokenId barring last character)
 *        |       |      |       |----- (#Last character of TokenId)
 *        |       |      ....
 *        |       |----- 2
 *        |       |      |----- (#TokenId barring last 2 characters)
 *        |       |      |       |----- (#Last 2 characters of TokenId)
 *        |       |      ....
 *        |       |----- 3
 *        |       |      |----- (#TokenId barring last 3 characters)
 *        |       |      |       |----- (#Last 3 characters of TokenId)
 *        |       |      ....
 *        |       |----- 4
 *        |       |      |----- (#TokenId barring last 4 characters)
 *        |       |      |       |----- (#Last 4 characters of TokenId)
 *        |       |      ....
 *        |       |----- Token_1
 *        |       |----- Token_2
 *        |       ....
 *        |
 *        |----- RM_DT_MASTER_KEYS_ROOT_ZNODE_NAME
 *        |      |----- Key_1
 *        |      |----- Key_2
 *                ....
 * |--- AMRMTOKEN_SECRET_MANAGER_ROOT
 *        |----- currentMasterKey
 *        |----- nextMasterKey
 *
 * |-- RESERVATION_SYSTEM_ROOT
 *        |------PLAN_1
 *        |      |------ RESERVATION_1
 *        |      |------ RESERVATION_2
 *        |      ....
 *        |------PLAN_2
 *        ....
 * |-- PROXY_CA_ROOT
 *        |----- caCert
 *        |----- caPrivateKey
 *
 * Note: Changes from 1.1 to 1.2 - AMRMTokenSecretManager state has been saved
 * separately. The currentMasterkey and nextMasterkey have been stored.
 * Also, AMRMToken has been removed from ApplicationAttemptState.
 *
 * Changes from 1.2 to 1.3, Addition of ReservationSystem state.
 *
 * Changes from 1.3 to 1.4 - Change the structure of application znode by
 * splitting it in 2 parts, depending on a configurable split index. This limits
 * the number of application znodes returned in a single call while loading
 * app state.
 *
 * Changes from 1.4 to 1.5 - Change the structure of delegation token znode by
 * splitting it in 2 parts, depending on a configurable split index. This limits
 * the number of delegation token znodes returned in a single call while loading
 * tokens state.
 */
@Private
@Unstable
public class ZKRMStateStore extends RMStateStore {
  private static final Logger LOG =
      LoggerFactory.getLogger(ZKRMStateStore.class);

  private static final String RM_DELEGATION_TOKENS_ROOT_ZNODE_NAME =
      "RMDelegationTokensRoot";
  private static final String RM_DT_SEQUENTIAL_NUMBER_ZNODE_NAME =
      "RMDTSequentialNumber";
  private static final String RM_DT_MASTER_KEYS_ROOT_ZNODE_NAME =
      "RMDTMasterKeysRoot";
  @VisibleForTesting
  public static final String ROOT_ZNODE_NAME = "ZKRMStateRoot";
  protected static final Version CURRENT_VERSION_INFO = Version
      .newInstance(1, 5);
  @VisibleForTesting
  public static final String RM_APP_ROOT_HIERARCHIES = "HIERARCHIES";

  /* Znode paths */
  private String zkRootNodePath;
  private String rmAppRoot;
  private Map<Integer, String> rmAppRootHierarchies;
  private Map<Integer, String> rmDelegationTokenHierarchies;
  private String rmDTSecretManagerRoot;
  private String dtMasterKeysRootPath;
  private String delegationTokensRootPath;
  private String dtSequenceNumberPath;
  private String amrmTokenSecretManagerRoot;
  private String reservationRoot;
  private String proxyCARoot;

  @VisibleForTesting
  protected String znodeWorkingPath;
  private int appIdNodeSplitIndex = 0;
  @VisibleForTesting
  protected int delegationTokenNodeSplitIndex = 0;

  /* Fencing related variables */
  private static final String FENCING_LOCK = "RM_ZK_FENCING_LOCK";
  private String fencingNodePath;
  private Thread verifyActiveStatusThread;
  private int zkSessionTimeout;
  private int zknodeLimit;

  /* ACL and auth info */
  private List<ACL> zkAcl;
  @VisibleForTesting
  List<ACL> zkRootNodeAcl;
  private String zkRootNodeUsername;

  private static final int CREATE_DELETE_PERMS =
      ZooDefs.Perms.CREATE | ZooDefs.Perms.DELETE;
  private final String zkRootNodeAuthScheme =
      new DigestAuthenticationProvider().getScheme();

  /** Manager for the ZooKeeper connection. */
  private ZKCuratorManager zkManager;

  private volatile Clock clock = SystemClock.getInstance();
  @VisibleForTesting
  protected ZKRMStateStoreOpDurations opDurations;

  /*
   * Indicates different app attempt state store operations.
   */
  private enum AppAttemptOp {
    STORE,
    UPDATE,
    REMOVE
  };

  /**
   * Encapsulates znode path and corresponding split index for hierarchical
   * znode layouts.
   */
  private final static class ZnodeSplitInfo {
    private final String path;
    private final int splitIndex;
    ZnodeSplitInfo(String path, int splitIndex) {
      this.path = path;
      this.splitIndex = splitIndex;
    }
  }

  /**
   * Given the {@link Configuration} and {@link ACL}s used (sourceACLs) for
   * ZooKeeper access, construct the {@link ACL}s for the store's root node.
   * In the constructed {@link ACL}, all the users allowed by sourceACLs are
   * given read-write-admin access, while the current RM has exclusive
   * create-delete access.
   *
   * To be called only when HA is enabled and the configuration doesn't set an
   * ACL for the root node.
   * @param conf the configuration
   * @param sourceACLs the source ACLs
   * @return ACLs for the store's root node
   * @throws java.security.NoSuchAlgorithmException thrown if the digest
   * algorithm used by Zookeeper cannot be found
   */
  @VisibleForTesting
  @Private
  @Unstable
  protected List<ACL> constructZkRootNodeACL(Configuration conf,
      List<ACL> sourceACLs) throws NoSuchAlgorithmException {
    List<ACL> zkRootNodeAclList = new ArrayList<>();

    for (ACL acl : sourceACLs) {
      zkRootNodeAclList.add(new ACL(
          ZKUtil.removeSpecificPerms(acl.getPerms(), CREATE_DELETE_PERMS),
          acl.getId()));
    }

    zkRootNodeUsername = HAUtil.getConfValueForRMInstance(
        YarnConfiguration.RM_ADDRESS,
        YarnConfiguration.DEFAULT_RM_ADDRESS, conf);
    Id rmId = new Id(zkRootNodeAuthScheme,
        DigestAuthenticationProvider.generateDigest(zkRootNodeUsername + ":"
            + resourceManager.getZkRootNodePassword()));
    zkRootNodeAclList.add(new ACL(CREATE_DELETE_PERMS, rmId));

    return zkRootNodeAclList;
  }

  @Override
  public synchronized void initInternal(Configuration conf)
      throws IOException, NoSuchAlgorithmException {
    /* Initialize fencing related paths, acls, and ops */
    znodeWorkingPath =
        conf.get(YarnConfiguration.ZK_RM_STATE_STORE_PARENT_PATH,
            YarnConfiguration.DEFAULT_ZK_RM_STATE_STORE_PARENT_PATH);
    zkRootNodePath = getNodePath(znodeWorkingPath, ROOT_ZNODE_NAME);
    rmAppRoot = getNodePath(zkRootNodePath, RM_APP_ROOT);
    String hierarchiesPath = getNodePath(rmAppRoot, RM_APP_ROOT_HIERARCHIES);
    rmAppRootHierarchies = new HashMap<>(5);
    rmAppRootHierarchies.put(0, rmAppRoot);
    for (int splitIndex = 1; splitIndex <= 4; splitIndex++) {
      rmAppRootHierarchies.put(splitIndex,
          getNodePath(hierarchiesPath, Integer.toString(splitIndex)));
    }

    fencingNodePath = getNodePath(zkRootNodePath, FENCING_LOCK);
    zkSessionTimeout = conf.getInt(YarnConfiguration.RM_ZK_TIMEOUT_MS,
        YarnConfiguration.DEFAULT_RM_ZK_TIMEOUT_MS);
    zknodeLimit = conf.getInt(YarnConfiguration.RM_ZK_ZNODE_SIZE_LIMIT_BYTES,
        YarnConfiguration.DEFAULT_RM_ZK_ZNODE_SIZE_LIMIT_BYTES);

    appIdNodeSplitIndex =
        conf.getInt(YarnConfiguration.ZK_APPID_NODE_SPLIT_INDEX,
            YarnConfiguration.DEFAULT_ZK_APPID_NODE_SPLIT_INDEX);
    if (appIdNodeSplitIndex < 0 || appIdNodeSplitIndex > 4) {
      LOG.info("Invalid value " + appIdNodeSplitIndex + " for config " +
          YarnConfiguration.ZK_APPID_NODE_SPLIT_INDEX + " specified. " +
              "Resetting it to " +
                  YarnConfiguration.DEFAULT_ZK_APPID_NODE_SPLIT_INDEX);
      appIdNodeSplitIndex = YarnConfiguration.DEFAULT_ZK_APPID_NODE_SPLIT_INDEX;
    }

    opDurations = ZKRMStateStoreOpDurations.getInstance();

    zkAcl = ZKCuratorManager.getZKAcls(conf);

    if (HAUtil.isHAEnabled(conf)) {
      String zkRootNodeAclConf = HAUtil.getConfValueForRMInstance
          (YarnConfiguration.ZK_RM_STATE_STORE_ROOT_NODE_ACL, conf);
      if (zkRootNodeAclConf != null) {
        zkRootNodeAclConf = ZKUtil.resolveConfIndirection(zkRootNodeAclConf);

        try {
          zkRootNodeAcl = ZKUtil.parseACLs(zkRootNodeAclConf);
        } catch (ZKUtil.BadAclFormatException bafe) {
          LOG.error("Invalid format for "
              + YarnConfiguration.ZK_RM_STATE_STORE_ROOT_NODE_ACL);
          throw bafe;
        }
      } else {
        zkRootNodeAcl = constructZkRootNodeACL(conf, zkAcl);
      }
    }

    rmDTSecretManagerRoot =
        getNodePath(zkRootNodePath, RM_DT_SECRET_MANAGER_ROOT);
    dtMasterKeysRootPath = getNodePath(rmDTSecretManagerRoot,
        RM_DT_MASTER_KEYS_ROOT_ZNODE_NAME);
    delegationTokensRootPath = getNodePath(rmDTSecretManagerRoot,
        RM_DELEGATION_TOKENS_ROOT_ZNODE_NAME);
    rmDelegationTokenHierarchies = new HashMap<>(5);
    rmDelegationTokenHierarchies.put(0, delegationTokensRootPath);
    for (int splitIndex = 1; splitIndex <= 4; splitIndex++) {
      rmDelegationTokenHierarchies.put(splitIndex,
          getNodePath(delegationTokensRootPath, Integer.toString(splitIndex)));
    }
    dtSequenceNumberPath = getNodePath(rmDTSecretManagerRoot,
        RM_DT_SEQUENTIAL_NUMBER_ZNODE_NAME);
    amrmTokenSecretManagerRoot =
        getNodePath(zkRootNodePath, AMRMTOKEN_SECRET_MANAGER_ROOT);
    proxyCARoot = getNodePath(zkRootNodePath, PROXY_CA_ROOT);
    reservationRoot = getNodePath(zkRootNodePath, RESERVATION_SYSTEM_ROOT);
    zkManager = resourceManager.getZKManager();
    if(zkManager==null) {
      zkManager = resourceManager.createAndStartZKManager(conf);
    }
    delegationTokenNodeSplitIndex =
        conf.getInt(YarnConfiguration.ZK_DELEGATION_TOKEN_NODE_SPLIT_INDEX,
            YarnConfiguration.DEFAULT_ZK_DELEGATION_TOKEN_NODE_SPLIT_INDEX);
    if (delegationTokenNodeSplitIndex < 0
        || delegationTokenNodeSplitIndex > 4) {
      LOG.info("Invalid value " + delegationTokenNodeSplitIndex + " for config "
          + YarnConfiguration.ZK_DELEGATION_TOKEN_NODE_SPLIT_INDEX
          + " specified.  Resetting it to " +
          YarnConfiguration.DEFAULT_ZK_DELEGATION_TOKEN_NODE_SPLIT_INDEX);
      delegationTokenNodeSplitIndex =
          YarnConfiguration.DEFAULT_ZK_DELEGATION_TOKEN_NODE_SPLIT_INDEX;
    }
  }

  @Override
  public synchronized void startInternal() throws Exception {
    // ensure root dirs exist
    zkManager.createRootDirRecursively(znodeWorkingPath, zkAcl);
    create(zkRootNodePath);
    setRootNodeAcls();
    delete(fencingNodePath);
    if (HAUtil.isHAEnabled(getConfig()) && !HAUtil
        .isAutomaticFailoverEnabled(getConfig())) {
      verifyActiveStatusThread = new VerifyActiveStatusThread();
      verifyActiveStatusThread.start();
    }
    create(rmAppRoot);
    create(getNodePath(rmAppRoot, RM_APP_ROOT_HIERARCHIES));
    for (int splitIndex = 1; splitIndex <= 4; splitIndex++) {
      create(rmAppRootHierarchies.get(splitIndex));
    }
    create(rmDTSecretManagerRoot);
    create(dtMasterKeysRootPath);
    create(delegationTokensRootPath);
    for (int splitIndex = 1; splitIndex <= 4; splitIndex++) {
      create(rmDelegationTokenHierarchies.get(splitIndex));
    }
    create(dtSequenceNumberPath);
    create(amrmTokenSecretManagerRoot);
    create(reservationRoot);
    create(proxyCARoot);
  }

  private void logRootNodeAcls(String prefix) throws Exception {
    Stat getStat = new Stat();
    List<ACL> getAcls = getACL(zkRootNodePath);

    StringBuilder builder = new StringBuilder();
    builder.append(prefix);

    for (ACL acl : getAcls) {
      builder.append(acl.toString());
    }

    builder.append(getStat.toString());
    LOG.debug("{}", builder);
  }

  private void setRootNodeAcls() throws Exception {
    if (LOG.isDebugEnabled()) {
      logRootNodeAcls("Before setting ACLs'\n");
    }

    CuratorFramework curatorFramework = zkManager.getCurator();
    if (HAUtil.isHAEnabled(getConfig())) {
      curatorFramework.setACL().withACL(zkRootNodeAcl).forPath(zkRootNodePath);
    } else {
      curatorFramework.setACL().withACL(zkAcl).forPath(zkRootNodePath);
    }

    if (LOG.isDebugEnabled()) {
      logRootNodeAcls("After setting ACLs'\n");
    }
  }

  @Override
  protected synchronized void closeInternal() throws Exception {
    if (verifyActiveStatusThread != null) {
      verifyActiveStatusThread.interrupt();
      verifyActiveStatusThread.join(1000);
    }

    if (resourceManager.getZKManager() == null) {
      CuratorFramework curatorFramework = zkManager.getCurator();
      IOUtils.closeStream(curatorFramework);
    }
  }

  @Override
  protected Version getCurrentVersion() {
    return CURRENT_VERSION_INFO;
  }

  @Override
  protected synchronized void storeVersion() throws Exception {
    String versionNodePath = getNodePath(zkRootNodePath, VERSION_NODE);
    byte[] data =
        ((VersionPBImpl) CURRENT_VERSION_INFO).getProto().toByteArray();

    if (exists(versionNodePath)) {
      zkManager.safeSetData(versionNodePath, data, -1, zkAcl, fencingNodePath);
    } else {
      zkManager.safeCreate(versionNodePath, data, zkAcl, CreateMode.PERSISTENT,
          zkAcl, fencingNodePath);
    }
  }

  @Override
  protected synchronized Version loadVersion() throws Exception {
    String versionNodePath = getNodePath(zkRootNodePath, VERSION_NODE);

    if (exists(versionNodePath)) {
      byte[] data = getData(versionNodePath);
      return new VersionPBImpl(VersionProto.parseFrom(data));
    }

    return null;
  }

  @Override
  public synchronized long getAndIncrementEpoch() throws Exception {
    String epochNodePath = getNodePath(zkRootNodePath, EPOCH_NODE);
    long currentEpoch = baseEpoch;

    if (exists(epochNodePath)) {
      // load current epoch
      byte[] data = getData(epochNodePath);
      Epoch epoch = new EpochPBImpl(EpochProto.parseFrom(data));
      currentEpoch = epoch.getEpoch();
      // increment epoch and store it
      byte[] storeData = Epoch.newInstance(nextEpoch(currentEpoch)).getProto()
          .toByteArray();
      zkManager.safeSetData(epochNodePath, storeData, -1, zkAcl,
          fencingNodePath);
    } else {
      // initialize epoch node with 1 for the next time.
      byte[] storeData = Epoch.newInstance(nextEpoch(currentEpoch)).getProto()
          .toByteArray();
      zkManager.safeCreate(epochNodePath, storeData, zkAcl,
          CreateMode.PERSISTENT, zkAcl, fencingNodePath);
    }

    return currentEpoch;
  }

  @Override
  public synchronized RMState loadState() throws Exception {
    long start = clock.getTime();
    RMState rmState = new RMState();
    // recover DelegationTokenSecretManager
    loadRMDTSecretManagerState(rmState);
    // recover RM applications
    loadRMAppState(rmState);
    // recover AMRMTokenSecretManager
    loadAMRMTokenSecretManagerState(rmState);
    // recover reservation state
    loadReservationSystemState(rmState);
    // recover ProxyCAManager state
    loadProxyCAManagerState(rmState);
    opDurations.addLoadStateCallDuration(clock.getTime() - start);
    return rmState;
  }

  private void loadReservationSystemState(RMState rmState) throws Exception {
    List<String> planNodes = getChildren(reservationRoot);

    for (String planName : planNodes) {
      LOG.debug("Loading plan from znode: {}", planName);

      String planNodePath = getNodePath(reservationRoot, planName);
      List<String> reservationNodes = getChildren(planNodePath);

      for (String reservationNodeName : reservationNodes) {
        String reservationNodePath =
            getNodePath(planNodePath, reservationNodeName);

        LOG.debug("Loading reservation from znode: {}", reservationNodePath);

        byte[] reservationData = getData(reservationNodePath);
        ReservationAllocationStateProto allocationState =
            ReservationAllocationStateProto.parseFrom(reservationData);

        if (!rmState.getReservationState().containsKey(planName)) {
          rmState.getReservationState().put(planName, new HashMap<>());
        }

        ReservationId reservationId =
            ReservationId.parseReservationId(reservationNodeName);
        rmState.getReservationState().get(planName).put(reservationId,
            allocationState);
      }
    }
  }

  private void loadAMRMTokenSecretManagerState(RMState rmState)
      throws Exception {
    byte[] data = getData(amrmTokenSecretManagerRoot);

    if (data == null) {
      LOG.warn("There is no data saved");
    } else {
      AMRMTokenSecretManagerStatePBImpl stateData =
          new AMRMTokenSecretManagerStatePBImpl(
            AMRMTokenSecretManagerStateProto.parseFrom(data));
      rmState.amrmTokenSecretManagerState =
          AMRMTokenSecretManagerState.newInstance(
            stateData.getCurrentMasterKey(), stateData.getNextMasterKey());
    }
  }

  private synchronized void loadRMDTSecretManagerState(RMState rmState)
      throws Exception {
    loadRMDelegationKeyState(rmState);
    loadRMSequentialNumberState(rmState);
    loadRMDelegationTokenState(rmState);
  }

  private void loadRMDelegationKeyState(RMState rmState) throws Exception {
    List<String> childNodes = getChildren(dtMasterKeysRootPath);

    for (String childNodeName : childNodes) {
      String childNodePath = getNodePath(dtMasterKeysRootPath, childNodeName);
      byte[] childData = getData(childNodePath);

      if (childData == null) {
        LOG.warn("Content of " + childNodePath + " is broken.");
        continue;
      }

      ByteArrayInputStream is = new ByteArrayInputStream(childData);

      try (DataInputStream fsIn = new DataInputStream(is)) {
        if (childNodeName.startsWith(DELEGATION_KEY_PREFIX)) {
          DelegationKey key = new DelegationKey();
          key.readFields(fsIn);
          rmState.rmSecretManagerState.masterKeyState.add(key);

          LOG.debug("Loaded delegation key: keyId={}, expirationDate={}",
              key.getKeyId(), key.getExpiryDate());

        }
      }
    }
  }

  private void loadRMSequentialNumberState(RMState rmState) throws Exception {
    byte[] seqData = getData(dtSequenceNumberPath);

    if (seqData != null) {
      ByteArrayInputStream seqIs = new ByteArrayInputStream(seqData);

      try (DataInputStream seqIn = new DataInputStream(seqIs)) {
        rmState.rmSecretManagerState.dtSequenceNumber = seqIn.readInt();
      }
    }
  }

  private void loadRMDelegationTokenState(RMState rmState) throws Exception {
    for (int splitIndex = 0; splitIndex <= 4; splitIndex++) {
      String tokenRoot = rmDelegationTokenHierarchies.get(splitIndex);
      if (tokenRoot == null) {
        continue;
      }
      List<String> childNodes = getChildren(tokenRoot);
      boolean dtNodeFound = false;
      for (String childNodeName : childNodes) {
        if (childNodeName.startsWith(DELEGATION_TOKEN_PREFIX)) {
          dtNodeFound = true;
          String parentNodePath = getNodePath(tokenRoot, childNodeName);
          if (splitIndex == 0) {
            loadDelegationTokenFromNode(rmState, parentNodePath);
          } else {
            // If znode is partitioned.
            List<String> leafNodes = getChildren(parentNodePath);
            for (String leafNodeName : leafNodes) {
              loadDelegationTokenFromNode(rmState,
                  getNodePath(parentNodePath, leafNodeName));
            }
          }
        } else if (splitIndex == 0
            && !(childNodeName.equals("1") || childNodeName.equals("2")
            || childNodeName.equals("3") || childNodeName.equals("4"))) {
          LOG.debug("Unknown child node with name {} under {}",
              childNodeName, tokenRoot);
        }
      }
      if (splitIndex != delegationTokenNodeSplitIndex && !dtNodeFound) {
        // If no loaded delegation token exists for a particular split index and
        // the split index for which tokens are being loaded is not the one
        // configured, then we do not need to keep track of this hierarchy for
        // storing/updating/removing delegation token znodes.
        rmDelegationTokenHierarchies.remove(splitIndex);
      }
    }
  }

  private void loadDelegationTokenFromNode(RMState rmState, String path)
      throws Exception {
    byte[] data = getData(path);
    if (data == null) {
      LOG.warn("Content of " + path + " is broken.");
    } else {
      ByteArrayInputStream is = new ByteArrayInputStream(data);
      try (DataInputStream fsIn = new DataInputStream(is)) {
        RMDelegationTokenIdentifierData identifierData =
            RMStateStoreUtils.readRMDelegationTokenIdentifierData(fsIn);
        RMDelegationTokenIdentifier identifier =
            identifierData.getTokenIdentifier();
        long renewDate = identifierData.getRenewDate();
        rmState.rmSecretManagerState.delegationTokenState.put(identifier,
            renewDate);
        LOG.debug("Loaded RMDelegationTokenIdentifier: {} renewDate={}",
            identifier, renewDate);
      }
    }
  }

  private void loadRMAppStateFromAppNode(RMState rmState, String appNodePath,
      String appIdStr) throws Exception {
    byte[] appData = getData(appNodePath);
    LOG.debug("Loading application from znode: {}", appNodePath);
    ApplicationId appId = ApplicationId.fromString(appIdStr);
    ApplicationStateDataPBImpl appState = new ApplicationStateDataPBImpl(
        ApplicationStateDataProto.parseFrom(appData));
    if (!appId.equals(
        appState.getApplicationSubmissionContext().getApplicationId())) {
      throw new YarnRuntimeException("The node name is different from the " +
             "application id");
    }
    rmState.appState.put(appId, appState);
    loadApplicationAttemptState(appState, appNodePath);
  }

  private synchronized void loadRMAppState(RMState rmState) throws Exception {
    for (int splitIndex = 0; splitIndex <= 4; splitIndex++) {
      String appRoot = rmAppRootHierarchies.get(splitIndex);
      if (appRoot == null) {
        continue;
      }
      List<String> childNodes = getChildren(appRoot);
      boolean appNodeFound = false;
      for (String childNodeName : childNodes) {
        if (childNodeName.startsWith(ApplicationId.appIdStrPrefix)) {
          appNodeFound = true;
          if (splitIndex == 0) {
            loadRMAppStateFromAppNode(rmState,
                getNodePath(appRoot, childNodeName), childNodeName);
          } else {
            // If AppId Node is partitioned.
            String parentNodePath = getNodePath(appRoot, childNodeName);
            List<String> leafNodes = getChildren(parentNodePath);
            for (String leafNodeName : leafNodes) {
              String appIdStr = childNodeName + leafNodeName;
              loadRMAppStateFromAppNode(rmState,
                  getNodePath(parentNodePath, leafNodeName), appIdStr);
            }
          }
        } else if (!childNodeName.equals(RM_APP_ROOT_HIERARCHIES)){
          LOG.debug("Unknown child node with name {} under {}", childNodeName,
              appRoot);
        }
      }
      if (splitIndex != appIdNodeSplitIndex && !appNodeFound) {
        // If no loaded app exists for a particular split index and the split
        // index for which apps are being loaded is not the one configured, then
        // we do not need to keep track of this hierarchy for storing/updating/
        // removing app/app attempt znodes.
        rmAppRootHierarchies.remove(splitIndex);
      }
    }
  }

  private void loadApplicationAttemptState(ApplicationStateData appState,
      String appPath) throws Exception {
    List<String> attempts = getChildren(appPath);

    for (String attemptIDStr : attempts) {
      if (attemptIDStr.startsWith(ApplicationAttemptId.appAttemptIdStrPrefix)) {
        String attemptPath = getNodePath(appPath, attemptIDStr);
        byte[] attemptData = getData(attemptPath);

        ApplicationAttemptStateDataPBImpl attemptState =
            new ApplicationAttemptStateDataPBImpl(
                ApplicationAttemptStateDataProto.parseFrom(attemptData));

        appState.attempts.put(attemptState.getAttemptId(), attemptState);
      }
    }
    LOG.debug("Done loading applications from ZK state store");
  }

  /**
   * Get znode path based on full path and split index supplied.
   * @param path path for which parent needs to be returned.
   * @param splitIndex split index.
   * @return parent app node path.
   */
  private String getSplitZnodeParent(String path, int splitIndex) {
    // Calculated as string up to index (path Length - split index - 1). We
    // deduct 1 to exclude path separator.
    return path.substring(0, path.length() - splitIndex - 1);
  }

  /**
   * Checks if parent znode has no leaf nodes and if it does not have,
   * removes it.
   * @param path path of znode to be removed.
   * @param splitIndex split index.
   * @throws Exception if any problem occurs while performing ZK operation.
   */
  private void checkRemoveParentZnode(String path, int splitIndex)
      throws Exception {
    if (splitIndex != 0) {
      String parentZnode = getSplitZnodeParent(path, splitIndex);
      List<String> children = null;
      try {
        children = getChildren(parentZnode);
      } catch (KeeperException.NoNodeException ke) {
        // It should be fine to swallow this exception as the parent znode we
        // intend to delete is already deleted.
        LOG.debug("Unable to remove parent node {} as it does not exist.",
            parentZnode);
        return;
      }
      // No apps stored under parent path.
      if (children != null && children.isEmpty()) {
        try {
          zkManager.safeDelete(parentZnode, zkAcl, fencingNodePath);
          LOG.debug("No leaf znode exists. Removing parent node {}",
              parentZnode);
        } catch (KeeperException.NotEmptyException ke) {
          // It should be fine to swallow this exception as the parent znode
          // has to be deleted only if it has no children. And this node has.
          LOG.debug("Unable to remove app parent node {} as it has children.",
              parentZnode);
        }
      }
    }
  }

  private void loadProxyCAManagerState(RMState rmState) throws Exception {
    String caCertPath = getNodePath(proxyCARoot, PROXY_CA_CERT_NODE);
    String caPrivateKeyPath = getNodePath(proxyCARoot,
        PROXY_CA_PRIVATE_KEY_NODE);

    if (!exists(caCertPath) || !exists(caPrivateKeyPath)) {
      LOG.warn("Couldn't find Proxy CA data");
      return;
    }

    byte[] caCertData = getData(caCertPath);
    byte[] caPrivateKeyData = getData(caPrivateKeyPath);

    if (caCertData == null || caPrivateKeyData == null) {
      LOG.warn("Couldn't recover Proxy CA data");
      return;
    }

    rmState.getProxyCAState().setCaCert(caCertData);
    rmState.getProxyCAState().setCaPrivateKey(caPrivateKeyData);
  }

  @Override
  public synchronized void storeApplicationStateInternal(ApplicationId appId,
      ApplicationStateData appStateDataPB) throws Exception {
    long start = clock.getTime();
    String nodeCreatePath = getLeafAppIdNodePath(appId.toString(), true);

    LOG.debug("Storing info for app: {} at: {}", appId, nodeCreatePath);

    byte[] appStateData = appStateDataPB.getProto().toByteArray();
    if (appStateData.length <= zknodeLimit) {
      zkManager.safeCreate(nodeCreatePath, appStateData, zkAcl,
          CreateMode.PERSISTENT, zkAcl, fencingNodePath);
    } else {
      LOG.debug("Application state data size for {} is {}",
          appId, appStateData.length);

      throw new StoreLimitException("Application " + appId
          + " exceeds the maximum allowed size for application data. "
          + "See yarn.resourcemanager.zk-max-znode-size.bytes.");
    }
    opDurations.addStoreApplicationStateCallDuration(clock.getTime() - start);
  }

  @Override
  protected synchronized void updateApplicationStateInternal(
      ApplicationId appId, ApplicationStateData appStateDataPB)
      throws Exception {
    long start = clock.getTime();
    String nodeUpdatePath = getLeafAppIdNodePath(appId.toString(), false);
    boolean pathExists = true;
    // Look for paths based on other split indices if path as per split index
    // does not exist.
    if (!exists(nodeUpdatePath)) {
      ZnodeSplitInfo alternatePathInfo = getAlternateAppPath(appId.toString());
      if (alternatePathInfo != null) {
        nodeUpdatePath = alternatePathInfo.path;
      } else {
        // No alternate path exists. Create path as per configured split index.
        pathExists = false;
        if (appIdNodeSplitIndex != 0) {
          String rootNode =
              getSplitZnodeParent(nodeUpdatePath, appIdNodeSplitIndex);
          if (!exists(rootNode)) {
            zkManager.safeCreate(rootNode, null, zkAcl, CreateMode.PERSISTENT,
                zkAcl, fencingNodePath);
          }
        }
      }
    }

    LOG.debug("Storing final state info for app: {} at: {}", appId,
        nodeUpdatePath);

    byte[] appStateData = appStateDataPB.getProto().toByteArray();

    if (pathExists) {
      zkManager.safeSetData(nodeUpdatePath, appStateData, -1, zkAcl,
          fencingNodePath);
    } else {
      zkManager.safeCreate(nodeUpdatePath, appStateData, zkAcl,
          CreateMode.PERSISTENT, zkAcl, fencingNodePath);
      LOG.debug("Path {} for {} didn't exist. Creating a new znode to update"
          + " the application state.", nodeUpdatePath, appId);
    }
    opDurations.addUpdateApplicationStateCallDuration(clock.getTime() - start);
  }

  /*
   * Handles store, update and remove application attempt state store
   * operations.
   */
  private void handleApplicationAttemptStateOp(
      ApplicationAttemptId appAttemptId,
      ApplicationAttemptStateData attemptStateDataPB, AppAttemptOp operation)
      throws Exception {
    String appId = appAttemptId.getApplicationId().toString();
    String appDirPath = getLeafAppIdNodePath(appId, false);
    // Look for paths based on other split indices.
    if (!exists(appDirPath)) {
      ZnodeSplitInfo alternatePathInfo = getAlternateAppPath(appId);
      if (alternatePathInfo == null) {
        if (operation == AppAttemptOp.REMOVE) {
          // Unexpected. Assume that app attempt has been deleted.
          return;
        } else { // Store or Update operation
          throw new YarnRuntimeException("Unexpected Exception. App node for " +
              "app " + appId + " not found");
        }
      } else {
        appDirPath = alternatePathInfo.path;
      }
    }
    String path = getNodePath(appDirPath, appAttemptId.toString());
    byte[] attemptStateData = (attemptStateDataPB == null) ? null :
        attemptStateDataPB.getProto().toByteArray();
    LOG.debug("{} info for attempt: {} at: {}", operation, appAttemptId, path);

    switch (operation) {
    case UPDATE:
      if (exists(path)) {
        zkManager.safeSetData(path, attemptStateData, -1, zkAcl,
            fencingNodePath);
      } else {
        zkManager.safeCreate(path, attemptStateData, zkAcl,
            CreateMode.PERSISTENT, zkAcl, fencingNodePath);
        LOG.debug("Path {} for {} didn't exist. Created a new znode to update"
            + " the application attempt state.", path, appAttemptId);

      }
      break;
    case STORE:
      zkManager.safeCreate(path, attemptStateData, zkAcl, CreateMode.PERSISTENT,
          zkAcl, fencingNodePath);
      break;
    case REMOVE:
      zkManager.safeDelete(path, zkAcl, fencingNodePath);
      break;
    default:
      break;
    }
  }

  @Override
  protected synchronized void storeApplicationAttemptStateInternal(
      ApplicationAttemptId appAttemptId,
      ApplicationAttemptStateData attemptStateDataPB)
      throws Exception {
    handleApplicationAttemptStateOp(appAttemptId, attemptStateDataPB,
        AppAttemptOp.STORE);
  }

  @Override
  protected synchronized void updateApplicationAttemptStateInternal(
      ApplicationAttemptId appAttemptId,
      ApplicationAttemptStateData attemptStateDataPB)
      throws Exception {
    handleApplicationAttemptStateOp(appAttemptId, attemptStateDataPB,
        AppAttemptOp.UPDATE);
  }

  @Override
  protected synchronized void removeApplicationAttemptInternal(
      ApplicationAttemptId appAttemptId) throws Exception {
    handleApplicationAttemptStateOp(appAttemptId, null, AppAttemptOp.REMOVE);
  }

  @Override
  protected synchronized void removeApplicationStateInternal(
      ApplicationStateData appState) throws Exception {
    long start = clock.getTime();
    removeApp(appState.getApplicationSubmissionContext().
        getApplicationId().toString(), true, appState.attempts.keySet());
    opDurations.addRemoveApplicationStateCallDuration(clock.getTime() - start);
  }

  private void removeApp(String removeAppId) throws Exception {
    removeApp(removeAppId, false, null);
  }

  /**
   * Remove application node and its attempt nodes.
   *
   * @param removeAppId Application Id to be removed.
   * @param safeRemove Flag indicating if application and attempt nodes have to
   *     be removed safely under a fencing or not.
   * @param attempts list of attempts to be removed associated with this app.
   *     Ignored if safeRemove flag is false as we recursively delete all the
   *     child nodes directly.
   * @throws Exception if any exception occurs during ZK operation.
   */
  private void removeApp(String removeAppId, boolean safeRemove,
      Set<ApplicationAttemptId> attempts) throws Exception {
    String appIdRemovePath = getLeafAppIdNodePath(removeAppId, false);
    int splitIndex = appIdNodeSplitIndex;
    // Look for paths based on other split indices if path as per configured
    // split index does not exist.
    if (!exists(appIdRemovePath)) {
      ZnodeSplitInfo alternatePathInfo = getAlternateAppPath(removeAppId);
      if (alternatePathInfo != null) {
        appIdRemovePath = alternatePathInfo.path;
        splitIndex = alternatePathInfo.splitIndex;
      } else {
        // Alternate path not found so return.
        return;
      }
    }
    if (safeRemove) {
      LOG.debug("Removing info for app: {} at: {} and its attempts.",
          removeAppId, appIdRemovePath);

      if (attempts != null) {
        for (ApplicationAttemptId attemptId : attempts) {
          String attemptRemovePath =
              getNodePath(appIdRemovePath, attemptId.toString());
          zkManager.safeDelete(attemptRemovePath, zkAcl, fencingNodePath);
        }
      }
      zkManager.safeDelete(appIdRemovePath, zkAcl, fencingNodePath);
    } else {
      CuratorFramework curatorFramework = zkManager.getCurator();
      curatorFramework.delete().deletingChildrenIfNeeded().
          forPath(appIdRemovePath);
    }
    // Check if we should remove the parent app node as well.
    checkRemoveParentZnode(appIdRemovePath, splitIndex);
  }

  @Override
  protected synchronized void storeRMDelegationTokenState(
      RMDelegationTokenIdentifier rmDTIdentifier, Long renewDate)
      throws Exception {
    String nodeCreatePath = getLeafDelegationTokenNodePath(
        rmDTIdentifier.getSequenceNumber(), true);
    LOG.debug("Storing {}{}", DELEGATION_TOKEN_PREFIX,
        rmDTIdentifier.getSequenceNumber());

    RMDelegationTokenIdentifierData identifierData =
        new RMDelegationTokenIdentifierData(rmDTIdentifier, renewDate);
    ByteArrayOutputStream seqOs = new ByteArrayOutputStream();
    try (DataOutputStream seqOut = new DataOutputStream(seqOs)) {
      SafeTransaction trx = zkManager.createTransaction(zkAcl,
          fencingNodePath);
      trx.create(nodeCreatePath, identifierData.toByteArray(), zkAcl,
          CreateMode.PERSISTENT);
      // Update Sequence number only while storing DT
      seqOut.writeInt(rmDTIdentifier.getSequenceNumber());

      LOG.debug("Storing {}. SequenceNumber: {}", dtSequenceNumberPath,
          rmDTIdentifier.getSequenceNumber());

      trx.setData(dtSequenceNumberPath, seqOs.toByteArray(), -1);
      trx.commit();
    }
  }

  @Override
  protected synchronized void removeRMDelegationTokenState(
      RMDelegationTokenIdentifier rmDTIdentifier) throws Exception {
    String nodeRemovePath = getLeafDelegationTokenNodePath(
        rmDTIdentifier.getSequenceNumber(), false);
    int splitIndex = delegationTokenNodeSplitIndex;
    // Look for paths based on other split indices if path as per configured
    // split index does not exist.
    if (!exists(nodeRemovePath)) {
      ZnodeSplitInfo alternatePathInfo =
          getAlternateDTPath(rmDTIdentifier.getSequenceNumber());
      if (alternatePathInfo != null) {
        nodeRemovePath = alternatePathInfo.path;
        splitIndex = alternatePathInfo.splitIndex;
      } else {
        // Alternate path not found so return.
        return;
      }
    }

    LOG.debug("Removing RMDelegationToken_{}",
        rmDTIdentifier.getSequenceNumber());

    zkManager.safeDelete(nodeRemovePath, zkAcl, fencingNodePath);

    // Check if we should remove the parent app node as well.
    checkRemoveParentZnode(nodeRemovePath, splitIndex);
  }

  @Override
  protected synchronized void updateRMDelegationTokenState(
      RMDelegationTokenIdentifier rmDTIdentifier, Long renewDate)
      throws Exception {
    String nodeUpdatePath = getLeafDelegationTokenNodePath(
        rmDTIdentifier.getSequenceNumber(), false);
    boolean pathExists = true;
    // Look for paths based on other split indices if path as per split index
    // does not exist.
    if (!exists(nodeUpdatePath)) {
      ZnodeSplitInfo alternatePathInfo =
          getAlternateDTPath(rmDTIdentifier.getSequenceNumber());
      if (alternatePathInfo != null) {
        nodeUpdatePath = alternatePathInfo.path;
      } else {
        pathExists = false;
      }
    }

    if (pathExists) {
      LOG.debug("Updating {}{}", DELEGATION_TOKEN_PREFIX,
          rmDTIdentifier.getSequenceNumber());

      RMDelegationTokenIdentifierData identifierData =
          new RMDelegationTokenIdentifierData(rmDTIdentifier, renewDate);
      zkManager.safeSetData(nodeUpdatePath, identifierData.toByteArray(), -1,
          zkAcl, fencingNodePath);
    } else {
      storeRMDelegationTokenState(rmDTIdentifier, renewDate);
    }
  }

  @Override
  protected synchronized void storeRMDTMasterKeyState(
      DelegationKey delegationKey) throws Exception {
    String nodeCreatePath = getNodePath(dtMasterKeysRootPath,
        DELEGATION_KEY_PREFIX + delegationKey.getKeyId());
    LOG.debug("Storing RMDelegationKey_{}", delegationKey.getKeyId());
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try(DataOutputStream fsOut = new DataOutputStream(os)) {
      delegationKey.write(fsOut);
      zkManager.safeCreate(nodeCreatePath, os.toByteArray(), zkAcl,
          CreateMode.PERSISTENT, zkAcl, fencingNodePath);
    }
  }

  @Override
  protected synchronized void removeRMDTMasterKeyState(
      DelegationKey delegationKey) throws Exception {
    String nodeRemovePath =
        getNodePath(dtMasterKeysRootPath, DELEGATION_KEY_PREFIX
            + delegationKey.getKeyId());

    LOG.debug("Removing RMDelegationKey_{}", delegationKey.getKeyId());

    zkManager.safeDelete(nodeRemovePath, zkAcl, fencingNodePath);
  }

  @Override
  public synchronized void deleteStore() throws Exception {
    delete(zkRootNodePath);
  }

  @Override
  public synchronized void removeApplication(ApplicationId removeAppId)
      throws Exception {
    removeApp(removeAppId.toString());
  }

  @VisibleForTesting
  String getNodePath(String root, String nodeName) {
    return (root + "/" + nodeName);
  }

  @Override
  protected synchronized void storeOrUpdateAMRMTokenSecretManagerState(
      AMRMTokenSecretManagerState amrmTokenSecretManagerState, boolean isUpdate)
      throws Exception {
    AMRMTokenSecretManagerState data =
        AMRMTokenSecretManagerState.newInstance(amrmTokenSecretManagerState);
    byte[] stateData = data.getProto().toByteArray();

    zkManager.safeSetData(amrmTokenSecretManagerRoot, stateData, -1, zkAcl,
        fencingNodePath);
  }

  @Override
  protected synchronized void removeReservationState(String planName,
      String reservationIdName) throws Exception {
    String planNodePath = getNodePath(reservationRoot, planName);
    String reservationPath = getNodePath(planNodePath, reservationIdName);

    LOG.debug("Removing reservationallocation {} for plan {}",
        reservationIdName, planName);

    zkManager.safeDelete(reservationPath, zkAcl, fencingNodePath);

    List<String> reservationNodes = getChildren(planNodePath);

    if (reservationNodes.isEmpty()) {
      zkManager.safeDelete(planNodePath, zkAcl, fencingNodePath);
    }
  }

  @Override
  protected synchronized void storeReservationState(
      ReservationAllocationStateProto reservationAllocation, String planName,
      String reservationIdName) throws Exception {
    SafeTransaction trx = zkManager.createTransaction(zkAcl, fencingNodePath);
    addOrUpdateReservationState(reservationAllocation, planName,
        reservationIdName, trx, false);
    trx.commit();
  }

  private void addOrUpdateReservationState(
      ReservationAllocationStateProto reservationAllocation, String planName,
      String reservationIdName, SafeTransaction trx, boolean isUpdate)
      throws Exception {
    String planCreatePath =
        getNodePath(reservationRoot, planName);
    String reservationPath = getNodePath(planCreatePath,
        reservationIdName);
    byte[] reservationData = reservationAllocation.toByteArray();

    if (!exists(planCreatePath)) {
      LOG.debug("Creating plan node: {} at: {}", planName, planCreatePath);

      trx.create(planCreatePath, null, zkAcl, CreateMode.PERSISTENT);
    }

    if (isUpdate) {
      LOG.debug("Updating reservation: {} in plan:{} at: {}",
          reservationIdName, planName, reservationPath);
      trx.setData(reservationPath, reservationData, -1);
    } else {
      LOG.debug("Storing reservation: {} in plan:{} at: {}",
          reservationIdName, planName, reservationPath);
      trx.create(reservationPath, reservationData, zkAcl,
          CreateMode.PERSISTENT);
    }
  }

  @Override
  protected void storeProxyCACertState(
      X509Certificate caCert, PrivateKey caPrivateKey) throws Exception {
    byte[] caCertData = caCert.getEncoded();
    byte[] caPrivateKeyData = caPrivateKey.getEncoded();

    String caCertPath = getNodePath(proxyCARoot, PROXY_CA_CERT_NODE);
    String caPrivateKeyPath = getNodePath(proxyCARoot,
        PROXY_CA_PRIVATE_KEY_NODE);

    if (exists(caCertPath)) {
      zkManager.safeSetData(caCertPath, caCertData, -1, zkAcl,
          fencingNodePath);
    } else {
      zkManager.safeCreate(caCertPath, caCertData, zkAcl,
          CreateMode.PERSISTENT, zkAcl, fencingNodePath);
    }
    if (exists(caPrivateKeyPath)) {
      zkManager.safeSetData(caPrivateKeyPath, caPrivateKeyData, -1, zkAcl,
          fencingNodePath);
    } else {
      zkManager.safeCreate(caPrivateKeyPath, caPrivateKeyData, zkAcl,
          CreateMode.PERSISTENT, zkAcl, fencingNodePath);
    }
  }

  /**
   * Get alternate path for app id if path according to configured split index
   * does not exist. We look for path based on all possible split indices.
   * @param appId
   * @return a {@link ZnodeSplitInfo} object containing the path and split
   *    index if it exists, null otherwise.
   * @throws Exception if any problem occurs while performing ZK operation.
   */
  private ZnodeSplitInfo getAlternateAppPath(String appId) throws Exception {
    for (Map.Entry<Integer, String> entry : rmAppRootHierarchies.entrySet()) {
      // Look for other paths
      int splitIndex = entry.getKey();
      if (splitIndex != appIdNodeSplitIndex) {
        String alternatePath =
            getLeafZnodePath(appId, entry.getValue(), splitIndex, false);
        if (exists(alternatePath)) {
          return new ZnodeSplitInfo(alternatePath, splitIndex);
        }
      }
    }
    return null;
  }

  /**
   * Returns leaf znode path based on node name and passed split index. If the
   * passed flag createParentIfNotExists is true, also creates the parent znode
   * if it does not exist.
   * @param nodeName the node name.
   * @param rootNode app root node based on split index.
   * @param splitIdx split index.
   * @param createParentIfNotExists flag which determines if parent znode
   *     needs to be created(as per split) if it does not exist.
   * @return leaf znode path.
   * @throws Exception if any problem occurs while performing ZK operation.
   */
  private String getLeafZnodePath(String nodeName, String rootNode,
      int splitIdx, boolean createParentIfNotExists) throws Exception {
    if (splitIdx == 0) {
      return getNodePath(rootNode, nodeName);
    }
    int split = nodeName.length() - splitIdx;
    String rootNodePath =
        getNodePath(rootNode, nodeName.substring(0, split));
    if (createParentIfNotExists && !exists(rootNodePath)) {
      try {
        zkManager.safeCreate(rootNodePath, null, zkAcl, CreateMode.PERSISTENT,
            zkAcl, fencingNodePath);
      } catch (KeeperException.NodeExistsException e) {
        LOG.debug("Unable to create app parent node {} as it already exists.",
            rootNodePath);
      }
    }
    return getNodePath(rootNodePath, nodeName.substring(split));
  }

  /**
   * Returns leaf app node path based on app id and configured split index. If
   * the passed flag createParentIfNotExists is true, also creates the parent
   * app node if it does not exist.
   * @param appId application id.
   * @param createParentIfNotExists flag which determines if parent app node
   *     needs to be created(as per split) if it does not exist.
   * @return leaf app node path.
   * @throws Exception if any problem occurs while performing ZK operation.
   */
  private String getLeafAppIdNodePath(String appId,
      boolean createParentIfNotExists) throws Exception {
    return getLeafZnodePath(appId, rmAppRootHierarchies.get(
        appIdNodeSplitIndex), appIdNodeSplitIndex, createParentIfNotExists);
  }

  /**
   * Returns leaf delegation token node path based on sequence number and
   * configured split index. If the passed flag createParentIfNotExists is true,
   * also creates the parent znode if it does not exist.  The sequence number
   * is padded to be at least 4 digits wide to ensure consistency with the split
   * indexing.
   * @param rmDTSequenceNumber delegation token sequence number.
   * @param createParentIfNotExists flag which determines if parent znode
   *     needs to be created(as per split) if it does not exist.
   * @return leaf delegation token node path.
   * @throws Exception if any problem occurs while performing ZK operation.
   */
  private String getLeafDelegationTokenNodePath(int rmDTSequenceNumber,
      boolean createParentIfNotExists) throws Exception {
    return getLeafDelegationTokenNodePath(rmDTSequenceNumber,
        createParentIfNotExists, delegationTokenNodeSplitIndex);
  }

  /**
   * Returns leaf delegation token node path based on sequence number and
   * passed split index. If the passed flag createParentIfNotExists is true,
   * also creates the parent znode if it does not exist.  The sequence number
   * is padded to be at least 4 digits wide to ensure consistency with the split
   * indexing.
   * @param rmDTSequenceNumber delegation token sequence number.
   * @param createParentIfNotExists flag which determines if parent znode
   *     needs to be created(as per split) if it does not exist.
   * @param split the split index to use
   * @return leaf delegation token node path.
   * @throws Exception if any problem occurs while performing ZK operation.
   */
  private String getLeafDelegationTokenNodePath(int rmDTSequenceNumber,
      boolean createParentIfNotExists, int split) throws Exception {
    String nodeName = DELEGATION_TOKEN_PREFIX;
    if (split == 0) {
      nodeName += rmDTSequenceNumber;
    } else {
      nodeName += String.format("%04d", rmDTSequenceNumber);
    }
    return getLeafZnodePath(nodeName, rmDelegationTokenHierarchies.get(split),
        split, createParentIfNotExists);
  }

  /**
   * Get alternate path for delegation token if path according to configured
   * split index does not exist. We look for path based on all possible split
   * indices.
   * @param rmDTSequenceNumber delegation token sequence number.
   * @return a {@link ZnodeSplitInfo} object containing the path and split
   *    index if it exists, null otherwise.
   * @throws Exception if any problem occurs while performing ZK operation.
   */
  private ZnodeSplitInfo getAlternateDTPath(int rmDTSequenceNumber)
      throws Exception {
    // Check all possible paths until we find it
    for (int splitIndex : rmDelegationTokenHierarchies.keySet()) {
      if (splitIndex != delegationTokenNodeSplitIndex) {
        String alternatePath = getLeafDelegationTokenNodePath(
            rmDTSequenceNumber, false, splitIndex);
        if (exists(alternatePath)) {
          return new ZnodeSplitInfo(alternatePath, splitIndex);
        }
      }
    }
    return null;
  }

  @VisibleForTesting
  byte[] getData(final String path) throws Exception {
    return zkManager.getData(path);
  }

  @VisibleForTesting
  List<ACL> getACL(final String path) throws Exception {
    return zkManager.getACL(path);
  }

  @VisibleForTesting
  List<String> getChildren(final String path) throws Exception {
    return zkManager.getChildren(path);
  }

  @VisibleForTesting
  boolean exists(final String path) throws Exception {
    return zkManager.exists(path);
  }

  @VisibleForTesting
  void create(final String path) throws Exception {
    zkManager.create(path, zkAcl);
  }

  @VisibleForTesting
  void delete(final String path) throws Exception {
    zkManager.delete(path);
  }

  /**
   * Helper class that periodically attempts creating a znode to ensure that
   * this RM continues to be the Active.
   */
  private class VerifyActiveStatusThread extends Thread {
    VerifyActiveStatusThread() {
      super(VerifyActiveStatusThread.class.getName());
    }

    @Override
    public void run() {
      try {
        while (!isFencedState()) {
          // Create and delete fencing node
          zkManager.createTransaction(zkAcl, fencingNodePath).commit();
          Thread.sleep(zkSessionTimeout);
        }
      } catch (InterruptedException ie) {
        LOG.info(getName() + " thread interrupted! Exiting!");
        interrupt();
      } catch (Exception e) {
        notifyStoreOperationFailed(new StoreFencedException());
      }
    }
  }
}
