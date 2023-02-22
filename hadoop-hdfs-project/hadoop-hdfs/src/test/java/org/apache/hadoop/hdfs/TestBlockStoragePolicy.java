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

import static org.apache.hadoop.hdfs.protocol.HdfsConstants.BLOCK_STORAGE_POLICY_ID_UNSPECIFIED;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

import org.apache.hadoop.thirdparty.com.google.common.collect.Lists;
import org.apache.hadoop.thirdparty.com.google.common.collect.Sets;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockStoragePolicySpi;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.protocol.*;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenSecretManager;
import org.apache.hadoop.hdfs.server.blockmanagement.*;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.DataNodeTestUtils;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.snapshot.SnapshotTestHelper;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorage;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.security.token.SecretManager;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.PathUtils;
import org.junit.Assert;
import static org.junit.Assert.fail;
import org.junit.Test;

/** Test {@link BlockStoragePolicy} */
public class TestBlockStoragePolicy {
  public static final BlockStoragePolicySuite POLICY_SUITE;
  public static final BlockStoragePolicy DEFAULT_STORAGE_POLICY;
  public static final Configuration conf;

  static {
    conf = new HdfsConfiguration();
    conf.setLong(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_REDUNDANCY_INTERVAL_SECONDS_KEY, 1);
    POLICY_SUITE = BlockStoragePolicySuite.createDefaultSuite();
    DEFAULT_STORAGE_POLICY = POLICY_SUITE.getDefaultPolicy();
  }

  static final EnumSet<StorageType> none = EnumSet.noneOf(StorageType.class);
  static final EnumSet<StorageType> archive = EnumSet.of(StorageType.ARCHIVE);
  static final EnumSet<StorageType> disk = EnumSet.of(StorageType.DISK);
  static final EnumSet<StorageType> ssd = EnumSet.of(StorageType.SSD);
  static final EnumSet<StorageType> disk_archive = EnumSet.of(StorageType.DISK,
      StorageType.ARCHIVE);
  static final EnumSet<StorageType> all = EnumSet.of(StorageType.SSD,
      StorageType.DISK, StorageType.ARCHIVE);

  static final long FILE_LEN = 1024;
  static final short REPLICATION = 3;

  static final byte COLD = HdfsConstants.COLD_STORAGE_POLICY_ID;
  static final byte WARM = HdfsConstants.WARM_STORAGE_POLICY_ID;
  static final byte HOT  = HdfsConstants.HOT_STORAGE_POLICY_ID;
  static final byte ONESSD  = HdfsConstants.ONESSD_STORAGE_POLICY_ID;
  static final byte ALLSSD  = HdfsConstants.ALLSSD_STORAGE_POLICY_ID;
  static final byte LAZY_PERSIST  = HdfsConstants.MEMORY_STORAGE_POLICY_ID;
  static final byte PROVIDED  = HdfsConstants.PROVIDED_STORAGE_POLICY_ID;

  @Test (timeout=300000)
  public void testConfigKeyEnabled() throws IOException {
    Configuration conf = new HdfsConfiguration();
    conf.setBoolean(DFSConfigKeys.DFS_STORAGE_POLICY_ENABLED_KEY, true);
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(1).build();
    try {
      cluster.waitActive();
      cluster.getFileSystem().setStoragePolicy(new Path("/"),
          HdfsConstants.COLD_STORAGE_POLICY_NAME);
    } finally {
      cluster.shutdown();
    }
  }

  /**
   * Ensure that setStoragePolicy throws IOException when
   * dfs.storage.policy.enabled is set to false.
   * @throws IOException
   */
  @Test (timeout=300000, expected=IOException.class)
  public void testConfigKeyDisabled() throws IOException {
    Configuration conf = new HdfsConfiguration();
    conf.setBoolean(DFSConfigKeys.DFS_STORAGE_POLICY_ENABLED_KEY, false);
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(1).build();
    try {
      cluster.waitActive();
      cluster.getFileSystem().setStoragePolicy(new Path("/"),
          HdfsConstants.COLD_STORAGE_POLICY_NAME);
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testDefaultPolicies() {
    final Map<Byte, String> expectedPolicyStrings = new HashMap<Byte, String>();
    expectedPolicyStrings.put(COLD,
        "BlockStoragePolicy{COLD:" + COLD + ", storageTypes=[ARCHIVE], " +
            "creationFallbacks=[], replicationFallbacks=[]}");
    expectedPolicyStrings.put(WARM,
        "BlockStoragePolicy{WARM:" + WARM + ", storageTypes=[DISK, ARCHIVE], " +
            "creationFallbacks=[DISK, ARCHIVE], " +
            "replicationFallbacks=[DISK, ARCHIVE]}");
    expectedPolicyStrings.put(HOT,
        "BlockStoragePolicy{HOT:" + HOT + ", storageTypes=[DISK], " +
            "creationFallbacks=[], replicationFallbacks=[ARCHIVE]}");
    expectedPolicyStrings.put(LAZY_PERSIST,
        "BlockStoragePolicy{LAZY_PERSIST:" + LAZY_PERSIST +
            ", storageTypes=[RAM_DISK, DISK], " +
            "creationFallbacks=[DISK], replicationFallbacks=[DISK]}");
    expectedPolicyStrings.put(ONESSD, "BlockStoragePolicy{ONE_SSD:" + ONESSD +
        ", storageTypes=[SSD, DISK], creationFallbacks=[SSD, DISK], " +
        "replicationFallbacks=[SSD, DISK]}");
    expectedPolicyStrings.put(ALLSSD, "BlockStoragePolicy{ALL_SSD:" + ALLSSD +
        ", storageTypes=[SSD], creationFallbacks=[DISK], " +
        "replicationFallbacks=[DISK]}");
    expectedPolicyStrings.put(PROVIDED,
        "BlockStoragePolicy{PROVIDED:" + PROVIDED
            + ", storageTypes=[PROVIDED, DISK], "
            + "creationFallbacks=[PROVIDED, DISK], "
            + "replicationFallbacks=[PROVIDED, DISK]}");

    for(byte i = 1; i < 16; i++) {
      final BlockStoragePolicy policy = POLICY_SUITE.getPolicy(i); 
      if (policy != null) {
        final String s = policy.toString();
        Assert.assertEquals(expectedPolicyStrings.get(i), s);
      }
    }
    Assert.assertEquals(POLICY_SUITE.getPolicy(HOT), POLICY_SUITE.getDefaultPolicy());
    
    { // check Cold policy
      final BlockStoragePolicy cold = POLICY_SUITE.getPolicy(COLD);
      for(short replication = 1; replication < 6; replication++) {
        final List<StorageType> computed = cold.chooseStorageTypes(replication);
        assertStorageType(computed, replication, StorageType.ARCHIVE);
      }
      assertCreationFallback(cold, null, null, null, null, null);
      assertReplicationFallback(cold, null, null, null, null);
    }
    
    { // check Warm policy
      final BlockStoragePolicy warm = POLICY_SUITE.getPolicy(WARM);
      for(short replication = 1; replication < 6; replication++) {
        final List<StorageType> computed = warm.chooseStorageTypes(replication);
        assertStorageType(computed, replication, StorageType.DISK, StorageType.ARCHIVE);
      }
      assertCreationFallback(warm, StorageType.DISK, StorageType.DISK,
          StorageType.ARCHIVE, StorageType.DISK, null);
      assertReplicationFallback(warm, StorageType.DISK, StorageType.DISK,
          StorageType.ARCHIVE, StorageType.DISK);
    }

    { // check Hot policy
      final BlockStoragePolicy hot = POLICY_SUITE.getPolicy(HOT);
      for(short replication = 1; replication < 6; replication++) {
        final List<StorageType> computed = hot.chooseStorageTypes(replication);
        assertStorageType(computed, replication, StorageType.DISK);
      }
      assertCreationFallback(hot, null, null, null, null, null);
      assertReplicationFallback(hot, StorageType.ARCHIVE, null,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    { // check ONE_SSD policy
      final BlockStoragePolicy onessd = POLICY_SUITE.getPolicy(ONESSD);
      for (short replication = 1; replication < 6; replication++) {
        final List<StorageType> computed = onessd
            .chooseStorageTypes(replication);
        assertStorageType(computed, replication, StorageType.SSD,
            StorageType.DISK);
      }
      assertCreationFallback(onessd, StorageType.SSD, StorageType.SSD,
          StorageType.SSD, StorageType.DISK, StorageType.SSD);
      assertReplicationFallback(onessd, StorageType.SSD, StorageType.SSD,
          StorageType.SSD, StorageType.DISK);
    }

    { // check ALL_SSD policy
      final BlockStoragePolicy allssd = POLICY_SUITE.getPolicy(ALLSSD);
      for (short replication = 1; replication < 6; replication++) {
        final List<StorageType> computed = allssd
            .chooseStorageTypes(replication);
        assertStorageType(computed, replication, StorageType.SSD);
      }
      assertCreationFallback(allssd, StorageType.DISK, StorageType.DISK, null,
          StorageType.DISK, null);
      assertReplicationFallback(allssd, StorageType.DISK, StorageType.DISK,
          null, StorageType.DISK);
    }

    { // check LAZY_PERSIST policy
      final BlockStoragePolicy lazyPersist = POLICY_SUITE
          .getPolicy(LAZY_PERSIST);
      for (short replication = 1; replication < 6; replication++) {
        final List<StorageType> computed = lazyPersist
            .chooseStorageTypes(replication);
        assertStorageType(computed, replication, StorageType.DISK);
      }
      assertCreationFallback(lazyPersist, StorageType.DISK, StorageType.DISK,
          null, StorageType.DISK, null);
      assertReplicationFallback(lazyPersist, StorageType.DISK,
          StorageType.DISK, null, StorageType.DISK);
    }
  }

  static StorageType[] newStorageTypes(int nDisk, int nArchive) {
    final StorageType[] t = new StorageType[nDisk + nArchive];
    Arrays.fill(t, 0, nDisk, StorageType.DISK);
    Arrays.fill(t, nDisk, t.length, StorageType.ARCHIVE);
    return t;
  }

  static List<StorageType> asList(int nDisk, int nArchive) {
    return Arrays.asList(newStorageTypes(nDisk, nArchive));
  }

  static void assertStorageType(List<StorageType> computed, short replication,
      StorageType... answers) {
    Assert.assertEquals(replication, computed.size());
    final StorageType last = answers[answers.length - 1];
    for(int i = 0; i < computed.size(); i++) {
      final StorageType expected = i < answers.length? answers[i]: last;
      Assert.assertEquals(expected, computed.get(i));
    }
  }

  static void assertCreationFallback(BlockStoragePolicy policy,
      StorageType noneExpected, StorageType archiveExpected,
      StorageType diskExpected, StorageType ssdExpected,
      StorageType disk_archiveExpected) {
    Assert.assertEquals(noneExpected, policy.getCreationFallback(none));
    Assert.assertEquals(archiveExpected, policy.getCreationFallback(archive));
    Assert.assertEquals(diskExpected, policy.getCreationFallback(disk));
    Assert.assertEquals(ssdExpected, policy.getCreationFallback(ssd));
    Assert.assertEquals(disk_archiveExpected,
        policy.getCreationFallback(disk_archive));
    Assert.assertEquals(null, policy.getCreationFallback(all));
  }

  static void assertReplicationFallback(BlockStoragePolicy policy,
      StorageType noneExpected, StorageType archiveExpected,
      StorageType diskExpected, StorageType ssdExpected) {
    Assert.assertEquals(noneExpected, policy.getReplicationFallback(none));
    Assert
        .assertEquals(archiveExpected, policy.getReplicationFallback(archive));
    Assert.assertEquals(diskExpected, policy.getReplicationFallback(disk));
    Assert.assertEquals(ssdExpected, policy.getReplicationFallback(ssd));
    Assert.assertEquals(null, policy.getReplicationFallback(all));
  }

  private static interface CheckChooseStorageTypes {
    public void checkChooseStorageTypes(BlockStoragePolicy p, short replication,
        List<StorageType> chosen, StorageType... expected);

    /** Basic case: pass only replication and chosen */
    static final CheckChooseStorageTypes Basic = new CheckChooseStorageTypes() {
      @Override
      public void checkChooseStorageTypes(BlockStoragePolicy p, short replication,
          List<StorageType> chosen, StorageType... expected) {
        final List<StorageType> types = p.chooseStorageTypes(replication, chosen);
        assertStorageTypes(types, expected);
      }
    };
    
    /** With empty unavailables and isNewBlock=true */
    static final CheckChooseStorageTypes EmptyUnavailablesAndNewBlock
        = new CheckChooseStorageTypes() {
      @Override
      public void checkChooseStorageTypes(BlockStoragePolicy p,
          short replication, List<StorageType> chosen, StorageType... expected) {
        final List<StorageType> types = p.chooseStorageTypes(replication,
            chosen, none, true);
        assertStorageTypes(types, expected);
      }
    };

    /** With empty unavailables and isNewBlock=false */
    static final CheckChooseStorageTypes EmptyUnavailablesAndNonNewBlock
        = new CheckChooseStorageTypes() {
      @Override
      public void checkChooseStorageTypes(BlockStoragePolicy p,
          short replication, List<StorageType> chosen, StorageType... expected) {
        final List<StorageType> types = p.chooseStorageTypes(replication,
            chosen, none, false);
        assertStorageTypes(types, expected);
      }
    };
    
    /** With both DISK and ARCHIVE unavailables and isNewBlock=true */
    static final CheckChooseStorageTypes BothUnavailableAndNewBlock
        = new CheckChooseStorageTypes() {
      @Override
      public void checkChooseStorageTypes(BlockStoragePolicy p,
          short replication, List<StorageType> chosen, StorageType... expected) {
        final List<StorageType> types = p.chooseStorageTypes(replication,
            chosen, disk_archive, true);
        assertStorageTypes(types, expected);
      }
    };

    /** With both DISK and ARCHIVE unavailable and isNewBlock=false */
    static final CheckChooseStorageTypes BothUnavailableAndNonNewBlock
        = new CheckChooseStorageTypes() {
      @Override
      public void checkChooseStorageTypes(BlockStoragePolicy p,
          short replication, List<StorageType> chosen, StorageType... expected) {
        final List<StorageType> types = p.chooseStorageTypes(replication,
            chosen, disk_archive, false);
        assertStorageTypes(types, expected);
      }
    };

    /** With ARCHIVE unavailable and isNewBlock=true */
    static final CheckChooseStorageTypes ArchivalUnavailableAndNewBlock
        = new CheckChooseStorageTypes() {
      @Override
      public void checkChooseStorageTypes(BlockStoragePolicy p,
          short replication, List<StorageType> chosen, StorageType... expected) {
        final List<StorageType> types = p.chooseStorageTypes(replication,
            chosen, archive, true);
        assertStorageTypes(types, expected);
      }
    };

    /** With ARCHIVE unavailable and isNewBlock=true */
    static final CheckChooseStorageTypes ArchivalUnavailableAndNonNewBlock
        = new CheckChooseStorageTypes() {
      @Override
      public void checkChooseStorageTypes(BlockStoragePolicy p,
          short replication, List<StorageType> chosen, StorageType... expected) {
        final List<StorageType> types = p.chooseStorageTypes(replication,
            chosen, archive, false);
        assertStorageTypes(types, expected);
      }
    };
  }

  @Test
  public void testChooseStorageTypes() {
    run(CheckChooseStorageTypes.Basic);
    run(CheckChooseStorageTypes.EmptyUnavailablesAndNewBlock);
    run(CheckChooseStorageTypes.EmptyUnavailablesAndNonNewBlock);
  }

  private static void run(CheckChooseStorageTypes method) {
    final BlockStoragePolicy hot = POLICY_SUITE.getPolicy(HOT);
    final BlockStoragePolicy warm = POLICY_SUITE.getPolicy(WARM);
    final BlockStoragePolicy cold = POLICY_SUITE.getPolicy(COLD);

    final short replication = 3;
    {
      final List<StorageType> chosen = Lists.newArrayList();
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.DISK, StorageType.ARCHIVE, StorageType.ARCHIVE);
      method.checkChooseStorageTypes(cold, replication, chosen,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(StorageType.DISK); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
      method.checkChooseStorageTypes(cold, replication, chosen,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(StorageType.ARCHIVE); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.DISK, StorageType.ARCHIVE);
      method.checkChooseStorageTypes(cold, replication, chosen,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.DISK); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
      method.checkChooseStorageTypes(cold, replication, chosen,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.ARCHIVE); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.ARCHIVE);
      method.checkChooseStorageTypes(cold, replication, chosen,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.ARCHIVE, StorageType.ARCHIVE); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.DISK);
      method.checkChooseStorageTypes(cold, replication, chosen,
          StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.DISK, StorageType.DISK); 
      method.checkChooseStorageTypes(hot, replication, chosen);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
      method.checkChooseStorageTypes(cold, replication, chosen,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.DISK, StorageType.ARCHIVE); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.ARCHIVE);
      method.checkChooseStorageTypes(cold, replication, chosen,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.ARCHIVE, StorageType.ARCHIVE); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen);
      method.checkChooseStorageTypes(cold, replication, chosen,
          StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.DISK);
      method.checkChooseStorageTypes(cold, replication, chosen);
    }
  }

  @Test
  public void testChooseStorageTypesWithBothUnavailable() {
    runWithBothUnavailable(CheckChooseStorageTypes.BothUnavailableAndNewBlock);
    runWithBothUnavailable(CheckChooseStorageTypes.BothUnavailableAndNonNewBlock);
  }

  private static void runWithBothUnavailable(CheckChooseStorageTypes method) {
    final BlockStoragePolicy hot = POLICY_SUITE.getPolicy(HOT);
    final BlockStoragePolicy warm = POLICY_SUITE.getPolicy(WARM);
    final BlockStoragePolicy cold = POLICY_SUITE.getPolicy(COLD);

    final short replication = 3;
    for(int n = 0; n <= 3; n++) {
      for(int d = 0; d <= n; d++) {
        final int a = n - d;
        final List<StorageType> chosen = asList(d, a);
        method.checkChooseStorageTypes(hot, replication, chosen);
        method.checkChooseStorageTypes(warm, replication, chosen);
        method.checkChooseStorageTypes(cold, replication, chosen);
      }
    }
  }

  @Test
  public void testChooseStorageTypesWithDiskUnavailableAndNewBlock() {
    final BlockStoragePolicy hot = POLICY_SUITE.getPolicy(HOT);
    final BlockStoragePolicy warm = POLICY_SUITE.getPolicy(WARM);
    final BlockStoragePolicy cold = POLICY_SUITE.getPolicy(COLD);

    final short replication = 3;
    final EnumSet<StorageType> unavailables = disk;
    final boolean isNewBlock = true;
    {
      final List<StorageType> chosen = Lists.newArrayList();
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(StorageType.DISK); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(StorageType.ARCHIVE); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.DISK); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.ARCHIVE); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.ARCHIVE, StorageType.ARCHIVE); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.DISK, StorageType.DISK); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.DISK, StorageType.ARCHIVE); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.ARCHIVE, StorageType.ARCHIVE); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock);
    }
  }

  @Test
  public void testChooseStorageTypesWithArchiveUnavailable() {
    runWithArchiveUnavailable(CheckChooseStorageTypes.ArchivalUnavailableAndNewBlock);
    runWithArchiveUnavailable(CheckChooseStorageTypes.ArchivalUnavailableAndNonNewBlock);
  }

  private static void runWithArchiveUnavailable(CheckChooseStorageTypes method) {
    final BlockStoragePolicy hot = POLICY_SUITE.getPolicy(HOT);
    final BlockStoragePolicy warm = POLICY_SUITE.getPolicy(WARM);
    final BlockStoragePolicy cold = POLICY_SUITE.getPolicy(COLD);

    final short replication = 3;
    {
      final List<StorageType> chosen = Lists.newArrayList();
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.DISK, StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(cold, replication, chosen);
    }

    {
      final List<StorageType> chosen = Arrays.asList(StorageType.DISK); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(cold, replication, chosen);
    }

    {
      final List<StorageType> chosen = Arrays.asList(StorageType.ARCHIVE); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(cold, replication, chosen);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.DISK); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.DISK);
      method.checkChooseStorageTypes(cold, replication, chosen);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.ARCHIVE); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK); 
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.DISK);
      method.checkChooseStorageTypes(cold, replication, chosen);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.ARCHIVE, StorageType.ARCHIVE); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK, StorageType.DISK); 
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.DISK);
      method.checkChooseStorageTypes(cold, replication, chosen);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.DISK, StorageType.DISK); 
      method.checkChooseStorageTypes(hot, replication, chosen);
      method.checkChooseStorageTypes(warm, replication, chosen);
      method.checkChooseStorageTypes(cold, replication, chosen);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.DISK, StorageType.ARCHIVE); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen);
      method.checkChooseStorageTypes(cold, replication, chosen);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.ARCHIVE, StorageType.ARCHIVE); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK); 
      method.checkChooseStorageTypes(warm, replication, chosen);
      method.checkChooseStorageTypes(cold, replication, chosen);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE); 
      method.checkChooseStorageTypes(hot, replication, chosen,
          StorageType.DISK, StorageType.DISK, StorageType.DISK);
      method.checkChooseStorageTypes(warm, replication, chosen,
          StorageType.DISK);
      method.checkChooseStorageTypes(cold, replication, chosen);
    }
  }

  @Test
  public void testChooseStorageTypesWithDiskUnavailableAndNonNewBlock() {
    final BlockStoragePolicy hot = POLICY_SUITE.getPolicy(HOT);
    final BlockStoragePolicy warm = POLICY_SUITE.getPolicy(WARM);
    final BlockStoragePolicy cold = POLICY_SUITE.getPolicy(COLD);

    final short replication = 3;
    final EnumSet<StorageType> unavailables = disk;
    final boolean isNewBlock = false;
    {
      final List<StorageType> chosen = Lists.newArrayList();
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(StorageType.DISK); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(StorageType.ARCHIVE); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.DISK); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.ARCHIVE); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.ARCHIVE, StorageType.ARCHIVE); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.DISK, StorageType.DISK); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.DISK, StorageType.ARCHIVE); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE, StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.DISK, StorageType.ARCHIVE, StorageType.ARCHIVE); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock,
          StorageType.ARCHIVE);
    }

    {
      final List<StorageType> chosen = Arrays.asList(
          StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE); 
      checkChooseStorageTypes(hot, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(warm, replication, chosen, unavailables, isNewBlock);
      checkChooseStorageTypes(cold, replication, chosen, unavailables, isNewBlock);
    }
  }

  static void checkChooseStorageTypes(BlockStoragePolicy p, short replication,
      List<StorageType> chosen, EnumSet<StorageType> unavailables,
      boolean isNewBlock, StorageType... expected) {
    final List<StorageType> types = p.chooseStorageTypes(replication, chosen,
        unavailables, isNewBlock);
    assertStorageTypes(types, expected);
  }

  static void assertStorageTypes(List<StorageType> computed, StorageType... expected) {
    assertStorageTypes(computed.toArray(StorageType.EMPTY_ARRAY), expected);
  }

  static void assertStorageTypes(StorageType[] computed, StorageType... expected) {
    Arrays.sort(expected);
    Arrays.sort(computed);
    Assert.assertArrayEquals(expected, computed);
  }

  @Test
  public void testChooseExcess() {
    final BlockStoragePolicy hot = POLICY_SUITE.getPolicy(HOT);
    final BlockStoragePolicy warm = POLICY_SUITE.getPolicy(WARM);
    final BlockStoragePolicy cold = POLICY_SUITE.getPolicy(COLD);

    final short replication = 3;
    for(int n = 0; n <= 6; n++) {
      for(int d = 0; d <= n; d++) {
        final int a = n - d;
        final List<StorageType> chosen = asList(d, a);
        {
          final int nDisk = Math.max(0, d - replication); 
          final int nArchive = a;
          final StorageType[] expected = newStorageTypes(nDisk, nArchive);
          checkChooseExcess(hot, replication, chosen, expected);
        }

        {
          final int nDisk = Math.max(0, d - 1); 
          final int nArchive = Math.max(0, a - replication + 1);
          final StorageType[] expected = newStorageTypes(nDisk, nArchive);
          checkChooseExcess(warm, replication, chosen, expected);
        }

        {
          final int nDisk = d; 
          final int nArchive = Math.max(0, a - replication );
          final StorageType[] expected = newStorageTypes(nDisk, nArchive);
          checkChooseExcess(cold, replication, chosen, expected);
        }
      }
    }
  }

  static void checkChooseExcess(BlockStoragePolicy p, short replication,
      List<StorageType> chosen, StorageType... expected) {
    final List<StorageType> types = p.chooseExcess(replication, chosen);
    assertStorageTypes(types, expected);
  }

  private void checkDirectoryListing(HdfsFileStatus[] stats, byte... policies) {
    Assert.assertEquals(stats.length, policies.length);
    for (int i = 0; i < stats.length; i++) {
      Assert.assertEquals(stats[i].getStoragePolicy(), policies[i]);
    }
  }

  @Test
  public void testSetStoragePolicy() throws Exception {
    final MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(REPLICATION).build();
    cluster.waitActive();
    final DistributedFileSystem fs = cluster.getFileSystem();
    try {
      final Path dir = new Path("/testSetStoragePolicy");
      final Path fooFile = new Path(dir, "foo");
      final Path barDir = new Path(dir, "bar");
      final Path barFile1= new Path(barDir, "f1");
      final Path barFile2= new Path(barDir, "f2");
      DFSTestUtil.createFile(fs, fooFile, FILE_LEN, REPLICATION, 0L);
      DFSTestUtil.createFile(fs, barFile1, FILE_LEN, REPLICATION, 0L);
      DFSTestUtil.createFile(fs, barFile2, FILE_LEN, REPLICATION, 0L);

      final String invalidPolicyName = "INVALID-POLICY";
      try {
        fs.setStoragePolicy(fooFile, invalidPolicyName);
        Assert.fail("Should throw a HadoopIllegalArgumentException");
      } catch (RemoteException e) {
        GenericTestUtils.assertExceptionContains(invalidPolicyName, e);
      }

      // check storage policy
      HdfsFileStatus[] dirList = fs.getClient().listPaths(dir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      HdfsFileStatus[] barList = fs.getClient().listPaths(barDir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      checkDirectoryListing(dirList, BLOCK_STORAGE_POLICY_ID_UNSPECIFIED,
                            BLOCK_STORAGE_POLICY_ID_UNSPECIFIED);
      checkDirectoryListing(barList, BLOCK_STORAGE_POLICY_ID_UNSPECIFIED,
                            BLOCK_STORAGE_POLICY_ID_UNSPECIFIED);

      final Path invalidPath = new Path("/invalidPath");
      try {
        fs.setStoragePolicy(invalidPath, HdfsConstants.WARM_STORAGE_POLICY_NAME);
        Assert.fail("Should throw a FileNotFoundException");
      } catch (FileNotFoundException e) {
        GenericTestUtils.assertExceptionContains(invalidPath.toString(), e);
      }

      try {
        fs.getStoragePolicy(invalidPath);
        Assert.fail("Should throw a FileNotFoundException");
      } catch (FileNotFoundException e) {
        GenericTestUtils.assertExceptionContains(invalidPath.toString(), e);
      }

      fs.setStoragePolicy(fooFile, HdfsConstants.COLD_STORAGE_POLICY_NAME);
      fs.setStoragePolicy(barDir, HdfsConstants.WARM_STORAGE_POLICY_NAME);
      fs.setStoragePolicy(barFile2, HdfsConstants.HOT_STORAGE_POLICY_NAME);
      Assert.assertEquals("File storage policy should be COLD",
          HdfsConstants.COLD_STORAGE_POLICY_NAME,
          fs.getStoragePolicy(fooFile).getName());
      Assert.assertEquals("File storage policy should be WARM",
          HdfsConstants.WARM_STORAGE_POLICY_NAME,
          fs.getStoragePolicy(barDir).getName());
      Assert.assertEquals("File storage policy should be HOT",
          HdfsConstants.HOT_STORAGE_POLICY_NAME,
          fs.getStoragePolicy(barFile2).getName());

      dirList = fs.getClient().listPaths(dir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      barList = fs.getClient().listPaths(barDir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      checkDirectoryListing(dirList, WARM, COLD); // bar is warm, foo is cold
      checkDirectoryListing(barList, WARM, HOT);

      // restart namenode to make sure the editlog is correct
      cluster.restartNameNode(true);
      dirList = fs.getClient().listPaths(dir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      barList = fs.getClient().listPaths(barDir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      checkDirectoryListing(dirList, WARM, COLD); // bar is warm, foo is cold
      checkDirectoryListing(barList, WARM, HOT);

      // restart namenode with checkpoint to make sure the fsimage is correct
      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fs.saveNamespace();
      fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);
      cluster.restartNameNode(true);
      dirList = fs.getClient().listPaths(dir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      barList = fs.getClient().listPaths(barDir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      checkDirectoryListing(dirList, WARM, COLD); // bar is warm, foo is cold
      checkDirectoryListing(barList, WARM, HOT);
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testGetStoragePolicy() throws Exception {
    final MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(REPLICATION).build();
    cluster.waitActive();
    final DistributedFileSystem fs = cluster.getFileSystem();
    try {
      final Path dir = new Path("/testGetStoragePolicy");
      final Path fooFile = new Path(dir, "foo");
      DFSTestUtil.createFile(fs, fooFile, FILE_LEN, REPLICATION, 0L);
      DFSClient client = new DFSClient(cluster.getNameNode(0)
          .getNameNodeAddress(), conf);
      client.setStoragePolicy("/testGetStoragePolicy/foo",
          HdfsConstants.COLD_STORAGE_POLICY_NAME);
      String policyName = client.getStoragePolicy("/testGetStoragePolicy/foo")
          .getName();
      Assert.assertEquals("File storage policy should be COLD",
          HdfsConstants.COLD_STORAGE_POLICY_NAME, policyName);
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testSetStoragePolicyWithSnapshot() throws Exception {
    final MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(REPLICATION).build();
    cluster.waitActive();
    final DistributedFileSystem fs = cluster.getFileSystem();
    try {
      final Path dir = new Path("/testSetStoragePolicyWithSnapshot");
      final Path fooDir = new Path(dir, "foo");
      final Path fooFile1= new Path(fooDir, "f1");
      final Path fooFile2= new Path(fooDir, "f2");
      DFSTestUtil.createFile(fs, fooFile1, FILE_LEN, REPLICATION, 0L);
      DFSTestUtil.createFile(fs, fooFile2, FILE_LEN, REPLICATION, 0L);

      fs.setStoragePolicy(fooDir, HdfsConstants.WARM_STORAGE_POLICY_NAME);

      HdfsFileStatus[] dirList = fs.getClient().listPaths(dir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      checkDirectoryListing(dirList, WARM);
      HdfsFileStatus[] fooList = fs.getClient().listPaths(fooDir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      checkDirectoryListing(fooList, WARM, WARM);

      // take snapshot
      SnapshotTestHelper.createSnapshot(fs, dir, "s1");
      // change the storage policy of fooFile1
      fs.setStoragePolicy(fooFile1, HdfsConstants.COLD_STORAGE_POLICY_NAME);

      fooList = fs.getClient().listPaths(fooDir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      checkDirectoryListing(fooList, COLD, WARM);

      // check the policy for /dir/.snapshot/s1/foo/f1. Note we always return
      // the latest storage policy for a file/directory.
      Path s1f1 = SnapshotTestHelper.getSnapshotPath(dir, "s1", "foo/f1");
      DirectoryListing f1Listing = fs.getClient().listPaths(s1f1.toString(),
          HdfsFileStatus.EMPTY_NAME);
      checkDirectoryListing(f1Listing.getPartialListing(), COLD);

      // delete f1
      fs.delete(fooFile1, true);
      fooList = fs.getClient().listPaths(fooDir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      checkDirectoryListing(fooList, WARM);
      // check the policy for /dir/.snapshot/s1/foo/f1 again after the deletion
      checkDirectoryListing(fs.getClient().listPaths(s1f1.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing(), COLD);

      // change the storage policy of foo dir
      fs.setStoragePolicy(fooDir, HdfsConstants.HOT_STORAGE_POLICY_NAME);
      // /dir/foo is now hot
      dirList = fs.getClient().listPaths(dir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      checkDirectoryListing(dirList, HOT);
      // /dir/foo/f2 is hot
      fooList = fs.getClient().listPaths(fooDir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      checkDirectoryListing(fooList, HOT);

      // check storage policy of snapshot path
      Path s1 = SnapshotTestHelper.getSnapshotRoot(dir, "s1");
      Path s1foo = SnapshotTestHelper.getSnapshotPath(dir, "s1", "foo");
      checkDirectoryListing(fs.getClient().listPaths(s1.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing(), HOT);
      // /dir/.snapshot/.s1/foo/f1 and /dir/.snapshot/.s1/foo/f2 should still
      // follow the latest
      checkDirectoryListing(fs.getClient().listPaths(s1foo.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing(), COLD, HOT);

      // delete foo
      fs.delete(fooDir, true);
      checkDirectoryListing(fs.getClient().listPaths(s1.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing(), HOT);
      checkDirectoryListing(fs.getClient().listPaths(s1foo.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing(), COLD, HOT);
    } finally {
      cluster.shutdown();
    }
  }

  private static StorageType[][] genStorageTypes(int numDataNodes) {
    StorageType[][] types = new StorageType[numDataNodes][];
    for (int i = 0; i < types.length; i++) {
      types[i] = new StorageType[]{StorageType.DISK, StorageType.ARCHIVE};
    }
    return types;
  }

  private void checkLocatedBlocks(HdfsLocatedFileStatus status, int blockNum,
                                  int replicaNum, StorageType... types) {
    List<StorageType> typeList = Lists.newArrayList();
    Collections.addAll(typeList, types);
    LocatedBlocks lbs = status.getLocatedBlocks();
    Assert.assertEquals(blockNum, lbs.getLocatedBlocks().size());
    for (LocatedBlock lb : lbs.getLocatedBlocks()) {
      Assert.assertEquals(replicaNum, lb.getStorageTypes().length);
      for (StorageType type : lb.getStorageTypes()) {
        Assert.assertTrue(typeList.remove(type));
      }
    }
    Assert.assertTrue(typeList.isEmpty());
  }

  private void testChangeFileRep(String policyName, byte policyId,
                                   StorageType[] before,
                                   StorageType[] after) throws Exception {
    final int numDataNodes = 5;
    final StorageType[][] types = genStorageTypes(numDataNodes);
    final MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(numDataNodes).storageTypes(types).build();
    cluster.waitActive();
    final DistributedFileSystem fs = cluster.getFileSystem();
    try {
      final Path dir = new Path("/test");
      fs.mkdirs(dir);
      fs.setStoragePolicy(dir, policyName);

      final Path foo = new Path(dir, "foo");
      DFSTestUtil.createFile(fs, foo, FILE_LEN, REPLICATION, 0L);

      HdfsFileStatus[] status = fs.getClient().listPaths(foo.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      checkDirectoryListing(status, policyId);
      HdfsLocatedFileStatus fooStatus = (HdfsLocatedFileStatus) status[0];
      checkLocatedBlocks(fooStatus, 1, 3, before);

      // change the replication factor to 5
      fs.setReplication(foo, (short) numDataNodes);
      Thread.sleep(1000);
      for (DataNode dn : cluster.getDataNodes()) {
        DataNodeTestUtils.triggerHeartbeat(dn);
      }
      Thread.sleep(1000);
      status = fs.getClient().listPaths(foo.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      checkDirectoryListing(status, policyId);
      fooStatus = (HdfsLocatedFileStatus) status[0];
      checkLocatedBlocks(fooStatus, 1, numDataNodes, after);

      // change the replication factor back to 3
      fs.setReplication(foo, REPLICATION);
      Thread.sleep(1000);
      for (DataNode dn : cluster.getDataNodes()) {
        DataNodeTestUtils.triggerHeartbeat(dn);
      }
      Thread.sleep(1000);
      for (DataNode dn : cluster.getDataNodes()) {
        DataNodeTestUtils.triggerBlockReport(dn);
      }
      Thread.sleep(1000);
      status = fs.getClient().listPaths(foo.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      checkDirectoryListing(status, policyId);
      fooStatus = (HdfsLocatedFileStatus) status[0];
      checkLocatedBlocks(fooStatus, 1, REPLICATION, before);
    } finally {
      cluster.shutdown();
    }
  }

  /**
   * Consider a File with Hot storage policy. Increase replication factor of
   * that file from 3 to 5. Make sure all replications are created in DISKS.
   */
  @Test
  public void testChangeHotFileRep() throws Exception {
    testChangeFileRep(HdfsConstants.HOT_STORAGE_POLICY_NAME, HOT,
        new StorageType[]{StorageType.DISK, StorageType.DISK,
            StorageType.DISK},
        new StorageType[]{StorageType.DISK, StorageType.DISK, StorageType.DISK,
            StorageType.DISK, StorageType.DISK});
  }

  /**
   * Consider a File with Warm temperature. Increase replication factor of
   * that file from 3 to 5. Make sure all replicas are created in DISKS
   * and ARCHIVE.
   */
  @Test
  public void testChangeWarmRep() throws Exception {
    testChangeFileRep(HdfsConstants.WARM_STORAGE_POLICY_NAME, WARM,
        new StorageType[]{StorageType.DISK, StorageType.ARCHIVE,
            StorageType.ARCHIVE},
        new StorageType[]{StorageType.DISK, StorageType.ARCHIVE,
            StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE});
  }

  /**
   * Consider a File with Cold temperature. Increase replication factor of
   * that file from 3 to 5. Make sure all replicas are created in ARCHIVE.
   */
  @Test
  public void testChangeColdRep() throws Exception {
    testChangeFileRep(HdfsConstants.COLD_STORAGE_POLICY_NAME, COLD,
        new StorageType[]{StorageType.ARCHIVE, StorageType.ARCHIVE,
            StorageType.ARCHIVE},
        new StorageType[]{StorageType.ARCHIVE, StorageType.ARCHIVE,
            StorageType.ARCHIVE, StorageType.ARCHIVE, StorageType.ARCHIVE});
  }

  @Test
  public void testChooseTargetWithTopology() throws Exception {
    BlockStoragePolicy policy1 = new BlockStoragePolicy((byte) 9, "TEST1",
        new StorageType[]{StorageType.SSD, StorageType.DISK,
            StorageType.ARCHIVE}, new StorageType[]{}, new StorageType[]{});
    BlockStoragePolicy policy2 = new BlockStoragePolicy((byte) 11, "TEST2",
        new StorageType[]{StorageType.DISK, StorageType.SSD,
            StorageType.ARCHIVE}, new StorageType[]{}, new StorageType[]{});

    final String[] racks = {"/d1/r1", "/d1/r2", "/d1/r2"};
    final String[] hosts = {"host1", "host2", "host3"};
    final StorageType[] types = {StorageType.DISK, StorageType.SSD,
        StorageType.ARCHIVE};

    final DatanodeStorageInfo[] storages = DFSTestUtil
        .createDatanodeStorageInfos(3, racks, hosts, types);
    final DatanodeDescriptor[] dataNodes = DFSTestUtil
        .toDatanodeDescriptor(storages);

    FileSystem.setDefaultUri(conf, "hdfs://localhost:0");
    conf.set(DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY, "0.0.0.0:0");
    File baseDir = PathUtils.getTestDir(TestReplicationPolicy.class);
    conf.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY,
        new File(baseDir, "name").getPath());
    DFSTestUtil.formatNameNode(conf);
    NameNode namenode = new NameNode(conf);

    try {
      final BlockManager bm = namenode.getNamesystem().getBlockManager();
      BlockPlacementPolicy replicator = bm.getBlockPlacementPolicy();
      NetworkTopology cluster = bm.getDatanodeManager().getNetworkTopology();
      for (DatanodeDescriptor datanode : dataNodes) {
        cluster.add(datanode);
      }

      DatanodeStorageInfo[] targets = replicator.chooseTarget("/foo", 3,
              dataNodes[0], Collections.<DatanodeStorageInfo>emptyList(), false,
              new HashSet<Node>(), 0, policy1, null);
      System.out.println(Arrays.asList(targets));
      Assert.assertEquals(3, targets.length);
      targets = replicator.chooseTarget("/foo", 3,
              dataNodes[0], Collections.<DatanodeStorageInfo>emptyList(), false,
              new HashSet<Node>(), 0, policy2, null);
      System.out.println(Arrays.asList(targets));
      Assert.assertEquals(3, targets.length);
    } finally {
      if (namenode != null) {
        namenode.stop();
      }
    }
  }

  @Test
  public void testChooseSsdOverDisk() throws Exception {
    BlockStoragePolicy policy = new BlockStoragePolicy((byte) 9, "TEST1",
        new StorageType[]{StorageType.SSD, StorageType.DISK,
            StorageType.ARCHIVE}, new StorageType[]{}, new StorageType[]{});

    final String[] racks = {"/d1/r1", "/d1/r1", "/d1/r1"};
    final String[] hosts = {"host1", "host2", "host3"};
    final StorageType[] disks = {StorageType.DISK, StorageType.DISK, StorageType.DISK};

    final DatanodeStorageInfo[] diskStorages
        = DFSTestUtil.createDatanodeStorageInfos(3, racks, hosts, disks);
    final DatanodeDescriptor[] dataNodes
        = DFSTestUtil.toDatanodeDescriptor(diskStorages);
    for(int i = 0; i < dataNodes.length; i++) {
      BlockManagerTestUtil.updateStorage(dataNodes[i],
          new DatanodeStorage("ssd" + i, DatanodeStorage.State.NORMAL,
              StorageType.SSD));
    }

    FileSystem.setDefaultUri(conf, "hdfs://localhost:0");
        conf.set(DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY, "0.0.0.0:0");
    File baseDir = PathUtils.getTestDir(TestReplicationPolicy.class);
    conf.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY,
        new File(baseDir, "name").getPath());
    DFSTestUtil.formatNameNode(conf);
    NameNode namenode = new NameNode(conf);

    try {
      final BlockManager bm = namenode.getNamesystem().getBlockManager();
      BlockPlacementPolicy replicator = bm.getBlockPlacementPolicy();
      NetworkTopology cluster = bm.getDatanodeManager().getNetworkTopology();
      for (DatanodeDescriptor datanode : dataNodes) {
        cluster.add(datanode);
      }

      DatanodeStorageInfo[] targets = replicator.chooseTarget("/foo", 3,
              dataNodes[0], Collections.<DatanodeStorageInfo>emptyList(), false,
              new HashSet<Node>(), 0, policy, null);
      System.out.println(policy.getName() + ": " + Arrays.asList(targets));
      Assert.assertEquals(2, targets.length);
      Assert.assertEquals(StorageType.SSD, targets[0].getStorageType());
      Assert.assertEquals(StorageType.DISK, targets[1].getStorageType());
    } finally {
      if (namenode != null) {
        namenode.stop();
      }
    }
  }

  /**
   * Consider a File with All_SSD storage policy.
   * 1. Choose 3 DISK DNs for pipeline because SSD DNs no enough at
   * the beginning.
   * 2. One of DISK DNs fails And it need choose one new DN for existing.
   * pipeline {@link DataStreamer addDatanode2ExistingPipeline()}.
   * Make sure the number of target DNs are 3.
   * see HDFS-16182.
   */
  @Test
  public void testAddDatanode2ExistingPipelineInSsd() throws Exception {
    BlockStoragePolicy policy = POLICY_SUITE.getPolicy(ALLSSD);

    final String[] racks = {"/d1/r1", "/d2/r2", "/d3/r3", "/d4/r4", "/d5/r5",
        "/d6/r6", "/d7/r7"};
    final String[] hosts = {"host1", "host2", "host3", "host4", "host5",
        "host6", "host7"};
    final StorageType[] disks = {StorageType.DISK, StorageType.DISK, StorageType.DISK};

    final DatanodeStorageInfo[] diskStorages
        = DFSTestUtil.createDatanodeStorageInfos(7, racks, hosts, disks);
    final DatanodeDescriptor[] dataNodes
        = DFSTestUtil.toDatanodeDescriptor(diskStorages);
    for (int i = 0; i < dataNodes.length; i++) {
      BlockManagerTestUtil.updateStorage(dataNodes[i],
          new DatanodeStorage("ssd" + i + 1, DatanodeStorage.State.NORMAL,
              StorageType.SSD));
    }

    FileSystem.setDefaultUri(conf, "hdfs://localhost:0");
    conf.set(DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY, "0.0.0.0:0");
    File baseDir = PathUtils.getTestDir(TestReplicationPolicy.class);
    conf.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY,
        new File(baseDir, "name").getPath());
    DFSTestUtil.formatNameNode(conf);
    NameNode namenode = new NameNode(conf);

    try {
      final BlockManager bm = namenode.getNamesystem().getBlockManager();
      BlockPlacementPolicy replicator = bm.getBlockPlacementPolicy();
      NetworkTopology cluster = bm.getDatanodeManager().getNetworkTopology();
      for (DatanodeDescriptor datanode : dataNodes) {
        cluster.add(datanode);
      }
      // chsenDs are DISK StorageType to simulate not enough SDD Storage
      List<DatanodeStorageInfo> chsenDs = new ArrayList<>();
      chsenDs.add(diskStorages[0]);
      chsenDs.add(diskStorages[1]);
      DatanodeStorageInfo[] targets = replicator.chooseTarget("/foo", 1,
              null, chsenDs, true,
              new HashSet<Node>(), 0, policy, null);
      Assert.assertEquals(3, targets.length);
    } finally {
      if (namenode != null) {
        namenode.stop();
      }
    }
  }

  @Test
  public void testGetFileStoragePolicyAfterRestartNN() throws Exception {
    //HDFS8219
    final MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(REPLICATION)
        .storageTypes(
            new StorageType[] {StorageType.DISK, StorageType.ARCHIVE})
        .build();
    cluster.waitActive();
    final DistributedFileSystem fs = cluster.getFileSystem();
    try {
      final String file = "/testScheduleWithinSameNode/file";
      Path dir = new Path("/testScheduleWithinSameNode");
      fs.mkdirs(dir);
      // 2. Set Dir policy
      fs.setStoragePolicy(dir, "COLD");
      // 3. Create file
      final FSDataOutputStream out = fs.create(new Path(file));
      out.writeChars("testScheduleWithinSameNode");
      out.close();
      // 4. Set Dir policy
      fs.setStoragePolicy(dir, "HOT");
      HdfsFileStatus status = fs.getClient().getFileInfo(file);
      // 5. get file policy, it should be parent policy.
      Assert
          .assertTrue("File storage policy should be HOT",
              status.getStoragePolicy() == HOT);
      // 6. restart NameNode for reloading edits logs.
      cluster.restartNameNode(true);
      // 7. get file policy, it should be parent policy.
      status = fs.getClient().getFileInfo(file);
      Assert
          .assertTrue("File storage policy should be HOT",
              status.getStoragePolicy() == HOT);

    } finally {
      cluster.shutdown();
    }
  }

  /**
   * Verify that {@link FileSystem#getAllStoragePolicies} returns all
   * known storage policies for DFS.
   *
   * @throws IOException
   */
  @Test
  public void testGetAllStoragePoliciesFromFs() throws IOException {
    final MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(REPLICATION)
        .storageTypes(
            new StorageType[] {StorageType.DISK, StorageType.ARCHIVE})
        .build();
    try {
      cluster.waitActive();

      // Get policies via {@link FileSystem#getAllStoragePolicies}
      Set<String> policyNamesSet1 = new HashSet<>();
      for (BlockStoragePolicySpi policy :
          cluster.getFileSystem().getAllStoragePolicies()) {
        policyNamesSet1.add(policy.getName());
      }

      // Get policies from the default BlockStoragePolicySuite.
      BlockStoragePolicySuite suite = BlockStoragePolicySuite.createDefaultSuite();
      Set<String> policyNamesSet2 = new HashSet<>();
      for (BlockStoragePolicy policy : suite.getAllPolicies()) {
        policyNamesSet2.add(policy.getName());
      }

      // Ensure that we got the same set of policies in both cases.
      Assert.assertTrue(Sets.difference(policyNamesSet1, policyNamesSet2).isEmpty());
      Assert.assertTrue(Sets.difference(policyNamesSet2, policyNamesSet1).isEmpty());
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testStorageType() {
    final EnumMap<StorageType, Integer> map = new EnumMap<>(StorageType.class);

    //put storage type is reversed order
    map.put(StorageType.ARCHIVE, 1);
    map.put(StorageType.DISK, 1);
    map.put(StorageType.SSD, 1);
    map.put(StorageType.RAM_DISK, 1);

    {
      final Iterator<StorageType> i = map.keySet().iterator();
      Assert.assertEquals(StorageType.RAM_DISK, i.next());
      Assert.assertEquals(StorageType.SSD, i.next());
      Assert.assertEquals(StorageType.DISK, i.next());
      Assert.assertEquals(StorageType.ARCHIVE, i.next());
    }

    {
      final Iterator<Map.Entry<StorageType, Integer>> i
          = map.entrySet().iterator();
      Assert.assertEquals(StorageType.RAM_DISK, i.next().getKey());
      Assert.assertEquals(StorageType.SSD, i.next().getKey());
      Assert.assertEquals(StorageType.DISK, i.next().getKey());
      Assert.assertEquals(StorageType.ARCHIVE, i.next().getKey());
    }
  }

  @Test
  public void testStorageTypeCheckAccess(){
    testStorageTypeCheckAccessResult(new StorageType[]{StorageType.DEFAULT},
        new StorageType[]{StorageType.DEFAULT}, true);

    testStorageTypeCheckAccessResult(StorageType.EMPTY_ARRAY,
        StorageType.EMPTY_ARRAY, false);

    testStorageTypeCheckAccessResult(new StorageType[]{StorageType.DISK},
        StorageType.EMPTY_ARRAY, false);

    testStorageTypeCheckAccessResult(StorageType.EMPTY_ARRAY,
        new StorageType[]{StorageType.RAM_DISK}, true);

    testStorageTypeCheckAccessResult(new StorageType[]{StorageType.DISK},
        new StorageType[]{StorageType.DISK}, true);

    testStorageTypeCheckAccessResult(new StorageType[]{StorageType.DISK},
        new StorageType[]{StorageType.DISK, StorageType.DISK, StorageType.DISK},
        false);

    testStorageTypeCheckAccessResult(
        new StorageType[]{StorageType.DISK, StorageType.DISK, StorageType.DISK},
        new StorageType[]{StorageType.DISK, StorageType.DISK, StorageType.DISK},
        true);

    testStorageTypeCheckAccessResult(
        new StorageType[]{StorageType.RAM_DISK, StorageType.SSD},
        new StorageType[]{StorageType.DISK, StorageType.RAM_DISK,
            StorageType.SSD},
        false);

    testStorageTypeCheckAccessResult(
        new StorageType[]{StorageType.DISK, StorageType.SSD},
        new StorageType[]{StorageType.SSD},
        true);

    testStorageTypeCheckAccessResult(new StorageType[]{StorageType.DISK},
        new StorageType[]{StorageType.RAM_DISK}, false);

    testStorageTypeCheckAccessResult(new StorageType[]{StorageType.DISK},
        new StorageType[]{StorageType.RAM_DISK, StorageType.SSD,
            StorageType.ARCHIVE},
        false);

    testStorageTypeCheckAccessResult(new StorageType[]{StorageType.RAM_DISK,
        StorageType.SSD, StorageType.ARCHIVE},
        new StorageType[]{StorageType.DISK}, false);

    testStorageTypeCheckAccessResult(
        new StorageType[]{StorageType.DISK, StorageType.SSD},
        new StorageType[]{StorageType.SSD},
        true);

    testStorageTypeCheckAccessResult(new StorageType[]{StorageType.RAM_DISK},
        new StorageType[]{StorageType.DISK}, false);

    testStorageTypeCheckAccessResult(
        new StorageType[]{StorageType.RAM_DISK, StorageType.SSD,
            StorageType.ARCHIVE},
        new StorageType[]{StorageType.DISK},
        false);

    testStorageTypeCheckAccessResult(
        new StorageType[]{StorageType.RAM_DISK, StorageType.SSD,
            StorageType.ARCHIVE},
        new StorageType[]{StorageType.DISK},
        false);

  }

  private void testStorageTypeCheckAccessResult(StorageType[] requested,
      StorageType[] allowed, boolean expAccess) {
    try {
      BlockTokenSecretManager.checkAccess(requested, allowed, "StorageTypes");
      if (!expAccess) {
        fail("No expected access with allowed StorageTypes "
            + Arrays.toString(allowed) + " and requested StorageTypes "
            + Arrays.toString(requested));
      }
    } catch (SecretManager.InvalidToken e) {
      if (expAccess) {
        fail("Expected access with allowed StorageTypes "
            + Arrays.toString(allowed) + " and requested StorageTypes "
            + Arrays.toString(requested));
      }
    }
  }

  @Test
  public void testStorageIDCheckAccess() {
    testStorageIDCheckAccessResult(
        new String[]{"DN1-Storage1"},
        new String[]{"DN1-Storage1"}, true);

    testStorageIDCheckAccessResult(new String[]{"DN1-Storage1", "DN2-Storage1"},
        new String[]{"DN1-Storage1"},
        true);

    testStorageIDCheckAccessResult(new String[]{"DN1-Storage1", "DN2-Storage1"},
        new String[]{"DN1-Storage1", "DN1-Storage2"}, false);

    testStorageIDCheckAccessResult(
        new String[]{"DN1-Storage1", "DN1-Storage2"},
        new String[]{"DN1-Storage1"}, true);

    testStorageIDCheckAccessResult(
        new String[]{"DN1-Storage1", "DN1-Storage2"},
        new String[]{"DN2-Storage1"}, false);

    testStorageIDCheckAccessResult(
        new String[]{"DN1-Storage2", "DN2-Storage2"},
        new String[]{"DN1-Storage1", "DN2-Storage1"}, false);

    testStorageIDCheckAccessResult(new String[0], new String[0], false);

    testStorageIDCheckAccessResult(new String[0], new String[]{"DN1-Storage1"},
        true);

    testStorageIDCheckAccessResult(new String[]{"DN1-Storage1"}, new String[0],
        false);
  }

  private void testStorageIDCheckAccessResult(String[] requested,
          String[] allowed, boolean expAccess) {
    try {
      BlockTokenSecretManager.checkAccess(requested, allowed, "StorageIDs");
      if (!expAccess) {
        fail("No expected access with allowed StorageIDs"
            + Arrays.toString(allowed) + " and requested StorageIDs"
            + Arrays.toString(requested));
      }
    } catch (SecretManager.InvalidToken e) {
      if (expAccess) {
        fail("Expected access with allowed StorageIDs "
            + Arrays.toString(allowed) + " and requested StorageIDs"
            + Arrays.toString(requested));
      }
    }
  }
}
