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

import static org.apache.hadoop.test.MetricsAsserts.getLongCounter;
import static org.apache.hadoop.test.MetricsAsserts.getMetrics;
import static org.apache.hadoop.test.PlatformAssumptions.assumeNotWindows;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.BlockReader;
import org.apache.hadoop.hdfs.ClientContext;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DFSUtilClient;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.RemotePeerFactory;
import org.apache.hadoop.hdfs.client.impl.BlockReaderFactory;
import org.apache.hadoop.hdfs.client.impl.DfsClientConf;
import org.apache.hadoop.hdfs.net.Peer;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManager;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManagerTestUtil;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsDatasetSpi;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeSpi;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.impl.AddBlockPoolException;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.impl.FsDatasetTestUtil;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;
import org.apache.hadoop.hdfs.server.protocol.VolumeFailureSummary;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.metrics2.MetricsRecordBuilder;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Fine-grain testing of block files and locations after volume failure.
 */
public class TestDataNodeVolumeFailure {
  private final static Logger LOG = LoggerFactory.getLogger(
      TestDataNodeVolumeFailure.class);
  final private int block_size = 512;
  MiniDFSCluster cluster = null;
  private Configuration conf;
  final int dn_num = 2;
  final int blocks_num = 30;
  final short repl=2;
  File dataDir = null;
  File data_fail = null;
  File failedDir = null;
  private FileSystem fs;

  // mapping blocks to Meta files(physical files) and locs(NameNode locations)
  private class BlockLocs {
    public int num_files = 0;
    public int num_locs = 0;
  }
  // block id to BlockLocs
  final Map<String, BlockLocs> block_map = new HashMap<String, BlockLocs> ();

  // specific the timeout for entire test class
  @Rule
  public Timeout timeout = new Timeout(120 * 1000);

  @Before
  public void setUp() throws Exception {
    // bring up a cluster of 2
    conf = new HdfsConfiguration();
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, block_size);
    // Allow a single volume failure (there are two volumes)
    conf.setInt(DFSConfigKeys.DFS_DATANODE_FAILED_VOLUMES_TOLERATED_KEY, 1);
    conf.setInt(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 30);
    conf.setTimeDuration(DFSConfigKeys.DFS_DATANODE_DISK_CHECK_MIN_GAP_KEY,
        0, TimeUnit.MILLISECONDS);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(dn_num).build();
    cluster.waitActive();
    fs = cluster.getFileSystem();
    dataDir = new File(cluster.getDataDirectory());
  }

  @After
  public void tearDown() throws Exception {
    if(data_fail != null) {
      FileUtil.setWritable(data_fail, true);
      data_fail = null;
    }
    if(failedDir != null) {
      FileUtil.setWritable(failedDir, true);
      failedDir = null;
    }
    if(cluster != null) {
      cluster.shutdown();
      cluster = null;
    }
  }
  
  /*
   * Verify the number of blocks and files are correct after volume failure,
   * and that we can replicate to both datanodes even after a single volume
   * failure if the configuration parameter allows this.
   */
  @Test(timeout = 120000)
  public void testVolumeFailure() throws Exception {
    System.out.println("Data dir: is " +  dataDir.getPath());
   
    
    // Data dir structure is dataDir/data[1-4]/[current,tmp...]
    // data1,2 is for datanode 1, data2,3 - datanode2 
    String filename = "/test.txt";
    Path filePath = new Path(filename);
    
    // we use only small number of blocks to avoid creating subdirs in the data dir..
    int filesize = block_size*blocks_num;
    DFSTestUtil.createFile(fs, filePath, filesize, repl, 1L);
    DFSTestUtil.waitReplication(fs, filePath, repl);
    System.out.println("file " + filename + "(size " +
        filesize + ") is created and replicated");
   
    // fail the volume
    // delete/make non-writable one of the directories (failed volume)
    data_fail = cluster.getInstanceStorageDir(1, 0);
    failedDir = MiniDFSCluster.getFinalizedDir(data_fail,
        cluster.getNamesystem().getBlockPoolId());
    if (failedDir.exists() &&
        //!FileUtil.fullyDelete(failedDir)
        !deteteBlocks(failedDir)
        ) {
      throw new IOException("Could not delete hdfs directory '" + failedDir + "'");
    }
    data_fail.setReadOnly();
    failedDir.setReadOnly();
    System.out.println("Deleteing " + failedDir.getPath() + "; exist=" + failedDir.exists());
    
    // access all the blocks on the "failed" DataNode, 
    // we need to make sure that the "failed" volume is being accessed - 
    // and that will cause failure, blocks removal, "emergency" block report
    triggerFailure(filename, filesize);
    // DN eventually have latest volume failure information for next heartbeat
    final DataNode dn = cluster.getDataNodes().get(1);
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        final VolumeFailureSummary summary =
            dn.getFSDataset().getVolumeFailureSummary();
        return summary != null &&
            summary.getFailedStorageLocations() != null &&
            summary.getFailedStorageLocations().length == 1;
      }
    }, 10, 30 * 1000);

    // trigger DN to send heartbeat
    DataNodeTestUtils.triggerHeartbeat(dn);
    final BlockManager bm = cluster.getNamesystem().getBlockManager();
    // trigger NN handel heartbeat
    BlockManagerTestUtil.checkHeartbeat(bm);
    // NN now should have latest volume failure
    assertEquals(1, cluster.getNamesystem().getVolumeFailuresTotal());

    // verify number of blocks and files...
    verify(filename, filesize);
    
    // create another file (with one volume failed).
    System.out.println("creating file test1.txt");
    Path fileName1 = new Path("/test1.txt");
    DFSTestUtil.createFile(fs, fileName1, filesize, repl, 1L);
    
    // should be able to replicate to both nodes (2 DN, repl=2)
    DFSTestUtil.waitReplication(fs, fileName1, repl);
    System.out.println("file " + fileName1.getName() + 
        " is created and replicated");
  }

  /*
   * If one of the sub-folders under the finalized directory is unreadable,
   * either due to permissions or a filesystem corruption, the DN will fail
   * to read it when scanning it for blocks to load into the replica map. This
   * test ensures the DN does not exit and reports the failed volume to the
   * NN (HDFS-14333). This is done by using a simulated FsDataset that throws
   * an exception for a failed volume when the block pool is initialized.
   */
  @Test(timeout=15000)
  public void testDnStartsAfterDiskErrorScanningBlockPool() throws Exception {
    // Don't use the cluster configured in the setup() method for this test.
    cluster.shutdown(true);
    cluster.close();

    conf.set(DFSConfigKeys.DFS_DATANODE_FSDATASET_FACTORY_KEY,
        BadDiskFSDataset.Factory.class.getName());

    final MiniDFSCluster localCluster = new MiniDFSCluster
        .Builder(conf).numDataNodes(1).build();

    try {
      localCluster.waitActive();
      DataNode dn = localCluster.getDataNodes().get(0);

      try {
        localCluster.waitDatanodeFullyStarted(dn, 3000);
      } catch (TimeoutException e) {
        fail("Datanode did not get fully started");
      }
      assertTrue(dn.isDatanodeUp());

      // trigger DN to send heartbeat
      DataNodeTestUtils.triggerHeartbeat(dn);
      final BlockManager bm = localCluster.getNamesystem().getBlockManager();
      // trigger NN handle heartbeat
      BlockManagerTestUtil.checkHeartbeat(bm);

      // NN now should have the failed volume
      assertEquals(1, localCluster.getNamesystem().getVolumeFailuresTotal());
    } finally {
      localCluster.close();
    }
  }

  /**
   * Test that DataStorage and BlockPoolSliceStorage remove the failed volume
   * after failure.
   */
  @Test(timeout=150000)
    public void testFailedVolumeBeingRemovedFromDataNode()
      throws Exception {
    // The test uses DataNodeTestUtils#injectDataDirFailure() to simulate
    // volume failures which is currently not supported on Windows.
    assumeNotWindows();

    Path file1 = new Path("/test1");
    DFSTestUtil.createFile(fs, file1, 1024, (short) 2, 1L);
    DFSTestUtil.waitReplication(fs, file1, (short) 2);

    File dn0Vol1 = cluster.getInstanceStorageDir(0, 0);
    DataNodeTestUtils.injectDataDirFailure(dn0Vol1);
    DataNode dn0 = cluster.getDataNodes().get(0);
    DataNodeTestUtils.waitForDiskError(dn0,
        DataNodeTestUtils.getVolume(dn0, dn0Vol1));

    // Verify dn0Vol1 has been completely removed from DN0.
    // 1. dn0Vol1 is removed from DataStorage.
    DataStorage storage = dn0.getStorage();
    assertEquals(1, storage.getNumStorageDirs());
    for (int i = 0; i < storage.getNumStorageDirs(); i++) {
      Storage.StorageDirectory sd = storage.getStorageDir(i);
      assertFalse(sd.getRoot().getAbsolutePath().startsWith(
          dn0Vol1.getAbsolutePath()
      ));
    }
    final String bpid = cluster.getNamesystem().getBlockPoolId();
    BlockPoolSliceStorage bpsStorage = storage.getBPStorage(bpid);
    assertEquals(1, bpsStorage.getNumStorageDirs());
    for (int i = 0; i < bpsStorage.getNumStorageDirs(); i++) {
      Storage.StorageDirectory sd = bpsStorage.getStorageDir(i);
      assertFalse(sd.getRoot().getAbsolutePath().startsWith(
          dn0Vol1.getAbsolutePath()
      ));
    }

    // 2. dn0Vol1 is removed from FsDataset
    FsDatasetSpi<? extends FsVolumeSpi> data = dn0.getFSDataset();
    try (FsDatasetSpi.FsVolumeReferences vols = data.getFsVolumeReferences()) {
      for (FsVolumeSpi volume : vols) {
        assertFalse(new File(volume.getStorageLocation().getUri())
            .getAbsolutePath().startsWith(dn0Vol1.getAbsolutePath()
        ));
      }
    }

    // 3. all blocks on dn0Vol1 have been removed.
    for (ReplicaInfo replica : FsDatasetTestUtil.getReplicas(data, bpid)) {
      assertNotNull(replica.getVolume());
      assertFalse(new File(replica.getVolume().getStorageLocation().getUri())
          .getAbsolutePath().startsWith(dn0Vol1.getAbsolutePath()
      ));
    }

    // 4. dn0Vol1 is not in DN0's configuration and dataDirs anymore.
    String[] dataDirStrs =
        dn0.getConf().get(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY).split(",");
    assertEquals(1, dataDirStrs.length);
    assertFalse(dataDirStrs[0].contains(dn0Vol1.getAbsolutePath()));
  }

  /**
   * Test DataNode stops when the number of failed volumes exceeds
   * dfs.datanode.failed.volumes.tolerated .
   */
  @Test(timeout=10000)
  public void testDataNodeShutdownAfterNumFailedVolumeExceedsTolerated()
      throws Exception {
    // The test uses DataNodeTestUtils#injectDataDirFailure() to simulate
    // volume failures which is currently not supported on Windows.
    assumeNotWindows();

    // make both data directories to fail on dn0
    final File dn0Vol1 = cluster.getInstanceStorageDir(0, 0);
    final File dn0Vol2 = cluster.getInstanceStorageDir(0, 1);
    DataNodeTestUtils.injectDataDirFailure(dn0Vol1, dn0Vol2);
    DataNode dn0 = cluster.getDataNodes().get(0);
    DataNodeTestUtils.waitForDiskError(dn0,
        DataNodeTestUtils.getVolume(dn0, dn0Vol1));
    DataNodeTestUtils.waitForDiskError(dn0,
        DataNodeTestUtils.getVolume(dn0, dn0Vol2));

    // DN0 should stop after the number of failure disks exceed tolerated
    // value (1).
    dn0.checkDiskError();
    assertFalse(dn0.shouldRun());
  }

  /**
   * Test that DN does not shutdown, as long as failure volumes being hot swapped.
   */
  @Test
  public void testVolumeFailureRecoveredByHotSwappingVolume()
      throws Exception {
    // The test uses DataNodeTestUtils#injectDataDirFailure() to simulate
    // volume failures which is currently not supported on Windows.
    assumeNotWindows();

    final File dn0Vol1 = cluster.getInstanceStorageDir(0, 0);
    final File dn0Vol2 = cluster.getInstanceStorageDir(0, 1);
    final DataNode dn0 = cluster.getDataNodes().get(0);
    final String oldDataDirs = dn0.getConf().get(
        DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY);

    // Fail dn0Vol1 first.
    DataNodeTestUtils.injectDataDirFailure(dn0Vol1);
    DataNodeTestUtils.waitForDiskError(dn0,
        DataNodeTestUtils.getVolume(dn0, dn0Vol1));

    // Hot swap out the failure volume.
    String dataDirs = dn0Vol2.getPath();
    assertThat(
        dn0.reconfigurePropertyImpl(
            DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY, dataDirs),
        is(dn0.getConf().get(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY)));

    // Fix failure volume dn0Vol1 and remount it back.
    DataNodeTestUtils.restoreDataDirFromFailure(dn0Vol1);
    assertThat(
        dn0.reconfigurePropertyImpl(
            DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY, oldDataDirs),
        is(dn0.getConf().get(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY)));

    // Fail dn0Vol2. Now since dn0Vol1 has been fixed, DN0 has sufficient
    // resources, thus it should keep running.
    DataNodeTestUtils.injectDataDirFailure(dn0Vol2);
    DataNodeTestUtils.waitForDiskError(dn0,
        DataNodeTestUtils.getVolume(dn0, dn0Vol2));
    assertTrue(dn0.shouldRun());
  }

  /**
   * Test {@link DataNode#refreshVolumes(String)} not deadLock with
   * {@link BPOfferService#registrationSucceeded(BPServiceActor,
   * DatanodeRegistration)}.
   */
  @Test(timeout=10000)
  public void testRefreshDeadLock() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    DataNodeFaultInjector.set(new DataNodeFaultInjector() {
      public void delayWhenOfferServiceHoldLock() {
        try {
          latch.await();
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    });

    DataNode dn = cluster.getDataNodes().get(0);
    File volume = cluster.getInstanceStorageDir(0, 0);
    String dataDirs = volume.getPath();
    List<BPOfferService> allBpOs = dn.getAllBpOs();
    BPOfferService service = allBpOs.get(0);
    BPServiceActor actor = service.getBPServiceActors().get(0);
    DatanodeRegistration bpRegistration = actor.getBpRegistration();

    Thread register = new Thread(() -> {
      try {
        service.registrationSucceeded(actor, bpRegistration);
      } catch (IOException e) {
        e.printStackTrace();
      }
    });

    register.start();
    String newdir = dataDirs + "tmp";
    // Make sure service have get writelock
    latch.countDown();
    String result = dn.reconfigurePropertyImpl(
        DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY, newdir);
    assertNotNull(result);
  }

  /**
   * Test changing the number of volumes does not impact the disk failure
   * tolerance.
   */
  @Test
  public void testTolerateVolumeFailuresAfterAddingMoreVolumes()
      throws Exception {
    // The test uses DataNodeTestUtils#injectDataDirFailure() to simulate
    // volume failures which is currently not supported on Windows.
    assumeNotWindows();

    final File dn0Vol1 = cluster.getInstanceStorageDir(0, 0);
    final File dn0Vol2 = cluster.getInstanceStorageDir(0, 1);
    final File dn0VolNew = new File(dataDir, "data_new");
    final DataNode dn0 = cluster.getDataNodes().get(0);
    final String oldDataDirs = dn0.getConf().get(
        DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY);

    // Add a new volume to DN0
    assertThat(
        dn0.reconfigurePropertyImpl(
            DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY,
            oldDataDirs + "," + dn0VolNew.getAbsolutePath()),
        is(dn0.getConf().get(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY)));

    // Fail dn0Vol1 first and hot swap it.
    DataNodeTestUtils.injectDataDirFailure(dn0Vol1);
    DataNodeTestUtils.waitForDiskError(dn0,
        DataNodeTestUtils.getVolume(dn0, dn0Vol1));
    assertTrue(dn0.shouldRun());

    // Fail dn0Vol2, now dn0 should stop, because we only tolerate 1 disk failure.
    DataNodeTestUtils.injectDataDirFailure(dn0Vol2);
    DataNodeTestUtils.waitForDiskError(dn0,
        DataNodeTestUtils.getVolume(dn0, dn0Vol2));
    dn0.checkDiskError();
    assertFalse(dn0.shouldRun());
  }

  /**
   * Test that there are under replication blocks after vol failures
   */
  @Test
  public void testUnderReplicationAfterVolFailure() throws Exception {
    // The test uses DataNodeTestUtils#injectDataDirFailure() to simulate
    // volume failures which is currently not supported on Windows.
    assumeNotWindows();

    // Bring up one more datanode
    cluster.startDataNodes(conf, 1, true, null, null);
    cluster.waitActive();

    final BlockManager bm = cluster.getNamesystem().getBlockManager();

    Path file1 = new Path("/test1");
    DFSTestUtil.createFile(fs, file1, 1024, (short)3, 1L);
    DFSTestUtil.waitReplication(fs, file1, (short)3);

    // Fail the first volume on both datanodes
    File dn1Vol1 = cluster.getInstanceStorageDir(0, 0);
    File dn2Vol1 = cluster.getInstanceStorageDir(1, 0);
    DataNodeTestUtils.injectDataDirFailure(dn1Vol1, dn2Vol1);

    Path file2 = new Path("/test2");
    DFSTestUtil.createFile(fs, file2, 1024, (short)3, 1L);
    DFSTestUtil.waitReplication(fs, file2, (short)3);

    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        // underReplicatedBlocks are due to failed volumes
        long underReplicatedBlocks = bm.getLowRedundancyBlocksCount()
            + bm.getPendingReconstructionBlocksCount();
        if (underReplicatedBlocks > 0) {
          return true;
        }
        LOG.info("There is no under replicated block after volume failure.");

        return false;
      }
    }, 500, 60000);
  }

  /**
   * Test if there is volume failure, the DataNode will fail to start.
   *
   * We fail a volume by setting the parent directory non-writable.
   */
  @Test (timeout = 120000)
  public void testDataNodeFailToStartWithVolumeFailure() throws Exception {
    // Method to simulate volume failures is currently not supported on Windows.
    assumeNotWindows();

    failedDir = new File(dataDir, "failedDir");
    assertTrue("Failed to fail a volume by setting it non-writable",
        failedDir.mkdir() && failedDir.setReadOnly());

    startNewDataNodeWithDiskFailure(new File(failedDir, "newDir1"), false);
  }

  /**
   * DataNode will start and tolerate one failing disk according to config.
   *
   * We fail a volume by setting the parent directory non-writable.
   */
  @Test (timeout = 120000)
  public void testDNStartAndTolerateOneVolumeFailure() throws Exception {
    // Method to simulate volume failures is currently not supported on Windows.
    assumeNotWindows();

    failedDir = new File(dataDir, "failedDir");
    assertTrue("Failed to fail a volume by setting it non-writable",
        failedDir.mkdir() && failedDir.setReadOnly());

    startNewDataNodeWithDiskFailure(new File(failedDir, "newDir1"), true);
  }

  /**
   * Test if data directory is not readable/writable, DataNode won't start.
   */
  @Test (timeout = 120000)
  public void testDNFailToStartWithDataDirNonWritable() throws Exception {
    // Method to simulate volume failures is currently not supported on Windows.
    assumeNotWindows();

    final File readOnlyDir = new File(dataDir, "nonWritable");
    assertTrue("Set the data dir permission non-writable",
        readOnlyDir.mkdir() && readOnlyDir.setReadOnly());

    startNewDataNodeWithDiskFailure(new File(readOnlyDir, "newDir1"), false);
  }

  /**
   * DataNode will start and tolerate one non-writable data directory
   * according to config.
   */
  @Test (timeout = 120000)
  public void testDNStartAndTolerateOneDataDirNonWritable() throws Exception {
    // Method to simulate volume failures is currently not supported on Windows.
    assumeNotWindows();

    final File readOnlyDir = new File(dataDir, "nonWritable");
    assertTrue("Set the data dir permission non-writable",
        readOnlyDir.mkdir() && readOnlyDir.setReadOnly());
    startNewDataNodeWithDiskFailure(new File(readOnlyDir, "newDir1"), true);
  }

  /**
   * @param badDataDir bad data dir, either disk failure or non-writable
   * @param tolerated true if one volume failure is allowed else false
   */
  private void startNewDataNodeWithDiskFailure(File badDataDir,
      boolean tolerated) throws Exception {
    final File data5 = new File(dataDir, "data5");
    final String newDirs = badDataDir.toString() + "," + data5.toString();
    final Configuration newConf = new Configuration(conf);
    newConf.set(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY, newDirs);
    LOG.info("Setting dfs.datanode.data.dir for new DataNode as {}", newDirs);
    newConf.setInt(DFSConfigKeys.DFS_DATANODE_FAILED_VOLUMES_TOLERATED_KEY,
        tolerated ? 1 : 0);

    // bring up one more DataNode
    assertEquals(repl, cluster.getDataNodes().size());

    try {
      cluster.startDataNodes(newConf, 1, false, null, null);
      assertTrue("Failed to get expected IOException", tolerated);
    } catch (IOException ioe) {
      assertFalse("Unexpected IOException " + ioe, tolerated);
      return;
    }

    assertEquals(repl + 1, cluster.getDataNodes().size());

    // create new file and it should be able to replicate to 3 nodes
    final Path p = new Path("/test1.txt");
    DFSTestUtil.createFile(fs, p, block_size * blocks_num, (short) 3, 1L);
    DFSTestUtil.waitReplication(fs, p, (short) (repl + 1));
  }

  /**
   * verifies two things:
   *  1. number of locations of each block in the name node
   *   matches number of actual files
   *  2. block files + pending block equals to total number of blocks that a file has 
   *     including the replication (HDFS file has 30 blocks, repl=2 - total 60
   * @param fn - file name
   * @param fs - file size
   * @throws IOException
   */
  private void verify(String fn, int fs) throws IOException{
    // now count how many physical blocks are there
    int totalReal = countRealBlocks(block_map);
    System.out.println("countRealBlocks counted " + totalReal + " blocks");

    // count how many blocks store in NN structures.
    int totalNN = countNNBlocks(block_map, fn, fs);
    System.out.println("countNNBlocks counted " + totalNN + " blocks");

    for(String bid : block_map.keySet()) {
      BlockLocs bl = block_map.get(bid);
      // System.out.println(bid + "->" + bl.num_files + "vs." + bl.num_locs);
      // number of physical files (1 or 2) should be same as number of datanodes
      // in the list of the block locations
      assertEquals("Num files should match num locations",
          bl.num_files, bl.num_locs);
    }
    assertEquals("Num physical blocks should match num stored in the NN",
        totalReal, totalNN);

    // now check the number of under-replicated blocks
    FSNamesystem fsn = cluster.getNamesystem();
    // force update of all the metric counts by calling computeDatanodeWork
    BlockManagerTestUtil.getComputedDatanodeWork(fsn.getBlockManager());
    // get all the counts 
    long underRepl = fsn.getUnderReplicatedBlocks();
    long pendRepl = fsn.getPendingReplicationBlocks();
    long totalRepl = underRepl + pendRepl;
    System.out.println("underreplicated after = "+ underRepl + 
        " and pending repl ="  + pendRepl + "; total underRepl = " + totalRepl);

    System.out.println("total blocks (real and replicating):" + 
        (totalReal + totalRepl) + " vs. all files blocks " + blocks_num*2);

    // together all the blocks should be equal to all real + all underreplicated
    assertEquals("Incorrect total block count",
        totalReal + totalRepl, blocks_num * repl);
  }
  
  /**
   * go to each block on the 2nd DataNode until it fails...
   * @param path
   * @param size
   * @throws IOException
   */
  private void triggerFailure(String path, long size) throws IOException {
    NamenodeProtocols nn = cluster.getNameNodeRpc();
    List<LocatedBlock> locatedBlocks =
      nn.getBlockLocations(path, 0, size).getLocatedBlocks();
    
    for (LocatedBlock lb : locatedBlocks) {
      DatanodeInfo dinfo = lb.getLocations()[1];
      ExtendedBlock b = lb.getBlock();
      try {
        accessBlock(dinfo, lb);
      } catch (IOException e) {
        System.out.println("Failure triggered, on block: " + b.getBlockId() +  
            "; corresponding volume should be removed by now");
        break;
      }
    }
  }
  
  /**
   * simulate failure delete all the block files
   * @param dir
   * @throws IOException
   */
  private boolean deteteBlocks(File dir) {
    Collection<File> fileList = FileUtils.listFiles(dir,
        TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
    for(File f : fileList) {
      if(f.getName().startsWith(Block.BLOCK_FILE_PREFIX)) {
        System.out.println("Deleting file " + f);
        if(!f.delete())
          return false;
        
      }
    }
    return true;
  }
  
  /**
   * try to access a block on a data node. If fails - throws exception
   * @param datanode
   * @param lblock
   * @throws IOException
   */
  private void accessBlock(DatanodeInfo datanode, LocatedBlock lblock)
    throws IOException {
    InetSocketAddress targetAddr = null;
    ExtendedBlock block = lblock.getBlock(); 
   
    targetAddr = NetUtils.createSocketAddr(datanode.getXferAddr());

    BlockReader blockReader = new BlockReaderFactory(new DfsClientConf(conf)).
      setInetSocketAddress(targetAddr).
      setBlock(block).
      setFileName(BlockReaderFactory.getFileName(targetAddr,
                    "test-blockpoolid", block.getBlockId())).
      setBlockToken(lblock.getBlockToken()).
      setStartOffset(0).
      setLength(0).
      setVerifyChecksum(true).
      setClientName("TestDataNodeVolumeFailure").
      setDatanodeInfo(datanode).
      setCachingStrategy(CachingStrategy.newDefaultStrategy()).
      setClientCacheContext(ClientContext.getFromConf(conf)).
      setConfiguration(conf).
      setRemotePeerFactory(new RemotePeerFactory() {
        @Override
        public Peer newConnectedPeer(InetSocketAddress addr,
            Token<BlockTokenIdentifier> blockToken, DatanodeID datanodeId)
            throws IOException {
          Peer peer = null;
          Socket sock = NetUtils.getDefaultSocketFactory(conf).createSocket();
          try {
            sock.connect(addr, HdfsConstants.READ_TIMEOUT);
            sock.setSoTimeout(HdfsConstants.READ_TIMEOUT);
            peer = DFSUtilClient.peerFromSocket(sock);
          } finally {
            if (peer == null) {
              IOUtils.closeSocket(sock);
            }
          }
          return peer;
        }
      }).
      build();
    blockReader.close();
  }
  
  /**
   * Count datanodes that have copies of the blocks for a file
   * put it into the map
   * @param map
   * @param path
   * @param size
   * @return
   * @throws IOException
   */
  private int countNNBlocks(Map<String, BlockLocs> map, String path, long size) 
    throws IOException {
    int total = 0;
    
    NamenodeProtocols nn = cluster.getNameNodeRpc();
    List<LocatedBlock> locatedBlocks = 
      nn.getBlockLocations(path, 0, size).getLocatedBlocks();
    //System.out.println("Number of blocks: " + locatedBlocks.size()); 
        
    for(LocatedBlock lb : locatedBlocks) {
      String blockId = ""+lb.getBlock().getBlockId();
      //System.out.print(blockId + ": ");
      DatanodeInfo[] dn_locs = lb.getLocations();
      BlockLocs bl = map.get(blockId);
      if(bl == null) {
        bl = new BlockLocs();
      }
      //System.out.print(dn_info.name+",");
      total += dn_locs.length;        
      bl.num_locs += dn_locs.length;
      map.put(blockId, bl);
      //System.out.println();
    }
    return total;
  }
  
  /**
   *  look for real blocks
   *  by counting *.meta files in all the storage dirs 
   * @param map
   * @return
   */
  private int countRealBlocks(Map<String, BlockLocs> map) {
    int total = 0;
    final String bpid = cluster.getNamesystem().getBlockPoolId();
    for(int i=0; i<dn_num; i++) {
      for(int j=0; j<=1; j++) {
        File storageDir = cluster.getInstanceStorageDir(i, j);
        File dir = MiniDFSCluster.getFinalizedDir(storageDir, bpid);
        if(dir == null) {
          System.out.println("dir is null for dn=" + i + " and data_dir=" + j);
          continue;
        }
      
        List<File> res = MiniDFSCluster.getAllBlockMetadataFiles(dir);
        if(res == null) {
          System.out.println("res is null for dir = " + dir + " i=" + i + " and j=" + j);
          continue;
        }
        //System.out.println("for dn" + i + "." + j + ": " + dir + "=" + res.length+ " files");
      
        //int ii = 0;
        for(File f: res) {
          String s = f.getName();
          // cut off "blk_-" at the beginning and ".meta" at the end
          assertNotNull("Block file name should not be null", s);
          String bid = s.substring(s.indexOf("_")+1, s.lastIndexOf("_"));
          //System.out.println(ii++ + ". block " + s + "; id=" + bid);
          BlockLocs val = map.get(bid);
          if(val == null) {
            val = new BlockLocs();
          }
          val.num_files ++; // one more file for the block
          map.put(bid, val);

        }
        //System.out.println("dir1="+dir.getPath() + "blocks=" + res.length);
        //System.out.println("dir2="+dir2.getPath() + "blocks=" + res2.length);

        total += res.size();
      }
    }
    return total;
  }

  private static class BadDiskFSDataset extends SimulatedFSDataset {

    BadDiskFSDataset(DataStorage storage, Configuration conf) {
      super(storage, conf);
    }

    private String[] failedStorageLocations = null;

    @Override
    public void addBlockPool(String bpid, Configuration conf) {
      super.addBlockPool(bpid, conf);
      Map<FsVolumeSpi, IOException>
          unhealthyDataDirs = new HashMap<>();
      unhealthyDataDirs.put(this.getStorages().get(0).getVolume(),
          new IOException());
      throw new AddBlockPoolException(unhealthyDataDirs);
    }

    @Override
    public synchronized void removeVolumes(Collection<StorageLocation> volumes,
        boolean clearFailure) {
      Iterator<StorageLocation> itr = volumes.iterator();
      String[] failedLocations = new String[volumes.size()];
      int index = 0;
      while(itr.hasNext()) {
        StorageLocation s = itr.next();
        failedLocations[index] = s.getUri().getPath();
        index += 1;
      }
      failedStorageLocations = failedLocations;
    }

    @Override
    public void handleVolumeFailures(Set<FsVolumeSpi> failedVolumes) {
      // do nothing
    }

    @Override
    public VolumeFailureSummary getVolumeFailureSummary() {
      if (failedStorageLocations != null) {
        return new VolumeFailureSummary(failedStorageLocations, 0, 0);
      } else {
        return new VolumeFailureSummary(ArrayUtils.EMPTY_STRING_ARRAY, 0, 0);
      }
    }

    static class Factory extends FsDatasetSpi.Factory<BadDiskFSDataset> {
      @Override
      public BadDiskFSDataset newInstance(DataNode datanode,
          DataStorage storage, Configuration conf) throws IOException {
        return new BadDiskFSDataset(storage, conf);
      }

      @Override
      public boolean isSimulated() {
        return true;
      }
    }
  }

  /*
   * Verify the failed volume can be cheched during dn startup
   */
  @Test(timeout = 120000)
  public void testVolumeFailureDuringStartup() throws Exception {
    LOG.debug("Data dir: is " +  dataDir.getPath());

    // fail the volume
    data_fail = cluster.getInstanceStorageDir(1, 0);
    failedDir = MiniDFSCluster.getFinalizedDir(data_fail,
        cluster.getNamesystem().getBlockPoolId());
    failedDir.setReadOnly();

    // restart the dn
    cluster.restartDataNode(1);
    final DataNode dn = cluster.getDataNodes().get(1);

    // should get the failed volume during startup
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        return dn.getFSDataset() !=null &&
            dn.getFSDataset().getVolumeFailureSummary() != null &&
            dn.getFSDataset().getVolumeFailureSummary().
                getFailedStorageLocations()!= null &&
            dn.getFSDataset().getVolumeFailureSummary().
                getFailedStorageLocations().length == 1;
      }
    }, 10, 30 * 1000);
  }

  /*
   * Fail two volumes, and check the metrics of VolumeFailures
   */
  @Test
  public void testVolumeFailureTwo() throws Exception {
    // fail two volumes
    data_fail = cluster.getInstanceStorageDir(1, 0);
    failedDir = MiniDFSCluster.getFinalizedDir(data_fail,
            cluster.getNamesystem().getBlockPoolId());
    failedDir.setReadOnly();
    data_fail = cluster.getInstanceStorageDir(1, 1);
    failedDir = MiniDFSCluster.getFinalizedDir(data_fail,
            cluster.getNamesystem().getBlockPoolId());
    failedDir.setReadOnly();

    final DataNode dn = cluster.getDataNodes().get(1);
    dn.checkDiskError();

    MetricsRecordBuilder rb = getMetrics(dn.getMetrics().name());
    long volumeFailures = getLongCounter("VolumeFailures", rb);
    assertEquals(2, volumeFailures);
  }
}
