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

import org.apache.hadoop.thirdparty.com.google.common.base.Preconditions;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockStoragePolicySuite;
import org.apache.hadoop.hdfs.server.namenode.*;
import org.apache.hadoop.hdfs.server.namenode.snapshot.SnapshotFSImageFormat.ReferenceMap;
import org.apache.hadoop.hdfs.util.Diff;
import org.apache.hadoop.hdfs.util.Diff.Container;
import org.apache.hadoop.hdfs.util.Diff.UndoInfo;
import org.apache.hadoop.hdfs.util.ReadOnlyList;
import org.apache.hadoop.security.AccessControlException;

import java.io.DataOutput;
import java.io.IOException;
import java.util.*;

import static org.apache.hadoop.hdfs.server.namenode.snapshot.Snapshot.NO_SNAPSHOT_ID;

/**
 * Feature used to store and process the snapshot diff information for a
 * directory. In particular, it contains a directory diff list recording changes
 * made to the directory and its children for each snapshot.
 */
@InterfaceAudience.Private
public class DirectoryWithSnapshotFeature implements INode.Feature {
  /**
   * The difference between the current state and a previous snapshot
   * of the children list of an INodeDirectory.
   */
  static class ChildrenDiff extends Diff<byte[], INode> {
    ChildrenDiff() {}

    private ChildrenDiff(final List<INode> created, final List<INode> deleted) {
      super(created, deleted);
    }

    /**
     * Replace the given child from the created list.
     * @return true if the child is replaced; false if the child is not found.
     */
    private boolean replaceCreated(final INode oldChild, final INode newChild) {
      final List<INode> list = getCreatedUnmodifiable();
      final int i = search(list, oldChild.getLocalNameBytes());
      if (i < 0 || list.get(i).getId() != oldChild.getId()) {
        return false;
      }

      final INode removed = setCreated(i, newChild);
      Preconditions.checkState(removed == oldChild);
      return true;
    }

    /** clear the created list */
    private void destroyCreatedList(INode.ReclaimContext reclaimContext,
        final INodeDirectory currentINode) {
      for (INode c : getCreatedUnmodifiable()) {
        c.destroyAndCollectBlocks(reclaimContext);
        // c should be contained in the children list, remove it
        currentINode.removeChild(c);
      }
      clearCreated();
    }

    /** clear the deleted list */
    private void destroyDeletedList(INode.ReclaimContext reclaimContext) {
      for (INode d : getDeletedUnmodifiable()) {
        d.destroyAndCollectBlocks(reclaimContext);
      }
      clearDeleted();
    }

    /** Serialize {@link #created} */
    private void writeCreated(DataOutput out) throws IOException {
      final List<INode> created = getCreatedUnmodifiable();
      out.writeInt(created.size());
      for (INode node : created) {
        // For INode in created list, we only need to record its local name
        byte[] name = node.getLocalNameBytes();
        out.writeShort(name.length);
        out.write(name);
      }
    }

    /** Serialize {@link #deleted} */
    private void writeDeleted(DataOutput out,
        ReferenceMap referenceMap) throws IOException {
      final List<INode> deleted = getDeletedUnmodifiable();
      out.writeInt(deleted.size());
      for (INode node : deleted) {
        FSImageSerialization.saveINode2Image(node, out, true, referenceMap);
      }
    }

    /** Serialize to out */
    private void write(DataOutput out, ReferenceMap referenceMap
        ) throws IOException {
      writeCreated(out);
      writeDeleted(out, referenceMap);
    }

    /** Get the list of INodeDirectory contained in the deleted list */
    private void getDirsInDeleted(List<INodeDirectory> dirList) {
      for (INode node : getDeletedUnmodifiable()) {
        if (node.isDirectory()) {
          dirList.add(node.asDirectory());
        }
      }
    }
  }

  /**
   * The difference of an {@link INodeDirectory} between two snapshots.
   */
  public static class DirectoryDiff extends
      AbstractINodeDiff<INodeDirectory, INodeDirectoryAttributes, DirectoryDiff> {
    /** The size of the children list at snapshot creation time. */
    private final int childrenSize;
    /** The children list diff. */
    private final ChildrenDiff diff;
    private boolean isSnapshotRoot = false;
    
    private DirectoryDiff(int snapshotId, INodeDirectory dir) {
      this(snapshotId, dir, new ChildrenDiff());
    }

    public DirectoryDiff(int snapshotId, INodeDirectory dir,
        ChildrenDiff diff) {
      super(snapshotId, null, null);
      this.childrenSize = dir.getChildrenList(Snapshot.CURRENT_STATE_ID).size();
      this.diff = diff;
    }
    /** Constructor used by FSImage loading */
    DirectoryDiff(int snapshotId, INodeDirectoryAttributes snapshotINode,
        DirectoryDiff posteriorDiff, int childrenSize, List<INode> createdList,
        List<INode> deletedList, boolean isSnapshotRoot) {
      super(snapshotId, snapshotINode, posteriorDiff);
      this.childrenSize = childrenSize;
      this.diff = new ChildrenDiff(createdList, deletedList);
      this.isSnapshotRoot = isSnapshotRoot;
    }

    public ChildrenDiff getChildrenDiff() {
      return diff;
    }
    
    void setSnapshotRoot(INodeDirectoryAttributes root) {
      this.snapshotINode = root;
      this.isSnapshotRoot = true;
    }
    
    boolean isSnapshotRoot() {
      return isSnapshotRoot;
    }

    @Override
    void combinePosteriorAndCollectBlocks(
        final INode.ReclaimContext reclaimContext,
        final INodeDirectory currentDir,
        final DirectoryDiff posterior) {
      diff.combinePosterior(posterior.diff, new Diff.Processor<INode>() {
        /** Collect blocks for deleted files. */
        @Override
        public void process(INode inode) {
          if (inode != null) {
            inode.destroyAndCollectBlocks(reclaimContext);
          }
        }
      });
    }

    /**
     * @return The children list of a directory in a snapshot.
     *         Since the snapshot is read-only, the logical view of the list is
     *         never changed although the internal data structure may mutate.
     */
    private ReadOnlyList<INode> getChildrenList(final INodeDirectory currentDir) {
      return new ReadOnlyList<INode>() {
        private List<INode> children = null;

        private List<INode> initChildren() {
          if (children == null) {
            final ChildrenDiff combined = new ChildrenDiff();
            DirectoryDiffList directoryDiffList =
                currentDir.getDirectoryWithSnapshotFeature().diffs;
            final int diffIndex =
                directoryDiffList.getDiffIndexById(getSnapshotId());
            List<DirectoryDiff> diffList = directoryDiffList
                .getDiffListBetweenSnapshots(diffIndex,
                    directoryDiffList.asList().size(), currentDir);
            for (DirectoryDiff d : diffList) {
              combined.combinePosterior(d.diff, null);
            }
            children = combined.apply2Current(ReadOnlyList.Util
                .asList(currentDir.getChildrenList(Snapshot.CURRENT_STATE_ID)));
          }
          return children;
        }

        @Override
        public Iterator<INode> iterator() {
          return initChildren().iterator();
        }

        @Override
        public boolean isEmpty() {
          return childrenSize == 0;
        }

        @Override
        public int size() {
          return childrenSize;
        }

        @Override
        public INode get(int i) {
          return initChildren().get(i);
        }
      };
    }

    /** @return the child with the given name. */
    INode getChild(byte[] name, boolean checkPosterior,
        INodeDirectory currentDir) {
      for(DirectoryDiff d = this; ; d = d.getPosterior()) {
        final Container<INode> returned = d.diff.accessPrevious(name);
        if (returned != null) {
          // the diff is able to determine the inode
          return returned.getElement();
        } else if (!checkPosterior) {
          // Since checkPosterior is false, return null, i.e. not found.
          return null;
        } else if (d.getPosterior() == null) {
          // no more posterior diff, get from current inode.
          return currentDir.getChild(name, Snapshot.CURRENT_STATE_ID);
        }
      }
    }

    @Override
    public String toString() {
      return super.toString() + " childrenSize=" + childrenSize + ", " + diff;
    }

    int getChildrenSize() {
      return childrenSize;
    }

    @Override
    void write(DataOutput out, ReferenceMap referenceMap) throws IOException {
      writeSnapshot(out);
      out.writeInt(childrenSize);

      // Write snapshotINode
      out.writeBoolean(isSnapshotRoot);
      if (!isSnapshotRoot) {
        if (snapshotINode != null) {
          out.writeBoolean(true);
          FSImageSerialization.writeINodeDirectoryAttributes(snapshotINode, out);
        } else {
          out.writeBoolean(false);
        }
      }
      // Write diff. Node need to write poseriorDiff, since diffs is a list.
      diff.write(out, referenceMap);
    }

    @Override
    void destroyDiffAndCollectBlocks(
        INode.ReclaimContext reclaimContext, INodeDirectory currentINode) {
      // this diff has been deleted
      diff.destroyDeletedList(reclaimContext);
      INodeDirectoryAttributes snapshotINode = getSnapshotINode();
      if (snapshotINode != null && snapshotINode.getAclFeature() != null) {
        AclStorage.removeAclFeature(snapshotINode.getAclFeature());
      }
    }
  }

  /** A list of directory diffs. */
  public static class DirectoryDiffList
      extends AbstractINodeDiffList<INodeDirectory, INodeDirectoryAttributes, DirectoryDiff> {

    @Override
    DirectoryDiff createDiff(int snapshot, INodeDirectory currentDir) {
      return new DirectoryDiff(snapshot, currentDir);
    }

    @Override
    INodeDirectoryAttributes createSnapshotCopy(INodeDirectory currentDir) {
      return currentDir.isQuotaSet()?
          new INodeDirectoryAttributes.CopyWithQuota(currentDir)
        : new INodeDirectoryAttributes.SnapshotCopy(currentDir);
    }

    @Override
    DiffList<DirectoryDiff> newDiffs() {
      return DirectoryDiffListFactory
          .createDiffList(INodeDirectory.DEFAULT_FILES_PER_DIRECTORY);
    }

    /** Replace the given child in the created/deleted list, if there is any. */
    public boolean replaceCreatedChild(final INode oldChild,
        final INode newChild) {
      final DiffList<DirectoryDiff> diffList = asList();
      for(int i = diffList.size() - 1; i >= 0; i--) {
        final ChildrenDiff diff = diffList.get(i).diff;
        if (diff.replaceCreated(oldChild, newChild)) {
          return true;
        }
      }
      return false;
    }

    /** Remove the given child from the deleted list, if there is any. */
    public boolean removeDeletedChild(final INode child) {
      final DiffList<DirectoryDiff> diffList = asList();
      for(int i = diffList.size() - 1; i >= 0; i--) {
        final ChildrenDiff diff = diffList.get(i).diff;
        if (diff.removeDeleted(child)) {
          return true;
        }
      }
      return false;
    }

    /**
     * Find the corresponding snapshot whose deleted list contains the given
     * inode.
     * @return the id of the snapshot. {@link Snapshot#NO_SNAPSHOT_ID} if the
     * given inode is not in any of the snapshot.
     */
    public int findSnapshotDeleted(final INode child) {
      final DiffList<DirectoryDiff> diffList = asList();
      for(int i = diffList.size() - 1; i >= 0; i--) {
        final DirectoryDiff diff = diffList.get(i);
        if (diff.getChildrenDiff().containsDeleted(child)) {
          return diff.getSnapshotId();
        }
      }
      return NO_SNAPSHOT_ID;
    }

    /**
     * Returns the list of diffs between two indexes corresponding to two
     * snapshots.
     * @param fromIndex Index of the diff corresponding to the earlier snapshot
     * @param toIndex   Index of the diff corresponding to the later snapshot
     * @param dir       The Directory to which the diffList belongs
     * @return list of directory diffs
     */
    List<DirectoryDiff> getDiffListBetweenSnapshots(int fromIndex, int toIndex,
        INodeDirectory dir) {
      return asList().getMinListForRange(fromIndex, toIndex, dir);
    }
  }
  
  private static Map<INode, INode> cloneDiffList(List<INode> diffList) {
    if (diffList == null || diffList.size() == 0) {
      return null;
    }
    Map<INode, INode> map = new HashMap<>(diffList.size());
    for (INode node : diffList) {
      map.put(node, node);
    }
    return map;
  }
  
  /**
   * Destroy a subtree under a DstReference node.
   */
  public static void destroyDstSubtree(INode.ReclaimContext reclaimContext,
      INode inode, final int snapshot, final int prior) {
    Preconditions.checkArgument(prior != NO_SNAPSHOT_ID);
    if (inode.isReference()) {
      if (inode instanceof INodeReference.WithName
          && snapshot != Snapshot.CURRENT_STATE_ID) {
        // this inode has been renamed before the deletion of the DstReference
        // subtree
        inode.cleanSubtree(reclaimContext, snapshot, prior);
      } else {
        // for DstReference node, continue this process to its subtree
        destroyDstSubtree(reclaimContext,
            inode.asReference().getReferredINode(), snapshot, prior);
      }
    } else if (inode.isFile()) {
      inode.cleanSubtree(reclaimContext, snapshot, prior);
    } else if (inode.isDirectory()) {
      Map<INode, INode> excludedNodes = null;
      INodeDirectory dir = inode.asDirectory();
      DirectoryWithSnapshotFeature sf = dir.getDirectoryWithSnapshotFeature();
      if (sf != null) {
        DirectoryDiffList diffList = sf.getDiffs();
        DirectoryDiff priorDiff = diffList.getDiffById(prior);
        if (priorDiff != null && priorDiff.getSnapshotId() == prior) {
          List<INode> dList = priorDiff.diff.getDeletedUnmodifiable();
          excludedNodes = cloneDiffList(dList);
        }
        
        if (snapshot != Snapshot.CURRENT_STATE_ID) {
          diffList.deleteSnapshotDiff(reclaimContext,
              snapshot, prior, dir);
        }
        priorDiff = diffList.getDiffById(prior);
        if (priorDiff != null && priorDiff.getSnapshotId() == prior) {
          priorDiff.diff.destroyCreatedList(reclaimContext, dir);
        }
      }
      for (INode child : inode.asDirectory().getChildrenList(prior)) {
        if (excludedNodes != null && excludedNodes.containsKey(child)) {
          continue;
        }
        destroyDstSubtree(reclaimContext, child, snapshot, prior);
      }
    }
  }
  
  /**
   * Clean an inode while we move it from the deleted list of post to the
   * deleted list of prior.
   * @param reclaimContext blocks and inodes that need to be reclaimed
   * @param inode The inode to clean.
   * @param post The post snapshot.
   * @param prior The id of the prior snapshot.
   */
  private static void cleanDeletedINode(INode.ReclaimContext reclaimContext,
      INode inode, final int post, final int prior) {
    Deque<INode> queue = new ArrayDeque<>();
    queue.addLast(inode);
    while (!queue.isEmpty()) {
      INode topNode = queue.pollFirst();
      if (topNode instanceof INodeReference.WithName) {
        INodeReference.WithName wn = (INodeReference.WithName) topNode;
        if (wn.getLastSnapshotId() >= post) {
          INodeReference.WithCount wc =
              (INodeReference.WithCount) wn.getReferredINode();
          if (wc.getLastWithName() == wn && wc.getParentReference() == null) {
            // this wn is the last wn inside of the wc, also the dstRef node has
            // been deleted. In this case, we should treat the referred file/dir
            // as normal case
            queue.add(wc.getReferredINode());
          } else {
            wn.cleanSubtree(reclaimContext, post, prior);
          }
        }
        // For DstReference node, since the node is not in the created list of
        // prior, we should treat it as regular file/dir
      } else if (topNode.isFile() && topNode.asFile().isWithSnapshot()) {
        INodeFile file = topNode.asFile();
        file.getDiffs().deleteSnapshotDiff(reclaimContext, post, prior, file);
      } else if (topNode.isDirectory()) {
        INodeDirectory dir = topNode.asDirectory();
        ChildrenDiff priorChildrenDiff = null;
        DirectoryWithSnapshotFeature sf = dir.getDirectoryWithSnapshotFeature();
        if (sf != null) {
          // delete files/dirs created after prior. Note that these
          // files/dirs, along with inode, were deleted right after post.
          DirectoryDiff priorDiff = sf.getDiffs().getDiffById(prior);
          if (priorDiff != null && priorDiff.getSnapshotId() == prior) {
            priorChildrenDiff = priorDiff.getChildrenDiff();
            priorChildrenDiff.destroyCreatedList(reclaimContext, dir);
          }
        }

        for (INode child : dir.getChildrenList(prior)) {
          if (priorChildrenDiff != null && priorChildrenDiff.getDeleted(
              child.getLocalNameBytes()) != null) {
            continue;
          }
          queue.addLast(child);
        }
      }
    }
  }

  /** Diff list sorted by snapshot IDs, i.e. in chronological order. */
  private final DirectoryDiffList diffs;

  public DirectoryWithSnapshotFeature(DirectoryDiffList diffs) {
    this.diffs = diffs != null ? diffs : new DirectoryDiffList();
  }

  /** @return the last snapshot. */
  public int getLastSnapshotId() {
    return diffs.getLastSnapshotId();
  }

  /** @return the snapshot diff list. */
  public DirectoryDiffList getDiffs() {
    return diffs;
  }
  
  /**
   * Get all the directories that are stored in some snapshot but not in the
   * current children list. These directories are equivalent to the directories
   * stored in the deletes lists.
   */
  public void getSnapshotDirectory(List<INodeDirectory> snapshotDir) {
    for (DirectoryDiff sdiff : diffs) {
      sdiff.getChildrenDiff().getDirsInDeleted(snapshotDir);
    }
  }

  /**
   * Add an inode into parent's children list. The caller of this method needs
   * to make sure that parent is in the given snapshot "latest".
   */
  public boolean addChild(INodeDirectory parent, INode inode,
      boolean setModTime, int latestSnapshotId) {
    ChildrenDiff diff = diffs.checkAndAddLatestSnapshotDiff(latestSnapshotId,
        parent).diff;
    final int undoInfo = diff.create(inode);
    boolean added = false;
    try {
      added = parent.addChild(inode, setModTime, Snapshot.CURRENT_STATE_ID);
    } finally {
      if (!added) {
        diff.undoCreate(inode, undoInfo);
      }
    }
    return added;
  }

  /**
   * Remove an inode from parent's children list. The caller of this method
   * needs to make sure that parent is in the given snapshot "latest".
   */
  public boolean removeChild(INodeDirectory parent, INode child,
      int latestSnapshotId) {
    // For a directory that is not a renamed node, if isInLatestSnapshot returns
    // false, the directory is not in the latest snapshot, thus we do not need
    // to record the removed child in any snapshot.
    // For a directory that was moved/renamed, note that if the directory is in
    // any of the previous snapshots, we will create a reference node for the
    // directory while rename, and isInLatestSnapshot will return true in that
    // scenario (if all previous snapshots have been deleted, isInLatestSnapshot
    // still returns false). Thus if isInLatestSnapshot returns false, the
    // directory node cannot be in any snapshot (not in current tree, nor in
    // previous src tree). Thus we do not need to record the removed child in
    // any snapshot.
    ChildrenDiff diff = diffs.checkAndAddLatestSnapshotDiff(latestSnapshotId,
        parent).diff;
    final UndoInfo<INode> undoInfo = diff.delete(child);
    boolean removed = false;
    try {
      removed = parent.removeChild(child);
    } finally {
      if (!removed) {
        diff.undoDelete(child, undoInfo);
      }
    }
    return removed;
  }
  
  /**
   * @return If there is no corresponding directory diff for the given
   *         snapshot, this means that the current children list should be
   *         returned for the snapshot. Otherwise we calculate the children list
   *         for the snapshot and return it. 
   */
  public ReadOnlyList<INode> getChildrenList(INodeDirectory currentINode,
      final int snapshotId) {
    final DirectoryDiff diff = diffs.getDiffById(snapshotId);
    return diff != null ? diff.getChildrenList(currentINode) : currentINode
        .getChildrenList(Snapshot.CURRENT_STATE_ID);
  }
  
  public INode getChild(INodeDirectory currentINode, byte[] name,
      int snapshotId) {
    final DirectoryDiff diff = diffs.getDiffById(snapshotId);
    return diff != null ? diff.getChild(name, true, currentINode)
        : currentINode.getChild(name, Snapshot.CURRENT_STATE_ID);
  }

  /** Used to record the modification of a symlink node */
  public INode saveChild2Snapshot(INodeDirectory currentINode,
      final INode child, final int latestSnapshotId, final INode snapshotCopy) {
    Preconditions.checkArgument(!child.isDirectory(),
        "child is a directory, child=%s", child);
    Preconditions.checkArgument(latestSnapshotId != Snapshot.CURRENT_STATE_ID);
    
    final DirectoryDiff diff = diffs.checkAndAddLatestSnapshotDiff(
        latestSnapshotId, currentINode);
    if (diff.getChild(child.getLocalNameBytes(), false, currentINode) != null) {
      // it was already saved in the latest snapshot earlier.  
      return child;
    }

    diff.diff.modify(snapshotCopy, child);
    return child;
  }

  public void clear(
      INode.ReclaimContext reclaimContext, INodeDirectory currentINode) {
    // destroy its diff list
    for (DirectoryDiff diff : diffs) {
      diff.destroyDiffAndCollectBlocks(reclaimContext, currentINode);
    }
    diffs.clear();
  }

  public QuotaCounts computeQuotaUsage4CurrentDirectory(
      BlockStoragePolicySuite bsps, byte storagePolicyId) {
    final QuotaCounts counts = new QuotaCounts.Builder().build();
    for(DirectoryDiff d : diffs) {
      for(INode deleted : d.getChildrenDiff().getDeletedUnmodifiable()) {
        final byte childPolicyId = deleted.getStoragePolicyIDForQuota(
            storagePolicyId);
        counts.add(deleted.computeQuotaUsage(bsps, childPolicyId, false,
            Snapshot.CURRENT_STATE_ID));
      }
    }
    return counts;
  }

  public void computeContentSummary4Snapshot(final BlockStoragePolicySuite bsps,
      final ContentCounts counts) throws AccessControlException {
    // Create a new blank summary context for blocking processing of subtree.
    ContentSummaryComputationContext summary = 
        new ContentSummaryComputationContext(bsps);
    for(DirectoryDiff d : diffs) {
      for(INode deleted : d.getChildrenDiff().getDeletedUnmodifiable()) {
        deleted.computeContentSummary(Snapshot.CURRENT_STATE_ID, summary);
      }
    }
    // Add the counts from deleted trees.
    counts.addContents(summary.getCounts());
  }
  
  /**
   * Compute the difference between Snapshots.
   *
   * @param fromSnapshot Start point of the diff computation. Null indicates
   *          current tree.
   * @param toSnapshot End point of the diff computation. Null indicates current
   *          tree.
   * @param diff Used to capture the changes happening to the children. Note
   *          that the diff still represents (later_snapshot - earlier_snapshot)
   *          although toSnapshot can be before fromSnapshot.
   * @param currentINode The {@link INodeDirectory} this feature belongs to.
   * @return Whether changes happened between the startSnapshot and endSnaphsot.
   */
  boolean computeDiffBetweenSnapshots(Snapshot fromSnapshot,
      Snapshot toSnapshot, ChildrenDiff diff, INodeDirectory currentINode) {
    int[] diffIndexPair = diffs.changedBetweenSnapshots(fromSnapshot,
        toSnapshot);
    if (diffIndexPair == null) {
      return false;
    }
    int earlierDiffIndex = diffIndexPair[0];
    int laterDiffIndex = diffIndexPair[1];

    boolean dirMetadataChanged = false;
    INodeDirectoryAttributes dirCopy = null;
    List<DirectoryDiff> difflist = diffs
        .getDiffListBetweenSnapshots(earlierDiffIndex, laterDiffIndex,
            currentINode);
    for (DirectoryDiff sdiff : difflist) {
      diff.combinePosterior(sdiff.diff, null);
      if (!dirMetadataChanged && sdiff.snapshotINode != null) {
        if (dirCopy == null) {
          dirCopy = sdiff.snapshotINode;
        } else if (!dirCopy.metadataEquals(sdiff.snapshotINode)) {
          dirMetadataChanged = true;
        }
      }
    }

    if (!diff.isEmpty() || dirMetadataChanged) {
      return true;
    } else if (dirCopy != null) {
      for (int i = laterDiffIndex; i < difflist.size(); i++) {
        if (!dirCopy.metadataEquals(difflist.get(i).snapshotINode)) {
          return true;
        }
      }
      return !dirCopy.metadataEquals(currentINode);
    } else {
      return false;
    }
  }

  public void cleanDirectory(INode.ReclaimContext reclaimContext,
      final INodeDirectory currentINode, final int snapshot, int prior) {
    Map<INode, INode> priorCreated = null;
    Map<INode, INode> priorDeleted = null;
    QuotaCounts old = reclaimContext.quotaDelta().getCountsCopy();
    if (snapshot == Snapshot.CURRENT_STATE_ID) { // delete the current directory
      currentINode.recordModification(prior);
      // delete everything in created list
      DirectoryDiff lastDiff = diffs.getLast();
      if (lastDiff != null) {
        lastDiff.diff.destroyCreatedList(reclaimContext, currentINode);
      }
      currentINode.cleanSubtreeRecursively(reclaimContext, snapshot, prior,
          null);
    } else {
      // update prior
      prior = getDiffs().updatePrior(snapshot, prior);
      // if there is a snapshot diff associated with prior, we need to record
      // its original created and deleted list before deleting post
      if (prior != NO_SNAPSHOT_ID) {
        DirectoryDiff priorDiff = this.getDiffs().getDiffById(prior);
        if (priorDiff != null && priorDiff.getSnapshotId() == prior) {
          priorCreated = cloneDiffList(priorDiff.diff.getCreatedUnmodifiable());
          priorDeleted = cloneDiffList(priorDiff.diff.getDeletedUnmodifiable());
        }
      }

      getDiffs().deleteSnapshotDiff(reclaimContext, snapshot, prior,
          currentINode);
      currentINode.cleanSubtreeRecursively(reclaimContext, snapshot, prior,
          priorDeleted);

      // check priorDiff again since it may be created during the diff deletion
      if (prior != NO_SNAPSHOT_ID) {
        DirectoryDiff priorDiff = this.getDiffs().getDiffById(prior);
        if (priorDiff != null && priorDiff.getSnapshotId() == prior) {
          // For files/directories created between "prior" and "snapshot", 
          // we need to clear snapshot copies for "snapshot". Note that we must
          // use null as prior in the cleanSubtree call. Files/directories that
          // were created before "prior" will be covered by the later 
          // cleanSubtreeRecursively call.
          if (priorCreated != null) {
            // The nodes in priorCreated must be destroyed if
            //   (1) this is the last reference, and
            //   (2) prior is the last snapshot, and
            //   (3) currentINode is not in the current state.
            final boolean destroy = currentINode.isLastReference()
                && currentINode.getDiffs().getLastSnapshotId() == prior
                && !currentINode.isInCurrentState();
            // we only check the node originally in prior's created list
            for (INode cNode : new ArrayList<>(priorDiff.
                    diff.getCreatedUnmodifiable())) {
              if (priorCreated.containsKey(cNode)) {
                if (destroy) {
                  cNode.destroyAndCollectBlocks(reclaimContext);
                  currentINode.removeChild(cNode);
                  priorDiff.diff.removeCreated(cNode);
                } else {
                  cNode.cleanSubtree(reclaimContext, snapshot, NO_SNAPSHOT_ID);
                }
              }
            }
          }
          // When a directory is moved from the deleted list of the posterior
          // diff to the deleted list of this diff, we need to destroy its
          // descendants that were 1) created after taking this diff and 2)
          // deleted after taking posterior diff.

          // For files moved from posterior's deleted list, we also need to
          // delete its snapshot copy associated with the posterior snapshot.
          
          for (INode dNode : priorDiff.diff.getDeletedUnmodifiable()) {
            if (priorDeleted == null || !priorDeleted.containsKey(dNode)) {
              cleanDeletedINode(reclaimContext, dNode, snapshot, prior);
            }
          }
        }
      }
    }

    QuotaCounts current = reclaimContext.quotaDelta().getCountsCopy();
    current.subtract(old);
    if (currentINode.isQuotaSet()) {
      reclaimContext.quotaDelta().addQuotaDirUpdate(currentINode, current);
    }
  }

  @Override
  public String toString() {
    return "" + diffs;
  }
}
