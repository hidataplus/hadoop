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

import org.apache.hadoop.thirdparty.com.google.common.base.Preconditions;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.protocol.BlockStoragePolicy;
import org.apache.hadoop.hdfs.protocol.QuotaExceededException;
import org.apache.hadoop.hdfs.protocol.SnapshotException;
import org.apache.hadoop.hdfs.server.namenode.FSDirectory.DirOp;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;

import static org.apache.hadoop.util.Time.now;

/**
 * Restrictions for a concat operation:
 * <pre>
 * 1. the src file and the target file are in the same dir
 * 2. all the source files are not in snapshot
 * 3. any source file cannot be the same with the target file
 * 4. source files cannot be under construction or empty
 * 5. source file's preferred block size cannot be greater than the target file
 * </pre>
 */
class FSDirConcatOp {

  static FileStatus concat(FSDirectory fsd, FSPermissionChecker pc,
      String target, String[] srcs, boolean logRetryCache) throws IOException {
    validatePath(target, srcs);
    assert srcs != null;
    NameNode.stateChangeLog.debug("DIR* NameSystem.concat: {} to {}",
        Arrays.toString(srcs), target);

    final INodesInPath targetIIP = fsd.resolvePath(pc, target, DirOp.WRITE);
    // write permission for the target
    if (fsd.isPermissionEnabled()) {
      fsd.checkPathAccess(pc, targetIIP, FsAction.WRITE);
    }

    // check the target
    verifyTargetFile(fsd, target, targetIIP);
    // check the srcs
    INodeFile[] srcFiles = verifySrcFiles(fsd, srcs, targetIIP, pc);

    long timestamp = now();
    fsd.writeLock();
    try {
      unprotectedConcat(fsd, targetIIP, srcFiles, timestamp);
    } finally {
      fsd.writeUnlock();
    }
    fsd.getEditLog().logConcat(target, srcs, timestamp, logRetryCache);
    return fsd.getAuditFileInfo(targetIIP);
  }

  private static void validatePath(String target, String[] srcs)
      throws IOException {
    Preconditions.checkArgument(!target.isEmpty(), "Target file name is empty");
    Preconditions.checkArgument(srcs != null && srcs.length > 0,
        "No sources given");
    if (FSDirectory.isReservedRawName(target)
        || FSDirectory.isReservedInodesName(target)) {
      throw new IOException("Concat operation doesn't support "
          + FSDirectory.DOT_RESERVED_STRING + " relative path : " + target);
    }
    for (String srcPath : srcs) {
      if (FSDirectory.isReservedRawName(srcPath)
          || FSDirectory.isReservedInodesName(srcPath)) {
        throw new IOException("Concat operation doesn't support "
            + FSDirectory.DOT_RESERVED_STRING + " relative path : " + srcPath);
      }
    }
  }

  private static void verifyTargetFile(FSDirectory fsd, final String target,
      final INodesInPath targetIIP) throws IOException {
    // check the target
    if (FSDirEncryptionZoneOp.getEZForPath(fsd, targetIIP) != null) {
      throw new HadoopIllegalArgumentException(
          "concat can not be called for files in an encryption zone.");
    }
    final INodeFile targetINode = INodeFile.valueOf(targetIIP.getLastINode(),
        target);
    if(targetINode.isUnderConstruction()) {
      throw new HadoopIllegalArgumentException("concat: target file "
          + target + " is under construction");
    }
  }

  private static INodeFile[] verifySrcFiles(FSDirectory fsd, String[] srcs,
      INodesInPath targetIIP, FSPermissionChecker pc) throws IOException {
    // to make sure no two files are the same
    Set<INodeFile> si = new LinkedHashSet<>();
    final INodeFile targetINode = targetIIP.getLastINode().asFile();
    final INodeDirectory targetParent = targetINode.getParent();
    // now check the srcs
    for(String src : srcs) {
      final INodesInPath iip = fsd.resolvePath(pc, src, DirOp.WRITE);
      // permission check for srcs
      if (pc != null) {
        fsd.checkPathAccess(pc, iip, FsAction.READ); // read the file
        fsd.checkParentAccess(pc, iip, FsAction.WRITE); // for delete
      }
      final INode srcINode = iip.getLastINode();
      final INodeFile srcINodeFile = INodeFile.valueOf(srcINode, src);
      // make sure the src file and the target file are in the same dir
      if (srcINodeFile.getParent() != targetParent) {
        throw new HadoopIllegalArgumentException("Source file " + src
            + " is not in the same directory with the target "
            + targetIIP.getPath());
      }
      // make sure all the source files are not in snapshot
      if (srcINode.isInLatestSnapshot(iip.getLatestSnapshotId())) {
        throw new SnapshotException("Concat: the source file " + src
            + " is in snapshot");
      }
      // check if the file has other references.
      if (srcINode.isReference() && ((INodeReference.WithCount)
          srcINode.asReference().getReferredINode()).getReferenceCount() > 1) {
        throw new SnapshotException("Concat: the source file " + src
            + " is referred by some other reference in some snapshot.");
      }
      // source file cannot be the same with the target file
      if (srcINode.equals(targetINode)) {
        throw new HadoopIllegalArgumentException("concat: the src file " + src
            + " is the same with the target file " + targetIIP.getPath());
      }
      // source file cannot be under construction or empty
      if(srcINodeFile.isUnderConstruction() || srcINodeFile.numBlocks() == 0) {
        throw new HadoopIllegalArgumentException("concat: source file " + src
            + " is invalid or empty or underConstruction");
      }

      // source file's preferred block size cannot be greater than the target
      // file
      if (srcINodeFile.getPreferredBlockSize() >
          targetINode.getPreferredBlockSize()) {
        throw new HadoopIllegalArgumentException("concat: source file " + src
            + " has preferred block size " + srcINodeFile.getPreferredBlockSize()
            + " which is greater than the target file's preferred block size "
            + targetINode.getPreferredBlockSize());
      }
      if(srcINodeFile.getErasureCodingPolicyID() !=
          targetINode.getErasureCodingPolicyID()) {
        throw new HadoopIllegalArgumentException("Source file " + src
            + " and target file " + targetIIP.getPath()
            + " have different erasure coding policy");
      }
      si.add(srcINodeFile);
    }

    // make sure no two files are the same
    if(si.size() < srcs.length) {
      // it means at least two files are the same
      throw new HadoopIllegalArgumentException(
          "concat: at least two of the source files are the same");
    }
    return si.toArray(new INodeFile[si.size()]);
  }

  private static QuotaCounts computeQuotaDeltas(FSDirectory fsd,
      INodeFile target, INodeFile[] srcList) {
    QuotaCounts deltas = new QuotaCounts.Builder().build();
    final short targetRepl = target.getPreferredBlockReplication();
    for (INodeFile src : srcList) {
      short srcRepl = src.getFileReplication();
      long fileSize = src.computeFileSize();
      if (targetRepl != srcRepl) {
        deltas.addStorageSpace(fileSize * (targetRepl - srcRepl));
        BlockStoragePolicy bsp =
            fsd.getBlockStoragePolicySuite().getPolicy(src.getStoragePolicyID());
        if (bsp != null) {
          List<StorageType> srcTypeChosen = bsp.chooseStorageTypes(srcRepl);
          for (StorageType t : srcTypeChosen) {
            if (t.supportTypeQuota()) {
              deltas.addTypeSpace(t, -fileSize);
            }
          }
          List<StorageType> targetTypeChosen = bsp.chooseStorageTypes(targetRepl);
          for (StorageType t : targetTypeChosen) {
            if (t.supportTypeQuota()) {
              deltas.addTypeSpace(t, fileSize);
            }
          }
        }
      }
    }
    deltas.addNameSpace(-srcList.length);
    return deltas;
  }

  private static void verifyQuota(FSDirectory fsd, INodesInPath targetIIP,
      QuotaCounts deltas) throws QuotaExceededException {
    if (!fsd.getFSNamesystem().isImageLoaded() || fsd.shouldSkipQuotaChecks()) {
      // Do not check quota if editlog is still being processed
      return;
    }
    FSDirectory.verifyQuota(targetIIP, targetIIP.length() - 1, deltas, null);
  }

  /**
   * Concat all the blocks from srcs to trg and delete the srcs files
   * @param fsd FSDirectory
   */
  static void unprotectedConcat(FSDirectory fsd, INodesInPath targetIIP,
      INodeFile[] srcList, long timestamp) throws IOException {
    assert fsd.hasWriteLock();
    NameNode.stateChangeLog.debug("DIR* NameSystem.concat to {}",
        targetIIP.getPath());

    final INodeFile trgInode = targetIIP.getLastINode().asFile();
    QuotaCounts deltas = computeQuotaDeltas(fsd, trgInode, srcList);
    verifyQuota(fsd, targetIIP, deltas);

    // the target file can be included in a snapshot
    trgInode.recordModification(targetIIP.getLatestSnapshotId());
    INodeDirectory trgParent = targetIIP.getINode(-2).asDirectory();
    trgInode.concatBlocks(srcList, fsd.getBlockManager());

    // since we are in the same dir - we can use same parent to remove files
    int count = 0;
    for (INodeFile nodeToRemove : srcList) {
      if(nodeToRemove != null) {
        nodeToRemove.clearBlocks();
        // Ensure the nodeToRemove is cleared from snapshot diff list
        nodeToRemove.getParent().removeChild(nodeToRemove,
            targetIIP.getLatestSnapshotId());
        fsd.getINodeMap().remove(nodeToRemove);
        count++;
      }
    }

    trgInode.setModificationTime(timestamp, targetIIP.getLatestSnapshotId());
    trgParent.updateModificationTime(timestamp, targetIIP.getLatestSnapshotId());
    // update quota on the parent directory with deltas
    FSDirectory.unprotectedUpdateCount(targetIIP, targetIIP.length() - 1, deltas);
  }
}
