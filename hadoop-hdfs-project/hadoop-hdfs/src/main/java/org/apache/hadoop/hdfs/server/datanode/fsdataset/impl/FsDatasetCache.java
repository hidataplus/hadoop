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

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_CACHE_REVOCATION_TIMEOUT_MS;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_CACHE_REVOCATION_TIMEOUT_MS_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_CACHE_REVOCATION_POLLING_MS;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_CACHE_REVOCATION_POLLING_MS_DEFAULT;

import org.apache.hadoop.thirdparty.com.google.common.base.Preconditions;
import org.apache.hadoop.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.ChecksumException;
import org.apache.hadoop.hdfs.ExtendedBlockId;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.server.datanode.DNConf;
import org.apache.hadoop.hdfs.server.datanode.DatanodeUtil;
import org.apache.hadoop.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages caching for an FsDatasetImpl by using the mmap(2) and mlock(2)
 * system calls to lock blocks into memory. Block checksums are verified upon
 * entry into the cache.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class FsDatasetCache {
  /**
   * MappableBlocks that we know about.
   */
  private static final class Value {
    final State state;
    final MappableBlock mappableBlock;

    Value(MappableBlock mappableBlock, State state) {
      this.mappableBlock = mappableBlock;
      this.state = state;
    }
  }

  private enum State {
    /**
     * The MappableBlock is in the process of being cached.
     */
    CACHING,

    /**
     * The MappableBlock was in the process of being cached, but it was
     * cancelled.  Only the FsDatasetCache#WorkerTask can remove cancelled
     * MappableBlock objects.
     */
    CACHING_CANCELLED,

    /**
     * The MappableBlock is in the cache.
     */
    CACHED,

    /**
     * The MappableBlock is in the process of uncaching.
     */
    UNCACHING;

    /**
     * Whether we should advertise this block as cached to the NameNode and
     * clients.
     */
    public boolean shouldAdvertise() {
      return (this == CACHED);
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(FsDatasetCache
      .class);

  /**
   * Stores MappableBlock objects and the states they're in.
   */
  private final HashMap<ExtendedBlockId, Value> mappableBlockMap =
      new HashMap<ExtendedBlockId, Value>();

  private final LongAdder numBlocksCached = new LongAdder();

  private final FsDatasetImpl dataset;

  private final ThreadPoolExecutor uncachingExecutor;

  private final ScheduledThreadPoolExecutor deferredUncachingExecutor;

  private final long revocationMs;

  private final long revocationPollingMs;

  /**
   * A specific cacheLoader could cache block either to DRAM or
   * to persistent memory.
   */
  private final MappableBlockLoader cacheLoader;

  private final CacheStats memCacheStats;

  /**
   * Number of cache commands that could not be completed successfully
   */
  final LongAdder numBlocksFailedToCache = new LongAdder();
  /**
   * Number of uncache commands that could not be completed successfully
   */
  final LongAdder numBlocksFailedToUncache = new LongAdder();

  public FsDatasetCache(FsDatasetImpl dataset) throws IOException {
    this.dataset = dataset;
    ThreadFactory workerFactory = new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat("FsDatasetCache-%d-" + dataset.toString())
        .build();
    this.uncachingExecutor = new ThreadPoolExecutor(
            0, 1,
            60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            workerFactory);
    this.uncachingExecutor.allowCoreThreadTimeOut(true);
    this.deferredUncachingExecutor = new ScheduledThreadPoolExecutor(
            1, workerFactory);
    this.revocationMs = dataset.datanode.getConf().getLong(
        DFS_DATANODE_CACHE_REVOCATION_TIMEOUT_MS,
        DFS_DATANODE_CACHE_REVOCATION_TIMEOUT_MS_DEFAULT);
    long confRevocationPollingMs = dataset.datanode.getConf().getLong(
        DFS_DATANODE_CACHE_REVOCATION_POLLING_MS,
        DFS_DATANODE_CACHE_REVOCATION_POLLING_MS_DEFAULT);
    long minRevocationPollingMs = revocationMs / 2;
    if (minRevocationPollingMs < confRevocationPollingMs) {
      throw new RuntimeException("configured value " +
              confRevocationPollingMs + "for " +
              DFS_DATANODE_CACHE_REVOCATION_POLLING_MS +
              " is too high.  It must not be more than half of the " +
              "value of " +  DFS_DATANODE_CACHE_REVOCATION_TIMEOUT_MS +
              ".  Reconfigure this to " + minRevocationPollingMs);
    }
    this.revocationPollingMs = confRevocationPollingMs;

    this.cacheLoader = MappableBlockLoaderFactory.createCacheLoader(
        this.getDnConf());
    // Both lazy writer and read cache are sharing this statistics.
    this.memCacheStats = cacheLoader.initialize(this.getDnConf());
  }

  /**
   * For persistent memory cache, create cache subdirectory specified with
   * blockPoolId to store cache data.
   * Recover the status of cache in persistent memory, if any.
   */
  public void initCache(String bpid) throws IOException {
    if (cacheLoader.isTransientCache()) {
      return;
    }
    PmemVolumeManager.getInstance().createBlockPoolDir(bpid);
    if (getDnConf().getPmemCacheRecoveryEnabled()) {
      final Map<ExtendedBlockId, MappableBlock> keyToMappableBlock =
          PmemVolumeManager.getInstance().recoverCache(bpid, cacheLoader);
      Set<Map.Entry<ExtendedBlockId, MappableBlock>> entrySet
          = keyToMappableBlock.entrySet();
      for (Map.Entry<ExtendedBlockId, MappableBlock> entry : entrySet) {
        mappableBlockMap.put(entry.getKey(),
            new Value(keyToMappableBlock.get(entry.getKey()), State.CACHED));
        numBlocksCached.increment();
        dataset.datanode.getMetrics().incrBlocksCached(1);
      }
    }
  }

  DNConf getDnConf() {
    return this.dataset.datanode.getDnConf();
  }

  /**
   * Get the cache path if the replica is cached into persistent memory.
   */
  String getReplicaCachePath(String bpid, long blockId) throws IOException {
    if (cacheLoader.isTransientCache() ||
        !isCached(bpid, blockId)) {
      return null;
    }
    ExtendedBlockId key = new ExtendedBlockId(blockId, bpid);
    return PmemVolumeManager.getInstance().getCachePath(key);
  }

  /**
   * Get cache address on persistent memory for read operation.
   * The cache address comes from PMDK lib function when mapping
   * block to persistent memory.
   *
   * @param bpid    blockPoolId
   * @param blockId blockId
   * @return address
   */
  long getCacheAddress(String bpid, long blockId) {
    if (cacheLoader.isTransientCache() ||
        !isCached(bpid, blockId)) {
      return -1;
    }
    if (!(cacheLoader.isNativeLoader())) {
      return -1;
    }
    ExtendedBlockId key = new ExtendedBlockId(blockId, bpid);
    MappableBlock mappableBlock = mappableBlockMap.get(key).mappableBlock;
    return mappableBlock.getAddress();
  }

  /**
   * @return List of cached blocks suitable for translation into a
   * {@link BlockListAsLongs} for a cache report.
   */
  synchronized List<Long> getCachedBlocks(String bpid) {
    List<Long> blocks = new ArrayList<Long>();
    for (Iterator<Entry<ExtendedBlockId, Value>> iter =
        mappableBlockMap.entrySet().iterator(); iter.hasNext(); ) {
      Entry<ExtendedBlockId, Value> entry = iter.next();
      if (entry.getKey().getBlockPoolId().equals(bpid)) {
        if (entry.getValue().state.shouldAdvertise()) {
          blocks.add(entry.getKey().getBlockId());
        }
      }
    }
    return blocks;
  }

  /**
   * Attempt to begin caching a block.
   */
  synchronized void cacheBlock(long blockId, String bpid,
      String blockFileName, long length, long genstamp,
      Executor volumeExecutor) {
    ExtendedBlockId key = new ExtendedBlockId(blockId, bpid);
    Value prevValue = mappableBlockMap.get(key);
    if (prevValue != null) {
      LOG.debug("Block with id {}, pool {} already exists in the "
              + "FsDatasetCache with state {}", blockId, bpid, prevValue.state
      );
      numBlocksFailedToCache.increment();
      return;
    }
    mappableBlockMap.put(key, new Value(null, State.CACHING));
    volumeExecutor.execute(
        new CachingTask(key, blockFileName, length, genstamp));
    LOG.debug("Initiating caching for Block with id {}, pool {}", blockId,
        bpid);
  }

  synchronized void uncacheBlock(String bpid, long blockId) {
    ExtendedBlockId key = new ExtendedBlockId(blockId, bpid);
    Value prevValue = mappableBlockMap.get(key);
    boolean deferred = false;

    if (cacheLoader.isTransientCache() && !dataset.datanode.
        getShortCircuitRegistry().processBlockMunlockRequest(key)) {
      deferred = true;
    }
    if (prevValue == null) {
      LOG.debug("Block with id {}, pool {} does not need to be uncached, "
          + "because it is not currently in the mappableBlockMap.", blockId,
          bpid);
      numBlocksFailedToUncache.increment();
      return;
    }
    switch (prevValue.state) {
    case CACHING:
      LOG.debug("Cancelling caching for block with id {}, pool {}.", blockId,
          bpid);
      mappableBlockMap.put(key,
          new Value(prevValue.mappableBlock, State.CACHING_CANCELLED));
      break;
    case CACHED:
      mappableBlockMap.put(key,
          new Value(prevValue.mappableBlock, State.UNCACHING));
      if (deferred) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("{} is anchored, and can't be uncached now.  Scheduling it " +
                  "for uncaching in {} ",
              key, DurationFormatUtils.formatDurationHMS(revocationPollingMs));
        }
        deferredUncachingExecutor.schedule(
            new UncachingTask(key, revocationMs),
            revocationPollingMs, TimeUnit.MILLISECONDS);
      } else {
        LOG.debug("{} has been scheduled for immediate uncaching.", key);
        uncachingExecutor.execute(new UncachingTask(key, 0));
      }
      break;
    default:
      LOG.debug("Block with id {}, pool {} does not need to be uncached, "
          + "because it is in state {}.", blockId, bpid, prevValue.state);
      numBlocksFailedToUncache.increment();
      break;
    }
  }

  /**
   * Try to reserve more bytes.
   *
   * @param count    The number of bytes to add.  We will round this
   *                 up to the page size.
   *
   * @return         The new number of usedBytes if we succeeded;
   *                 -1 if we failed.
   */
  long reserve(long count) {
    return memCacheStats.reserve(count);
  }

  /**
   * Release some bytes that we're using.
   *
   * @param count    The number of bytes to release.  We will round this
   *                 up to the page size.
   *
   * @return         The new number of usedBytes.
   */
  long release(long count) {
    return memCacheStats.release(count);
  }

  /**
   * Release some bytes that we're using rounded down to the page size.
   *
   * @param count    The number of bytes to release.  We will round this
   *                 down to the page size.
   *
   * @return         The new number of usedBytes.
   */
  long releaseRoundDown(long count) {
    return memCacheStats.releaseRoundDown(count);
  }

  /**
   * Get the OS page size.
   *
   * @return the OS page size.
   */
  long getOsPageSize() {
    return memCacheStats.getPageSize();
  }

  /**
   * Round up to the OS page size.
   */
  long roundUpPageSize(long count) {
    return memCacheStats.roundUpPageSize(count);
  }

  /**
   * Background worker that mmaps, mlocks, and checksums a block
   */
  private class CachingTask implements Runnable {
    private final ExtendedBlockId key; 
    private final String blockFileName;
    private final long length;
    private final long genstamp;

    CachingTask(ExtendedBlockId key, String blockFileName, long length, long genstamp) {
      this.key = key;
      this.blockFileName = blockFileName;
      this.length = length;
      this.genstamp = genstamp;
    }

    @Override
    public void run() {
      boolean success = false;
      FileInputStream blockIn = null, metaIn = null;
      MappableBlock mappableBlock = null;
      ExtendedBlock extBlk = new ExtendedBlock(key.getBlockPoolId(),
          key.getBlockId(), length, genstamp);
      long newUsedBytes = cacheLoader.reserve(key, length);
      boolean reservedBytes = false;
      try {
        if (newUsedBytes < 0) {
          LOG.warn("Failed to cache " + key + ": could not reserve " +
              "more bytes in the cache: " + cacheLoader.getCacheCapacity() +
              " exceeded when try to reserve " + length + "bytes.");
          return;
        }
        reservedBytes = true;
        try {
          blockIn = (FileInputStream)dataset.getBlockInputStream(extBlk, 0);
          metaIn = DatanodeUtil.getMetaDataInputStream(extBlk, dataset);
        } catch (ClassCastException e) {
          LOG.warn("Failed to cache " + key +
              ": Underlying blocks are not backed by files.", e);
          return;
        } catch (FileNotFoundException e) {
          LOG.info("Failed to cache " + key + ": failed to find backing " +
              "files.");
          return;
        } catch (IOException e) {
          LOG.warn("Failed to cache " + key + ": failed to open file", e);
          return;
        }

        try {
          mappableBlock = cacheLoader.load(length, blockIn, metaIn,
              blockFileName, key);
        } catch (ChecksumException e) {
          // Exception message is bogus since this wasn't caused by a file read
          LOG.warn("Failed to cache " + key + ": checksum verification failed.");
          return;
        } catch (IOException e) {
          LOG.warn("Failed to cache the block [key=" + key + "]!", e);
          return;
        }

        synchronized (FsDatasetCache.this) {
          Value value = mappableBlockMap.get(key);
          Preconditions.checkNotNull(value);
          Preconditions.checkState(value.state == State.CACHING ||
                                   value.state == State.CACHING_CANCELLED);
          if (value.state == State.CACHING_CANCELLED) {
            mappableBlockMap.remove(key);
            LOG.warn("Caching of " + key + " was cancelled.");
            return;
          }
          mappableBlockMap.put(key, new Value(mappableBlock, State.CACHED));
        }
        LOG.debug("Successfully cached {}.  We are now caching {} bytes in"
            + " total.", key, newUsedBytes);
        // Only applicable to DRAM cache.
        if (cacheLoader.isTransientCache()) {
          dataset.datanode.
              getShortCircuitRegistry().processBlockMlockEvent(key);
        }
        numBlocksCached.increment();
        dataset.datanode.getMetrics().incrBlocksCached(1);
        success = true;
      } finally {
        IOUtils.closeQuietly(blockIn);
        IOUtils.closeQuietly(metaIn);
        if (!success) {
          if (reservedBytes) {
            cacheLoader.release(key, length);
          }
          LOG.debug("Caching of {} was aborted.  We are now caching only {} "
                  + "bytes in total.", key, cacheLoader.getCacheUsed());
          IOUtils.closeQuietly(mappableBlock);
          numBlocksFailedToCache.increment();

          synchronized (FsDatasetCache.this) {
            mappableBlockMap.remove(key);
          }
        }
      }
    }
  }

  private class UncachingTask implements Runnable {
    private final ExtendedBlockId key; 
    private final long revocationTimeMs;

    UncachingTask(ExtendedBlockId key, long revocationDelayMs) {
      this.key = key;
      if (revocationDelayMs == 0) {
        this.revocationTimeMs = 0;
      } else {
        this.revocationTimeMs = revocationDelayMs + Time.monotonicNow();
      }
    }

    private boolean shouldDefer() {
      // Currently, defer condition is just checked for DRAM cache case.
      if (!cacheLoader.isTransientCache()) {
        return false;
      }

      /* If revocationTimeMs == 0, this is an immediate uncache request.
       * No clients were anchored at the time we made the request. */
      if (revocationTimeMs == 0) {
        return false;
      }
      /* Let's check if any clients still have this block anchored. */
      boolean anchored =
        !dataset.datanode.getShortCircuitRegistry().
            processBlockMunlockRequest(key);
      if (!anchored) {
        LOG.debug("Uncaching {} now that it is no longer in use " +
            "by any clients.", key);
        return false;
      }
      long delta = revocationTimeMs - Time.monotonicNow();
      if (delta < 0) {
        LOG.warn("Forcibly uncaching {} after {} " +
            "because client(s) {} refused to stop using it.", key,
            DurationFormatUtils.formatDurationHMS(revocationTimeMs),
            dataset.datanode.getShortCircuitRegistry().getClientNames(key));
        return false;
      }
      LOG.info("Replica {} still can't be uncached because some " +
          "clients continue to use it.  Will wait for {}", key,
          DurationFormatUtils.formatDurationHMS(delta));
      return true;
    }

    @Override
    public void run() {
      Value value;

      if (shouldDefer()) {
        deferredUncachingExecutor.schedule(
            this, revocationPollingMs, TimeUnit.MILLISECONDS);
        return;
      }

      synchronized (FsDatasetCache.this) {
        value = mappableBlockMap.get(key);
      }
      Preconditions.checkNotNull(value);
      Preconditions.checkArgument(value.state == State.UNCACHING);

      IOUtils.closeQuietly(value.mappableBlock);
      synchronized (FsDatasetCache.this) {
        mappableBlockMap.remove(key);
      }
      long newUsedBytes = cacheLoader.
          release(key, value.mappableBlock.getLength());
      numBlocksCached.decrement();
      dataset.datanode.getMetrics().incrBlocksUncached(1);
      if (revocationTimeMs != 0) {
        LOG.debug("Uncaching of {} completed. usedBytes = {}",
            key, newUsedBytes);
      } else {
        LOG.debug("Deferred uncaching of {} completed. usedBytes = {}",
            key, newUsedBytes);
      }
    }
  }

  // Stats related methods for FSDatasetMBean

  /**
   * Get the approximate amount of DRAM cache space used.
   */
  public long getMemCacheUsed() {
    return memCacheStats.getCacheUsed();
  }

  /**
   * Get the approximate amount of cache space used either on DRAM or
   * on persistent memory.
   * @return
   */
  public long getCacheUsed() {
    return cacheLoader.getCacheUsed();
  }

  /**
   * Get the maximum amount of bytes we can cache on DRAM. This is a constant.
   */
  public long getMemCacheCapacity() {
    return memCacheStats.getCacheCapacity();
  }

  /**
   * Get the maximum amount of bytes we can cache either on DRAM or
   * on persistent memory. This is a constant.
   */
  public long getCacheCapacity() {
    return cacheLoader.getCacheCapacity();
  }

  public long getNumBlocksFailedToCache() {
    return numBlocksFailedToCache.longValue();
  }

  public long getNumBlocksFailedToUncache() {
    return numBlocksFailedToUncache.longValue();
  }

  public long getNumBlocksCached() {
    return numBlocksCached.longValue();
  }

  public synchronized boolean isCached(String bpid, long blockId) {
    ExtendedBlockId block = new ExtendedBlockId(blockId, bpid);
    Value val = mappableBlockMap.get(block);
    return (val != null) && val.state.shouldAdvertise();
  }

  /**
   * This method can be executed during DataNode shutdown.
   */
  void shutdown() {
    cacheLoader.shutdown();
  }
}
