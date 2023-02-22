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

package org.apache.hadoop.hdfs.server.blockmanagement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.apache.hadoop.hdfs.server.datanode.InternalDataNodeTestUtils;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.NameNodeAdapter;
import org.apache.hadoop.hdfs.util.HostsFileWriter;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.Test;
import org.slf4j.event.Level;

import static org.junit.Assert.*;

public class TestBlocksWithNotEnoughRacks {
  public static final Logger LOG =
      LoggerFactory.getLogger(TestBlocksWithNotEnoughRacks.class);
  static {
    GenericTestUtils.setLogLevel(FSNamesystem.LOG, Level.TRACE);
    GenericTestUtils.setLogLevel(LOG, Level.TRACE);
  }

  /*
   * Return a configuration object with low timeouts for testing and 
   * a topology script set (which enables rack awareness).  
   */
  private Configuration getConf() {
    Configuration conf = new HdfsConfiguration();

    // Lower the heart beat interval so the NN quickly learns of dead
    // or decommissioned DNs and the NN issues replication and invalidation
    // commands quickly (as replies to heartbeats)
    conf.setLong(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1L);

    // Have the NN RedundancyMonitor compute the reconstruction and
    // invalidation commands to send DNs every second.
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_REDUNDANCY_INTERVAL_SECONDS_KEY, 1);

    // Have the NN check for pending replications every second so it
    // quickly schedules additional replicas as they are identified.
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_RECONSTRUCTION_PENDING_TIMEOUT_SEC_KEY, 1);

    // The DNs report blocks every second.
    conf.setLong(DFSConfigKeys.DFS_BLOCKREPORT_INTERVAL_MSEC_KEY, 1000L);

    // Indicates we have multiple racks
    conf.set(DFSConfigKeys.NET_TOPOLOGY_SCRIPT_FILE_NAME_KEY, "xyz");
    return conf;
  }

  /*
   * Creates a block with all datanodes on the same rack, though the block
   * is sufficiently replicated. Adds an additional datanode on a new rack. 
   * The block should be replicated to the new rack.
   */
  @Test
  public void testSufficientlyReplBlocksUsesNewRack() throws Exception {
    Configuration conf = getConf();
    final short REPLICATION_FACTOR = 3;
    final Path filePath = new Path("/testFile");
    // All datanodes are on the same rack
    String racks[] = {"/rack1", "/rack1", "/rack1"};
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
      .numDataNodes(racks.length).racks(racks).build();

    try {
      // Create a file with one block with a replication factor of 3
      final FileSystem fs = cluster.getFileSystem();
      DFSTestUtil.createFile(fs, filePath, 1L, REPLICATION_FACTOR, 1L);
      ExtendedBlock b = DFSTestUtil.getFirstBlock(fs, filePath);
      DFSTestUtil.waitForReplication(cluster, b, 1, REPLICATION_FACTOR, 0);

      // Add a new datanode on a different rack
      String newRacks[] = {"/rack2"};
      cluster.startDataNodes(conf, 1, true, null, newRacks);
      cluster.waitActive();

      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);
    } finally {
      cluster.shutdown();
    }
  }

  /*
   * Like the previous test but the block starts with a single replica,
   * and therefore unlike the previous test the block does not start
   * off needing replicas.
   */
  @Test
  public void testSufficientlySingleReplBlockUsesNewRack() throws Exception {
    Configuration conf = getConf();
    short REPLICATION_FACTOR = 1;
    final Path filePath = new Path("/testFile");

    String racks[] = {"/rack1", "/rack1", "/rack1", "/rack2"};
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
      .numDataNodes(racks.length).racks(racks).build();
    final FSNamesystem ns = cluster.getNameNode().getNamesystem();

    try {
      // Create a file with one block with a replication factor of 1
      final FileSystem fs = cluster.getFileSystem();
      DFSTestUtil.createFile(fs, filePath, 1L, REPLICATION_FACTOR, 1L);
      ExtendedBlock b = DFSTestUtil.getFirstBlock(fs, filePath);
      DFSTestUtil.waitForReplication(cluster, b, 1, REPLICATION_FACTOR, 0);

      REPLICATION_FACTOR = 2;
      NameNodeAdapter.setReplication(ns, "/testFile", REPLICATION_FACTOR);
      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);
    } finally {
      cluster.shutdown();
    }
  }

  /*
   * Initialize a cluster with datanodes on two different racks and shutdown
   * all datanodes on one rack. Now create a file with a single block. Even
   * though the block is sufficiently replicated, it violates the replica
   * placement policy. Now restart the datanodes stopped earlier. Run the fsck
   * command with -replicate option to schedule the replication of these
   * mis-replicated blocks and verify if it indeed works as expected.
   */
  @Test
  public void testMisReplicatedBlockUsesNewRack() throws Exception {
    Configuration conf = getConf();
    conf.setInt("dfs.namenode.heartbeat.recheck-interval", 500);

    final short replicationFactor = 3;
    final Path filePath = new Path("/testFile");
    // All datanodes are on two different racks
    String[] racks = new String[]{"/rack1", "/rack1", "/rack1", "/rack2"};

    try (MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
            .numDataNodes(racks.length).racks(racks).build()) {
      cluster.waitActive();

      String poolId = cluster.getNamesystem().getBlockPoolId();
      DatanodeRegistration reg = InternalDataNodeTestUtils.
              getDNRegistrationForBP(cluster.getDataNodes().get(3), poolId);
      // Shutdown datanode on rack2 and wait for it to be marked dead
      cluster.stopDataNode(3);
      DFSTestUtil.waitForDatanodeState(cluster, reg.getDatanodeUuid(),
              false, 20000);

      // Create a file with one block with a replication factor of 3
      final FileSystem fs = cluster.getFileSystem();
      DFSTestUtil.createFile(fs, filePath, 1L, replicationFactor, 1L);
      ExtendedBlock b = DFSTestUtil.getFirstBlock(fs, filePath);
      DFSTestUtil.waitReplication(cluster.getFileSystem(), filePath,
              replicationFactor);

      // Add datanode on rack2 and wait for it be recognized as alive by NN
      cluster.startDataNodes(conf, 1, true,
              null, new String[]{"/rack2"});
      cluster.waitActive();

      try {
        DFSTestUtil.waitForReplication(cluster, b, 2, replicationFactor, 0);
        fail("NameNode should not have fixed the mis-replicated blocks" +
                " automatically.");
      } catch (TimeoutException e) {
        //Expected.
      }

      String fsckOp = DFSTestUtil.runFsck(conf, 0, true, filePath.toString(),
              "-replicate");
      LOG.info("fsck response {}", fsckOp);
      assertTrue(fsckOp.contains(
              "/testFile:  Replica placement policy is violated"));
      assertTrue(fsckOp.contains(" Block should be additionally replicated" +
              " on 1 more rack(s). Total number of racks in the cluster: 2"));
      assertTrue(fsckOp.contains(" Blocks queued for replication:\t1"));
      try {
        DFSTestUtil.waitForReplication(cluster, b, 2, replicationFactor, 0);
      } catch (TimeoutException e) {
        fail("NameNode should have fixed the mis-replicated blocks as a" +
                " result of fsck command.");
      }
    }
  }

  /*
   * Creates a block with all datanodes on the same rack. Add additional
   * datanodes on a different rack and increase the replication factor, 
   * making sure there are enough replicas across racks. If the previous
   * test passes this one should too, however this test may pass when
   * the previous one fails because the replication code is explicitly
   * triggered by setting the replication factor.
   */
  @Test
  public void testUnderReplicatedUsesNewRacks() throws Exception {
    Configuration conf = getConf();
    short REPLICATION_FACTOR = 3;
    final Path filePath = new Path("/testFile");
    // All datanodes are on the same rack
    String racks[] = {"/rack1", "/rack1", "/rack1", "/rack1", "/rack1"};
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
      .numDataNodes(racks.length).racks(racks).build();
    final FSNamesystem ns = cluster.getNameNode().getNamesystem();

    try {
      // Create a file with one block
      final FileSystem fs = cluster.getFileSystem();
      DFSTestUtil.createFile(fs, filePath, 1L, REPLICATION_FACTOR, 1L);
      ExtendedBlock b = DFSTestUtil.getFirstBlock(fs, filePath);
      DFSTestUtil.waitForReplication(cluster, b, 1, REPLICATION_FACTOR, 0);
      
      // Add new datanodes on a different rack and increase the
      // replication factor so the block is underreplicated and make
      // sure at least one of the hosts on the new rack is used. 
      String newRacks[] = {"/rack2", "/rack2"};
      cluster.startDataNodes(conf, 2, true, null, newRacks);
      REPLICATION_FACTOR = 5;
      NameNodeAdapter.setReplication(ns, "/testFile", REPLICATION_FACTOR);

      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);
    } finally {
      cluster.shutdown();
    }
  }

  /*
   * Test that a block that is re-replicated because one of its replicas
   * is found to be corrupt and is re-replicated across racks.
   */
  @Test
  public void testCorruptBlockRereplicatedAcrossRacks() throws Exception {
    Configuration conf = getConf();
    short REPLICATION_FACTOR = 2;
    int fileLen = 512;
    final Path filePath = new Path("/testFile");
    // Datanodes are spread across two racks
    String racks[] = {"/rack1", "/rack1", "/rack2", "/rack2"};
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
      .numDataNodes(racks.length).racks(racks).build();
    final FSNamesystem ns = cluster.getNameNode().getNamesystem();

    try {
      // Create a file with one block with a replication factor of 2
      final FileSystem fs = cluster.getFileSystem();
      
      DFSTestUtil.createFile(fs, filePath, fileLen, REPLICATION_FACTOR, 1L);
      final byte[] fileContent = DFSTestUtil.readFileAsBytes(fs, filePath);

      ExtendedBlock b = DFSTestUtil.getFirstBlock(fs, filePath);
      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);

      // Corrupt a replica of the block
      int dnToCorrupt = DFSTestUtil.firstDnWithBlock(cluster, b);
      cluster.corruptReplica(dnToCorrupt, b);

      // Restart the datanode so blocks are re-scanned, and the corrupt
      // block is detected.
      cluster.restartDataNode(dnToCorrupt);

      // Wait for the namenode to notice the corrupt replica
      DFSTestUtil.waitCorruptReplicas(fs, ns, filePath, b, 1);

      // The rack policy is still respected
      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);

      // Ensure all replicas are valid (the corrupt replica may not
      // have been cleaned up yet).
      for (int i = 0; i < racks.length; i++) {
        byte[] blockContent = cluster.readBlockOnDataNodeAsBytes(i, b);
        if (blockContent != null && i != dnToCorrupt) {
          assertArrayEquals("Corrupt replica", fileContent, blockContent);
        }
      }
    } finally {
      cluster.shutdown();
    }
  }

  /*
   * Reduce the replication factor of a file, making sure that the only
   * cross rack replica is not removed when deleting replicas.
   */
  @Test
  public void testReduceReplFactorRespectsRackPolicy() throws Exception {
    Configuration conf = getConf();
    short REPLICATION_FACTOR = 3;
    final Path filePath = new Path("/testFile");
    String racks[] = {"/rack1", "/rack1", "/rack2", "/rack2"};
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
      .numDataNodes(racks.length).racks(racks).build();
    final FSNamesystem ns = cluster.getNameNode().getNamesystem();

    try {
      // Create a file with one block
      final FileSystem fs = cluster.getFileSystem();
      DFSTestUtil.createFile(fs, filePath, 1L, REPLICATION_FACTOR, 1L);
      ExtendedBlock b = DFSTestUtil.getFirstBlock(fs, filePath);
      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);

      // Decrease the replication factor, make sure the deleted replica
      // was not the one that lived on the rack with only one replica,
      // ie we should still have 2 racks after reducing the repl factor.
      REPLICATION_FACTOR = 2;
      NameNodeAdapter.setReplication(ns, "/testFile", REPLICATION_FACTOR); 

      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);
    } finally {
      cluster.shutdown();
    }
  }

  /*
   * Test that when a block is replicated because a replica is lost due
   * to host failure the the rack policy is preserved.
   */
  @Test
  public void testReplDueToNodeFailRespectsRackPolicy() throws Exception {
    Configuration conf = getConf();
    short REPLICATION_FACTOR = 3;
    final Path filePath = new Path("/testFile");
    // Last datanode is on a different rack
    String racks[] = {"/rack1", "/rack1", "/rack1", "/rack2", "/rack2"};
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
      .numDataNodes(racks.length).racks(racks).build();
    final FSNamesystem ns = cluster.getNameNode().getNamesystem();
    final DatanodeManager dm = ns.getBlockManager().getDatanodeManager();

    try {
      // Create a file with one block with a replication factor of 3
      final FileSystem fs = cluster.getFileSystem();
      DFSTestUtil.createFile(fs, filePath, 1L, REPLICATION_FACTOR, 1L);
      ExtendedBlock b = DFSTestUtil.getFirstBlock(fs, filePath);
      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);

      // Make the last datanode look like it failed to heartbeat by 
      // calling removeDatanode and stopping it.
      ArrayList<DataNode> datanodes = cluster.getDataNodes();
      int idx = datanodes.size() - 1;
      DataNode dataNode = datanodes.get(idx);
      DatanodeID dnId = dataNode.getDatanodeId();
      cluster.stopDataNode(idx);
      dm.removeDatanode(dnId);

      // The block should still have sufficient # replicas, across racks.
      // The last node may not have contained a replica, but if it did
      // it should have been replicated within the same rack.
      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);
      
      // Fail the last datanode again, it's also on rack2 so there is
      // only 1 rack for all the replicas
      datanodes = cluster.getDataNodes();
      idx = datanodes.size() - 1;
      dataNode = datanodes.get(idx);
      dnId = dataNode.getDatanodeId();
      cluster.stopDataNode(idx);
      dm.removeDatanode(dnId);

      // Make sure we have enough live replicas even though we are
      // short one rack. The cluster now has only 1 rack thus we just make sure
      // we still have 3 replicas.
      DFSTestUtil.waitForReplication(cluster, b, 1, REPLICATION_FACTOR, 0);
    } finally {
      cluster.shutdown();
    }
  }
  
  /*
   * Test that when the excess replicas of a block are reduced due to
   * a node re-joining the cluster the rack policy is not violated.
   */
  @Test
  public void testReduceReplFactorDueToRejoinRespectsRackPolicy() 
      throws Exception {
    Configuration conf = getConf();
    short REPLICATION_FACTOR = 2;
    final Path filePath = new Path("/testFile");
    // Last datanode is on a different rack
    String racks[] = {"/rack1", "/rack1", "/rack2"};
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
      .numDataNodes(racks.length).racks(racks).build();
    final FSNamesystem ns = cluster.getNameNode().getNamesystem();
    final DatanodeManager dm = ns.getBlockManager().getDatanodeManager();

    try {
      // Create a file with one block
      final FileSystem fs = cluster.getFileSystem();
      DFSTestUtil.createFile(fs, filePath, 1L, REPLICATION_FACTOR, 1L);
      ExtendedBlock b = DFSTestUtil.getFirstBlock(fs, filePath);
      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);

      // Make the last (cross rack) datanode look like it failed
      // to heartbeat by stopping it and calling removeDatanode.
      ArrayList<DataNode> datanodes = cluster.getDataNodes();
      assertEquals(3, datanodes.size());
      DataNode dataNode = datanodes.get(2);
      DatanodeID dnId = dataNode.getDatanodeId();
      cluster.stopDataNode(2);
      dm.removeDatanode(dnId);

      // The block gets re-replicated to another datanode so it has a 
      // sufficient # replicas, but not across racks, so there should
      // be 1 rack.
      DFSTestUtil.waitForReplication(cluster, b, 1, REPLICATION_FACTOR, 0);

      // Start the "failed" datanode, which has a replica so the block is
      // now over-replicated and therefore a replica should be removed but
      // not on the restarted datanode as that would violate the rack policy.
      String rack2[] = {"/rack2"};
      cluster.startDataNodes(conf, 1, true, null, rack2);
      cluster.waitActive();      
      
      // The block now has sufficient # replicas, across racks
      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);
    } finally {
      cluster.shutdown();
    }
  }

  /*
   * Test that rack policy is still respected when blocks are replicated
   * due to node decommissioning.
   */
  @Test
  public void testNodeDecomissionRespectsRackPolicy() throws Exception {
    Configuration conf = getConf();
    short REPLICATION_FACTOR = 2;
    final Path filePath = new Path("/testFile");

    HostsFileWriter hostsFileWriter = new HostsFileWriter();
    hostsFileWriter.initialize(conf, "temp/decommission");

    // Two blocks and four racks
    String racks[] = {"/rack1", "/rack1", "/rack2", "/rack2"};
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
      .numDataNodes(racks.length).racks(racks).build();
    final FSNamesystem ns = cluster.getNameNode().getNamesystem();

    try {
      // Create a file with one block
      final FileSystem fs = cluster.getFileSystem();
      DFSTestUtil.createFile(fs, filePath, 1L, REPLICATION_FACTOR, 1L);
      ExtendedBlock b = DFSTestUtil.getFirstBlock(fs, filePath);
      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);

      // Decommission one of the hosts with the block, this should cause 
      // the block to get replicated to another host on the same rack,
      // otherwise the rack policy is violated.
      BlockLocation locs[] = fs.getFileBlockLocations(
          fs.getFileStatus(filePath), 0, Long.MAX_VALUE);
      String name = locs[0].getNames()[0];
      hostsFileWriter.initExcludeHost(name);
      ns.getBlockManager().getDatanodeManager().refreshNodes(conf);
      DFSTestUtil.waitForDecommission(fs, name);

      // Check the block still has sufficient # replicas across racks
      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);
    } finally {
      cluster.shutdown();
      hostsFileWriter.cleanup();
    }
  }

  /*
   * Test that rack policy is still respected when blocks are replicated
   * due to node decommissioning, when the blocks are over-replicated.
   */
  @Test
  public void testNodeDecomissionWithOverreplicationRespectsRackPolicy() 
      throws Exception {
    Configuration conf = getConf();
    short REPLICATION_FACTOR = 5;
    final Path filePath = new Path("/testFile");

    HostsFileWriter hostsFileWriter = new HostsFileWriter();
    hostsFileWriter.initialize(conf, "temp/decommission");

    // All hosts are on two racks, only one host on /rack2
    String racks[] = {"/rack1", "/rack2", "/rack1", "/rack1", "/rack1"};
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
      .numDataNodes(racks.length).racks(racks).build();
    final FSNamesystem ns = cluster.getNameNode().getNamesystem();

    try {
      final FileSystem fs = cluster.getFileSystem();
      DFSTestUtil.createFile(fs, filePath, 1L, REPLICATION_FACTOR, 1L);
      ExtendedBlock b = DFSTestUtil.getFirstBlock(fs, filePath);
      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);

      // Lower the replication factor so the blocks are over replicated
      REPLICATION_FACTOR = 2;
      fs.setReplication(filePath, REPLICATION_FACTOR);

      // Decommission one of the hosts with the block that is not on
      // the lone host on rack2 (if we decomission that host it would
      // be impossible to respect the rack policy).
      BlockLocation locs[] = fs.getFileBlockLocations(
          fs.getFileStatus(filePath), 0, Long.MAX_VALUE);
      for (String top : locs[0].getTopologyPaths()) {
        if (!top.startsWith("/rack2")) {
          String name = top.substring("/rack1".length()+1);
          hostsFileWriter.initExcludeHost(name);
          ns.getBlockManager().getDatanodeManager().refreshNodes(conf);
          DFSTestUtil.waitForDecommission(fs, name);
          break;
        }
      }

      // Check the block still has sufficient # replicas across racks,
      // ie we didn't remove the replica on the host on /rack1.
      DFSTestUtil.waitForReplication(cluster, b, 2, REPLICATION_FACTOR, 0);
    } finally {
      cluster.shutdown();
      hostsFileWriter.cleanup();
    }
  }

  @Test
  public void testMultipleReplicasScheduledForUpgradeDomain() throws Exception {
    Configuration conf = getConf();
    final short replicationFactor = 3;
    final Path filePath = new Path("/testFile");

    conf.set("dfs.block.replicator.classname",
        "org.apache.hadoop.hdfs.server.blockmanagement." +
            "BlockPlacementPolicyWithUpgradeDomain");
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(6).build();
    cluster.waitClusterUp();

    List<DatanodeDescriptor> dnDescriptors = getDnDescriptors(cluster);

    try {
      // Create a file with one block with a replication factor of 3
      // No upgrade domains are set.
      final FileSystem fs = cluster.getFileSystem();
      DFSTestUtil.createFile(fs, filePath, 1L, replicationFactor, 1L);
      ExtendedBlock b = DFSTestUtil.getFirstBlock(fs, filePath);

      BlockManager bm = cluster.getNamesystem().getBlockManager();
      BlockInfo storedBlock = bm.getStoredBlock(b.getLocalBlock());

      // The block should be replicated OK - so Reconstruction Work will be null
      BlockReconstructionWork work = scheduleReconstruction(
          cluster.getNamesystem(), storedBlock, 2);
      assertNull(work);
      // Set the upgradeDomain to "3" for the 3 nodes hosting the block.
      // Then alternately set the remaining 3 nodes to have an upgradeDomain
      // of 0 or 1 giving a total of 3 upgradeDomains.
      for (int i=0; i<storedBlock.getReplication(); i++) {
        storedBlock.getDatanode(i).setUpgradeDomain("3");
      }
      int udInd = 0;
      for (DatanodeDescriptor d : dnDescriptors) {
        if (d.getUpgradeDomain() == null) {
          d.setUpgradeDomain(Integer.toString(udInd % 2));
          udInd++;
        }
      }
      // Now reconWork is non-null and 2 extra targets are needed
      work = scheduleReconstruction(
          cluster.getNamesystem(), storedBlock, 2);
      assertEquals(2, work.getAdditionalReplRequired());

      // Add the block to the replication queue and ensure it is replicated
      // correctly.
      bm.neededReconstruction.add(storedBlock, 3, 0, 0, replicationFactor);
      DFSTestUtil.waitForReplication(cluster, b, 1, replicationFactor, 0, 3);
    } finally {
      cluster.shutdown();
    }
  }

  static BlockReconstructionWork scheduleReconstruction(
      FSNamesystem fsn, BlockInfo block, int priority) {
    fsn.writeLock();
    try {
      return fsn.getBlockManager().scheduleReconstruction(block, priority);
    } finally {
      fsn.writeUnlock();
    }
  }

  @Test
  public void testUnderReplicatedRespectsRacksAndUpgradeDomain()
      throws Exception {
    Configuration conf = getConf();
    final short replicationFactor = 3;
    final Path filePath = new Path("/testFile");

    conf.set("dfs.block.replicator.classname",
        "org.apache.hadoop.hdfs.server.blockmanagement." +
        "BlockPlacementPolicyWithUpgradeDomain");

    // All hosts are on two racks
    String[] racks = {"/r1", "/r1", "/r1", "/r2", "/r2", "/r2"};
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(6).racks(racks).build();
    cluster.waitClusterUp();
    List<DatanodeDescriptor> dnDescriptors = getDnDescriptors(cluster);
    for (int i=0; i < dnDescriptors.size(); i++) {
      dnDescriptors.get(i).setUpgradeDomain(Integer.toString(i%3));
    }
    try {
      final FileSystem fs = cluster.getFileSystem();
      DFSTestUtil.createFile(fs, filePath, 1L, (short)1, 1L);
      ExtendedBlock b = DFSTestUtil.getFirstBlock(fs, filePath);
      fs.setReplication(filePath, replicationFactor);
      DFSTestUtil.waitForReplication(cluster, b, 2, replicationFactor, 0, 3);
    } finally {
      cluster.shutdown();
    }
  }

  private List<DatanodeDescriptor> getDnDescriptors(MiniDFSCluster cluster)
      throws IOException {
    List<DatanodeDescriptor> dnDesc = new ArrayList<>();
    DatanodeManager dnManager = cluster.getNamesystem().getBlockManager()
        .getDatanodeManager();
    for (DataNode dn : cluster.getDataNodes()) {
      DatanodeDescriptor d = dnManager.getDatanode(dn.getDatanodeUuid());
      if (d == null) {
        throw new IOException("DatanodeDescriptor not found for DN "+
            dn.getDatanodeUuid());
      }
      dnDesc.add(d);
    }
    return dnDesc;
  }
}
