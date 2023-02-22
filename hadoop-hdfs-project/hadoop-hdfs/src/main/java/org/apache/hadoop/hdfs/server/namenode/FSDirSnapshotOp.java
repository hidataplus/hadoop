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
package org.apache.hadoop.hdfs.server.namenode;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.fs.InvalidPathException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.protocol.FSLimitException;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.SnapshotDiffReport;
import org.apache.hadoop.hdfs.protocol.SnapshotDiffReportListing;
import org.apache.hadoop.hdfs.protocol.SnapshotException;
import org.apache.hadoop.hdfs.protocol.SnapshottableDirectoryStatus;
import org.apache.hadoop.hdfs.server.namenode.FSDirectory.DirOp;
import org.apache.hadoop.hdfs.server.namenode.snapshot.DirectorySnapshottableFeature;
import org.apache.hadoop.hdfs.server.namenode.snapshot.Snapshot;
import org.apache.hadoop.hdfs.server.namenode.snapshot.SnapshotManager;
import org.apache.hadoop.hdfs.util.ReadOnlyList;
import org.apache.hadoop.util.ChunkedArrayList;
import org.apache.hadoop.util.Time;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

class FSDirSnapshotOp {
  /** Verify if the snapshot name is legal. */
  static void verifySnapshotName(FSDirectory fsd, String snapshotName,
      String path)
      throws FSLimitException.PathComponentTooLongException {
    if (snapshotName.contains(Path.SEPARATOR)) {
      throw new HadoopIllegalArgumentException(
          "Snapshot name cannot contain \"" + Path.SEPARATOR + "\"");
    }
    final byte[] bytes = DFSUtil.string2Bytes(snapshotName);
    fsd.verifyINodeName(bytes);
    fsd.verifyMaxComponentLength(bytes, path);
  }

  /** Allow snapshot on a directory. */
  static void allowSnapshot(FSDirectory fsd, SnapshotManager snapshotManager,
                            String path) throws IOException {
    fsd.writeLock();
    try {
      snapshotManager.setSnapshottable(path, true);
    } finally {
      fsd.writeUnlock();
    }
    fsd.getEditLog().logAllowSnapshot(path);
  }

  static void disallowSnapshot(
      FSDirectory fsd, SnapshotManager snapshotManager,
      String path) throws IOException {
    fsd.writeLock();
    try {
      snapshotManager.resetSnapshottable(path);
    } finally {
      fsd.writeUnlock();
    }
    fsd.getEditLog().logDisallowSnapshot(path);
  }

  /**
   * Create a snapshot
   * @param fsd FS directory
   * @param pc FS permission checker
   * @param snapshotRoot The directory path where the snapshot is taken
   * @param snapshotName The name of the snapshot
   * @param logRetryCache whether to record RPC ids in editlog for retry cache
   *                      rebuilding.
   */
  static String createSnapshot(
      FSDirectory fsd, FSPermissionChecker pc, SnapshotManager snapshotManager,
      String snapshotRoot, String snapshotName, boolean logRetryCache)
      throws IOException {
    final INodesInPath iip = fsd.resolvePath(pc, snapshotRoot, DirOp.WRITE);
    if (fsd.isPermissionEnabled()) {
      fsd.checkOwner(pc, iip);
    }

    if (snapshotName == null || snapshotName.isEmpty()) {
      snapshotName = Snapshot.generateDefaultSnapshotName();
    } else if (!DFSUtil.isValidNameForComponent(snapshotName)) {
      throw new InvalidPathException("Invalid snapshot name: " + snapshotName);
    }

    String snapshotPath;
    verifySnapshotName(fsd, snapshotName, snapshotRoot);
    // time of snapshot creation
    final long now = Time.now();
    fsd.writeLock();
    try {
      snapshotPath = snapshotManager.createSnapshot(
          fsd.getFSNamesystem().getLeaseManager(),
          iip, snapshotRoot, snapshotName, now);
    } finally {
      fsd.writeUnlock();
    }
    fsd.getEditLog().logCreateSnapshot(snapshotRoot, snapshotName,
        logRetryCache, now);

    return snapshotPath;
  }

  static void renameSnapshot(FSDirectory fsd, FSPermissionChecker pc,
      SnapshotManager snapshotManager, String path, String snapshotOldName,
      String snapshotNewName, boolean logRetryCache) throws IOException {
    final INodesInPath iip = fsd.resolvePath(pc, path, DirOp.WRITE);
    if (fsd.isPermissionEnabled()) {
      fsd.checkOwner(pc, iip);
    }
    verifySnapshotName(fsd, snapshotNewName, path);
    // time of snapshot modification
    final long now = Time.now();
    fsd.writeLock();
    try {
      snapshotManager.renameSnapshot(iip, path, snapshotOldName,
          snapshotNewName, now);
    } finally {
      fsd.writeUnlock();
    }
    fsd.getEditLog().logRenameSnapshot(path, snapshotOldName,
        snapshotNewName, logRetryCache, now);
  }

  static SnapshottableDirectoryStatus[] getSnapshottableDirListing(
      FSDirectory fsd, FSPermissionChecker pc, SnapshotManager snapshotManager)
      throws IOException {
    fsd.readLock();
    try {
      final String user = pc.isSuperUser()? null : pc.getUser();
      return snapshotManager.getSnapshottableDirListing(user);
    } finally {
      fsd.readUnlock();
    }
  }

  static SnapshotDiffReport getSnapshotDiffReport(FSDirectory fsd,
      FSPermissionChecker pc, SnapshotManager snapshotManager, String path,
      String fromSnapshot, String toSnapshot) throws IOException {
    SnapshotDiffReport diffs;
    fsd.readLock();
    try {
      INodesInPath iip = fsd.resolvePath(pc, path, DirOp.READ);
      if (fsd.isPermissionEnabled()) {
        checkSubtreeReadPermission(fsd, pc, path, fromSnapshot);
        checkSubtreeReadPermission(fsd, pc, path, toSnapshot);
      }
      diffs = snapshotManager.diff(iip, path, fromSnapshot, toSnapshot);
    } finally {
      fsd.readUnlock();
    }
    return diffs;
  }

  static SnapshotDiffReportListing getSnapshotDiffReportListing(FSDirectory fsd,
      FSPermissionChecker pc, SnapshotManager snapshotManager, String path,
      String fromSnapshot, String toSnapshot, byte[] startPath, int index,
      int snapshotDiffReportLimit) throws IOException {
    SnapshotDiffReportListing diffs;
    fsd.readLock();
    try {
      INodesInPath iip = fsd.resolvePath(pc, path, DirOp.READ);
      if (fsd.isPermissionEnabled()) {
        checkSubtreeReadPermission(fsd, pc, path, fromSnapshot);
        checkSubtreeReadPermission(fsd, pc, path, toSnapshot);
      }
      diffs = snapshotManager
          .diff(iip, path, fromSnapshot, toSnapshot, startPath, index,
              snapshotDiffReportLimit);
    } catch (Exception e) {
      throw e;
    } finally {
      fsd.readUnlock();
    }
    return diffs;
  }
  /** Get a collection of full snapshot paths given file and snapshot dir.
   * @param lsf a list of snapshottable features
   * @param file full path of the file
   * @return collection of full paths of snapshot of the file
   */
  static Collection<String> getSnapshotFiles(FSDirectory fsd,
      List<DirectorySnapshottableFeature> lsf,
      String file) throws IOException {
    ArrayList<String> snaps = new ArrayList<>();
    for (DirectorySnapshottableFeature sf : lsf) {
      // for each snapshottable dir e.g. /dir1, /dir2
      final ReadOnlyList<Snapshot> lsnap = sf.getSnapshotList();
      for (Snapshot s : lsnap) {
        // for each snapshot name under snapshottable dir
        // e.g. /dir1/.snapshot/s1, /dir1/.snapshot/s2
        final String dirName = s.getRoot().getRootFullPathName();
        if (!file.startsWith(dirName)) {
          // file not in current snapshot root dir, no need to check other snaps
          break;
        }
        String snapname = s.getRoot().getFullPathName();
        if (dirName.equals(Path.SEPARATOR)) { // handle rootDir
          snapname += Path.SEPARATOR;
        }
        snapname += file.substring(file.indexOf(dirName) + dirName.length());
        HdfsFileStatus stat =
            fsd.getFSNamesystem().getFileInfo(snapname, true, false, false);
        if (stat != null) {
          snaps.add(snapname);
        }
      }
    }
    return snaps;
  }

  /**
   * Delete a snapshot of a snapshottable directory
   * @param fsd The FS directory
   * @param pc The permission checker
   * @param snapshotManager The snapshot manager
   * @param snapshotRoot The snapshottable directory
   * @param snapshotName The name of the to-be-deleted snapshot
   * @param logRetryCache whether to record RPC ids in editlog for retry cache
   *                      rebuilding.
   * @throws IOException
   */
  static INode.BlocksMapUpdateInfo deleteSnapshot(
      FSDirectory fsd, FSPermissionChecker pc, SnapshotManager snapshotManager,
      String snapshotRoot, String snapshotName, boolean logRetryCache)
      throws IOException {
    final INodesInPath iip = fsd.resolvePath(pc, snapshotRoot, DirOp.WRITE);
    if (fsd.isPermissionEnabled()) {
      fsd.checkOwner(pc, iip);
    }

    INode.BlocksMapUpdateInfo collectedBlocks = new INode.BlocksMapUpdateInfo();
    ChunkedArrayList<INode> removedINodes = new ChunkedArrayList<>();
    INode.ReclaimContext context = new INode.ReclaimContext(
        fsd.getBlockStoragePolicySuite(), collectedBlocks, removedINodes, null);
    // time of snapshot deletion
    final long now = Time.now();
    fsd.writeLock();
    try {
      snapshotManager.deleteSnapshot(iip, snapshotName, context, now);
      fsd.updateCount(iip, context.quotaDelta(), false);
      fsd.removeFromInodeMap(removedINodes);
      fsd.updateReplicationFactor(context.collectedBlocks()
                                      .toUpdateReplicationInfo());
    } finally {
      fsd.writeUnlock();
    }
    removedINodes.clear();
    fsd.getEditLog().logDeleteSnapshot(snapshotRoot, snapshotName,
        logRetryCache, now);

    return collectedBlocks;
  }

  private static void checkSubtreeReadPermission(
      FSDirectory fsd, final FSPermissionChecker pc, String snapshottablePath,
      String snapshot) throws IOException {
    final String fromPath = snapshot == null ?
        snapshottablePath : Snapshot.getSnapshotPath(snapshottablePath,
        snapshot);
    INodesInPath iip = fsd.resolvePath(pc, fromPath, DirOp.READ);
    fsd.checkPermission(pc, iip, false, null, null, FsAction.READ,
        FsAction.READ);
  }

  /**
   * Check if the given INode (or one of its descendants) is snapshottable and
   * already has snapshots.
   *
   * @param target The given INode
   * @param snapshottableDirs The list of directories that are snapshottable
   *                          but do not have snapshots yet
   */
  private static void checkSnapshot(
      INode target, List<INodeDirectory> snapshottableDirs)
      throws SnapshotException {
    if (target.isDirectory()) {
      INodeDirectory targetDir = target.asDirectory();
      DirectorySnapshottableFeature sf = targetDir
          .getDirectorySnapshottableFeature();
      if (sf != null) {
        if (sf.getNumSnapshots() > 0) {
          String fullPath = targetDir.getFullPathName();
          throw new SnapshotException("The directory " + fullPath
              + " cannot be deleted since " + fullPath
              + " is snapshottable and already has snapshots");
        } else {
          if (snapshottableDirs != null) {
            snapshottableDirs.add(targetDir);
          }
        }
      }
      for (INode child : targetDir.getChildrenList(Snapshot.CURRENT_STATE_ID)) {
        checkSnapshot(child, snapshottableDirs);
      }
    }
  }

  /**
   * Check if the given path (or one of its descendants) is snapshottable and
   * already has snapshots.
   *
   * @param fsd the FSDirectory
   * @param iip inodes of the path
   * @param snapshottableDirs The list of directories that are snapshottable
   *                          but do not have snapshots yet
   */
  static void checkSnapshot(FSDirectory fsd, INodesInPath iip,
      List<INodeDirectory> snapshottableDirs) throws SnapshotException {
    // avoid the performance penalty of recursing the tree if snapshots
    // are not in use
    SnapshotManager sm = fsd.getFSNamesystem().getSnapshotManager();
    if (sm.getNumSnapshottableDirs() > 0) {
      checkSnapshot(iip.getLastINode(), snapshottableDirs);
    }
  }
}
