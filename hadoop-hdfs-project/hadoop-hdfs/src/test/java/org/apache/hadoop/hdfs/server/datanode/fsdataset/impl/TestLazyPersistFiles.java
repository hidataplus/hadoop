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
package org.apache.hadoop.hdfs.server.datanode.fsdataset.impl;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.DataNodeTestUtils;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsDatasetSpi;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.util.ThreadUtil;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.hadoop.fs.StorageType.RAM_DISK;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class TestLazyPersistFiles extends LazyPersistTestCase {
  private static final int THREADPOOL_SIZE = 10;

  /**
   * Append to lazy persist file is denied.
   * @throws IOException
   */
  @Test
  public void testAppendIsDenied() throws IOException {
    getClusterBuilder().build();
    final String METHOD_NAME = GenericTestUtils.getMethodName();
    Path path = new Path("/" + METHOD_NAME + ".dat");

    makeTestFile(path, BLOCK_SIZE, true);

    try {
      client.append(path.toString(), BUFFER_LENGTH,
          EnumSet.of(CreateFlag.APPEND), null, null).close();
      fail("Append to LazyPersist file did not fail as expected");
    } catch (Throwable t) {
      LOG.info("Got expected exception ", t);
    }
  }

  /**
   * Truncate to lazy persist file is denied.
   * @throws IOException
   */
  @Test
  public void testTruncateIsDenied() throws IOException {
    getClusterBuilder().build();
    final String METHOD_NAME = GenericTestUtils.getMethodName();
    Path path = new Path("/" + METHOD_NAME + ".dat");

    makeTestFile(path, BLOCK_SIZE, true);

    try {
      client.truncate(path.toString(), BLOCK_SIZE / 2);
      fail("Truncate to LazyPersist file did not fail as expected");
    } catch (Throwable t) {
      LOG.info("Got expected exception ", t);
    }
  }

  /**
   * If one or more replicas of a lazyPersist file are lost, then the file
   * must be discarded by the NN, instead of being kept around as a
   * 'corrupt' file.
   */
  @Test
  public void testCorruptFilesAreDiscarded()
      throws IOException, InterruptedException, TimeoutException {
    getClusterBuilder().setRamDiskReplicaCapacity(2).build();
    final String METHOD_NAME = GenericTestUtils.getMethodName();
    Path path1 = new Path("/" + METHOD_NAME + ".01.dat");

    makeTestFile(path1, BLOCK_SIZE, true);
    ensureFileReplicasOnStorageType(path1, RAM_DISK);

    // Stop the DataNode.
    shutdownDataNodes();
    assertThat(cluster.getNamesystem().getNumDeadDataNodes(), is(1));

    // Next, wait for the redundancy monitor to mark the file as corrupt.
    waitForRedundancyMonitorCycle();
    // Wait for the LazyPersistFileScrubber to run
    waitForScrubberCycle();
    // Ensure that path1 does not exist anymore, whereas path2 does.
    waitForFile(path1, false);

    // We should have zero blocks that needs replication i.e. the one
    // belonging to path2. This needs a wait.
    waitForLowRedundancyCount(0L);
  }

  @Test
  public void testDisableLazyPersistFileScrubber()
      throws IOException, InterruptedException, TimeoutException {
    getClusterBuilder().setRamDiskReplicaCapacity(2).disableScrubber().build();
    final String METHOD_NAME = GenericTestUtils.getMethodName();
    Path path1 = new Path("/" + METHOD_NAME + ".01.dat");

    makeTestFile(path1, BLOCK_SIZE, true);
    ensureFileReplicasOnStorageType(path1, RAM_DISK);

    // Stop the DataNode and sleep for the time it takes the NN to
    // detect the DN as being dead.
    shutdownDataNodes();

    // wait for the redundancy monitor to mark the file as corrupt.
    waitForCorruptBlock(1L);
    // Wait for the LazyPersistFileScrubber to run
    waitForScrubberCycle();
    // Ensure that path1 exist.
    waitForFile(path1, true);
  }

 /**
  * If NN restarted then lazyPersist files should not deleted
  */
  @Test(timeout = 20000)
  public void testFileShouldNotDiscardedIfNNRestarted()
      throws IOException, InterruptedException, TimeoutException {
    getClusterBuilder().setRamDiskReplicaCapacity(2).build();
    final String METHOD_NAME = GenericTestUtils.getMethodName();
    Path path1 = new Path("/" + METHOD_NAME + ".01.dat");
    makeTestFile(path1, BLOCK_SIZE, true);
    ensureFileReplicasOnStorageType(path1, RAM_DISK);

    shutdownDataNodes();

    cluster.restartNameNodes();

    // wait for the redundancy monitor to mark the file as corrupt.
    waitForCorruptBlock(1L);
    // Ensure path1 exist.
    waitForFile(path1, true);
  }

  /**
   * Concurrent read from the same node and verify the contents.
   */
  @Test
  public void testConcurrentRead()
    throws Exception {
    getClusterBuilder().setRamDiskReplicaCapacity(2).build();
    final String METHOD_NAME = GenericTestUtils.getMethodName();
    final Path path1 = new Path("/" + METHOD_NAME + ".dat");

    final int SEED = 0xFADED;
    final int NUM_TASKS = 5;
    makeRandomTestFile(path1, BLOCK_SIZE, true, SEED);
    ensureFileReplicasOnStorageType(path1, RAM_DISK);

    //Read from multiple clients
    final CountDownLatch latch = new CountDownLatch(NUM_TASKS);
    final AtomicBoolean testFailed = new AtomicBoolean(false);

    Runnable readerRunnable = new Runnable() {
      @Override
      public void run() {
        try {
          Assert.assertTrue(verifyReadRandomFile(path1, BLOCK_SIZE, SEED));
        } catch (Throwable e) {
          LOG.error("readerRunnable error", e);
          testFailed.set(true);
        } finally {
          latch.countDown();
        }
      }
    };

    Thread threads[] = new Thread[NUM_TASKS];
    for (int i = 0; i < NUM_TASKS; i++) {
      threads[i] = new Thread(readerRunnable);
      threads[i].start();
    }

    for (int i = 0; i < NUM_TASKS; i++) {
      ThreadUtil.joinUninterruptibly(threads[i]);
    }
    Assert.assertFalse(testFailed.get());
  }

  /**
   * Concurrent write with eviction
   * RAM_DISK can hold 9 replicas
   * 4 threads each write 5 replicas
   * @throws IOException
   * @throws InterruptedException
   */
  @Test
  public void testConcurrentWrites()
      throws IOException, InterruptedException, TimeoutException {
    getClusterBuilder().setRamDiskReplicaCapacity(9).build();
    final String METHOD_NAME = GenericTestUtils.getMethodName();
    final int SEED = 0xFADED;
    final int NUM_WRITERS = 4;
    final int NUM_WRITER_PATHS = 5;

    Path paths[][] = new Path[NUM_WRITERS][NUM_WRITER_PATHS];
    for (int i = 0; i < NUM_WRITERS; i++) {
      paths[i] = new Path[NUM_WRITER_PATHS];
      for (int j = 0; j < NUM_WRITER_PATHS; j++) {
        paths[i][j] =
          new Path("/" + METHOD_NAME + ".Writer" + i + ".File." + j + ".dat");
      }
    }

    final CountDownLatch latch = new CountDownLatch(NUM_WRITERS);
    final AtomicBoolean testFailed = new AtomicBoolean(false);

    ExecutorService executor = Executors.newFixedThreadPool(THREADPOOL_SIZE);
    for (int i = 0; i < NUM_WRITERS; i++) {
      Runnable writer = new WriterRunnable(i, paths[i], SEED, latch, testFailed);
      executor.execute(writer);
    }

    Thread.sleep(3 * LAZY_WRITER_INTERVAL_SEC * 1000);
    triggerBlockReport();

    // Stop executor from adding new tasks to finish existing threads in queue
    latch.await();

    assertThat(testFailed.get(), is(false));
  }

  class WriterRunnable implements Runnable {
    private final int id;
    private final Path paths[];
    private final int seed;
    private CountDownLatch latch;
    private AtomicBoolean bFail;

    public WriterRunnable(int threadIndex, Path[] paths,
                          int seed, CountDownLatch latch,
                          AtomicBoolean bFail) {
      id = threadIndex;
      this.paths = paths;
      this.seed = seed;
      this.latch = latch;
      this.bFail = bFail;
      LOG.info("Creating Writer: {}", id);
    }

    public void run() {
      LOG.info("Writer {} starting... ", id);
      int i = 0;
      try {
        for (i = 0; i < paths.length; i++) {
          makeRandomTestFile(paths[i], BLOCK_SIZE, true, seed);
          // eviction may faiL when all blocks are not persisted yet.
          // ensureFileReplicasOnStorageType(paths[i], RAM_DISK);
        }
      } catch (IOException e) {
        bFail.set(true);
        LOG.error("Writer exception: writer id:{} testfile: {}",
            id, paths[i].toString(), e);
      } finally {
        latch.countDown();
      }
    }
  }

  @Test(timeout = 20000)
  public void testReleaseVolumeRefIfExceptionThrown()
      throws IOException, InterruptedException {
    getClusterBuilder().setRamDiskReplicaCapacity(2).build();
    final String methodName = GenericTestUtils.getMethodName();
    final int seed = 0xFADED;
    Path path = new Path("/" + methodName + ".Writer.File.dat");

    DataNode dn = cluster.getDataNodes().get(0);
    FsDatasetSpi.FsVolumeReferences volumes =
        DataNodeTestUtils.getFSDataset(dn).getFsVolumeReferences();
    int[] beforeCnts = new int[volumes.size()];
    FsDatasetImpl ds = (FsDatasetImpl) DataNodeTestUtils.getFSDataset(dn);

    // Create a runtime exception.
    ds.asyncLazyPersistService.shutdown();
    for (int i = 0; i < volumes.size(); ++i) {
      beforeCnts[i] = ((FsVolumeImpl) volumes.get(i)).getReferenceCount();
    }

    makeRandomTestFile(path, BLOCK_SIZE, true, seed);
    Thread.sleep(3 * LAZY_WRITER_INTERVAL_SEC * 1000);

    for (int i = 0; i < volumes.size(); ++i) {
      int afterCnt = ((FsVolumeImpl) volumes.get(i)).getReferenceCount();
      // LazyWriter keeps trying to save copies even if
      // asyncLazyPersistService is already shutdown.
      // If we do not release references, the number of
      // references will increase infinitely.
      Assert.assertTrue(
          beforeCnts[i] == afterCnt || beforeCnts[i] == (afterCnt - 1));
    }
  }
}
