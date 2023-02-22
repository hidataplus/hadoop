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
package org.apache.hadoop.hdfs.shortcircuit;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_BLOCK_SIZE_KEY;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_CONTEXT;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_DOMAIN_SOCKET_DATA_TRAFFIC;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DOMAIN_SOCKET_PATH_KEY;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_SOCKET_TIMEOUT_KEY;
import static org.hamcrest.CoreMatchers.equalTo;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import net.jcip.annotations.NotThreadSafe;
import org.apache.commons.collections.map.LinkedMap;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.ClientContext;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DFSUtilClient;
import org.apache.hadoop.hdfs.PeerCache;
import org.apache.hadoop.hdfs.client.impl.BlockReaderFactory;
import org.apache.hadoop.hdfs.client.impl.BlockReaderTestUtil;
import org.apache.hadoop.hdfs.DFSInputStream;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.ExtendedBlockId;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.client.HdfsClientConfigKeys;
import org.apache.hadoop.hdfs.client.impl.DfsClientConf;
import org.apache.hadoop.hdfs.net.DomainPeer;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo.DatanodeInfoBuilder;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.server.datanode.BlockMetadataHeader;
import org.apache.hadoop.hdfs.server.datanode.DataNodeFaultInjector;
import org.apache.hadoop.hdfs.server.datanode.ShortCircuitRegistry;
import org.apache.hadoop.hdfs.server.datanode.ShortCircuitRegistry.RegisteredShm;
import org.apache.hadoop.hdfs.shortcircuit.DfsClientShmManager.PerDatanodeVisitorInfo;
import org.apache.hadoop.hdfs.shortcircuit.DfsClientShmManager.Visitor;
import org.apache.hadoop.hdfs.shortcircuit.ShortCircuitCache.CacheVisitor;
import org.apache.hadoop.hdfs.shortcircuit.ShortCircuitCache.ShortCircuitReplicaCreator;
import org.apache.hadoop.hdfs.shortcircuit.ShortCircuitShm.ShmId;
import org.apache.hadoop.hdfs.shortcircuit.ShortCircuitShm.Slot;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.ipc.RetriableException;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.unix.DomainSocket;
import org.apache.hadoop.net.unix.TemporarySocketDirectory;
import org.apache.hadoop.security.token.SecretManager.InvalidToken;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.Time;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import org.apache.hadoop.thirdparty.com.google.common.base.Preconditions;
import java.util.function.Supplier;
import org.apache.hadoop.thirdparty.com.google.common.collect.HashMultimap;

@NotThreadSafe
public class TestShortCircuitCache {
  static final Logger LOG =
      LoggerFactory.getLogger(TestShortCircuitCache.class);
  
  private static class TestFileDescriptorPair {
    final TemporarySocketDirectory dir = new TemporarySocketDirectory();
    final FileInputStream[] fis;

    public TestFileDescriptorPair() throws IOException {
      fis = new FileInputStream[2];
      for (int i = 0; i < 2; i++) {
        String name = dir.getDir() + "/file" + i;
        FileOutputStream fos = new FileOutputStream(name);
        if (i == 0) {
          // write 'data' file
          fos.write(1);
        } else {
          // write 'metadata' file
          BlockMetadataHeader header =
              new BlockMetadataHeader((short)1,
                  DataChecksum.newDataChecksum(DataChecksum.Type.NULL, 4));
          DataOutputStream dos = new DataOutputStream(fos);
          BlockMetadataHeader.writeHeader(dos, header);
          dos.close();
        }
        fos.close();
        fis[i] = new FileInputStream(name);
      }
    }

    public FileInputStream[] getFileInputStreams() {
      return fis;
    }

    public void close() throws IOException {
      IOUtils.cleanupWithLogger(LOG, fis);
      dir.close();
    }

    public boolean compareWith(FileInputStream data, FileInputStream meta) {
      return ((data == fis[0]) && (meta == fis[1]));
    }
  }

  private static class SimpleReplicaCreator
      implements ShortCircuitReplicaCreator {
    private final int blockId;
    private final ShortCircuitCache cache;
    private final TestFileDescriptorPair pair;

    SimpleReplicaCreator(int blockId, ShortCircuitCache cache,
        TestFileDescriptorPair pair) {
      this.blockId = blockId;
      this.cache = cache;
      this.pair = pair;
    }

    @Override
    public ShortCircuitReplicaInfo createShortCircuitReplicaInfo() {
      try {
        ExtendedBlockId key = new ExtendedBlockId(blockId, "test_bp1");
        return new ShortCircuitReplicaInfo(
            new ShortCircuitReplica(key,
                pair.getFileInputStreams()[0], pair.getFileInputStreams()[1],
                cache, Time.monotonicNow(), null));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Test(timeout=60000)
  public void testCreateAndDestroy() throws Exception {
    ShortCircuitCache cache =
        new ShortCircuitCache(10, 1, 10, 1, 1, 10000, 0);
    cache.close();
  }
  
  @Test(timeout=60000)
  public void testAddAndRetrieve() throws Exception {
    final ShortCircuitCache cache =
        new ShortCircuitCache(10, 10000000, 10, 10000000, 1, 10000, 0);
    final TestFileDescriptorPair pair = new TestFileDescriptorPair();
    ShortCircuitReplicaInfo replicaInfo1 =
      cache.fetchOrCreate(new ExtendedBlockId(123, "test_bp1"),
        new SimpleReplicaCreator(123, cache, pair));
    Preconditions.checkNotNull(replicaInfo1.getReplica());
    Preconditions.checkState(replicaInfo1.getInvalidTokenException() == null);
    pair.compareWith(replicaInfo1.getReplica().getDataStream(),
                     replicaInfo1.getReplica().getMetaStream());
    ShortCircuitReplicaInfo replicaInfo2 =
      cache.fetchOrCreate(new ExtendedBlockId(123, "test_bp1"),
          new ShortCircuitReplicaCreator() {
        @Override
        public ShortCircuitReplicaInfo createShortCircuitReplicaInfo() {
          Assert.fail("expected to use existing entry.");
          return null;
        }
      });
    Preconditions.checkNotNull(replicaInfo2.getReplica());
    Preconditions.checkState(replicaInfo2.getInvalidTokenException() == null);
    Preconditions.checkState(replicaInfo1 == replicaInfo2);
    pair.compareWith(replicaInfo2.getReplica().getDataStream(),
                     replicaInfo2.getReplica().getMetaStream());
    replicaInfo1.getReplica().unref();
    replicaInfo2.getReplica().unref();
    
    // Even after the reference count falls to 0, we still keep the replica
    // around for a while (we have configured the expiry period to be really,
    // really long here)
    ShortCircuitReplicaInfo replicaInfo3 =
      cache.fetchOrCreate(
          new ExtendedBlockId(123, "test_bp1"), new ShortCircuitReplicaCreator() {
        @Override
        public ShortCircuitReplicaInfo createShortCircuitReplicaInfo() {
          Assert.fail("expected to use existing entry.");
          return null;
        }
      });
    Preconditions.checkNotNull(replicaInfo3.getReplica());
    Preconditions.checkState(replicaInfo3.getInvalidTokenException() == null);
    replicaInfo3.getReplica().unref();
    
    pair.close();
    cache.close();
  }

  @Test(timeout=100000)
  public void testExpiry() throws Exception {
    final ShortCircuitCache cache =
        new ShortCircuitCache(2, 1, 1, 10000000, 1, 10000000, 0);
    final TestFileDescriptorPair pair = new TestFileDescriptorPair();
    ShortCircuitReplicaInfo replicaInfo1 =
      cache.fetchOrCreate(
        new ExtendedBlockId(123, "test_bp1"),
          new SimpleReplicaCreator(123, cache, pair));
    Preconditions.checkNotNull(replicaInfo1.getReplica());
    Preconditions.checkState(replicaInfo1.getInvalidTokenException() == null);
    pair.compareWith(replicaInfo1.getReplica().getDataStream(),
                     replicaInfo1.getReplica().getMetaStream());
    replicaInfo1.getReplica().unref();
    final MutableBoolean triedToCreate = new MutableBoolean(false);
    do {
      Thread.sleep(10);
      ShortCircuitReplicaInfo replicaInfo2 =
        cache.fetchOrCreate(
          new ExtendedBlockId(123, "test_bp1"), new ShortCircuitReplicaCreator() {
          @Override
          public ShortCircuitReplicaInfo createShortCircuitReplicaInfo() {
            triedToCreate.setValue(true);
            return null;
          }
        });
      if ((replicaInfo2 != null) && (replicaInfo2.getReplica() != null)) {
        replicaInfo2.getReplica().unref();
      }
    } while (triedToCreate.isFalse());
    cache.close();
  }
  
  
  @Test(timeout=60000)
  public void testEviction() throws Exception {
    final ShortCircuitCache cache =
        new ShortCircuitCache(2, 10000000, 1, 10000000, 1, 10000, 0);
    final TestFileDescriptorPair pairs[] = new TestFileDescriptorPair[] {
      new TestFileDescriptorPair(),
      new TestFileDescriptorPair(),
      new TestFileDescriptorPair(),
    };
    ShortCircuitReplicaInfo replicaInfos[] = new ShortCircuitReplicaInfo[] {
      null,
      null,
      null
    };
    for (int i = 0; i < pairs.length; i++) {
      replicaInfos[i] = cache.fetchOrCreate(
          new ExtendedBlockId(i, "test_bp1"), 
            new SimpleReplicaCreator(i, cache, pairs[i]));
      Preconditions.checkNotNull(replicaInfos[i].getReplica());
      Preconditions.checkState(replicaInfos[i].getInvalidTokenException() == null);
      pairs[i].compareWith(replicaInfos[i].getReplica().getDataStream(),
                           replicaInfos[i].getReplica().getMetaStream());
    }
    // At this point, we have 3 replicas in use.
    // Let's close them all.
    for (int i = 0; i < pairs.length; i++) {
      replicaInfos[i].getReplica().unref();
    }
    // The last two replicas should still be cached.
    for (int i = 1; i < pairs.length; i++) {
      final Integer iVal = i;
      replicaInfos[i] = cache.fetchOrCreate(
          new ExtendedBlockId(i, "test_bp1"),
            new ShortCircuitReplicaCreator() {
        @Override
        public ShortCircuitReplicaInfo createShortCircuitReplicaInfo() {
          Assert.fail("expected to use existing entry for " + iVal);
          return null;
        }
      });
      Preconditions.checkNotNull(replicaInfos[i].getReplica());
      Preconditions.checkState(replicaInfos[i].getInvalidTokenException() == null);
      pairs[i].compareWith(replicaInfos[i].getReplica().getDataStream(),
                           replicaInfos[i].getReplica().getMetaStream());
    }
    // The first (oldest) replica should not be cached.
    final MutableBoolean calledCreate = new MutableBoolean(false);
    replicaInfos[0] = cache.fetchOrCreate(
        new ExtendedBlockId(0, "test_bp1"),
          new ShortCircuitReplicaCreator() {
        @Override
        public ShortCircuitReplicaInfo createShortCircuitReplicaInfo() {
          calledCreate.setValue(true);
          return null;
        }
      });
    Preconditions.checkState(replicaInfos[0].getReplica() == null);
    Assert.assertTrue(calledCreate.isTrue());
    // Clean up
    for (int i = 1; i < pairs.length; i++) {
      replicaInfos[i].getReplica().unref();
    }
    for (int i = 0; i < pairs.length; i++) {
      pairs[i].close();
    }
    cache.close();
  }
  
  @Test(timeout=60000)
  public void testTimeBasedStaleness() throws Exception {
    // Set up the cache with a short staleness time.
    final ShortCircuitCache cache =
        new ShortCircuitCache(2, 10000000, 1, 10000000, 1, 10, 0);
    final TestFileDescriptorPair pairs[] = new TestFileDescriptorPair[] {
      new TestFileDescriptorPair(),
      new TestFileDescriptorPair(),
    };
    ShortCircuitReplicaInfo replicaInfos[] = new ShortCircuitReplicaInfo[] {
      null,
      null
    };
    final long HOUR_IN_MS = 60 * 60 * 1000;
    for (int i = 0; i < pairs.length; i++) {
      final Integer iVal = i;
      final ExtendedBlockId key = new ExtendedBlockId(i, "test_bp1");
      replicaInfos[i] = cache.fetchOrCreate(key,
          new ShortCircuitReplicaCreator() {
        @Override
        public ShortCircuitReplicaInfo createShortCircuitReplicaInfo() {
          try {
            return new ShortCircuitReplicaInfo(
                new ShortCircuitReplica(key,
                    pairs[iVal].getFileInputStreams()[0],
                    pairs[iVal].getFileInputStreams()[1],
                    cache, Time.monotonicNow() + (iVal * HOUR_IN_MS), null));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      });
      Preconditions.checkNotNull(replicaInfos[i].getReplica());
      Preconditions.checkState(replicaInfos[i].getInvalidTokenException() == null);
      pairs[i].compareWith(replicaInfos[i].getReplica().getDataStream(),
                           replicaInfos[i].getReplica().getMetaStream());
    }

    // Keep trying to getOrCreate block 0 until it goes stale (and we must re-create.)
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        ShortCircuitReplicaInfo info = cache.fetchOrCreate(
          new ExtendedBlockId(0, "test_bp1"), new ShortCircuitReplicaCreator() {
          @Override
          public ShortCircuitReplicaInfo createShortCircuitReplicaInfo() {
            return null;
          }
        });
        if (info.getReplica() != null) {
          info.getReplica().unref();
          return false;
        }
        return true;
      }
    }, 500, 60000);

    // Make sure that second replica did not go stale.
    ShortCircuitReplicaInfo info = cache.fetchOrCreate(
        new ExtendedBlockId(1, "test_bp1"), new ShortCircuitReplicaCreator() {
      @Override
      public ShortCircuitReplicaInfo createShortCircuitReplicaInfo() {
        Assert.fail("second replica went stale, despite 1 " +
            "hour staleness time.");
        return null;
      }
    });
    info.getReplica().unref();

    // Clean up
    for (int i = 1; i < pairs.length; i++) {
      replicaInfos[i].getReplica().unref();
    }
    cache.close();
  }

  private static Configuration createShortCircuitConf(String testName,
      TemporarySocketDirectory sockDir) {
    Configuration conf = new Configuration();
    conf.set(DFS_CLIENT_CONTEXT, testName);
    conf.setLong(DFS_BLOCK_SIZE_KEY, 4096);
    conf.set(DFS_DOMAIN_SOCKET_PATH_KEY, new File(sockDir.getDir(),
        testName).getAbsolutePath());
    conf.setBoolean(HdfsClientConfigKeys.Read.ShortCircuit.KEY, true);
    conf.setBoolean(HdfsClientConfigKeys.Read.ShortCircuit.SKIP_CHECKSUM_KEY,
        false);
    conf.setBoolean(DFS_CLIENT_DOMAIN_SOCKET_DATA_TRAFFIC, false);
    DFSInputStream.tcpReadsDisabledForTesting = true;
    DomainSocket.disableBindPathValidation();
    Assume.assumeThat(DomainSocket.getLoadingFailureReason(), equalTo(null));
    return conf;
  }
  
  private static DomainPeer getDomainPeerToDn(Configuration conf)
      throws IOException {
    DomainSocket sock =
        DomainSocket.connect(conf.get(DFS_DOMAIN_SOCKET_PATH_KEY));
    return new DomainPeer(sock);
  }
  
  @Test(timeout=60000)
  public void testAllocShm() throws Exception {
    BlockReaderTestUtil.enableShortCircuitShmTracing();
    TemporarySocketDirectory sockDir = new TemporarySocketDirectory();
    Configuration conf = createShortCircuitConf("testAllocShm", sockDir);
    MiniDFSCluster cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    cluster.waitActive();
    DistributedFileSystem fs = cluster.getFileSystem();
    final ShortCircuitCache cache =
        fs.getClient().getClientContext().getShortCircuitCache(0);
    cache.getDfsClientShmManager().visit(new Visitor() {
      @Override
      public void visit(HashMap<DatanodeInfo, PerDatanodeVisitorInfo> info)
          throws IOException {
        // The ClientShmManager starts off empty
        Assert.assertEquals(0,  info.size());
      }
    });
    DomainPeer peer = getDomainPeerToDn(conf);
    MutableBoolean usedPeer = new MutableBoolean(false);
    ExtendedBlockId blockId = new ExtendedBlockId(123, "xyz");
    final DatanodeInfo datanode = new DatanodeInfoBuilder()
        .setNodeID(cluster.getDataNodes().get(0).getDatanodeId())
        .build();
    // Allocating the first shm slot requires using up a peer.
    Slot slot = cache.allocShmSlot(datanode, peer, usedPeer,
                    blockId, "testAllocShm_client");
    Assert.assertNotNull(slot);
    Assert.assertTrue(usedPeer.booleanValue());
    cache.getDfsClientShmManager().visit(new Visitor() {
      @Override
      public void visit(HashMap<DatanodeInfo, PerDatanodeVisitorInfo> info)
          throws IOException {
        // The ClientShmManager starts off empty
        Assert.assertEquals(1,  info.size());
        PerDatanodeVisitorInfo vinfo = info.get(datanode);
        Assert.assertFalse(vinfo.disabled);
        Assert.assertEquals(0, vinfo.full.size());
        Assert.assertEquals(1, vinfo.notFull.size());
      }
    });
    cache.scheduleSlotReleaser(slot);
    // Wait for the slot to be released, and the shared memory area to be
    // closed.  Since we didn't register this shared memory segment on the
    // server, it will also be a test of how well the server deals with
    // bogus client behavior.
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        final MutableBoolean done = new MutableBoolean(false);
        try {
          cache.getDfsClientShmManager().visit(new Visitor() {
            @Override
            public void visit(HashMap<DatanodeInfo, PerDatanodeVisitorInfo> info)
                throws IOException {
              done.setValue(info.get(datanode).full.isEmpty() &&
                  info.get(datanode).notFull.isEmpty());
            }
          });
        } catch (IOException e) {
          LOG.error("error running visitor", e);
        }
        return done.booleanValue();
      }
    }, 10, 60000);
    cluster.shutdown();
    sockDir.close();
  }

  @Test(timeout=60000)
  public void testShmBasedStaleness() throws Exception {
    BlockReaderTestUtil.enableShortCircuitShmTracing();
    TemporarySocketDirectory sockDir = new TemporarySocketDirectory();
    Configuration conf = createShortCircuitConf("testShmBasedStaleness", sockDir);
    MiniDFSCluster cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    cluster.waitActive();
    DistributedFileSystem fs = cluster.getFileSystem();
    final ShortCircuitCache cache =
        fs.getClient().getClientContext().getShortCircuitCache(0);
    String TEST_FILE = "/test_file";
    final int TEST_FILE_LEN = 8193;
    final int SEED = 0xFADED;
    DFSTestUtil.createFile(fs, new Path(TEST_FILE), TEST_FILE_LEN,
        (short)1, SEED);
    FSDataInputStream fis = fs.open(new Path(TEST_FILE));
    int first = fis.read();
    final ExtendedBlock block =
        DFSTestUtil.getFirstBlock(fs, new Path(TEST_FILE));
    Assert.assertTrue(first != -1);
    cache.accept(new CacheVisitor() {
      @Override
      public void visit(int numOutstandingMmaps,
          Map<ExtendedBlockId, ShortCircuitReplica> replicas,
          Map<ExtendedBlockId, InvalidToken> failedLoads,
          LinkedMap evictable,
          LinkedMap evictableMmapped) {
        ShortCircuitReplica replica = replicas.get(
            ExtendedBlockId.fromExtendedBlock(block));
        Assert.assertNotNull(replica);
        Assert.assertTrue(replica.getSlot().isValid());
      }
    });
    // Stop the Namenode.  This will close the socket keeping the client's
    // shared memory segment alive, and make it stale.
    cluster.getDataNodes().get(0).shutdown();
    cache.accept(new CacheVisitor() {
      @Override
      public void visit(int numOutstandingMmaps,
          Map<ExtendedBlockId, ShortCircuitReplica> replicas,
          Map<ExtendedBlockId, InvalidToken> failedLoads,
          LinkedMap evictable,
          LinkedMap evictableMmapped) {
        ShortCircuitReplica replica = replicas.get(
            ExtendedBlockId.fromExtendedBlock(block));
        Assert.assertNotNull(replica);
        Assert.assertFalse(replica.getSlot().isValid());
      }
    });
    cluster.shutdown();
    sockDir.close();
  }

  /**
   * Test unlinking a file whose blocks we are caching in the DFSClient.
   * The DataNode will notify the DFSClient that the replica is stale via the
   * ShortCircuitShm.
   */
  @Test(timeout=60000)
  public void testUnlinkingReplicasInFileDescriptorCache() throws Exception {
    BlockReaderTestUtil.enableShortCircuitShmTracing();
    TemporarySocketDirectory sockDir = new TemporarySocketDirectory();
    Configuration conf = createShortCircuitConf(
        "testUnlinkingReplicasInFileDescriptorCache", sockDir);
    // We don't want the CacheCleaner to time out short-circuit shared memory
    // segments during the test, so set the timeout really high.
    conf.setLong(HdfsClientConfigKeys.Read.ShortCircuit.STREAMS_CACHE_EXPIRY_MS_KEY,
        1000000000L);
    MiniDFSCluster cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    cluster.waitActive();
    DistributedFileSystem fs = cluster.getFileSystem();
    final ShortCircuitCache cache =
        fs.getClient().getClientContext().getShortCircuitCache(0);
    cache.getDfsClientShmManager().visit(new Visitor() {
      @Override
      public void visit(HashMap<DatanodeInfo, PerDatanodeVisitorInfo> info)
          throws IOException {
        // The ClientShmManager starts off empty.
        Assert.assertEquals(0,  info.size());
      }
    });
    final Path TEST_PATH = new Path("/test_file");
    final int TEST_FILE_LEN = 8193;
    final int SEED = 0xFADE0;
    DFSTestUtil.createFile(fs, TEST_PATH, TEST_FILE_LEN,
        (short)1, SEED);
    byte contents[] = DFSTestUtil.readFileBuffer(fs, TEST_PATH);
    byte expected[] = DFSTestUtil.
        calculateFileContentsFromSeed(SEED, TEST_FILE_LEN);
    Assert.assertTrue(Arrays.equals(contents, expected));
    // Loading this file brought the ShortCircuitReplica into our local
    // replica cache.
    final DatanodeInfo datanode = new DatanodeInfoBuilder()
        .setNodeID(cluster.getDataNodes().get(0).getDatanodeId())
        .build();
    cache.getDfsClientShmManager().visit(new Visitor() {
      @Override
      public void visit(HashMap<DatanodeInfo, PerDatanodeVisitorInfo> info)
          throws IOException {
        Assert.assertTrue(info.get(datanode).full.isEmpty());
        Assert.assertFalse(info.get(datanode).disabled);
        Assert.assertEquals(1, info.get(datanode).notFull.values().size());
        DfsClientShm shm =
            info.get(datanode).notFull.values().iterator().next();
        Assert.assertFalse(shm.isDisconnected());
      }
    });
    // Remove the file whose blocks we just read.
    fs.delete(TEST_PATH, false);

    // Wait for the replica to be purged from the DFSClient's cache.
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      MutableBoolean done = new MutableBoolean(true);
      @Override
      public Boolean get() {
        try {
          done.setValue(true);
          cache.getDfsClientShmManager().visit(new Visitor() {
            @Override
            public void visit(HashMap<DatanodeInfo,
                  PerDatanodeVisitorInfo> info) throws IOException {
              Assert.assertTrue(info.get(datanode).full.isEmpty());
              Assert.assertFalse(info.get(datanode).disabled);
              Assert.assertEquals(1,
                  info.get(datanode).notFull.values().size());
              DfsClientShm shm = info.get(datanode).notFull.values().
                  iterator().next();
              // Check that all slots have been invalidated.
              for (Iterator<Slot> iter = shm.slotIterator();
                   iter.hasNext(); ) {
                Slot slot = iter.next();
                if (slot.isValid()) {
                  done.setValue(false);
                }
              }
            }
          });
        } catch (IOException e) {
          LOG.error("error running visitor", e);
        }
        return done.booleanValue();
      }
    }, 10, 60000);
    cluster.shutdown();
    sockDir.close();
  }

  static private void checkNumberOfSegmentsAndSlots(final int expectedSegments,
        final int expectedSlots, final ShortCircuitRegistry registry)
  throws InterruptedException, TimeoutException {
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        return registry.visit(new ShortCircuitRegistry.Visitor() {
          @Override
          public boolean accept(HashMap<ShmId, RegisteredShm> segments,
              HashMultimap<ExtendedBlockId, Slot> slots) {
            return (expectedSegments == segments.size()) &&
                (expectedSlots == slots.size());
          }
        });
      }
    }, 100, 10000);

  }

  public static class TestCleanupFailureInjector
        extends BlockReaderFactory.FailureInjector {
    @Override
    public void injectRequestFileDescriptorsFailure() throws IOException {
      throw new IOException("injected I/O error");
    }
  }

  // Regression test for HDFS-7915
  @Test(timeout=60000)
  public void testDataXceiverCleansUpSlotsOnFailure() throws Exception {
    BlockReaderTestUtil.enableShortCircuitShmTracing();
    TemporarySocketDirectory sockDir = new TemporarySocketDirectory();
    Configuration conf = createShortCircuitConf(
        "testDataXceiverCleansUpSlotsOnFailure", sockDir);
    conf.setLong(
        HdfsClientConfigKeys.Read.ShortCircuit.STREAMS_CACHE_EXPIRY_MS_KEY,
        1000000000L);
    MiniDFSCluster cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    cluster.waitActive();
    DistributedFileSystem fs = cluster.getFileSystem();
    final Path TEST_PATH1 = new Path("/test_file1");
    final Path TEST_PATH2 = new Path("/test_file2");
    final int TEST_FILE_LEN = 4096;
    final int SEED = 0xFADE1;
    DFSTestUtil.createFile(fs, TEST_PATH1, TEST_FILE_LEN,
        (short)1, SEED);
    DFSTestUtil.createFile(fs, TEST_PATH2, TEST_FILE_LEN,
        (short)1, SEED);

    // The first read should allocate one shared memory segment and slot.
    DFSTestUtil.readFileBuffer(fs, TEST_PATH1);

    // The second read should fail, and we should only have 1 segment and 1 slot
    // left.
    BlockReaderFactory.setFailureInjectorForTesting(
        new TestCleanupFailureInjector());
    try {
      DFSTestUtil.readFileBuffer(fs, TEST_PATH2);
    } catch (Throwable t) {
      GenericTestUtils.assertExceptionContains("TCP reads were disabled for " +
          "testing, but we failed to do a non-TCP read.", t);
    }
    checkNumberOfSegmentsAndSlots(1, 1,
        cluster.getDataNodes().get(0).getShortCircuitRegistry());
    cluster.shutdown();
    sockDir.close();
  }

  // Regression test for HADOOP-11802
  @Test(timeout=60000)
  public void testDataXceiverHandlesRequestShortCircuitShmFailure()
      throws Exception {
    BlockReaderTestUtil.enableShortCircuitShmTracing();
    TemporarySocketDirectory sockDir = new TemporarySocketDirectory();
    Configuration conf = createShortCircuitConf(
        "testDataXceiverHandlesRequestShortCircuitShmFailure", sockDir);
    conf.setLong(HdfsClientConfigKeys.Read.ShortCircuit.STREAMS_CACHE_EXPIRY_MS_KEY,
        1000000000L);
    MiniDFSCluster cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    cluster.waitActive();
    DistributedFileSystem fs = cluster.getFileSystem();
    final Path TEST_PATH1 = new Path("/test_file1");
    DFSTestUtil.createFile(fs, TEST_PATH1, 4096,
        (short)1, 0xFADE1);
    LOG.info("Setting failure injector and performing a read which " +
        "should fail...");
    DataNodeFaultInjector failureInjector = Mockito.mock(DataNodeFaultInjector.class);
    Mockito.doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        throw new IOException("injected error into sendShmResponse");
      }
    }).when(failureInjector).sendShortCircuitShmResponse();
    DataNodeFaultInjector prevInjector = DataNodeFaultInjector.get();
    DataNodeFaultInjector.set(failureInjector);

    try {
      // The first read will try to allocate a shared memory segment and slot.
      // The shared memory segment allocation will fail because of the failure
      // injector.
      DFSTestUtil.readFileBuffer(fs, TEST_PATH1);
      Assert.fail("expected readFileBuffer to fail, but it succeeded.");
    } catch (Throwable t) {
      GenericTestUtils.assertExceptionContains("TCP reads were disabled for " +
          "testing, but we failed to do a non-TCP read.", t);
    }

    checkNumberOfSegmentsAndSlots(0, 0,
        cluster.getDataNodes().get(0).getShortCircuitRegistry());

    LOG.info("Clearing failure injector and performing another read...");
    DataNodeFaultInjector.set(prevInjector);

    fs.getClient().getClientContext().getDomainSocketFactory().clearPathMap();

    // The second read should succeed.
    DFSTestUtil.readFileBuffer(fs, TEST_PATH1);

    // We should have added a new short-circuit shared memory segment and slot.
    checkNumberOfSegmentsAndSlots(1, 1,
        cluster.getDataNodes().get(0).getShortCircuitRegistry());

    cluster.shutdown();
    sockDir.close();
  }

  public static class TestPreReceiptVerificationFailureInjector
      extends BlockReaderFactory.FailureInjector {
    @Override
    public boolean getSupportsReceiptVerification() {
      return false;
    }
  }

  // Regression test for HDFS-8070
  @Test(timeout=60000)
  public void testPreReceiptVerificationDfsClientCanDoScr() throws Exception {
    BlockReaderTestUtil.enableShortCircuitShmTracing();
    TemporarySocketDirectory sockDir = new TemporarySocketDirectory();
    Configuration conf = createShortCircuitConf(
        "testPreReceiptVerificationDfsClientCanDoScr", sockDir);
    conf.setLong(
        HdfsClientConfigKeys.Read.ShortCircuit.STREAMS_CACHE_EXPIRY_MS_KEY,
        1000000000L);
    MiniDFSCluster cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    cluster.waitActive();
    DistributedFileSystem fs = cluster.getFileSystem();
    BlockReaderFactory.setFailureInjectorForTesting(
        new TestPreReceiptVerificationFailureInjector());
    final Path TEST_PATH1 = new Path("/test_file1");
    DFSTestUtil.createFile(fs, TEST_PATH1, 4096, (short)1, 0xFADE2);
    final Path TEST_PATH2 = new Path("/test_file2");
    DFSTestUtil.createFile(fs, TEST_PATH2, 4096, (short)1, 0xFADE2);
    DFSTestUtil.readFileBuffer(fs, TEST_PATH1);
    DFSTestUtil.readFileBuffer(fs, TEST_PATH2);
    checkNumberOfSegmentsAndSlots(1, 2,
        cluster.getDataNodes().get(0).getShortCircuitRegistry());
    cluster.shutdown();
    sockDir.close();
  }

  @Test
  public void testFetchOrCreateRetries() throws Exception {
    try(ShortCircuitCache cache = Mockito
        .spy(new ShortCircuitCache(10, 10000000, 10, 10000000, 1, 10000, 0))) {
      final TestFileDescriptorPair pair = new TestFileDescriptorPair();
      ExtendedBlockId extendedBlockId = new ExtendedBlockId(123, "test_bp1");
      SimpleReplicaCreator sRC = new SimpleReplicaCreator(123, cache, pair);

      // Arrange that fetch will throw RetriableException for any call
      Mockito.doThrow(new RetriableException("Retry")).when(cache)
        .fetch(Mockito.eq(extendedBlockId), Mockito.any());

      // Act: calling fetchOrCreate two times
      //  first call: it will create and put entry to replicaInfoMap
      //  second call: it will call fetch to get info for entry, and should
      //               retry 3 times because RetriableException thrown
      cache.fetchOrCreate(extendedBlockId, sRC);
      cache.fetchOrCreate(extendedBlockId, sRC);

      // Assert that fetchOrCreate retried to fetch at least 3 times
      Mockito.verify(cache, Mockito.atLeast(3))
        .fetch(Mockito.eq(extendedBlockId), Mockito.any());
    }
  }

  @Test
  public void testRequestFileDescriptorsWhenULimit() throws Exception {
    TemporarySocketDirectory sockDir = new TemporarySocketDirectory();
    Configuration conf = createShortCircuitConf(
        "testRequestFileDescriptorsWhenULimit", sockDir);

    final short replicas = 1;
    final int fileSize = 3;
    final String testFile = "/testfile";

    try (MiniDFSCluster cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(replicas).build()) {

      cluster.waitActive();

      DistributedFileSystem fs = cluster.getFileSystem();
      DFSTestUtil.createFile(fs, new Path(testFile), fileSize, replicas, 0L);

      LocatedBlock blk = new DFSClient(DFSUtilClient.getNNAddress(conf), conf)
          .getLocatedBlocks(testFile, 0, fileSize).get(0);

      ClientContext clientContext = Mockito.mock(ClientContext.class);
      Mockito.when(clientContext.getPeerCache()).thenAnswer(
          (Answer<PeerCache>) peerCacheCall -> {
            PeerCache peerCache = new PeerCache(10, Long.MAX_VALUE);
            DomainPeer peer = Mockito.spy(getDomainPeerToDn(conf));
            peerCache.put(blk.getLocations()[0], peer);

            Mockito.when(peer.getDomainSocket()).thenAnswer(
                (Answer<DomainSocket>) domainSocketCall -> {
                  DomainSocket domainSocket = Mockito.mock(DomainSocket.class);
                  Mockito.when(domainSocket
                      .recvFileInputStreams(
                          Mockito.any(FileInputStream[].class),
                          Mockito.any(byte[].class),
                          Mockito.anyInt(),
                          Mockito.anyInt())
                  ).thenAnswer(
                      // we are mocking the FileOutputStream array with nulls
                      (Answer<Void>) recvFileInputStreamsCall -> null
                  );
                  return domainSocket;
                }
            );

            return peerCache;
          });

      Mockito.when(clientContext.getShortCircuitCache(
          blk.getBlock().getBlockId())).thenAnswer(
          (Answer<ShortCircuitCache>) shortCircuitCacheCall -> {
              ShortCircuitCache cache = Mockito.mock(ShortCircuitCache.class);
              Mockito.when(cache.allocShmSlot(
                Mockito.any(DatanodeInfo.class),
                Mockito.any(DomainPeer.class),
                Mockito.any(MutableBoolean.class),
                Mockito.any(ExtendedBlockId.class),
                Mockito.anyString()))
                  .thenAnswer((Answer<Slot>) call -> null);

              return cache;
            }
      );

      DatanodeInfo[] nodes = blk.getLocations();

      try {
        Assert.assertNull(new BlockReaderFactory(new DfsClientConf(conf))
            .setInetSocketAddress(NetUtils.createSocketAddr(nodes[0]
                .getXferAddr()))
            .setClientCacheContext(clientContext)
            .setDatanodeInfo(blk.getLocations()[0])
            .setBlock(blk.getBlock())
            .setBlockToken(new Token())
            .createShortCircuitReplicaInfo());
      } catch (NullPointerException ex) {
        Assert.fail("Should not throw NPE when the native library is unable " +
            "to create new files!");
      }
    }
  }

  @Test(timeout = 60000)
  public void testDomainSocketClosedByDN() throws Exception {
    TemporarySocketDirectory sockDir = new TemporarySocketDirectory();
    Configuration conf =
        createShortCircuitConf("testDomainSocketClosedByDN", sockDir);
    MiniDFSCluster cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(1).build();

    try {
      cluster.waitActive();
      DistributedFileSystem fs = cluster.getFileSystem();
      final ShortCircuitCache cache =
          fs.getClient().getClientContext().getShortCircuitCache();
      DomainPeer peer = getDomainPeerToDn(conf);
      MutableBoolean usedPeer = new MutableBoolean(false);
      ExtendedBlockId blockId = new ExtendedBlockId(123, "xyz");
      final DatanodeInfo datanode = new DatanodeInfo.DatanodeInfoBuilder()
          .setNodeID(cluster.getDataNodes().get(0).getDatanodeId()).build();
      // Allocating the first shm slot requires using up a peer.
      Slot slot1 = cache.allocShmSlot(datanode, peer, usedPeer, blockId,
          "testReleaseSlotReuseDomainSocket_client");

      cluster.getDataNodes().get(0).getShortCircuitRegistry()
          .registerSlot(blockId, slot1.getSlotId(), false);

      Slot slot2 = cache.allocShmSlot(datanode, peer, usedPeer, blockId,
          "testReleaseSlotReuseDomainSocket_client");

      cluster.getDataNodes().get(0).getShortCircuitRegistry()
          .registerSlot(blockId, slot2.getSlotId(), false);

      cache.scheduleSlotReleaser(slot1);

      Thread.sleep(2000);
      cache.scheduleSlotReleaser(slot2);
      Thread.sleep(2000);
      Assert.assertEquals(0,
          cluster.getDataNodes().get(0).getShortCircuitRegistry().getShmNum());
      Assert.assertEquals(0, cache.getDfsClientShmManager().getShmNum());
    } finally {
      cluster.shutdown();
    }
  }

  @Test(timeout = 60000)
  public void testDNRestart() throws Exception {
    TemporarySocketDirectory sockDir = new TemporarySocketDirectory();
    Configuration conf = createShortCircuitConf("testDNRestart", sockDir);
    MiniDFSCluster cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    try {
      cluster.waitActive();
      DistributedFileSystem fs = cluster.getFileSystem();
      final ShortCircuitCache cache =
          fs.getClient().getClientContext().getShortCircuitCache();
      DomainPeer peer = getDomainPeerToDn(conf);
      MutableBoolean usedPeer = new MutableBoolean(false);
      ExtendedBlockId blockId = new ExtendedBlockId(123, "xyz");
      final DatanodeInfo datanode = new DatanodeInfo.DatanodeInfoBuilder()
          .setNodeID(cluster.getDataNodes().get(0).getDatanodeId()).build();
      // Allocating the first shm slot requires using up a peer.
      Slot slot1 = cache.allocShmSlot(datanode, peer, usedPeer, blockId,
          "testReleaseSlotReuseDomainSocket_client");

      cluster.getDataNodes().get(0).getShortCircuitRegistry()
          .registerSlot(blockId, slot1.getSlotId(), false);

      // restart the datanode to invalidate the cache
      cluster.restartDataNode(0);
      Thread.sleep(1000);
      // after the restart, new allocation and release should not be affect
      cache.scheduleSlotReleaser(slot1);

      Slot slot2 = null;
      try {
        slot2 = cache.allocShmSlot(datanode, peer, usedPeer, blockId,
            "testReleaseSlotReuseDomainSocket_client");
      } catch (ClosedChannelException ce) {

      }
      cache.scheduleSlotReleaser(slot2);
      Thread.sleep(2000);
      Assert.assertEquals(0,
          cluster.getDataNodes().get(0).getShortCircuitRegistry().getShmNum());
      Assert.assertEquals(0, cache.getDfsClientShmManager().getShmNum());
    } finally {
      cluster.shutdown();
    }
  }
}
