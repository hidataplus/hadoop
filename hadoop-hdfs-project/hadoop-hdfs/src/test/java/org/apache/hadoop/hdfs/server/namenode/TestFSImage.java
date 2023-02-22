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

import static org.apache.hadoop.test.GenericTestUtils.assertExceptionContains;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;

import org.apache.hadoop.thirdparty.com.google.common.collect.Lists;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.hdfs.StripedFileTestUtil;
import org.apache.hadoop.hdfs.protocol.AddErasureCodingPolicyResponse;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicyInfo;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicyState;
import org.apache.hadoop.hdfs.protocol.SystemErasureCodingPolicies;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicy;
import org.apache.hadoop.hdfs.protocolPB.PBHelperClient;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfoContiguous;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfoStriped;
import org.apache.hadoop.hdfs.protocol.BlockType;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.StartupOption;
import org.apache.hadoop.hdfs.server.namenode.snapshot.SnapshotTestHelper;
import org.apache.hadoop.io.erasurecode.ECSchema;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.util.NativeCodeLoader;
import org.junit.Assert;

import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSOutputStream;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.client.HdfsDataOutputStream.SyncFlag;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.namenode.LeaseManager.Lease;
import org.apache.hadoop.hdfs.server.namenode.NNStorage.NameNodeDirType;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.INodeSection;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto.FileSummary.Section;
import org.apache.hadoop.hdfs.server.namenode.FSImageFormatProtobuf.SectionName;
import org.apache.hadoop.hdfs.util.MD5FileUtils;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.PathUtils;
import org.apache.hadoop.util.Time;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class TestFSImage {

  private static final String HADOOP_2_7_ZER0_BLOCK_SIZE_TGZ =
      "image-with-zero-block-size.tar.gz";
  private static final ErasureCodingPolicy testECPolicy =
      SystemErasureCodingPolicies.getByID(
          SystemErasureCodingPolicies.RS_10_4_POLICY_ID);

  @Test
  public void testPersist() throws IOException {
    Configuration conf = new Configuration();
    testPersistHelper(conf);
  }

  @Test
  public void testCompression() throws IOException {
    Configuration conf = new Configuration();
    conf.setBoolean(DFSConfigKeys.DFS_IMAGE_COMPRESS_KEY, true);
    setCompressCodec(conf, "org.apache.hadoop.io.compress.DefaultCodec");
    setCompressCodec(conf, "org.apache.hadoop.io.compress.GzipCodec");
    setCompressCodec(conf, "org.apache.hadoop.io.compress.BZip2Codec");
  }

  @Test
  public void testNativeCompression() throws IOException {
    Assume.assumeTrue(NativeCodeLoader.isNativeCodeLoaded());
    Configuration conf = new Configuration();
    conf.setBoolean(DFSConfigKeys.DFS_IMAGE_COMPRESS_KEY, true);
    setCompressCodec(conf, "org.apache.hadoop.io.compress.Lz4Codec");
  }

  private void setCompressCodec(Configuration conf, String compressCodec)
      throws IOException {
    conf.set(DFSConfigKeys.DFS_IMAGE_COMPRESSION_CODEC_KEY, compressCodec);
    testPersistHelper(conf);
  }

  private void testPersistHelper(Configuration conf) throws IOException {
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).build();
      cluster.waitActive();
      FSNamesystem fsn = cluster.getNamesystem();
      DistributedFileSystem fs = cluster.getFileSystem();

      final Path dir = new Path("/abc/def");
      final Path file1 = new Path(dir, "f1");
      final Path file2 = new Path(dir, "f2");

      // create an empty file f1
      fs.create(file1).close();

      // create an under-construction file f2
      FSDataOutputStream out = fs.create(file2);
      out.writeBytes("hello");
      ((DFSOutputStream) out.getWrappedStream()).hsync(EnumSet
          .of(SyncFlag.UPDATE_LENGTH));

      // checkpoint
      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fs.saveNamespace();
      fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

      cluster.restartNameNode();
      cluster.waitActive();
      fs = cluster.getFileSystem();

      assertTrue(fs.isDirectory(dir));
      assertTrue(fs.exists(file1));
      assertTrue(fs.exists(file2));

      // check internals of file2
      INodeFile file2Node = fsn.dir.getINode4Write(file2.toString()).asFile();
      assertEquals("hello".length(), file2Node.computeFileSize());
      assertTrue(file2Node.isUnderConstruction());
      BlockInfo[] blks = file2Node.getBlocks();
      assertEquals(1, blks.length);
      assertEquals(BlockUCState.UNDER_CONSTRUCTION, blks[0].getBlockUCState());
      // check lease manager
      Lease lease = fsn.leaseManager.getLease(file2Node);
      Assert.assertNotNull(lease);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  private void testSaveAndLoadStripedINodeFile(FSNamesystem fsn, Configuration conf,
                                               boolean isUC) throws IOException{
    // Construct an INode with StripedBlock for saving and loading
    fsn.setErasureCodingPolicy("/", testECPolicy.getName(), false);
    long id = 123456789;
    byte[] name = "testSaveAndLoadInodeFile_testfile".getBytes();
    PermissionStatus permissionStatus = new PermissionStatus("testuser_a",
            "testuser_groups", new FsPermission((short)0x755));
    long mtime = 1426222916-3600;
    long atime = 1426222916;
    BlockInfoContiguous[] blocks = new BlockInfoContiguous[0];
    byte erasureCodingPolicyID = testECPolicy.getId();
    long preferredBlockSize = 128*1024*1024;
    INodeFile file = new INodeFile(id, name, permissionStatus, mtime, atime,
        blocks, null, erasureCodingPolicyID, preferredBlockSize,
        (byte) 0, BlockType.STRIPED);
    ByteArrayOutputStream bs = new ByteArrayOutputStream();

    // Construct StripedBlocks for the INode
    BlockInfoStriped[] stripedBlocks = new BlockInfoStriped[3];
    long stripedBlkId = 10000001;
    long timestamp = mtime+3600;
    for (int i = 0; i < stripedBlocks.length; i++) {
      stripedBlocks[i] = new BlockInfoStriped(
              new Block(stripedBlkId + i, preferredBlockSize, timestamp),
              testECPolicy);
      file.addBlock(stripedBlocks[i]);
    }

    final String client = "testClient";
    final String clientMachine = "testClientMachine";
    final String path = "testUnderConstructionPath";

    // Save the INode to byte array
    DataOutput out = new DataOutputStream(bs);
    if (isUC) {
      file.toUnderConstruction(client, clientMachine);
      FSImageSerialization.writeINodeUnderConstruction((DataOutputStream) out,
          file, path);
    } else {
      FSImageSerialization.writeINodeFile(file, out, false);
    }
    DataInput in = new DataInputStream(
            new ByteArrayInputStream(bs.toByteArray()));

    // load the INode from the byte array
    INodeFile fileByLoaded;
    if (isUC) {
      fileByLoaded = FSImageSerialization.readINodeUnderConstruction(in,
              fsn, fsn.getFSImage().getLayoutVersion());
    } else {
      fileByLoaded = (INodeFile) new FSImageFormat.Loader(conf, fsn)
              .loadINodeWithLocalName(false, in, false);
    }

    assertEquals(id, fileByLoaded.getId() );
    assertArrayEquals(isUC ? path.getBytes() : name,
        fileByLoaded.getLocalName().getBytes());
    assertEquals(permissionStatus.getUserName(),
        fileByLoaded.getPermissionStatus().getUserName());
    assertEquals(permissionStatus.getGroupName(),
        fileByLoaded.getPermissionStatus().getGroupName());
    assertEquals(permissionStatus.getPermission(),
        fileByLoaded.getPermissionStatus().getPermission());
    assertEquals(mtime, fileByLoaded.getModificationTime());
    assertEquals(isUC ? mtime : atime, fileByLoaded.getAccessTime());
    // TODO for striped blocks, we currently save and load them as contiguous
    // blocks to/from legacy fsimage
    assertEquals(3, fileByLoaded.getBlocks().length);
    assertEquals(preferredBlockSize, fileByLoaded.getPreferredBlockSize());
    assertEquals(file.getFileReplication(), fileByLoaded.getFileReplication());

    if (isUC) {
      assertEquals(client,
          fileByLoaded.getFileUnderConstructionFeature().getClientName());
      assertEquals(clientMachine,
          fileByLoaded.getFileUnderConstructionFeature().getClientMachine());
    }
  }

  /**
   * Test if a INodeFile with BlockInfoStriped can be saved by
   * FSImageSerialization and loaded by FSImageFormat#Loader.
   */
  @Test
  public void testSaveAndLoadStripedINodeFile() throws IOException{
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).build();
      cluster.waitActive();
      DFSTestUtil.enableAllECPolicies(cluster.getFileSystem());
      testSaveAndLoadStripedINodeFile(cluster.getNamesystem(), conf, false);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * Test if a INodeFileUnderConstruction with BlockInfoStriped can be
   * saved and loaded by FSImageSerialization
   */
  @Test
  public void testSaveAndLoadStripedINodeFileUC() throws IOException {
    // construct a INode with StripedBlock for saving and loading
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).build();
      cluster.waitActive();
      DFSTestUtil.enableAllECPolicies(cluster.getFileSystem());
      testSaveAndLoadStripedINodeFile(cluster.getNamesystem(), conf, true);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

   /**
   * On checkpointing , stale fsimage checkpoint file should be deleted.
   */
  @Test
  public void testRemovalStaleFsimageCkpt() throws IOException {
    MiniDFSCluster cluster = null;
    SecondaryNameNode secondary = null;
    Configuration conf = new HdfsConfiguration();
    try {
      cluster = new MiniDFSCluster.Builder(conf).
          numDataNodes(1).format(true).build();
      conf.set(DFSConfigKeys.DFS_NAMENODE_SECONDARY_HTTP_ADDRESS_KEY,
          "0.0.0.0:0");
      secondary = new SecondaryNameNode(conf);
      // Do checkpointing
      secondary.doCheckpoint();
      NNStorage storage = secondary.getFSImage().storage;
      File currentDir = FSImageTestUtil.
          getCurrentDirs(storage, NameNodeDirType.IMAGE).get(0);
      // Create a stale fsimage.ckpt file
      File staleCkptFile = new File(currentDir.getPath() +
          "/fsimage.ckpt_0000000000000000002");
      staleCkptFile.createNewFile();
      assertTrue(staleCkptFile.exists());
      // After checkpoint stale fsimage.ckpt file should be deleted
      secondary.doCheckpoint();
      assertFalse(staleCkptFile.exists());
    } finally {
      if (secondary != null) {
        secondary.shutdown();
        secondary = null;
      }
      if (cluster != null) {
        cluster.shutdown();
        cluster = null;
      }
    }
  }

  /**
   * Ensure that the digest written by the saver equals to the digest of the
   * file.
   */
  @Test
  public void testDigest() throws IOException {
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      DistributedFileSystem fs = cluster.getFileSystem();
      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fs.saveNamespace();
      fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);
      File currentDir = FSImageTestUtil.getNameNodeCurrentDirs(cluster, 0).get(
          0);
      File fsimage = FSImageTestUtil.findNewestImageFile(currentDir
          .getAbsolutePath());
      assertEquals(MD5FileUtils.readStoredMd5ForFile(fsimage),
          MD5FileUtils.computeMd5ForFile(fsimage));
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * Ensure mtime and atime can be loaded from fsimage.
   */
  @Test(timeout=60000)
  public void testLoadMtimeAtime() throws Exception {
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
      cluster.waitActive();
      DistributedFileSystem hdfs = cluster.getFileSystem();
      String userDir = hdfs.getHomeDirectory().toUri().getPath().toString();
      Path file = new Path(userDir, "file");
      Path dir = new Path(userDir, "/dir");
      Path link = new Path(userDir, "/link");
      hdfs.createNewFile(file);
      hdfs.mkdirs(dir);
      hdfs.createSymlink(file, link, false);

      long mtimeFile = hdfs.getFileStatus(file).getModificationTime();
      long atimeFile = hdfs.getFileStatus(file).getAccessTime();
      long mtimeDir = hdfs.getFileStatus(dir).getModificationTime();
      long mtimeLink = hdfs.getFileLinkStatus(link).getModificationTime();
      long atimeLink = hdfs.getFileLinkStatus(link).getAccessTime();

      // save namespace and restart cluster
      hdfs.setSafeMode(HdfsConstants.SafeModeAction.SAFEMODE_ENTER);
      hdfs.saveNamespace();
      hdfs.setSafeMode(HdfsConstants.SafeModeAction.SAFEMODE_LEAVE);
      cluster.shutdown();
      cluster = new MiniDFSCluster.Builder(conf).format(false)
          .numDataNodes(1).build();
      cluster.waitActive();
      hdfs = cluster.getFileSystem();
      
      assertEquals(mtimeFile, hdfs.getFileStatus(file).getModificationTime());
      assertEquals(atimeFile, hdfs.getFileStatus(file).getAccessTime());
      assertEquals(mtimeDir, hdfs.getFileStatus(dir).getModificationTime());
      assertEquals(mtimeLink, hdfs.getFileLinkStatus(link).getModificationTime());
      assertEquals(atimeLink, hdfs.getFileLinkStatus(link).getAccessTime());
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * Ensure ctime is set during namenode formatting.
   */
  @Test(timeout=60000)
  public void testCtime() throws Exception {
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      final long pre = Time.now();
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
      cluster.waitActive();
      final long post = Time.now();
      final long ctime = cluster.getNamesystem().getCTime();

      assertTrue(pre <= ctime);
      assertTrue(ctime <= post);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * In this test case, I have created an image with a file having
   * preferredblockSize = 0. We are trying to read this image (since file with
   * preferredblockSize = 0 was allowed pre 2.1.0-beta version. The namenode 
   * after 2.6 version will not be able to read this particular file.
   * See HDFS-7788 for more information.
   * @throws Exception
   */
  @Test
  public void testZeroBlockSize() throws Exception {
    final Configuration conf = new HdfsConfiguration();
    String tarFile = System.getProperty("test.cache.data", "build/test/cache")
      + "/" + HADOOP_2_7_ZER0_BLOCK_SIZE_TGZ;
    String testDir = PathUtils.getTestDirName(getClass());
    File dfsDir = new File(testDir, "image-with-zero-block-size");
    if (dfsDir.exists() && !FileUtil.fullyDelete(dfsDir)) {
      throw new IOException("Could not delete dfs directory '" + dfsDir + "'");
    }
    FileUtil.unTar(new File(tarFile), new File(testDir));
    File nameDir = new File(dfsDir, "name");
    GenericTestUtils.assertExists(nameDir);
    conf.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY, 
        nameDir.getAbsolutePath());
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1)
        .format(false)
        .manageDataDfsDirs(false)
        .manageNameDfsDirs(false)
        .waitSafeMode(false).startupOption(StartupOption.UPGRADE)
        .build();
    try {
      FileSystem fs = cluster.getFileSystem();
      Path testPath = new Path("/tmp/zeroBlockFile");
      assertTrue("File /tmp/zeroBlockFile doesn't exist ", fs.exists(testPath));
      assertTrue("Name node didn't come up", cluster.isNameNodeUp(0));
    } finally {
      cluster.shutdown();
      //Clean up
      FileUtil.fullyDelete(dfsDir);
    }
  }

  /**
   * Ensure that FSImage supports BlockGroup.
   */
  @Test(timeout = 60000)
  public void testSupportBlockGroup() throws Exception {
    final short GROUP_SIZE = (short) (testECPolicy.getNumDataUnits() +
        testECPolicy.getNumParityUnits());
    final int BLOCK_SIZE = 8 * 1024 * 1024;
    Configuration conf = new HdfsConfiguration();
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, BLOCK_SIZE);
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(GROUP_SIZE)
          .build();
      cluster.waitActive();
      DistributedFileSystem fs = cluster.getFileSystem();
      DFSTestUtil.enableAllECPolicies(fs);
      Path parentDir = new Path("/ec-10-4");
      Path childDir = new Path(parentDir, "ec-3-2");
      ErasureCodingPolicy ec32Policy = SystemErasureCodingPolicies
          .getByID(SystemErasureCodingPolicies.RS_3_2_POLICY_ID);

      // Create directories and files
      fs.mkdirs(parentDir);
      fs.mkdirs(childDir);
      fs.setErasureCodingPolicy(parentDir, testECPolicy.getName());
      fs.setErasureCodingPolicy(childDir, ec32Policy.getName());
      Path file_10_4 = new Path(parentDir, "striped_file_10_4");
      Path file_3_2 = new Path(childDir, "striped_file_3_2");

      // Write content to files
      byte[] bytes = StripedFileTestUtil.generateBytes(BLOCK_SIZE);
      DFSTestUtil.writeFile(fs, file_10_4, new String(bytes));
      DFSTestUtil.writeFile(fs, file_3_2, new String(bytes));

      // Save namespace and restart NameNode
      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fs.saveNamespace();
      fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

      cluster.restartNameNodes();
      fs = cluster.getFileSystem();
      assertTrue(fs.exists(file_10_4));
      assertTrue(fs.exists(file_3_2));

      // check the information of file_10_4
      FSNamesystem fsn = cluster.getNamesystem();
      INodeFile inode = fsn.dir.getINode(file_10_4.toString()).asFile();
      assertTrue(inode.isStriped());
      assertEquals(testECPolicy.getId(), inode.getErasureCodingPolicyID());
      BlockInfo[] blks = inode.getBlocks();
      assertEquals(1, blks.length);
      assertTrue(blks[0].isStriped());
      assertEquals(testECPolicy.getId(),
          fs.getErasureCodingPolicy(file_10_4).getId());
      assertEquals(testECPolicy.getId(),
          ((BlockInfoStriped)blks[0]).getErasureCodingPolicy().getId());
      assertEquals(testECPolicy.getNumDataUnits(),
          ((BlockInfoStriped) blks[0]).getDataBlockNum());
      assertEquals(testECPolicy.getNumParityUnits(),
          ((BlockInfoStriped) blks[0]).getParityBlockNum());
      byte[] content = DFSTestUtil.readFileAsBytes(fs, file_10_4);
      assertArrayEquals(bytes, content);


      // check the information of file_3_2
      inode = fsn.dir.getINode(file_3_2.toString()).asFile();
      assertTrue(inode.isStriped());
      assertEquals(SystemErasureCodingPolicies.getByID(
          SystemErasureCodingPolicies.RS_3_2_POLICY_ID).getId(),
          inode.getErasureCodingPolicyID());
      blks = inode.getBlocks();
      assertEquals(1, blks.length);
      assertTrue(blks[0].isStriped());
      assertEquals(ec32Policy.getId(),
          fs.getErasureCodingPolicy(file_3_2).getId());
      assertEquals(ec32Policy.getNumDataUnits(),
          ((BlockInfoStriped) blks[0]).getDataBlockNum());
      assertEquals(ec32Policy.getNumParityUnits(),
          ((BlockInfoStriped) blks[0]).getParityBlockNum());
      content = DFSTestUtil.readFileAsBytes(fs, file_3_2);
      assertArrayEquals(bytes, content);

      // check the EC policy on parent Dir
      ErasureCodingPolicy ecPolicy =
          fsn.getErasureCodingPolicy(parentDir.toString());
      assertNotNull(ecPolicy);
      assertEquals(testECPolicy.getId(), ecPolicy.getId());

      // check the EC policy on child Dir
      ecPolicy = fsn.getErasureCodingPolicy(childDir.toString());
      assertNotNull(ecPolicy);
      assertEquals(ec32Policy.getId(), ecPolicy.getId());

      // check the EC policy on root directory
      ecPolicy = fsn.getErasureCodingPolicy("/");
      assertNull(ecPolicy);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  @Test
  public void testHasNonEcBlockUsingStripedIDForLoadFile() throws IOException{
    // start a cluster
    Configuration conf = new HdfsConfiguration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(9)
          .build();
      cluster.waitActive();
      DistributedFileSystem fs = cluster.getFileSystem();
      FSNamesystem fns = cluster.getNamesystem();

      String testDir = "/test_block_manager";
      String testFile = "testfile_loadfile";
      String testFilePath = testDir + "/" + testFile;
      String clientName = "testUser_loadfile";
      String clientMachine = "testMachine_loadfile";
      long blkId = -1;
      long blkNumBytes = 1024;
      long timestamp = 1426222918;

      fs.mkdir(new Path(testDir), new FsPermission("755"));
      Path p = new Path(testFilePath);

      DFSTestUtil.createFile(fs, p, 0, (short) 1, 1);
      BlockInfoContiguous cBlk = new BlockInfoContiguous(
          new Block(blkId, blkNumBytes, timestamp), (short)3);
      INodeFile file = (INodeFile)fns.getFSDirectory().getINode(testFilePath);
      file.toUnderConstruction(clientName, clientMachine);
      file.addBlock(cBlk);
      TestINodeFile.toCompleteFile(file);
      fns.enterSafeMode(false);
      fns.saveNamespace(0, 0);
      cluster.restartNameNodes();
      cluster.waitActive();
      fns = cluster.getNamesystem();
      assertTrue(fns.getBlockManager().hasNonEcBlockUsingStripedID());

      //after nonEcBlockUsingStripedID is deleted
      //the hasNonEcBlockUsingStripedID is set to false
      fs = cluster.getFileSystem();
      fs.delete(p,false);
      fns.enterSafeMode(false);
      fns.saveNamespace(0, 0);
      cluster.restartNameNodes();
      cluster.waitActive();
      fns = cluster.getNamesystem();
      assertFalse(fns.getBlockManager().hasNonEcBlockUsingStripedID());

      cluster.shutdown();
      cluster = null;
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  @Test
  public void testHasNonEcBlockUsingStripedIDForLoadUCFile()
      throws IOException{
    // start a cluster
    Configuration conf = new HdfsConfiguration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(9)
          .build();
      cluster.waitActive();
      DistributedFileSystem fs = cluster.getFileSystem();
      FSNamesystem fns = cluster.getNamesystem();

      String testDir = "/test_block_manager";
      String testFile = "testfile_loaducfile";
      String testFilePath = testDir + "/" + testFile;
      String clientName = "testUser_loaducfile";
      String clientMachine = "testMachine_loaducfile";
      long blkId = -1;
      long blkNumBytes = 1024;
      long timestamp = 1426222918;

      fs.mkdir(new Path(testDir), new FsPermission("755"));
      Path p = new Path(testFilePath);

      DFSTestUtil.createFile(fs, p, 0, (short) 1, 1);
      BlockInfoContiguous cBlk = new BlockInfoContiguous(
          new Block(blkId, blkNumBytes, timestamp), (short)3);
      INodeFile file = (INodeFile)fns.getFSDirectory().getINode(testFilePath);
      file.toUnderConstruction(clientName, clientMachine);
      file.addBlock(cBlk);
      fns.enterSafeMode(false);
      fns.saveNamespace(0, 0);
      cluster.restartNameNodes();
      cluster.waitActive();
      fns = cluster.getNamesystem();
      assertTrue(fns.getBlockManager().hasNonEcBlockUsingStripedID());

      cluster.shutdown();
      cluster = null;
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  @Test
  public void testHasNonEcBlockUsingStripedIDForLoadSnapshot()
      throws IOException{
    // start a cluster
    Configuration conf = new HdfsConfiguration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(9)
          .build();
      cluster.waitActive();
      DistributedFileSystem fs = cluster.getFileSystem();
      FSNamesystem fns = cluster.getNamesystem();

      String testDir = "/test_block_manager";
      String testFile = "testfile_loadSnapshot";
      String testFilePath = testDir + "/" + testFile;
      String clientName = "testUser_loadSnapshot";
      String clientMachine = "testMachine_loadSnapshot";
      long blkId = -1;
      long blkNumBytes = 1024;
      long timestamp = 1426222918;

      Path d = new Path(testDir);
      fs.mkdir(d, new FsPermission("755"));
      fs.allowSnapshot(d);

      Path p = new Path(testFilePath);
      DFSTestUtil.createFile(fs, p, 0, (short) 1, 1);
      BlockInfoContiguous cBlk = new BlockInfoContiguous(
          new Block(blkId, blkNumBytes, timestamp), (short)3);
      INodeFile file = (INodeFile)fns.getFSDirectory().getINode(testFilePath);
      file.toUnderConstruction(clientName, clientMachine);
      file.addBlock(cBlk);
      TestINodeFile.toCompleteFile(file);

      fs.createSnapshot(d,"testHasNonEcBlockUsingStripeID");
      fs.truncate(p,0);
      fns.enterSafeMode(false);
      fns.saveNamespace(0, 0);
      cluster.restartNameNodes();
      cluster.waitActive();
      fns = cluster.getNamesystem();
      assertTrue(fns.getBlockManager().hasNonEcBlockUsingStripedID());

      cluster.shutdown();
      cluster = null;
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  @Test
  public void testBlockTypeProtoDefaultsToContiguous() throws Exception {
    INodeSection.INodeFile.Builder builder = INodeSection.INodeFile
        .newBuilder();
    INodeSection.INodeFile inodeFile = builder.build();
    BlockType defaultBlockType = PBHelperClient.convert(inodeFile
        .getBlockType());
    assertEquals(defaultBlockType, BlockType.CONTIGUOUS);
  }

  /**
   * Test if a INodeFile under a replication EC policy directory
   * can be saved by FSImageSerialization and loaded by FSImageFormat#Loader.
   */
  @Test
  public void testSaveAndLoadFileUnderReplicationPolicyDir()
      throws IOException {
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(conf).build();
      cluster.waitActive();
      FSNamesystem fsn = cluster.getNamesystem();
      DistributedFileSystem fs = cluster.getFileSystem();
      DFSTestUtil.enableAllECPolicies(fs);
      ErasureCodingPolicy replicaPolicy =
          SystemErasureCodingPolicies.getReplicationPolicy();
      ErasureCodingPolicy defaultEcPolicy =
          StripedFileTestUtil.getDefaultECPolicy();

      final Path ecDir = new Path("/ec");
      final Path replicaDir = new Path(ecDir, "replica");
      final Path replicaFile1 = new Path(replicaDir, "f1");
      final Path replicaFile2 = new Path(replicaDir, "f2");

      // create root directory
      fs.mkdir(ecDir, null);
      fs.setErasureCodingPolicy(ecDir, defaultEcPolicy.getName());

      // create directory, and set replication Policy
      fs.mkdir(replicaDir, null);
      fs.setErasureCodingPolicy(replicaDir, replicaPolicy.getName());

      // create an empty file f1
      fs.create(replicaFile1).close();

      // create an under-construction file f2
      FSDataOutputStream out = fs.create(replicaFile2, (short) 2);
      out.writeBytes("hello");
      ((DFSOutputStream) out.getWrappedStream()).hsync(EnumSet
          .of(SyncFlag.UPDATE_LENGTH));

      // checkpoint
      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fs.saveNamespace();
      fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

      cluster.restartNameNode();
      cluster.waitActive();
      fs = cluster.getFileSystem();

      assertTrue(fs.getFileStatus(ecDir).isDirectory());
      assertTrue(fs.getFileStatus(replicaDir).isDirectory());
      assertTrue(fs.exists(replicaFile1));
      assertTrue(fs.exists(replicaFile2));

      // check directories
      assertEquals("Directory should have default EC policy.",
          defaultEcPolicy, fs.getErasureCodingPolicy(ecDir));
      assertEquals("Directory should hide replication EC policy.",
          null, fs.getErasureCodingPolicy(replicaDir));

      // check file1
      assertEquals("File should not have EC policy.", null,
          fs.getErasureCodingPolicy(replicaFile1));
      // check internals of file2
      INodeFile file2Node =
          fsn.dir.getINode4Write(replicaFile2.toString()).asFile();
      assertEquals("hello".length(), file2Node.computeFileSize());
      assertTrue(file2Node.isUnderConstruction());
      BlockInfo[] blks = file2Node.getBlocks();
      assertEquals(1, blks.length);
      assertEquals(BlockUCState.UNDER_CONSTRUCTION, blks[0].getBlockUCState());
      assertEquals("File should return expected replication factor.",
          2, blks[0].getReplication());
      assertEquals("File should not have EC policy.", null,
          fs.getErasureCodingPolicy(replicaFile2));
      // check lease manager
      Lease lease = fsn.leaseManager.getLease(file2Node);
      Assert.assertNotNull(lease);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * Test persist and load erasure coding policies.
   */
  @Test
  public void testSaveAndLoadErasureCodingPolicies() throws IOException{
    Configuration conf = new Configuration();
    final int blockSize = 16 * 1024 * 1024;
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, blockSize);
    try (MiniDFSCluster cluster =
             new MiniDFSCluster.Builder(conf).numDataNodes(10).build()) {
      cluster.waitActive();
      DistributedFileSystem fs = cluster.getFileSystem();
      DFSTestUtil.enableAllECPolicies(fs);

      // Save namespace and restart NameNode
      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fs.saveNamespace();
      fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

      cluster.restartNameNodes();
      cluster.waitActive();

      assertEquals("Erasure coding policy number should match",
          SystemErasureCodingPolicies.getPolicies().size(),
          ErasureCodingPolicyManager.getInstance().getPolicies().length);

      // Add new erasure coding policy
      ECSchema newSchema = new ECSchema("rs", 5, 4);
      ErasureCodingPolicy newPolicy =
          new ErasureCodingPolicy(newSchema, 2 * 1024, (byte) 254);
      ErasureCodingPolicy[] policies = new ErasureCodingPolicy[]{newPolicy};
      AddErasureCodingPolicyResponse[] ret =
          fs.addErasureCodingPolicies(policies);
      assertEquals(1, ret.length);
      assertEquals(true, ret[0].isSucceed());
      newPolicy = ret[0].getPolicy();

      // Save namespace and restart NameNode
      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fs.saveNamespace();
      fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

      cluster.restartNameNodes();
      cluster.waitActive();

      assertEquals("Erasure coding policy number should match",
          SystemErasureCodingPolicies.getPolicies().size() + 1,
          ErasureCodingPolicyManager.getInstance().getPolicies().length);
      ErasureCodingPolicy ecPolicy =
          ErasureCodingPolicyManager.getInstance().getByID(newPolicy.getId());
      assertEquals("Newly added erasure coding policy is not found",
          newPolicy, ecPolicy);
      assertEquals(
          "Newly added erasure coding policy should be of disabled state",
          ErasureCodingPolicyState.DISABLED,
          DFSTestUtil.getECPolicyState(ecPolicy));

      // Test enable/disable/remove user customized erasure coding policy
      testChangeErasureCodingPolicyState(cluster, blockSize, newPolicy, false);
      // Test enable/disable default built-in erasure coding policy
      testChangeErasureCodingPolicyState(cluster, blockSize,
          SystemErasureCodingPolicies.getByID((byte) 1), true);
      // Test enable/disable non-default built-in erasure coding policy
      testChangeErasureCodingPolicyState(cluster, blockSize,
          SystemErasureCodingPolicies.getByID((byte) 2), false);
    }
  }

  private void testChangeErasureCodingPolicyState(MiniDFSCluster cluster,
      int blockSize, ErasureCodingPolicy targetPolicy, boolean isDefault)
      throws IOException {
    DistributedFileSystem fs = cluster.getFileSystem();

    // 1. Enable an erasure coding policy
    fs.enableErasureCodingPolicy(targetPolicy.getName());
    // Create file, using the new policy
    final Path dirPath = new Path("/striped");
    final Path filePath = new Path(dirPath, "file");
    final int fileLength = blockSize * targetPolicy.getNumDataUnits();
    fs.mkdirs(dirPath);
    fs.setErasureCodingPolicy(dirPath, targetPolicy.getName());
    final byte[] bytes = StripedFileTestUtil.generateBytes(fileLength);
    DFSTestUtil.writeFile(fs, filePath, bytes);


    // Save namespace and restart NameNode
    fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
    fs.saveNamespace();
    fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

    cluster.restartNameNodes();
    cluster.waitActive();
    ErasureCodingPolicy ecPolicy =
        ErasureCodingPolicyManager.getInstance().getByID(targetPolicy.getId());
    assertEquals("The erasure coding policy is not found",
        targetPolicy, ecPolicy);
    assertEquals("The erasure coding policy should be of enabled state",
        ErasureCodingPolicyState.ENABLED,
        DFSTestUtil.getECPolicyState(ecPolicy));
    assertTrue("Policy should be in disabled state in FSImage!",
        isPolicyEnabledInFsImage(targetPolicy));

    // Read file regardless of the erasure coding policy state
    DFSTestUtil.readFileAsBytes(fs, filePath);

    // 2. Disable an erasure coding policy
    fs.disableErasureCodingPolicy(ecPolicy.getName());
    // Save namespace and restart NameNode
    fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
    fs.saveNamespace();
    fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

    cluster.restartNameNodes();
    cluster.waitActive();
    ecPolicy =
        ErasureCodingPolicyManager.getInstance().getByID(targetPolicy.getId());
    assertEquals("The erasure coding policy is not found",
        targetPolicy, ecPolicy);
    ErasureCodingPolicyState ecPolicyState =
        DFSTestUtil.getECPolicyState(ecPolicy);
    if (isDefault) {
      assertEquals("The erasure coding policy should be of " +
              "enabled state", ErasureCodingPolicyState.ENABLED, ecPolicyState);
    } else {
      assertEquals("The erasure coding policy should be of " +
          "disabled state", ErasureCodingPolicyState.DISABLED, ecPolicyState);
    }
    assertFalse("Policy should be in disabled state in FSImage!",
        isPolicyEnabledInFsImage(targetPolicy));

    // Read file regardless of the erasure coding policy state
    DFSTestUtil.readFileAsBytes(fs, filePath);

    // 3. Remove an erasure coding policy
    try {
      fs.removeErasureCodingPolicy(ecPolicy.getName());
    } catch (RemoteException e) {
      // built-in policy cannot been removed
      assertTrue("Built-in policy cannot be removed",
          ecPolicy.isSystemPolicy());
      assertExceptionContains("System erasure coding policy", e);
      return;
    }

    fs.removeErasureCodingPolicy(ecPolicy.getName());
    // Save namespace and restart NameNode
    fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
    fs.saveNamespace();
    fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

    cluster.restartNameNodes();
    cluster.waitActive();
    ecPolicy = ErasureCodingPolicyManager.getInstance().getByID(
        targetPolicy.getId());
    assertEquals("The erasure coding policy saved into and loaded from " +
        "fsImage is bad", targetPolicy, ecPolicy);
    assertEquals("The erasure coding policy should be of removed state",
        ErasureCodingPolicyState.REMOVED,
        DFSTestUtil.getECPolicyState(ecPolicy));
    // Read file regardless of the erasure coding policy state
    DFSTestUtil.readFileAsBytes(fs, filePath);
    fs.delete(dirPath, true);
  }

  private boolean isPolicyEnabledInFsImage(ErasureCodingPolicy testPolicy) {
    ErasureCodingPolicyInfo[] persistedPolicies =
        ErasureCodingPolicyManager.getInstance().getPersistedPolicies();
    for (ErasureCodingPolicyInfo p : persistedPolicies) {
      if(p.getPolicy().getName().equals(testPolicy.getName())) {
        return p.isEnabled();
      }
    }
    throw new AssertionError("Policy is not found!");
  }

  private ArrayList<Section> getSubSectionsOfName(ArrayList<Section> sections,
      FSImageFormatProtobuf.SectionName name) {
    ArrayList<Section> subSec = new ArrayList<>();
    for (Section s : sections) {
      if (s.getName().equals(name.toString())) {
        subSec.add(s);
      }
    }
    return subSec;
  }

  private MiniDFSCluster createAndLoadParallelFSImage(Configuration conf)
    throws IOException {
    conf.set(DFSConfigKeys.DFS_IMAGE_PARALLEL_LOAD_KEY, "true");
    conf.set(DFSConfigKeys.DFS_IMAGE_PARALLEL_INODE_THRESHOLD_KEY, "1");
    conf.set(DFSConfigKeys.DFS_IMAGE_PARALLEL_TARGET_SECTIONS_KEY, "4");
    conf.set(DFSConfigKeys.DFS_IMAGE_PARALLEL_THREADS_KEY, "4");

    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).build();
    cluster.waitActive();
    DistributedFileSystem fs = cluster.getFileSystem();

    // Create 10 directories, each containing 5 files
    String baseDir = "/abc/def";
    for (int i=0; i<10; i++) {
      Path dir = new Path(baseDir+"/"+i);
      for (int j=0; j<5; j++) {
        Path f = new Path(dir, Integer.toString(j));
        FSDataOutputStream os = fs.create(f);
        os.write(1);
        os.close();
      }
    }

    // checkpoint
    fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
    fs.saveNamespace();
    fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

    cluster.restartNameNode();
    cluster.waitActive();
    fs = cluster.getFileSystem();

    // Ensure all the files created above exist, proving they were loaded
    // correctly
    for (int i=0; i<10; i++) {
      Path dir = new Path(baseDir+"/"+i);
      assertTrue(fs.getFileStatus(dir).isDirectory());
      for (int j=0; j<5; j++) {
        Path f = new Path(dir, Integer.toString(j));
        assertTrue(fs.exists(f));
      }
    }
    return cluster;
  }

  @Test
  public void testParallelSaveAndLoad() throws IOException {
    Configuration conf = new Configuration();

    MiniDFSCluster cluster = null;
    try {
      cluster = createAndLoadParallelFSImage(conf);

      // Obtain the image summary section to check the sub-sections
      // are being correctly created when the image is saved.
      FsImageProto.FileSummary summary = FSImageTestUtil.
          getLatestImageSummary(cluster);
      ArrayList<Section> sections = Lists.newArrayList(
          summary.getSectionsList());

      ArrayList<Section> inodeSubSections =
          getSubSectionsOfName(sections, SectionName.INODE_SUB);
      ArrayList<Section> dirSubSections =
          getSubSectionsOfName(sections, SectionName.INODE_DIR_SUB);
      Section inodeSection =
          getSubSectionsOfName(sections, SectionName.INODE).get(0);
      Section dirSection = getSubSectionsOfName(sections,
              SectionName.INODE_DIR).get(0);

      // Expect 4 sub-sections for inodes and directories as target Sections
      // is 4
      assertEquals(4, inodeSubSections.size());
      assertEquals(4, dirSubSections.size());

      // Expect the sub-section offset and lengths do not overlap and cover a
      // continuous range of the file. They should also line up with the parent
      ensureSubSectionsAlignWithParent(inodeSubSections, inodeSection);
      ensureSubSectionsAlignWithParent(dirSubSections, dirSection);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  @Test
  public void testNoParallelSectionsWithCompressionEnabled()
      throws IOException {
    Configuration conf = new Configuration();
    conf.setBoolean(DFSConfigKeys.DFS_IMAGE_COMPRESS_KEY, true);
    conf.set(DFSConfigKeys.DFS_IMAGE_COMPRESSION_CODEC_KEY,
        "org.apache.hadoop.io.compress.GzipCodec");

    MiniDFSCluster cluster = null;
    try {
      cluster = createAndLoadParallelFSImage(conf);

      // Obtain the image summary section to check the sub-sections
      // are being correctly created when the image is saved.
      FsImageProto.FileSummary summary = FSImageTestUtil.
          getLatestImageSummary(cluster);
      ArrayList<Section> sections = Lists.newArrayList(
          summary.getSectionsList());

      ArrayList<Section> inodeSubSections =
          getSubSectionsOfName(sections, SectionName.INODE_SUB);
      ArrayList<Section> dirSubSections =
          getSubSectionsOfName(sections, SectionName.INODE_DIR_SUB);

      // As compression is enabled, there should be no sub-sections in the
      // image header
      assertEquals(0, inodeSubSections.size());
      assertEquals(0, dirSubSections.size());
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  private void ensureSubSectionsAlignWithParent(ArrayList<Section> subSec,
      Section parent) {
    // For each sub-section, check its offset + length == the next section
    // offset
    for (int i=0; i<subSec.size()-1; i++) {
      Section s = subSec.get(i);
      long endOffset = s.getOffset() + s.getLength();
      assertEquals(subSec.get(i+1).getOffset(), endOffset);
    }
    // The last sub-section should align with the parent section
    Section lastSubSection = subSec.get(subSec.size()-1);
    assertEquals(parent.getLength()+parent.getOffset(),
        lastSubSection.getLength() + lastSubSection.getOffset());
    // The first sub-section and parent section should have the same offset
    assertEquals(parent.getOffset(), subSec.get(0).getOffset());
  }

  @Test
  public void testUpdateBlocksMapAndNameCacheAsync() throws IOException {
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).build();
    cluster.waitActive();
    DistributedFileSystem fs = cluster.getFileSystem();
    FSDirectory fsdir = cluster.getNameNode().namesystem.getFSDirectory();
    File workingDir = GenericTestUtils.getTestDir();

    File preRestartTree = new File(workingDir, "preRestartTree");
    File postRestartTree = new File(workingDir, "postRestartTree");

    Path baseDir = new Path("/user/foo");
    fs.mkdirs(baseDir);
    fs.allowSnapshot(baseDir);
    for (int i = 0; i < 5; i++) {
      Path dir = new Path(baseDir, Integer.toString(i));
      fs.mkdirs(dir);
      for (int j = 0; j < 5; j++) {
        Path file = new Path(dir, Integer.toString(j));
        FSDataOutputStream os = fs.create(file);
        os.write((byte) j);
        os.close();
      }
      fs.createSnapshot(baseDir, "snap_"+i);
      fs.rename(new Path(dir, "0"), new Path(dir, "renamed"));
    }
    SnapshotTestHelper.dumpTree2File(fsdir, preRestartTree);

    // checkpoint
    fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
    fs.saveNamespace();
    fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

    cluster.restartNameNode();
    cluster.waitActive();
    fs = cluster.getFileSystem();
    fsdir = cluster.getNameNode().namesystem.getFSDirectory();

    // Ensure all the files created above exist, and blocks is correct.
    for (int i = 0; i < 5; i++) {
      Path dir = new Path(baseDir, Integer.toString(i));
      assertTrue(fs.getFileStatus(dir).isDirectory());
      for (int j = 0; j < 5; j++) {
        Path file = new Path(dir, Integer.toString(j));
        if (j == 0) {
          file = new Path(dir, "renamed");
        }
        FSDataInputStream in = fs.open(file);
        int n = in.readByte();
        assertEquals(j, n);
        in.close();
      }
    }
    SnapshotTestHelper.dumpTree2File(fsdir, postRestartTree);
    SnapshotTestHelper.compareDumpedTreeInFile(
        preRestartTree, postRestartTree, true);
  }
}