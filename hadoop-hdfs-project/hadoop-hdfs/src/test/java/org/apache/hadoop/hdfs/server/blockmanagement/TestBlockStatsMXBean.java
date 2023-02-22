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

import static org.apache.hadoop.test.PlatformAssumptions.assumeNotWindows;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.datanode.DataNodeTestUtils;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.rules.Timeout;

/**
 * Class for testing {@link BlockStatsMXBean} implementation
 */
public class TestBlockStatsMXBean {

  private MiniDFSCluster cluster;

  @Rule
  public Timeout globalTimeout = new Timeout(300000);

  @Before
  public void setup() throws IOException {
    HdfsConfiguration conf = new HdfsConfiguration();
    conf.setTimeDuration(DFSConfigKeys.DFS_DATANODE_DISK_CHECK_MIN_GAP_KEY,
        0, TimeUnit.MILLISECONDS);
    cluster = null;
    StorageType[][] types = new StorageType[6][];
    for (int i=0; i<3; i++) {
      types[i] = new StorageType[] {StorageType.RAM_DISK, StorageType.DISK};
    }
    for (int i=3; i< 5; i++) {
      types[i] = new StorageType[] {StorageType.RAM_DISK, StorageType.ARCHIVE};
    }
    types[5] = new StorageType[] {StorageType.RAM_DISK, StorageType.ARCHIVE,
        StorageType.ARCHIVE};

    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(6).
        storageTypes(types).storagesPerDatanode(3).build();
    cluster.waitActive();
  }

  @After
  public void tearDown() {
    if (cluster != null) {
      cluster.shutdown();
      cluster = null;
    }
  }

  @Test
  public void testStorageTypeStats() throws Exception {
    Map<StorageType, StorageTypeStats> storageTypeStatsMap =
        cluster.getNamesystem().getBlockManager().getStorageTypeStats();
    assertTrue(storageTypeStatsMap.containsKey(StorageType.RAM_DISK));
    assertTrue(storageTypeStatsMap.containsKey(StorageType.DISK));
    assertTrue(storageTypeStatsMap.containsKey(StorageType.ARCHIVE));

    StorageTypeStats storageTypeStats =
        storageTypeStatsMap.get(StorageType.RAM_DISK);
    assertEquals(6, storageTypeStats.getNodesInService());

    storageTypeStats = storageTypeStatsMap.get(StorageType.DISK);
    assertEquals(3, storageTypeStats.getNodesInService());

    storageTypeStats = storageTypeStatsMap.get(StorageType.ARCHIVE);
    assertEquals(3, storageTypeStats.getNodesInService());
  }

  protected static String readOutput(URL url) throws IOException {
    StringBuilder out = new StringBuilder();
    InputStream in = url.openConnection().getInputStream();
    byte[] buffer = new byte[64 * 1024];
    int len = in.read(buffer);
    while (len > 0) {
      out.append(new String(buffer, 0, len));
      len = in.read(buffer);
    }
    return out.toString();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testStorageTypeStatsJMX() throws Exception {
    URL baseUrl = new URL (cluster.getHttpUri(0));
    String result = readOutput(new URL(baseUrl, "/jmx"));

    Map<String, Object> stat = (Map<String, Object>) JSON.parse(result);
    Object[] beans =(Object[]) stat.get("beans");
    Map<String, Object> blockStats  = null;
    for (Object bean : beans) {
      Map<String, Object> map = (Map<String, Object>) bean;
      if (map.get("name").equals("Hadoop:service=NameNode,name=BlockStats")) {
        blockStats = map;
      }
    }
    assertNotNull(blockStats);
    Object[] storageTypeStatsList =
        (Object[])blockStats.get("StorageTypeStats");
    assertNotNull(storageTypeStatsList);
    assertEquals (3, storageTypeStatsList.length);

    Set<String> typesPresent = new HashSet<> ();
    for (Object obj : storageTypeStatsList) {
      Map<String, Object> entry = (Map<String, Object>)obj;
      String storageType = (String)entry.get("key");
      Map<String,Object> storageTypeStats = (Map<String,Object>)entry.get("value");
      typesPresent.add(storageType);
      if (storageType.equals("ARCHIVE") || storageType.equals("DISK") ) {
        assertEquals(3l, storageTypeStats.get("nodesInService"));
      } else if (storageType.equals("RAM_DISK")) {
        assertEquals(6l, storageTypeStats.get("nodesInService"));
      }
      else {
        fail();
      }
    }

    assertTrue(typesPresent.contains("ARCHIVE"));
    assertTrue(typesPresent.contains("DISK"));
    assertTrue(typesPresent.contains("RAM_DISK"));
  }

  @Test
  public void testStorageTypeStatsWhenStorageFailed() throws Exception {
    // The test uses DataNodeTestUtils#injectDataDirFailure() to simulate
    // volume failures which is currently not supported on Windows.
    assumeNotWindows();

    DFSTestUtil.createFile(cluster.getFileSystem(),
        new Path("/blockStatsFile1"), 1024, (short) 1, 0L);
    Map<StorageType, StorageTypeStats> storageTypeStatsMap = cluster
        .getNamesystem().getBlockManager().getStorageTypeStats();

    StorageTypeStats storageTypeStats = storageTypeStatsMap
        .get(StorageType.RAM_DISK);
    assertEquals(6, storageTypeStats.getNodesInService());

    storageTypeStats = storageTypeStatsMap.get(StorageType.DISK);
    assertEquals(3, storageTypeStats.getNodesInService());

    storageTypeStats = storageTypeStatsMap.get(StorageType.ARCHIVE);
    assertEquals(3, storageTypeStats.getNodesInService());
    File dn1ArcVol1 = cluster.getInstanceStorageDir(0, 1);
    File dn2ArcVol1 = cluster.getInstanceStorageDir(1, 1);
    File dn3ArcVol1 = cluster.getInstanceStorageDir(2, 1);
    DataNodeTestUtils.injectDataDirFailure(dn1ArcVol1);
    DataNodeTestUtils.injectDataDirFailure(dn2ArcVol1);
    DataNodeTestUtils.injectDataDirFailure(dn3ArcVol1);
    try {
      DFSTestUtil.createFile(cluster.getFileSystem(), new Path(
          "/blockStatsFile2"), 1024, (short) 1, 0L);
      fail("Should throw exception, becuase no DISK storage available");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains(
          "could only be written to 0 of the 1 minReplication"));
    }
    // wait for heartbeat
    Thread.sleep(6000);
    storageTypeStatsMap = cluster.getNamesystem().getBlockManager()
        .getStorageTypeStats();
    assertFalse("StorageTypeStatsMap should not contain DISK Storage type",
        storageTypeStatsMap.containsKey(StorageType.DISK));
    DataNodeTestUtils.restoreDataDirFromFailure(dn1ArcVol1);
    DataNodeTestUtils.restoreDataDirFromFailure(dn2ArcVol1);
    DataNodeTestUtils.restoreDataDirFromFailure(dn3ArcVol1);
    for (int i = 0; i < 3; i++) {
      cluster.restartDataNode(0, true);
    }
    // wait for heartbeat
    Thread.sleep(6000);
    storageTypeStatsMap = cluster.getNamesystem().getBlockManager()
        .getStorageTypeStats();
    storageTypeStats = storageTypeStatsMap.get(StorageType.RAM_DISK);
    assertEquals(6, storageTypeStats.getNodesInService());

    storageTypeStats = storageTypeStatsMap.get(StorageType.DISK);
    assertEquals(3, storageTypeStats.getNodesInService());

    storageTypeStats = storageTypeStatsMap.get(StorageType.ARCHIVE);
    assertEquals(3, storageTypeStats.getNodesInService());
  }

  @Test
  public void testStorageTypeLoad() throws Exception {
    HeartbeatManager heartbeatManager =
        cluster.getNamesystem().getBlockManager().getDatanodeManager()
            .getHeartbeatManager();
    Map<StorageType, StorageTypeStats> storageTypeStatsMap =
        heartbeatManager.getStorageTypeStats();
    DistributedFileSystem dfs = cluster.getFileSystem();

    // Create a file with HOT storage policy.
    Path hotSpDir = new Path("/HOT");
    dfs.mkdir(hotSpDir, FsPermission.getDirDefault());
    dfs.setStoragePolicy(hotSpDir, "HOT");
    FSDataOutputStream hotSpFileStream =
        dfs.create(new Path(hotSpDir, "hotFile"));
    hotSpFileStream.write("Storage Policy Hot".getBytes());
    hotSpFileStream.hflush();

    // Create a file with COLD storage policy.
    Path coldSpDir = new Path("/COLD");
    dfs.mkdir(coldSpDir, FsPermission.getDirDefault());
    dfs.setStoragePolicy(coldSpDir, "COLD");
    FSDataOutputStream coldSpFileStream =
        dfs.create(new Path(coldSpDir, "coldFile"));
    coldSpFileStream.write("Writing to ARCHIVE storage type".getBytes());
    coldSpFileStream.hflush();

    // Trigger heartbeats manually to speed up the test.
    cluster.triggerHeartbeats();

    // The load would be 2*replication since both the
    // write xceiver & packet responder threads are counted.
    GenericTestUtils.waitFor(() -> storageTypeStatsMap.get(StorageType.DISK)
        .getNodesInServiceXceiverCount() == 6, 100, 5000);

    // The count for ARCHIVE should be independent of the value of DISK.
    GenericTestUtils.waitFor(() -> storageTypeStatsMap.get(StorageType.ARCHIVE)
        .getNodesInServiceXceiverCount() == 6, 100, 5000);

    // The total count should stay unaffected, that is sum of load from all
    // datanodes.
    GenericTestUtils
        .waitFor(() -> heartbeatManager.getInServiceXceiverCount() == 12, 100,
            5000);
    IOUtils.closeStreams(hotSpFileStream, coldSpFileStream);
  }
}
