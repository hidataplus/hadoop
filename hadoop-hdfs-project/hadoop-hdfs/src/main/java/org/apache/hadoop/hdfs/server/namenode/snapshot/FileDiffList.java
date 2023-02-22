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
package org.apache.hadoop.hdfs.server.namenode.snapshot;

import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfoContiguous;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.INode.BlocksMapUpdateInfo;
import org.apache.hadoop.hdfs.server.namenode.INodeFile;
import org.apache.hadoop.hdfs.server.namenode.INodeFileAttributes;

/** A list of FileDiffs for storing snapshot data. */
public class FileDiffList extends
    AbstractINodeDiffList<INodeFile, INodeFileAttributes, FileDiff> {
  
  @Override
  FileDiff createDiff(int snapshotId, INodeFile file) {
    return new FileDiff(snapshotId, file);
  }
  
  @Override
  INodeFileAttributes createSnapshotCopy(INodeFile currentINode) {
    return new INodeFileAttributes.SnapshotCopy(currentINode);
  }

  public void destroyAndCollectSnapshotBlocks(
      BlocksMapUpdateInfo collectedBlocks) {
    for (FileDiff d : asList()) {
      d.destroyAndCollectSnapshotBlocks(collectedBlocks);
    }
  }

  public void saveSelf2Snapshot(int latestSnapshotId, INodeFile iNodeFile,
      INodeFileAttributes snapshotCopy, boolean withBlocks) {
    final FileDiff diff =
        super.saveSelf2Snapshot(latestSnapshotId, iNodeFile, snapshotCopy);
    if (withBlocks) {  // Store blocks if this is the first update
      BlockInfo[] blks = iNodeFile.getBlocks();
      assert blks != null;
      diff.setBlocks(blks);
    }
  }

  public BlockInfo[] findEarlierSnapshotBlocks(int snapshotId) {
    assert snapshotId != Snapshot.NO_SNAPSHOT_ID : "Wrong snapshot id";
    if (snapshotId == Snapshot.CURRENT_STATE_ID) {
      return null;
    }
    DiffList<FileDiff> diffs = this.asList();
    int i = diffs.binarySearch(snapshotId);
    BlockInfo[] blocks = null;
    for(i = i >= 0 ? i : -i-2; i >= 0; i--) {
      blocks = diffs.get(i).getBlocks();
      if(blocks != null) {
        break;
      }
    }
    return blocks;
  }

  public BlockInfo[] findLaterSnapshotBlocks(int snapshotId) {
    assert snapshotId != Snapshot.NO_SNAPSHOT_ID : "Wrong snapshot id";
    if (snapshotId == Snapshot.CURRENT_STATE_ID) {
      return null;
    }
    DiffList<FileDiff> diffs = this.asList();
    int i = diffs.binarySearch(snapshotId);
    BlockInfo[] blocks = null;
    for (i = i >= 0 ? i+1 : -i-1; i < diffs.size(); i++) {
      blocks = diffs.get(i).getBlocks();
      if (blocks != null) {
        break;
      }
    }
    return blocks;
  }

  /**
   * Copy blocks from the removed snapshot into the previous snapshot
   * up to the file length of the latter.
   * Collect unused blocks of the removed snapshot.
   */
  void combineAndCollectSnapshotBlocks(
      INode.ReclaimContext reclaimContext, INodeFile file, FileDiff removed) {
    BlockInfo[] removedBlocks = removed.getBlocks();
    if (removedBlocks == null) {
      FileWithSnapshotFeature sf = file.getFileWithSnapshotFeature();
      assert sf != null : "FileWithSnapshotFeature is null";
      if(sf.isCurrentFileDeleted())
        sf.collectBlocksAndClear(reclaimContext, file);
      return;
    }
    int p = getPrior(removed.getSnapshotId(), true);
    FileDiff earlierDiff = p == Snapshot.NO_SNAPSHOT_ID ? null : getDiffById(p);
    // Copy blocks to the previous snapshot if not set already
    if (earlierDiff != null) {
      earlierDiff.setBlocks(removedBlocks);
    }
    BlockInfo[] earlierBlocks =
        (earlierDiff == null ? new BlockInfoContiguous[]{} : earlierDiff.getBlocks());
    // Find later snapshot (or file itself) with blocks
    BlockInfo[] laterBlocks = findLaterSnapshotBlocks(removed.getSnapshotId());
    laterBlocks = (laterBlocks == null) ? file.getBlocks() : laterBlocks;
    // Skip blocks, which belong to either the earlier or the later lists
    int i = 0;
    for(; i < removedBlocks.length; i++) {
      if(i < earlierBlocks.length && removedBlocks[i] == earlierBlocks[i])
        continue;
      if(i < laterBlocks.length && removedBlocks[i] == laterBlocks[i])
        continue;
      break;
    }
    // Check if last block is part of truncate recovery
    BlockInfo lastBlock = file.getLastBlock();
    BlockInfo dontRemoveBlock = null;
    if (lastBlock != null && lastBlock.getBlockUCState().equals(
        HdfsServerConstants.BlockUCState.UNDER_RECOVERY)) {
      dontRemoveBlock = lastBlock.getUnderConstructionFeature()
          .getTruncateBlock();
    }
    // Collect the remaining blocks of the file, ignoring truncate block
    for (;i < removedBlocks.length; i++) {
      if(dontRemoveBlock == null || !removedBlocks[i].equals(dontRemoveBlock)) {
        reclaimContext.collectedBlocks().addDeleteBlock(removedBlocks[i]);
      }
    }
  }
}
