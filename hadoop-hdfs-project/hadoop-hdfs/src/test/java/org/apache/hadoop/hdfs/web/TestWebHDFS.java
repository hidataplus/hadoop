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

package org.apache.hadoop.hdfs.web;

import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.FS_TRASH_INTERVAL_KEY;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.IO_FILE_BUFFER_SIZE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_BLOCK_SIZE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CHECKSUM_TYPE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_ENCRYPT_DATA_TRANSFER_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_REPLICATION_KEY;
import static org.apache.hadoop.hdfs.TestDistributedFileSystem.checkOpStatistics;
import static org.apache.hadoop.hdfs.TestDistributedFileSystem.checkStatistics;
import static org.apache.hadoop.hdfs.TestDistributedFileSystem.getOpStatistics;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_BYTES_PER_CHECKSUM_KEY;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_WRITE_PACKET_SIZE_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;

import org.apache.hadoop.thirdparty.com.google.common.collect.ImmutableList;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.fs.QuotaUsage;
import org.apache.hadoop.hdfs.DFSOpsCountStatistics;
import org.apache.hadoop.test.LambdaTestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.BlockStoragePolicySpi;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileSystemTestHelper;
import org.apache.hadoop.fs.FsServerDefaults;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.fs.contract.ContractTestUtils;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclEntryScope;
import org.apache.hadoop.fs.permission.AclEntryType;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.TestDFSClientRetries;
import org.apache.hadoop.hdfs.TestFileCreation;
import org.apache.hadoop.hdfs.client.CreateEncryptionZoneFlag;
import org.apache.hadoop.hdfs.client.HdfsAdmin;
import org.apache.hadoop.hdfs.client.HdfsClientConfigKeys;
import org.apache.hadoop.hdfs.protocol.DSQuotaExceededException;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicy;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicyInfo;
import org.apache.hadoop.hdfs.protocol.SystemErasureCodingPolicies;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.StoragePolicySatisfierMode;
import org.apache.hadoop.hdfs.protocol.SnapshotDiffReport;
import static org.apache.hadoop.hdfs.protocol.SnapshotDiffReport.DiffType;
import static org.apache.hadoop.hdfs.protocol.SnapshotDiffReport.DiffReportEntry;
import org.apache.hadoop.hdfs.protocol.SnapshottableDirectoryStatus;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.NameNodeAdapter;
import org.apache.hadoop.hdfs.server.namenode.snapshot.SnapshotTestHelper;
import org.apache.hadoop.hdfs.server.namenode.sps.StoragePolicySatisfier;
import org.apache.hadoop.hdfs.server.namenode.web.resources.NamenodeWebHdfsMethods;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;
import org.apache.hadoop.hdfs.server.sps.ExternalSPSContext;
import org.apache.hadoop.hdfs.web.WebHdfsFileSystem.WebHdfsInputStream;
import org.apache.hadoop.hdfs.web.resources.LengthParam;
import org.apache.hadoop.hdfs.web.resources.NoRedirectParam;
import org.apache.hadoop.hdfs.web.resources.OffsetParam;
import org.apache.hadoop.hdfs.web.resources.Param;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.io.retry.RetryPolicy.RetryAction;
import org.apache.hadoop.io.retry.RetryPolicy.RetryAction.RetryDecision;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.ipc.RetriableException;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.token.SecretManager.InvalidToken;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.test.Whitebox;
import org.slf4j.event.Level;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Test WebHDFS */
public class TestWebHDFS {
  static final Logger LOG = LoggerFactory.getLogger(TestWebHDFS.class);
  
  static final Random RANDOM = new Random();
  
  static final long systemStartTime = System.nanoTime();

  private static MiniDFSCluster cluster = null;

  @After
  public void tearDown() {
    if (null != cluster) {
      cluster.shutdown();
      cluster = null;
    }
  }

  /** A timer for measuring performance. */
  static class Ticker {
    final String name;
    final long startTime = System.nanoTime();
    private long previousTick = startTime;

    Ticker(final String name, String format, Object... args) {
      this.name = name;
      LOG.info(String.format("\n\n%s START: %s\n",
          name, String.format(format, args)));
    }

    void tick(final long nBytes, String format, Object... args) {
      final long now = System.nanoTime();
      if (now - previousTick > 10000000000L) {
        previousTick = now;
        final double mintues = (now - systemStartTime)/60000000000.0;
        LOG.info(String.format("\n\n%s %.2f min) %s %s\n", name, mintues,
            String.format(format, args), toMpsString(nBytes, now)));
      }
    }
    
    void end(final long nBytes) {
      final long now = System.nanoTime();
      final double seconds = (now - startTime)/1000000000.0;
      LOG.info(String.format("\n\n%s END: duration=%.2fs %s\n",
          name, seconds, toMpsString(nBytes, now)));
    }
    
    String toMpsString(final long nBytes, final long now) {
      final double mb = nBytes/(double)(1<<20);
      final double mps = mb*1000000000.0/(now - startTime);
      return String.format("[nBytes=%.2fMB, speed=%.2fMB/s]", mb, mps);
    }
  }

  @Test(timeout=300000)
  public void testLargeFile() throws Exception {
    largeFileTest(200L << 20); //200MB file length
  }

  /** Test read and write large files. */
  static void largeFileTest(final long fileLength) throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();

    cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(3)
        .build();
    cluster.waitActive();

    final FileSystem fs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    final Path dir = new Path("/test/largeFile");
    Assert.assertTrue(fs.mkdirs(dir));

    final byte[] data = new byte[1 << 20];
    RANDOM.nextBytes(data);

    final byte[] expected = new byte[2 * data.length];
    System.arraycopy(data, 0, expected, 0, data.length);
    System.arraycopy(data, 0, expected, data.length, data.length);

    final Path p = new Path(dir, "file");
    final Ticker t = new Ticker("WRITE", "fileLength=" + fileLength);
    final FSDataOutputStream out = fs.create(p);
    try {
      long remaining = fileLength;
      for (; remaining > 0;) {
        t.tick(fileLength - remaining, "remaining=%d", remaining);

        final int n = (int) Math.min(remaining, data.length);
        out.write(data, 0, n);
        remaining -= n;
      }
    } finally {
      out.close();
    }
    t.end(fileLength);

    Assert.assertEquals(fileLength, fs.getFileStatus(p).getLen());

    final long smallOffset = RANDOM.nextInt(1 << 20) + (1 << 20);
    final long largeOffset = fileLength - smallOffset;
    final byte[] buf = new byte[data.length];

    verifySeek(fs, p, largeOffset, fileLength, buf, expected);
    verifySeek(fs, p, smallOffset, fileLength, buf, expected);

    verifyPread(fs, p, largeOffset, fileLength, buf, expected);
  }

  static void checkData(long offset, long remaining, int n,
      byte[] actual, byte[] expected) {
    if (RANDOM.nextInt(100) == 0) {
      int j = (int)(offset % actual.length);
      for(int i = 0; i < n; i++) {
        if (expected[j] != actual[i]) {
          Assert.fail("expected[" + j + "]=" + expected[j]
              + " != actual[" + i + "]=" + actual[i]
              + ", offset=" + offset + ", remaining=" + remaining + ", n=" + n);
        }
        j++;
      }
    }
  }

  /** test seek */
  static void verifySeek(FileSystem fs, Path p, long offset, long length,
      byte[] buf, byte[] expected) throws IOException { 
    long remaining = length - offset;
    long checked = 0;
    LOG.info("XXX SEEK: offset=" + offset + ", remaining=" + remaining);

    final Ticker t = new Ticker("SEEK", "offset=%d, remaining=%d",
        offset, remaining);
    final FSDataInputStream in = fs.open(p, 64 << 10);
    in.seek(offset);
    for(; remaining > 0; ) {
      t.tick(checked, "offset=%d, remaining=%d", offset, remaining);
      final int n = (int)Math.min(remaining, buf.length);
      in.readFully(buf, 0, n);
      checkData(offset, remaining, n, buf, expected);

      offset += n;
      remaining -= n;
      checked += n;
    }
    in.close();
    t.end(checked);
  }

  static void verifyPread(FileSystem fs, Path p, long offset, long length,
      byte[] buf, byte[] expected) throws IOException {
    long remaining = length - offset;
    long checked = 0;
    LOG.info("XXX PREAD: offset=" + offset + ", remaining=" + remaining);

    final Ticker t = new Ticker("PREAD", "offset=%d, remaining=%d",
        offset, remaining);
    final FSDataInputStream in = fs.open(p, 64 << 10);
    for(; remaining > 0; ) {
      t.tick(checked, "offset=%d, remaining=%d", offset, remaining);
      final int n = (int)Math.min(remaining, buf.length);
      in.readFully(offset, buf, 0, n);
      checkData(offset, remaining, n, buf, expected);

      offset += n;
      remaining -= n;
      checked += n;
    }
    in.close();
    t.end(checked);
  }

  /** Test client retry with namenode restarting. */
  @Test(timeout=300000)
  public void testNamenodeRestart() throws Exception {
    GenericTestUtils.setLogLevel(NamenodeWebHdfsMethods.LOG, Level.TRACE);
    final Configuration conf = WebHdfsTestUtil.createConf();
    TestDFSClientRetries.namenodeRestartTest(conf, true);
  }
  
  @Test(timeout=300000)
  public void testLargeDirectory() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    final int listLimit = 2;
    // force small chunking of directory listing
    conf.setInt(DFSConfigKeys.DFS_LIST_LIMIT, listLimit);
    // force paths to be only owner-accessible to ensure ugi isn't changing
    // during listStatus
    FsPermission.setUMask(conf, new FsPermission((short)0077));
    
    cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(3).build();
    cluster.waitActive();
    WebHdfsTestUtil.getWebHdfsFileSystem(conf, WebHdfsConstants.WEBHDFS_SCHEME)
        .setPermission(new Path("/"),
            new FsPermission(FsAction.ALL, FsAction.ALL, FsAction.ALL));

    // trick the NN into not believing it's not the superuser so we can
    // tell if the correct user is used by listStatus
    UserGroupInformation.setLoginUser(UserGroupInformation.createUserForTesting(
        "not-superuser", new String[] {"not-supergroup" }));

    UserGroupInformation.createUserForTesting("me", new String[] {"my-group" })
        .doAs(new PrivilegedExceptionAction<Void>() {
          @Override
          public Void run() throws IOException, URISyntaxException {
            FileSystem fs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
                WebHdfsConstants.WEBHDFS_SCHEME);
            Path d = new Path("/my-dir");
            Assert.assertTrue(fs.mkdirs(d));
            // Iterator should have no items when dir is empty
            RemoteIterator<FileStatus> it = fs.listStatusIterator(d);
            assertFalse(it.hasNext());
            Path p = new Path(d, "file-" + 0);
            Assert.assertTrue(fs.createNewFile(p));
            // Iterator should have an item when dir is not empty
            it = fs.listStatusIterator(d);
            assertTrue(it.hasNext());
            it.next();
            assertFalse(it.hasNext());
            for (int i = 1; i < listLimit * 3; i++) {
              p = new Path(d, "file-" + i);
              Assert.assertTrue(fs.createNewFile(p));
            }
            // Check the FileStatus[] listing
            FileStatus[] statuses = fs.listStatus(d);
            Assert.assertEquals(listLimit * 3, statuses.length);
            // Check the iterator-based listing
            GenericTestUtils.setLogLevel(WebHdfsFileSystem.LOG, Level.TRACE);
            GenericTestUtils.setLogLevel(NamenodeWebHdfsMethods.LOG,
                Level.TRACE);
            it = fs.listStatusIterator(d);
            int count = 0;
            while (it.hasNext()) {
              FileStatus stat = it.next();
              assertEquals("FileStatuses not equal", statuses[count], stat);
              count++;
            }
            assertEquals("Different # of statuses!", statuses.length, count);
            // Do some more basic iterator tests
            it = fs.listStatusIterator(d);
            // Try advancing the iterator without calling hasNext()
            for (int i = 0; i < statuses.length; i++) {
              FileStatus stat = it.next();
              assertEquals("FileStatuses not equal", statuses[i], stat);
            }
            assertFalse("No more items expected", it.hasNext());
            // Try doing next when out of items
            try {
              it.next();
              fail("Iterator should error if out of elements.");
            } catch (NoSuchElementException e) {
              // pass
            }
            return null;
          }
        });
  }

  /**
   * Test client receives correct DSQuotaExceededException.
   */
  @Test
  public void testExceedingFileSpaceQuota() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    long spaceQuota = 50L << 20;
    long fileLength = 80L << 20;

    cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(3)
        .build();

    cluster.waitActive();

    final FileSystem fs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    final Path dir = new Path("/test/largeFile");
    assertTrue(fs.mkdirs(dir));

    final byte[] data = new byte[1 << 20];
    RANDOM.nextBytes(data);

    cluster.getFileSystem().setQuota(dir, HdfsConstants.QUOTA_DONT_SET,
        spaceQuota);

    final Path p = new Path(dir, "file");

    FSDataOutputStream out = fs.create(p);
    try {
      for (long remaining = fileLength; remaining > 0;) {
        final int n = (int) Math.min(remaining, data.length);
        out.write(data, 0, n);
        remaining -= n;
      }
      fail("should have thrown exception during the write");
    } catch (DSQuotaExceededException e) {
      // expected
    } finally {
      try {
        out.close();
      } catch (Exception e) {
        // discard exception from close
      }
    }
  }

  @Test(timeout=300000)
  public void testCustomizedUserAndGroupNames() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    conf.setBoolean(DFSConfigKeys.DFS_NAMENODE_ACLS_ENABLED_KEY, true);
    // Modify username pattern to allow numeric usernames
    conf.set(HdfsClientConfigKeys.DFS_WEBHDFS_USER_PATTERN_KEY, "^[A-Za-z0-9_][A-Za-z0-9" +
        "._-]*[$]?$");
    // Modify acl pattern to allow numeric and "@" characters user/groups in ACL spec
    conf.set(HdfsClientConfigKeys.DFS_WEBHDFS_ACL_PERMISSION_PATTERN_KEY,
        "^(default:)?(user|group|mask|other):" +
            "[[0-9A-Za-z_][@A-Za-z0-9._-]]*:([rwx-]{3})?(,(default:)?" +
            "(user|group|mask|other):[[0-9A-Za-z_][@A-Za-z0-9._-]]*:([rwx-]{3})?)*$");
    cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    cluster.waitActive();
    WebHdfsTestUtil.getWebHdfsFileSystem(conf, WebHdfsConstants.WEBHDFS_SCHEME)
        .setPermission(new Path("/"),
            new FsPermission(FsAction.ALL, FsAction.ALL, FsAction.ALL));

    // Test a numeric username
    UserGroupInformation
        .createUserForTesting("123", new String[] {"my-group" })
        .doAs(new PrivilegedExceptionAction<Void>() {
          @Override
          public Void run() throws IOException, URISyntaxException {
            FileSystem fs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
                WebHdfsConstants.WEBHDFS_SCHEME);
            Path d = new Path("/my-dir");
            Assert.assertTrue(fs.mkdirs(d));
            // Test also specifying a default ACL with a numeric username
            // and another of a groupname with '@'
            fs.modifyAclEntries(d, ImmutableList.of(new AclEntry.Builder()
                .setPermission(FsAction.READ).setScope(AclEntryScope.DEFAULT)
                .setType(AclEntryType.USER).setName("11010").build(),
                new AclEntry.Builder().setPermission(FsAction.READ_WRITE)
                    .setType(AclEntryType.GROUP).setName("foo@bar").build()));
            return null;
          }
        });
  }

  /**
   * Test for catching "no datanode" IOException, when to create a file
   * but datanode is not running for some reason.
   */
  @Test(timeout=300000)
  public void testCreateWithNoDN() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      conf.setInt(DFSConfigKeys.DFS_REPLICATION_KEY, 1);
      cluster.waitActive();
      FileSystem fs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
          WebHdfsConstants.WEBHDFS_SCHEME);
      fs.create(new Path("/testnodatanode"));
      Assert.fail("No exception was thrown");
    } catch (IOException ex) {
      GenericTestUtils.assertExceptionContains("Failed to find datanode", ex);
    }
  }

  /**
   * Test allow and disallow snapshot through WebHdfs. Verifying webhdfs with
   * Distributed filesystem methods.
   */
  @Test
  public void testWebHdfsAllowandDisallowSnapshots() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
    cluster.waitActive();
    final DistributedFileSystem dfs = cluster.getFileSystem();
    final WebHdfsFileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);

    final Path bar = new Path("/bar");
    dfs.mkdirs(bar);

    // allow snapshots on /bar using webhdfs
    webHdfs.allowSnapshot(bar);
    // check if snapshot status is enabled
    assertTrue(dfs.getFileStatus(bar).isSnapshotEnabled());
    assertTrue(webHdfs.getFileStatus(bar).isSnapshotEnabled());
    webHdfs.createSnapshot(bar, "s1");
    final Path s1path = SnapshotTestHelper.getSnapshotRoot(bar, "s1");
    Assert.assertTrue(webHdfs.exists(s1path));
    SnapshottableDirectoryStatus[] snapshottableDirs =
        dfs.getSnapshottableDirListing();
    assertEquals(1, snapshottableDirs.length);
    assertEquals(bar, snapshottableDirs[0].getFullPath());
    dfs.deleteSnapshot(bar, "s1");
    dfs.disallowSnapshot(bar);
    // check if snapshot status is disabled
    assertFalse(dfs.getFileStatus(bar).isSnapshotEnabled());
    assertFalse(webHdfs.getFileStatus(bar).isSnapshotEnabled());
    snapshottableDirs = dfs.getSnapshottableDirListing();
    assertNull(snapshottableDirs);

    // disallow snapshots on /bar using webhdfs
    dfs.allowSnapshot(bar);
    // check if snapshot status is enabled, again
    assertTrue(dfs.getFileStatus(bar).isSnapshotEnabled());
    assertTrue(webHdfs.getFileStatus(bar).isSnapshotEnabled());
    snapshottableDirs = dfs.getSnapshottableDirListing();
    assertEquals(1, snapshottableDirs.length);
    assertEquals(bar, snapshottableDirs[0].getFullPath());
    webHdfs.disallowSnapshot(bar);
    // check if snapshot status is disabled, again
    assertFalse(dfs.getFileStatus(bar).isSnapshotEnabled());
    assertFalse(webHdfs.getFileStatus(bar).isSnapshotEnabled());
    snapshottableDirs = dfs.getSnapshottableDirListing();
    assertNull(snapshottableDirs);
    try {
      webHdfs.createSnapshot(bar);
      fail("Cannot create snapshot on a non-snapshottable directory");
    } catch (Exception e) {
      GenericTestUtils.assertExceptionContains(
          "Directory is not a snapshottable directory", e);
    }
  }

  @Test (timeout = 60000)
  public void testWebHdfsErasureCodingFiles() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(3).build();
    cluster.waitActive();
    final DistributedFileSystem dfs = cluster.getFileSystem();
    dfs.enableErasureCodingPolicy(SystemErasureCodingPolicies
        .getByID(SystemErasureCodingPolicies.XOR_2_1_POLICY_ID).getName());
    final WebHdfsFileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);

    final Path ecDir = new Path("/ec");
    dfs.mkdirs(ecDir);
    dfs.setErasureCodingPolicy(ecDir, SystemErasureCodingPolicies
        .getByID(SystemErasureCodingPolicies.XOR_2_1_POLICY_ID).getName());
    final Path ecFile = new Path(ecDir, "ec-file.log");
    DFSTestUtil.createFile(dfs, ecFile, 1024 * 10, (short) 1, 0xFEED);

    final Path normalDir = new Path("/dir");
    dfs.mkdirs(normalDir);
    final Path normalFile = new Path(normalDir, "file.log");
    DFSTestUtil.createFile(dfs, normalFile, 1024 * 10, (short) 1, 0xFEED);

    FileStatus expectedECDirStatus = dfs.getFileStatus(ecDir);
    FileStatus actualECDirStatus = webHdfs.getFileStatus(ecDir);
    Assert.assertEquals(expectedECDirStatus.isErasureCoded(),
        actualECDirStatus.isErasureCoded());
    ContractTestUtils.assertErasureCoded(dfs, ecDir);
    assertTrue(
        ecDir + " should have erasure coding set in "
            + "FileStatus#toString(): " + actualECDirStatus,
        actualECDirStatus.toString().contains("isErasureCoded=true"));

    FileStatus expectedECFileStatus = dfs.getFileStatus(ecFile);
    FileStatus actualECFileStatus = webHdfs.getFileStatus(ecFile);
    Assert.assertEquals(expectedECFileStatus.isErasureCoded(),
        actualECFileStatus.isErasureCoded());
    ContractTestUtils.assertErasureCoded(dfs, ecFile);
    assertTrue(
        ecFile + " should have erasure coding set in "
            + "FileStatus#toString(): " + actualECFileStatus,
        actualECFileStatus.toString().contains("isErasureCoded=true"));

    FileStatus expectedNormalDirStatus = dfs.getFileStatus(normalDir);
    FileStatus actualNormalDirStatus = webHdfs.getFileStatus(normalDir);
    Assert.assertEquals(expectedNormalDirStatus.isErasureCoded(),
        actualNormalDirStatus.isErasureCoded());
    ContractTestUtils.assertNotErasureCoded(dfs, normalDir);
    assertTrue(
        normalDir + " should have erasure coding unset in "
            + "FileStatus#toString(): " + actualNormalDirStatus,
        actualNormalDirStatus.toString().contains("isErasureCoded=false"));

    FileStatus expectedNormalFileStatus = dfs.getFileStatus(normalFile);
    FileStatus actualNormalFileStatus = webHdfs.getFileStatus(normalDir);
    Assert.assertEquals(expectedNormalFileStatus.isErasureCoded(),
        actualNormalFileStatus.isErasureCoded());
    ContractTestUtils.assertNotErasureCoded(dfs, normalFile);
    assertTrue(
        normalFile + " should have erasure coding unset in "
            + "FileStatus#toString(): " + actualNormalFileStatus,
        actualNormalFileStatus.toString().contains("isErasureCoded=false"));
  }

  /**
   * Test snapshot creation through WebHdfs.
   */
  @Test
  public void testWebHdfsCreateSnapshot() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
    cluster.waitActive();
    final DistributedFileSystem dfs = cluster.getFileSystem();
    final FileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);

    final Path foo = new Path("/foo");
    dfs.mkdirs(foo);

    try {
      webHdfs.createSnapshot(foo);
      fail("Cannot create snapshot on a non-snapshottable directory");
    } catch (Exception e) {
      GenericTestUtils.assertExceptionContains(
          "Directory is not a snapshottable directory", e);
    }

    // allow snapshots on /foo
    dfs.allowSnapshot(foo);
    // create snapshots on foo using WebHdfs
    webHdfs.createSnapshot(foo, "s1");
    // create snapshot without specifying name
    final Path spath = webHdfs.createSnapshot(foo, null);

    Assert.assertTrue(webHdfs.exists(spath));
    final Path s1path = SnapshotTestHelper.getSnapshotRoot(foo, "s1");
    Assert.assertTrue(webHdfs.exists(s1path));
  }

  /**
   * Test snapshot deletion through WebHdfs.
   */
  @Test
  public void testWebHdfsDeleteSnapshot() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
    cluster.waitActive();
    final DistributedFileSystem dfs = cluster.getFileSystem();
    final FileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);

    final Path foo = new Path("/foo");
    dfs.mkdirs(foo);
    dfs.allowSnapshot(foo);

    webHdfs.createSnapshot(foo, "s1");
    final Path spath = webHdfs.createSnapshot(foo, null);
    Assert.assertTrue(webHdfs.exists(spath));
    final Path s1path = SnapshotTestHelper.getSnapshotRoot(foo, "s1");
    Assert.assertTrue(webHdfs.exists(s1path));

    // delete operation snapshot name as null
    try {
      webHdfs.deleteSnapshot(foo, null);
      fail("Expected IllegalArgumentException");
    } catch (RemoteException e) {
      Assert.assertEquals("Required param snapshotname for "
          + "op: DELETESNAPSHOT is null or empty", e.getLocalizedMessage());
    }

    // delete the two snapshots
    webHdfs.deleteSnapshot(foo, "s1");
    assertFalse(webHdfs.exists(s1path));
    webHdfs.deleteSnapshot(foo, spath.getName());
    assertFalse(webHdfs.exists(spath));
  }

  /**
   * Test snapshot diff through WebHdfs.
   */
  @Test
  public void testWebHdfsSnapshotDiff() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    cluster.waitActive();
    final DistributedFileSystem dfs = cluster.getFileSystem();
    final WebHdfsFileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);

    final Path foo = new Path("/foo");
    dfs.mkdirs(foo);
    Path file0 = new Path(foo, "file0");
    DFSTestUtil.createFile(dfs, file0, 100, (short) 1, 0);
    Path file1 = new Path(foo, "file1");
    DFSTestUtil.createFile(dfs, file1, 100, (short) 1, 0);
    Path file2 = new Path(foo, "file2");
    DFSTestUtil.createFile(dfs, file2, 100, (short) 1, 0);

    dfs.allowSnapshot(foo);
    webHdfs.createSnapshot(foo, "s1");
    final Path s1path = SnapshotTestHelper.getSnapshotRoot(foo, "s1");
    Assert.assertTrue(webHdfs.exists(s1path));

    Path file3 = new Path(foo, "file3");
    DFSTestUtil.createFile(dfs, file3, 100, (short) 1, 0);
    DFSTestUtil.appendFile(dfs, file0, 100);
    dfs.delete(file1, false);
    Path file4 = new Path(foo, "file4");
    dfs.rename(file2, file4);

    webHdfs.createSnapshot(foo, "s2");
    SnapshotDiffReport diffReport =
        webHdfs.getSnapshotDiffReport(foo, "s1", "s2");

    Assert.assertEquals("/foo", diffReport.getSnapshotRoot());
    Assert.assertEquals("s1", diffReport.getFromSnapshot());
    Assert.assertEquals("s2", diffReport.getLaterSnapshotName());
    DiffReportEntry entry0 =
        new DiffReportEntry(DiffType.MODIFY, DFSUtil.string2Bytes(""));
    DiffReportEntry entry1 =
        new DiffReportEntry(DiffType.MODIFY, DFSUtil.string2Bytes("file0"));
    DiffReportEntry entry2 =
        new DiffReportEntry(DiffType.DELETE, DFSUtil.string2Bytes("file1"));
    DiffReportEntry entry3 = new DiffReportEntry(DiffType.RENAME,
        DFSUtil.string2Bytes("file2"), DFSUtil.string2Bytes("file4"));
    DiffReportEntry entry4 =
        new DiffReportEntry(DiffType.CREATE, DFSUtil.string2Bytes("file3"));
    Assert.assertTrue(diffReport.getDiffList().contains(entry0));
    Assert.assertTrue(diffReport.getDiffList().contains(entry1));
    Assert.assertTrue(diffReport.getDiffList().contains(entry2));
    Assert.assertTrue(diffReport.getDiffList().contains(entry3));
    Assert.assertTrue(diffReport.getDiffList().contains(entry4));
    Assert.assertEquals(diffReport.getDiffList().size(), 5);

    // Test with fromSnapshot and toSnapshot as null.
    diffReport = webHdfs.getSnapshotDiffReport(foo, null, "s2");
    Assert.assertEquals(diffReport.getDiffList().size(), 0);
    diffReport = webHdfs.getSnapshotDiffReport(foo, "s1", null);
    Assert.assertEquals(diffReport.getDiffList().size(), 5);
  }

  /**
   * Test snapshottable directory list through WebHdfs.
   */
  @Test
  public void testWebHdfsSnapshottableDirectoryList() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    cluster.waitActive();
    final DistributedFileSystem dfs = cluster.getFileSystem();
    final WebHdfsFileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    final Path foo = new Path("/foo");
    final Path bar = new Path("/bar");
    dfs.mkdirs(foo);
    dfs.mkdirs(bar);
    SnapshottableDirectoryStatus[] statuses =
        webHdfs.getSnapshottableDirectoryList();
    Assert.assertNull(statuses);
    dfs.allowSnapshot(foo);
    dfs.allowSnapshot(bar);
    Path file0 = new Path(foo, "file0");
    DFSTestUtil.createFile(dfs, file0, 100, (short) 1, 0);
    Path file1 = new Path(bar, "file1");
    DFSTestUtil.createFile(dfs, file1, 100, (short) 1, 0);
    statuses = webHdfs.getSnapshottableDirectoryList();
    SnapshottableDirectoryStatus[] dfsStatuses =
        dfs.getSnapshottableDirListing();

    for (int i = 0; i < dfsStatuses.length; i++) {
      Assert.assertEquals(statuses[i].getSnapshotNumber(),
          dfsStatuses[i].getSnapshotNumber());
      Assert.assertEquals(statuses[i].getSnapshotQuota(),
          dfsStatuses[i].getSnapshotQuota());
      Assert.assertTrue(Arrays.equals(statuses[i].getParentFullPath(),
          dfsStatuses[i].getParentFullPath()));
      Assert.assertEquals(dfsStatuses[i].getDirStatus().getChildrenNum(),
          statuses[i].getDirStatus().getChildrenNum());
      Assert.assertEquals(dfsStatuses[i].getDirStatus().getModificationTime(),
          statuses[i].getDirStatus().getModificationTime());
      Assert.assertEquals(dfsStatuses[i].getDirStatus().isDir(),
          statuses[i].getDirStatus().isDir());
      Assert.assertEquals(dfsStatuses[i].getDirStatus().getAccessTime(),
          statuses[i].getDirStatus().getAccessTime());
      Assert.assertEquals(dfsStatuses[i].getDirStatus().getPermission(),
          statuses[i].getDirStatus().getPermission());
      Assert.assertEquals(dfsStatuses[i].getDirStatus().getOwner(),
          statuses[i].getDirStatus().getOwner());
      Assert.assertEquals(dfsStatuses[i].getDirStatus().getGroup(),
          statuses[i].getDirStatus().getGroup());
      Assert.assertEquals(dfsStatuses[i].getDirStatus().getPath(),
          statuses[i].getDirStatus().getPath());
      Assert.assertEquals(dfsStatuses[i].getDirStatus().getFileId(),
          statuses[i].getDirStatus().getFileId());
      Assert.assertEquals(dfsStatuses[i].getDirStatus().hasAcl(),
          statuses[i].getDirStatus().hasAcl());
      Assert.assertEquals(dfsStatuses[i].getDirStatus().isEncrypted(),
          statuses[i].getDirStatus().isEncrypted());
      Assert.assertEquals(dfsStatuses[i].getDirStatus().isErasureCoded(),
          statuses[i].getDirStatus().isErasureCoded());
      Assert.assertEquals(dfsStatuses[i].getDirStatus().isSnapshotEnabled(),
          statuses[i].getDirStatus().isSnapshotEnabled());
    }
  }

  @Test
  public void testWebHdfsCreateNonRecursive() throws IOException, URISyntaxException {
    final Configuration conf = WebHdfsTestUtil.createConf();
    WebHdfsFileSystem webHdfs = null;

    try {
      cluster = new MiniDFSCluster.Builder(conf).build();
      cluster.waitActive();

      webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf, WebHdfsConstants.WEBHDFS_SCHEME);

      TestFileCreation.testFileCreationNonRecursive(webHdfs);

    } finally {
      if(webHdfs != null) {
       webHdfs.close();
      }
    }
  }
  /**
   * Test snapshot rename through WebHdfs.
   */
  @Test
  public void testWebHdfsRenameSnapshot() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
    cluster.waitActive();
    final DistributedFileSystem dfs = cluster.getFileSystem();
    final FileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);

    final Path foo = new Path("/foo");
    dfs.mkdirs(foo);
    dfs.allowSnapshot(foo);

    webHdfs.createSnapshot(foo, "s1");
    final Path s1path = SnapshotTestHelper.getSnapshotRoot(foo, "s1");
    Assert.assertTrue(webHdfs.exists(s1path));

    // rename s1 to s2 with oldsnapshotName as null
    try {
      webHdfs.renameSnapshot(foo, null, "s2");
      fail("Expected IllegalArgumentException");
    } catch (RemoteException e) {
      Assert.assertEquals("Required param oldsnapshotname for "
          + "op: RENAMESNAPSHOT is null or empty", e.getLocalizedMessage());
    }

    // rename s1 to s2
    webHdfs.renameSnapshot(foo, "s1", "s2");
    assertFalse(webHdfs.exists(s1path));
    final Path s2path = SnapshotTestHelper.getSnapshotRoot(foo, "s2");
    Assert.assertTrue(webHdfs.exists(s2path));

    webHdfs.deleteSnapshot(foo, "s2");
    assertFalse(webHdfs.exists(s2path));
  }

  /**
   * Make sure a RetriableException is thrown when rpcServer is null in
   * NamenodeWebHdfsMethods.
   */
  @Test
  public void testRaceWhileNNStartup() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
    cluster.waitActive();
    final NameNode namenode = cluster.getNameNode();
    final NamenodeProtocols rpcServer = namenode.getRpcServer();
    Whitebox.setInternalState(namenode, "rpcServer", null);

    final Path foo = new Path("/foo");
    final FileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    try {
      webHdfs.mkdirs(foo);
      fail("Expected RetriableException");
    } catch (RetriableException e) {
      GenericTestUtils.assertExceptionContains("Namenode is in startup mode",
          e);
    }
    Whitebox.setInternalState(namenode, "rpcServer", rpcServer);
  }

  @Test
  public void testDTInInsecureClusterWithFallback()
      throws IOException, URISyntaxException {
    final Configuration conf = WebHdfsTestUtil.createConf();
    conf.setBoolean(CommonConfigurationKeys
        .IPC_CLIENT_FALLBACK_TO_SIMPLE_AUTH_ALLOWED_KEY, true);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
    final FileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    Assert.assertNull(webHdfs.getDelegationToken(null));
  }

  @Test
  public void testDTInInsecureCluster() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
      final FileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
          WebHdfsConstants.WEBHDFS_SCHEME);
      webHdfs.getDelegationToken(null);
      fail("No exception is thrown.");
    } catch (AccessControlException ace) {
      Assert.assertTrue(ace.getMessage().startsWith(
          WebHdfsFileSystem.CANT_FALLBACK_TO_INSECURE_MSG));
    }
  }

  @Test
  public void testWebHdfsOffsetAndLength() throws Exception{
    final Configuration conf = WebHdfsTestUtil.createConf();
    final int OFFSET = 42;
    final int LENGTH = 512;
    final String PATH = "/foo";
    byte[] CONTENTS = new byte[1024];
    RANDOM.nextBytes(CONTENTS);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    final WebHdfsFileSystem fs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    try (OutputStream os = fs.create(new Path(PATH))) {
      os.write(CONTENTS);
    }
    InetSocketAddress addr = cluster.getNameNode().getHttpAddress();
    URL url = new URL("http", addr.getHostString(), addr.getPort(),
        WebHdfsFileSystem.PATH_PREFIX + PATH + "?op=OPEN"
            + Param.toSortedString("&", new OffsetParam((long) OFFSET),
                new LengthParam((long) LENGTH)));
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setInstanceFollowRedirects(true);
    Assert.assertEquals(LENGTH, conn.getContentLength());
    byte[] subContents = new byte[LENGTH];
    byte[] realContents = new byte[LENGTH];
    System.arraycopy(CONTENTS, OFFSET, subContents, 0, LENGTH);
    IOUtils.readFully(conn.getInputStream(), realContents);
    Assert.assertArrayEquals(subContents, realContents);
  }

  @Test
  public void testContentSummary() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    final Path path = new Path("/QuotaDir");
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
    final WebHdfsFileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    final DistributedFileSystem dfs = cluster.getFileSystem();
    dfs.mkdirs(path);
    dfs.setQuotaByStorageType(path, StorageType.DISK, 100000);
    ContentSummary contentSummary = webHdfs.getContentSummary(path);
    Assert
        .assertTrue((contentSummary.getTypeQuota(StorageType.DISK) == 100000));
  }

  /**
   * Test Snapshot related information in ContentSummary.
   */
  @Test
  public void testSnapshotInContentSummary() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    Path dirPath = new Path("/dir");
    final Path filePath = new Path("/dir/file");
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(3).build();
    final WebHdfsFileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    final DistributedFileSystem dfs = cluster.getFileSystem();
    DFSTestUtil.createFile(dfs, filePath, 10, (short) 3, 0L);
    dfs.allowSnapshot(dirPath);
    dfs.createSnapshot(dirPath);
    dfs.delete(filePath, true);
    ContentSummary contentSummary = webHdfs.getContentSummary(dirPath);
    assertEquals(1, contentSummary.getSnapshotFileCount());
    assertEquals(10, contentSummary.getSnapshotLength());
    assertEquals(30, contentSummary.getSnapshotSpaceConsumed());
    assertEquals(dfs.getContentSummary(dirPath),
        webHdfs.getContentSummary(dirPath));
  }

  @Test
  public void testQuotaUsage() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    final Path path = new Path("/TestDir");
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(3).build();
    final WebHdfsFileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    final DistributedFileSystem dfs = cluster.getFileSystem();

    final long nsQuota = 100;
    final long spaceQuota = 600 * 1024 * 1024;
    final long diskQuota = 100000;
    final byte[] bytes = {0x0, 0x1, 0x2, 0x3 };

    dfs.mkdirs(path);
    dfs.setQuota(path, nsQuota, spaceQuota);
    for (int i = 0; i < 10; i++) {
      dfs.createNewFile(new Path(path, "test_file_" + i));
    }
    FSDataOutputStream out = dfs.create(new Path(path, "test_file"));
    out.write(bytes);
    out.close();

    dfs.setQuotaByStorageType(path, StorageType.DISK, diskQuota);

    QuotaUsage quotaUsage = webHdfs.getQuotaUsage(path);
    assertEquals(12, quotaUsage.getFileAndDirectoryCount());
    assertEquals(nsQuota, quotaUsage.getQuota());
    assertEquals(bytes.length * dfs.getDefaultReplication(),
        quotaUsage.getSpaceConsumed());
    assertEquals(spaceQuota, quotaUsage.getSpaceQuota());
    assertEquals(diskQuota, quotaUsage.getTypeQuota(StorageType.DISK));
  }

  @Test
  public void testSetQuota() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    final Path path = new Path("/TestDir");
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(3).build();
    final WebHdfsFileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    final DistributedFileSystem dfs = cluster.getFileSystem();

    final long nsQuota = 100;
    final long spaceQuota = 1024;

    webHdfs.mkdirs(path);

    webHdfs.setQuota(path, nsQuota, spaceQuota);
    QuotaUsage quotaUsage = dfs.getQuotaUsage(path);
    assertEquals(nsQuota, quotaUsage.getQuota());
    assertEquals(spaceQuota, quotaUsage.getSpaceQuota());

    webHdfs.setQuota(path, HdfsConstants.QUOTA_RESET,
        HdfsConstants.QUOTA_RESET);
    quotaUsage = dfs.getQuotaUsage(path);
    assertEquals(HdfsConstants.QUOTA_RESET, quotaUsage.getQuota());
    assertEquals(HdfsConstants.QUOTA_RESET, quotaUsage.getSpaceQuota());

    webHdfs.setQuotaByStorageType(path, StorageType.DISK, spaceQuota);
    webHdfs.setQuotaByStorageType(path, StorageType.ARCHIVE, spaceQuota);
    webHdfs.setQuotaByStorageType(path, StorageType.SSD, spaceQuota);
    quotaUsage = dfs.getQuotaUsage(path);
    assertEquals(spaceQuota, quotaUsage.getTypeQuota(StorageType.DISK));
    assertEquals(spaceQuota, quotaUsage.getTypeQuota(StorageType.ARCHIVE));
    assertEquals(spaceQuota, quotaUsage.getTypeQuota(StorageType.SSD));

    // Test invalid parameters

    LambdaTestUtils.intercept(IllegalArgumentException.class,
        () -> webHdfs.setQuota(path, -100, 100));
    LambdaTestUtils.intercept(IllegalArgumentException.class,
        () -> webHdfs.setQuota(path, 100, -100));
    LambdaTestUtils.intercept(IllegalArgumentException.class,
        () -> webHdfs.setQuotaByStorageType(path, StorageType.SSD, -100));
    LambdaTestUtils.intercept(IllegalArgumentException.class,
        () -> webHdfs.setQuotaByStorageType(path, null, 100));
    LambdaTestUtils.intercept(IllegalArgumentException.class,
        () -> webHdfs.setQuotaByStorageType(path, StorageType.SSD, -100));
    LambdaTestUtils.intercept(IllegalArgumentException.class,
        () -> webHdfs.setQuotaByStorageType(path, StorageType.RAM_DISK, 100));
  }


  @Test
  public void testWebHdfsPread() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1)
        .build();
    byte[] content = new byte[1024];
    RANDOM.nextBytes(content);
    final Path foo = new Path("/foo");
    FSDataInputStream in = null;
    try {
      final WebHdfsFileSystem fs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
          WebHdfsConstants.WEBHDFS_SCHEME);
      try (OutputStream os = fs.create(foo)) {
        os.write(content);
      }

      // pread
      in = fs.open(foo, 1024);
      byte[] buf = new byte[1024];
      try {
        in.readFully(1020, buf, 0, 5);
        Assert.fail("EOF expected");
      } catch (EOFException ignored) {}

      // mix pread with stateful read
      int length = in.read(buf, 0, 512);
      in.readFully(100, new byte[1024], 0, 100);
      int preadLen = in.read(200, new byte[1024], 0, 200);
      Assert.assertTrue(preadLen > 0);
      IOUtils.readFully(in, buf, length, 1024 - length);
      Assert.assertArrayEquals(content, buf);
    } finally {
      if (in != null) {
        in.close();
      }
    }
  }

  @Test(timeout = 30000)
  public void testGetHomeDirectory() throws Exception {
    Configuration conf = new Configuration();
    cluster = new MiniDFSCluster.Builder(conf).build();
    cluster.waitActive();
    DistributedFileSystem hdfs = cluster.getFileSystem();

    final URI uri = new URI(WebHdfsConstants.WEBHDFS_SCHEME + "://"
        + cluster.getHttpUri(0).replace("http://", ""));
    final Configuration confTemp = new Configuration();

    WebHdfsFileSystem webhdfs = (WebHdfsFileSystem) FileSystem.get(uri,
        confTemp);

    assertEquals(hdfs.getHomeDirectory().toUri().getPath(),
        webhdfs.getHomeDirectory().toUri().getPath());

    webhdfs.close();

    webhdfs = createWebHDFSAsTestUser(confTemp, uri, "XXX");

    assertNotEquals(hdfs.getHomeDirectory().toUri().getPath(),
        webhdfs.getHomeDirectory().toUri().getPath());

    webhdfs.close();
  }

  @Test
  public void testWebHdfsGetBlockLocationsWithStorageType() throws Exception{
    final Configuration conf = WebHdfsTestUtil.createConf();
    final int OFFSET = 42;
    final int LENGTH = 512;
    final Path PATH = new Path("/foo");
    byte[] CONTENTS = new byte[1024];
    RANDOM.nextBytes(CONTENTS);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    final WebHdfsFileSystem fs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    try (OutputStream os = fs.create(PATH)) {
      os.write(CONTENTS);
    }
    BlockLocation[] locations = fs.getFileBlockLocations(PATH, OFFSET, LENGTH);
    for (BlockLocation location : locations) {
      StorageType[] storageTypes = location.getStorageTypes();
      Assert.assertTrue(storageTypes != null && storageTypes.length > 0
          && storageTypes[0] == StorageType.DISK);
    }
  }

  @Test
  public void testWebHdfsGetBlockLocations() throws Exception{
    final Configuration conf = WebHdfsTestUtil.createConf();
    final int offset = 42;
    final int length = 512;
    final Path path = new Path("/foo");
    byte[] contents = new byte[1024];
    RANDOM.nextBytes(contents);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    final WebHdfsFileSystem fs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    try (OutputStream os = fs.create(path)) {
      os.write(contents);
    }
    BlockLocation[] locations = fs.getFileBlockLocations(path, offset, length);

    // Query webhdfs REST API to get block locations
    InetSocketAddress addr = cluster.getNameNode().getHttpAddress();

    // Case 1
    // URL without length or offset parameters
    URL url1 = new URL("http", addr.getHostString(), addr.getPort(),
        WebHdfsFileSystem.PATH_PREFIX + "/foo?op=GETFILEBLOCKLOCATIONS");

    String response1 = getResponse(url1, "GET");
    // Parse BlockLocation array from json output using object mapper
    BlockLocation[] locationArray1 = toBlockLocationArray(response1);

    // Verify the result from rest call is same as file system api
    verifyEquals(locations, locationArray1);

    // Case 2
    // URL contains length and offset parameters
    URL url2 = new URL("http", addr.getHostString(), addr.getPort(),
        WebHdfsFileSystem.PATH_PREFIX + "/foo?op=GETFILEBLOCKLOCATIONS"
            + "&length=" + length + "&offset=" + offset);

    String response2 = getResponse(url2, "GET");
    BlockLocation[] locationArray2 = toBlockLocationArray(response2);

    verifyEquals(locations, locationArray2);

    // Case 3
    // URL contains length parameter but without offset parameters
    URL url3 = new URL("http", addr.getHostString(), addr.getPort(),
        WebHdfsFileSystem.PATH_PREFIX + "/foo?op=GETFILEBLOCKLOCATIONS"
            + "&length=" + length);

    String response3 = getResponse(url3, "GET");
    BlockLocation[] locationArray3 = toBlockLocationArray(response3);

    verifyEquals(locations, locationArray3);

    // Case 4
    // URL contains offset parameter but without length parameter
    URL url4 = new URL("http", addr.getHostString(), addr.getPort(),
        WebHdfsFileSystem.PATH_PREFIX + "/foo?op=GETFILEBLOCKLOCATIONS"
            + "&offset=" + offset);

    String response4 = getResponse(url4, "GET");
    BlockLocation[] locationArray4 = toBlockLocationArray(response4);

    verifyEquals(locations, locationArray4);

    // Case 5
    // URL specifies offset exceeds the file length
    URL url5 = new URL("http", addr.getHostString(), addr.getPort(),
        WebHdfsFileSystem.PATH_PREFIX + "/foo?op=GETFILEBLOCKLOCATIONS"
            + "&offset=1200");

    String response5 = getResponse(url5, "GET");
    BlockLocation[] locationArray5 = toBlockLocationArray(response5);

    // Expected an empty array of BlockLocation
    verifyEquals(new BlockLocation[] {}, locationArray5);
  }

  private BlockLocation[] toBlockLocationArray(String json)
      throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    MapType subType = mapper.getTypeFactory().constructMapType(
        Map.class,
        String.class,
        BlockLocation[].class);
    MapType rootType = mapper.getTypeFactory().constructMapType(
        Map.class,
        mapper.constructType(String.class),
        mapper.constructType(subType));

    Map<String, Map<String, BlockLocation[]>> jsonMap = mapper
        .readValue(json, rootType);
    Map<String, BlockLocation[]> locationMap = jsonMap
        .get("BlockLocations");
    BlockLocation[] locationArray = locationMap.get(
        BlockLocation.class.getSimpleName());
    return locationArray;
  }

  private void verifyEquals(BlockLocation[] locations1,
      BlockLocation[] locations2) throws IOException {
    for(int i=0; i<locations1.length; i++) {
      BlockLocation location1 = locations1[i];
      BlockLocation location2 = locations2[i];
      Assert.assertEquals(location1.getLength(),
          location2.getLength());
      Assert.assertEquals(location1.getOffset(),
          location2.getOffset());
      Assert.assertArrayEquals(location1.getCachedHosts(),
          location2.getCachedHosts());
      Assert.assertArrayEquals(location1.getHosts(),
          location2.getHosts());
      Assert.assertArrayEquals(location1.getNames(),
          location2.getNames());
      Assert.assertArrayEquals(location1.getTopologyPaths(),
          location2.getTopologyPaths());
      Assert.assertArrayEquals(location1.getStorageTypes(),
          location2.getStorageTypes());
    }
  }

  private static String getResponse(URL url, String httpRequestType)
      throws IOException {
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod(httpRequestType);
      conn.setInstanceFollowRedirects(false);
      return IOUtils.toString(conn.getInputStream(),
          StandardCharsets.UTF_8);
    } finally {
      if(conn != null) {
        conn.disconnect();
      }
    }
  }

  private WebHdfsFileSystem createWebHDFSAsTestUser(final Configuration conf,
      final URI uri, final String userName) throws Exception {

    final UserGroupInformation ugi = UserGroupInformation.createUserForTesting(
        userName, new String[] {"supergroup" });

    return ugi.doAs(new PrivilegedExceptionAction<WebHdfsFileSystem>() {
      @Override
      public WebHdfsFileSystem run() throws IOException {
        WebHdfsFileSystem webhdfs = (WebHdfsFileSystem) FileSystem.get(uri,
            conf);
        return webhdfs;
      }
    });
  }

  @Test(timeout=90000)
  public void testWebHdfsReadRetries() throws Exception {
    // ((Log4JLogger)DFSClient.LOG).getLogger().setLevel(Level.ALL);
    final Configuration conf = WebHdfsTestUtil.createConf();
    final Path dir = new Path("/testWebHdfsReadRetries");

    conf.setBoolean(HdfsClientConfigKeys.Retry.POLICY_ENABLED_KEY, true);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_SAFEMODE_MIN_DATANODES_KEY, 1);
    conf.setInt(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, 1024*512);
    conf.setInt(DFSConfigKeys.DFS_REPLICATION_KEY, 1);

    final short numDatanodes = 1;
    cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(numDatanodes)
        .build();
    cluster.waitActive();
    final FileSystem fs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);

    // create a file
    final long length = 1L << 20;
    final Path file1 = new Path(dir, "testFile");

    DFSTestUtil.createFile(fs, file1, length, numDatanodes, 20120406L);

    // get file status and check that it was written properly.
    final FileStatus s1 = fs.getFileStatus(file1);
    assertEquals("Write failed for file " + file1, length, s1.getLen());

    // Ensure file can be read through WebHdfsInputStream
    FSDataInputStream in = fs.open(file1);
    assertTrue("Input stream is not an instance of class WebHdfsInputStream",
        in.getWrappedStream() instanceof WebHdfsInputStream);
    int count = 0;
    for (; in.read() != -1; count++)
      ;
    assertEquals("Read failed for file " + file1, s1.getLen(), count);
    assertEquals("Sghould not be able to read beyond end of file", in.read(),
        -1);
    in.close();
    try {
      in.read();
      fail("Read after close should have failed");
    } catch (IOException ioe) {
    }

    WebHdfsFileSystem wfs = (WebHdfsFileSystem) fs;
    // Read should not be retried if AccessControlException is encountered.
    String msg = "ReadRetries: Test Access Control Exception";
    testReadRetryExceptionHelper(wfs, file1, new AccessControlException(msg),
        msg, false, 1);

    // Retry policy should be invoked if IOExceptions are thrown.
    msg = "ReadRetries: Test SocketTimeoutException";
    testReadRetryExceptionHelper(wfs, file1, new SocketTimeoutException(msg),
        msg, true, 5);
    msg = "ReadRetries: Test SocketException";
    testReadRetryExceptionHelper(wfs, file1, new SocketException(msg), msg,
        true, 5);
    msg = "ReadRetries: Test EOFException";
    testReadRetryExceptionHelper(wfs, file1, new EOFException(msg), msg, true,
        5);
    msg = "ReadRetries: Test Generic IO Exception";
    testReadRetryExceptionHelper(wfs, file1, new IOException(msg), msg, true,
        5);

    // If InvalidToken exception occurs, WebHdfs only retries if the
    // delegation token was replaced. Do that twice, then verify by checking
    // the number of times it tried.
    WebHdfsFileSystem spyfs = spy(wfs);
    when(spyfs.replaceExpiredDelegationToken()).thenReturn(true, true, false);
    msg = "ReadRetries: Test Invalid Token Exception";
    testReadRetryExceptionHelper(spyfs, file1, new InvalidToken(msg), msg,
        false, 3);
  }

  public boolean attemptedRetry;
  private void testReadRetryExceptionHelper(WebHdfsFileSystem fs, Path fn,
      final IOException ex, String msg, boolean shouldAttemptRetry,
      int numTimesTried)
      throws Exception {
    // Ovverride WebHdfsInputStream#getInputStream so that it returns
    // an input stream that throws the specified exception when read
    // is called.
    FSDataInputStream in = fs.open(fn);
    in.read(); // Connection is made only when the first read() occurs.
    final WebHdfsInputStream webIn =
        (WebHdfsInputStream)(in.getWrappedStream());

    final InputStream spyInputStream =
        spy(webIn.getReadRunner().getInputStream());
    doThrow(ex).when(spyInputStream).read((byte[])any(), anyInt(), anyInt());
    final WebHdfsFileSystem.ReadRunner rr = spy(webIn.getReadRunner());
    doReturn(spyInputStream)
        .when(rr).initializeInputStream((HttpURLConnection) any());
    rr.setInputStream(spyInputStream);
    webIn.setReadRunner(rr);

    // Override filesystem's retry policy in order to verify that
    // WebHdfsInputStream is calling shouldRetry for the appropriate
    // exceptions.
    final RetryAction retryAction = new RetryAction(RetryDecision.RETRY);
    final RetryAction failAction = new RetryAction(RetryDecision.FAIL);
    RetryPolicy rp = new RetryPolicy() {
      @Override
      public RetryAction shouldRetry(Exception e, int retries, int failovers,
          boolean isIdempotentOrAtMostOnce) throws Exception {
        attemptedRetry = true;
        if (retries > 3) {
          return failAction;
        } else {
          return retryAction;
        }
      }
    };
    fs.setRetryPolicy(rp);

    // If the retry logic is exercised, attemptedRetry will be true. Some
    // exceptions should exercise the retry logic and others should not.
    // Either way, the value of attemptedRetry should match shouldAttemptRetry.
    attemptedRetry = false;
    try {
      webIn.read();
      fail(msg + ": Read should have thrown exception.");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains(msg));
    }
    assertEquals(msg + ": Read should " + (shouldAttemptRetry ? "" : "not ")
                + "have called shouldRetry. ",
        attemptedRetry, shouldAttemptRetry);

    verify(rr, times(numTimesTried)).getResponse((HttpURLConnection) any());
    webIn.close();
    in.close();
  }

  private void checkResponseContainsLocation(URL url, String TYPE)
    throws JSONException, IOException {
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod(TYPE);
    conn.setInstanceFollowRedirects(false);
    String response =
        IOUtils.toString(conn.getInputStream(), Charset.defaultCharset());
    LOG.info("Response was : " + response);
    Assert.assertEquals(
      "Response wasn't " + HttpURLConnection.HTTP_OK,
      HttpURLConnection.HTTP_OK, conn.getResponseCode());

    JSONObject responseJson = new JSONObject(response);
    Assert.assertTrue("Response didn't give us a location. " + response,
      responseJson.has("Location"));

    //Test that the DN allows CORS on Create
    if(TYPE.equals("CREATE")) {
      URL dnLocation = new URL(responseJson.getString("Location"));
      HttpURLConnection dnConn = (HttpURLConnection) dnLocation.openConnection();
      dnConn.setRequestMethod("OPTIONS");
      Assert.assertEquals("Datanode url : " + dnLocation + " didn't allow "
        + "CORS", HttpURLConnection.HTTP_OK, dnConn.getResponseCode());
    }
  }

  @Test
  /**
   * Test that when "&noredirect=true" is added to operations CREATE, APPEND,
   * OPEN, and GETFILECHECKSUM the response (which is usually a 307 temporary
   * redirect) is a 200 with JSON that contains the redirected location
   */
  public void testWebHdfsNoRedirect() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(3).build();
    LOG.info("Started cluster");
    InetSocketAddress addr = cluster.getNameNode().getHttpAddress();

    URL url = new URL("http", addr.getHostString(), addr.getPort(),
        WebHdfsFileSystem.PATH_PREFIX + "/testWebHdfsNoRedirectCreate"
            + "?op=CREATE"
            + Param.toSortedString("&", new NoRedirectParam(true)));
    LOG.info("Sending create request " + url);
    checkResponseContainsLocation(url, "PUT");

    // Write a file that we can read
    final WebHdfsFileSystem fs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    final String PATH = "/testWebHdfsNoRedirect";
    byte[] CONTENTS = new byte[1024];
    RANDOM.nextBytes(CONTENTS);
    try (OutputStream os = fs.create(new Path(PATH))) {
      os.write(CONTENTS);
    }
    url = new URL("http", addr.getHostString(), addr.getPort(),
        WebHdfsFileSystem.PATH_PREFIX + "/testWebHdfsNoRedirect" + "?op=OPEN"
            + Param.toSortedString("&", new NoRedirectParam(true)));
    LOG.info("Sending open request " + url);
    checkResponseContainsLocation(url, "GET");

    url = new URL("http", addr.getHostString(), addr.getPort(),
        WebHdfsFileSystem.PATH_PREFIX + "/testWebHdfsNoRedirect"
            + "?op=GETFILECHECKSUM"
            + Param.toSortedString("&", new NoRedirectParam(true)));
    LOG.info("Sending getfilechecksum request " + url);
    checkResponseContainsLocation(url, "GET");

    url = new URL("http", addr.getHostString(), addr.getPort(),
        WebHdfsFileSystem.PATH_PREFIX + "/testWebHdfsNoRedirect" + "?op=APPEND"
            + Param.toSortedString("&", new NoRedirectParam(true)));
    LOG.info("Sending append request " + url);
    checkResponseContainsLocation(url, "POST");
  }

  @Test
  public void testGetTrashRoot() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    final String currentUser =
        UserGroupInformation.getCurrentUser().getShortUserName();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
    final WebHdfsFileSystem webFS = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);

    Path trashPath = webFS.getTrashRoot(new Path("/"));
    Path expectedPath = new Path(FileSystem.USER_HOME_PREFIX,
        new Path(currentUser, FileSystem.TRASH_PREFIX));
    assertEquals(expectedPath.toUri().getPath(), trashPath.toUri().getPath());
  }

  @Test
  public void testGetEZTrashRoot() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    FileSystemTestHelper fsHelper = new FileSystemTestHelper();
    File testRootDir = new File(fsHelper.getTestRootDir()).getAbsoluteFile();
    conf.set(CommonConfigurationKeysPublic.HADOOP_SECURITY_KEY_PROVIDER_PATH,
        "jceks://file" + new Path(testRootDir.toString(), "test.jks").toUri());
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
    cluster.waitActive();
    DistributedFileSystem dfs = cluster.getFileSystem();
    final WebHdfsFileSystem webhdfs = WebHdfsTestUtil.getWebHdfsFileSystem(
        conf, WebHdfsConstants.WEBHDFS_SCHEME);
    HdfsAdmin dfsAdmin = new HdfsAdmin(cluster.getURI(), conf);
    dfs.getClient().setKeyProvider(
        cluster.getNameNode().getNamesystem().getProvider());
    final String testkey = "test_key";
    DFSTestUtil.createKey(testkey, cluster, conf);

    final Path zone1 = new Path("/zone1");
    dfs.mkdirs(zone1, new FsPermission(700));
    dfsAdmin.createEncryptionZone(zone1, testkey,
        EnumSet.of(CreateEncryptionZoneFlag.PROVISION_TRASH));

    final Path insideEZ = new Path(zone1, "insideEZ");
    dfs.mkdirs(insideEZ, new FsPermission(700));
    assertEquals(
        dfs.getTrashRoot(insideEZ).toUri().getPath(),
        webhdfs.getTrashRoot(insideEZ).toUri().getPath());

    final Path outsideEZ = new Path("/outsideEZ");
    dfs.mkdirs(outsideEZ, new FsPermission(755));
    assertEquals(
        dfs.getTrashRoot(outsideEZ).toUri().getPath(),
        webhdfs.getTrashRoot(outsideEZ).toUri().getPath());

    final Path root = new Path("/");
    assertEquals(
        dfs.getTrashRoot(root).toUri().getPath(),
        webhdfs.getTrashRoot(root).toUri().getPath());
    assertEquals(
        webhdfs.getTrashRoot(root).toUri().getPath(),
        webhdfs.getTrashRoot(zone1).toUri().getPath());
    assertEquals(
        webhdfs.getTrashRoot(outsideEZ).toUri().getPath(),
        webhdfs.getTrashRoot(zone1).toUri().getPath());
  }

  @Test
  public void testStoragePolicy() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    final Path path = new Path("/file");
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
    final DistributedFileSystem dfs = cluster.getFileSystem();
    final WebHdfsFileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);

    // test getAllStoragePolicies
    Assert.assertTrue(Arrays.equals(dfs.getAllStoragePolicies().toArray(),
        webHdfs.getAllStoragePolicies().toArray()));

    // test get/set/unset policies
    DFSTestUtil.createFile(dfs, path, 0, (short) 1, 0L);
    // get defaultPolicy
    BlockStoragePolicySpi defaultdfsPolicy = dfs.getStoragePolicy(path);
    // set policy through webhdfs
    webHdfs.setStoragePolicy(path, HdfsConstants.COLD_STORAGE_POLICY_NAME);
    // get policy from dfs
    BlockStoragePolicySpi dfsPolicy = dfs.getStoragePolicy(path);
    // get policy from webhdfs
    BlockStoragePolicySpi webHdfsPolicy = webHdfs.getStoragePolicy(path);
    Assert.assertEquals(HdfsConstants.COLD_STORAGE_POLICY_NAME.toString(),
        webHdfsPolicy.getName());
    Assert.assertEquals(webHdfsPolicy, dfsPolicy);
    // unset policy
    webHdfs.unsetStoragePolicy(path);
    Assert.assertEquals(defaultdfsPolicy, webHdfs.getStoragePolicy(path));
  }

  @Test
  public void testSetStoragePolicyWhenPolicyDisabled() throws Exception {
    Configuration conf = new HdfsConfiguration();
    conf.setBoolean(DFSConfigKeys.DFS_STORAGE_POLICY_ENABLED_KEY, false);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0)
        .build();
    try {
      cluster.waitActive();
      final WebHdfsFileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(
          conf, WebHdfsConstants.WEBHDFS_SCHEME);
      webHdfs.setStoragePolicy(new Path("/"),
          HdfsConstants.COLD_STORAGE_POLICY_NAME);
      fail("Should throw exception, when storage policy disabled");
    } catch (IOException e) {
      Assert.assertTrue(e.getMessage().contains(
          "Failed to set storage policy since"));
    }
  }

  private void checkECPolicyState(Collection<ErasureCodingPolicyInfo> policies,
      String ecpolicy, String state) {
    Iterator<ErasureCodingPolicyInfo> itr = policies.iterator();
    boolean found = false;
    while (policies.iterator().hasNext()) {
      ErasureCodingPolicyInfo policy = itr.next();
      if (policy.getPolicy().getName().equals(ecpolicy)) {
        found = true;
        if (state.equals("disable")) {
          Assert.assertTrue(policy.isDisabled());
        } else if (state.equals("enable")) {
          Assert.assertTrue(policy.isEnabled());
        }
        break;
      }
    }
    Assert.assertTrue(found);
  }

  // Test For Enable/Disable EC Policy in DFS.
  @Test
  public void testECPolicyCommands() throws Exception {
    Configuration conf = new HdfsConfiguration();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
    cluster.waitActive();
    final DistributedFileSystem dfs = cluster.getFileSystem();
    final WebHdfsFileSystem webHdfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    String policy = "RS-10-4-1024k";
    // Check for Enable EC policy via WEBHDFS.
    dfs.disableErasureCodingPolicy(policy);
    checkECPolicyState(dfs.getAllErasureCodingPolicies(), policy, "disable");
    webHdfs.enableECPolicy(policy);
    checkECPolicyState(dfs.getAllErasureCodingPolicies(), policy, "enable");
    Path dir = new Path("/tmp");
    dfs.mkdirs(dir);
    // Check for Set EC policy via WEBHDFS
    assertNull(dfs.getErasureCodingPolicy(dir));
    webHdfs.setErasureCodingPolicy(dir, policy);
    assertEquals(policy, dfs.getErasureCodingPolicy(dir).getName());

    // Check for Get EC policy via WEBHDFS
    assertEquals(policy, webHdfs.getErasureCodingPolicy(dir).getName());

    // Check for Unset EC policy via WEBHDFS
    webHdfs.unsetErasureCodingPolicy(dir);
    assertNull(dfs.getErasureCodingPolicy(dir));

    // Check for Disable EC policy via WEBHDFS.
    webHdfs.disableECPolicy(policy);
    checkECPolicyState(dfs.getAllErasureCodingPolicies(), policy, "disable");
  }

  // Test for Storage Policy Satisfier in DFS.

  @Test
  public void testWebHdfsSps() throws Exception {
    final Configuration conf = new HdfsConfiguration();
    conf.set(DFSConfigKeys.DFS_STORAGE_POLICY_SATISFIER_MODE_KEY,
        StoragePolicySatisfierMode.EXTERNAL.toString());
    StoragePolicySatisfier sps = new StoragePolicySatisfier(conf);
    try {
      cluster = new MiniDFSCluster.Builder(conf)
          .storageTypes(
              new StorageType[][] {{StorageType.DISK, StorageType.ARCHIVE } })
          .storagesPerDatanode(2).numDataNodes(1).build();
      cluster.waitActive();
      sps.init(new ExternalSPSContext(sps, DFSTestUtil.getNameNodeConnector(
          conf, HdfsServerConstants.MOVER_ID_PATH, 1, false)));
      sps.start(StoragePolicySatisfierMode.EXTERNAL);
      sps.start(StoragePolicySatisfierMode.EXTERNAL);
      DistributedFileSystem dfs = cluster.getFileSystem();
      DFSTestUtil.createFile(dfs, new Path("/file"), 1024L, (short) 1, 0L);
      DFSTestUtil.waitForReplication(dfs, new Path("/file"), (short) 1, 5000);
      dfs.setStoragePolicy(new Path("/file"), "COLD");
      dfs.satisfyStoragePolicy(new Path("/file"));
      DFSTestUtil.waitExpectedStorageType("/file", StorageType.ARCHIVE, 1,
          30000, dfs);
    } finally {
      sps.stopGracefully();
    }
  }

  @Test
  public void testWebHdfsAppend() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    final int dnNumber = 3;
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(dnNumber).build();

    final WebHdfsFileSystem webFS = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);

    final DistributedFileSystem fs = cluster.getFileSystem();

    final Path appendFile = new Path("/testAppend.txt");
    final String content = "hello world";
    DFSTestUtil.writeFile(fs, appendFile, content);

    for (int index = 0; index < dnNumber - 1; index++) {
      cluster.shutdownDataNode(index);
    }
    cluster.restartNameNodes();
    cluster.waitActive();

    try {
      DFSTestUtil.appendFile(webFS, appendFile, content);
      fail("Should fail to append file since "
          + "datanode number is 1 and replication is 3");
    } catch (IOException ignored) {
      String resultContent = DFSTestUtil.readFile(fs, appendFile);
      assertTrue(resultContent.equals(content));
    }
  }

  /**
   * Test fsserver defaults response from {@link DistributedFileSystem} and
   * {@link WebHdfsFileSystem} are the same.
   * @throws Exception
   */
  @Test
  public void testFsserverDefaults() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    // Here we override all the default values so that we can verify that it
    // doesn't pick up the default value.
    long blockSize = 256*1024*1024;
    int bytesPerChecksum = 256;
    int writePacketSize = 128*1024;
    int replicationFactor = 0;
    int bufferSize = 1024;
    boolean encryptDataTransfer = true;
    long trashInterval = 1;
    String checksumType = "CRC32";
    // Setting policy to a special value 7 because BlockManager will
    // create defaultSuite with policy id 7.
    byte policyId = (byte) 7;

    conf.setLong(DFS_BLOCK_SIZE_KEY, blockSize);
    conf.setInt(DFS_BYTES_PER_CHECKSUM_KEY, bytesPerChecksum);
    conf.setInt(DFS_CLIENT_WRITE_PACKET_SIZE_KEY, writePacketSize);
    conf.setInt(DFS_REPLICATION_KEY, replicationFactor);
    conf.setInt(IO_FILE_BUFFER_SIZE_KEY, bufferSize);
    conf.setBoolean(DFS_ENCRYPT_DATA_TRANSFER_KEY, encryptDataTransfer);
    conf.setLong(FS_TRASH_INTERVAL_KEY, trashInterval);
    conf.set(DFS_CHECKSUM_TYPE_KEY, checksumType);
    FsServerDefaults originalServerDefaults = new FsServerDefaults(blockSize,
        bytesPerChecksum, writePacketSize, (short)replicationFactor,
        bufferSize, encryptDataTransfer, trashInterval,
        DataChecksum.Type.valueOf(checksumType), "", policyId);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
    final DistributedFileSystem dfs = cluster.getFileSystem();
    final WebHdfsFileSystem webfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    FsServerDefaults dfsServerDefaults = dfs.getServerDefaults();
    FsServerDefaults webfsServerDefaults = webfs.getServerDefaults();
    // Verify whether server defaults value that we override is equal to
    // dfsServerDefaults.
    compareFsServerDefaults(originalServerDefaults, dfsServerDefaults);
    // Verify whether dfs serverdefaults is equal to
    // webhdfsServerDefaults.
    compareFsServerDefaults(dfsServerDefaults, webfsServerDefaults);
    webfs.getServerDefaults();
  }

  private void compareFsServerDefaults(FsServerDefaults serverDefaults1,
      FsServerDefaults serverDefaults2) throws Exception {
    Assert.assertEquals("Block size is different",
        serverDefaults1.getBlockSize(),
        serverDefaults2.getBlockSize());
    Assert.assertEquals("Bytes per checksum are different",
        serverDefaults1.getBytesPerChecksum(),
        serverDefaults2.getBytesPerChecksum());
    Assert.assertEquals("Write packet size is different",
        serverDefaults1.getWritePacketSize(),
        serverDefaults2.getWritePacketSize());
    Assert.assertEquals("Default replication is different",
        serverDefaults1.getReplication(),
        serverDefaults2.getReplication());
    Assert.assertEquals("File buffer size are different",
        serverDefaults1.getFileBufferSize(),
        serverDefaults2.getFileBufferSize());
    Assert.assertEquals("Encrypt data transfer key is different",
        serverDefaults1.getEncryptDataTransfer(),
        serverDefaults2.getEncryptDataTransfer());
    Assert.assertEquals("Trash interval is different",
        serverDefaults1.getTrashInterval(),
        serverDefaults2.getTrashInterval());
    Assert.assertEquals("Checksum type is different",
        serverDefaults1.getChecksumType(),
        serverDefaults2.getChecksumType());
    Assert.assertEquals("Key provider uri is different",
        serverDefaults1.getKeyProviderUri(),
        serverDefaults2.getKeyProviderUri());
    Assert.assertEquals("Default storage policy is different",
        serverDefaults1.getDefaultStoragePolicyId(),
        serverDefaults2.getDefaultStoragePolicyId());
  }

  /**
   * Tests the case when client is upgraded to return {@link FsServerDefaults}
   * but then namenode is not upgraded.
   * @throws Exception
   */
  @Test
  public void testFsserverDefaultsBackwardsCompatible() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).build();
    final WebHdfsFileSystem webfs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);
    FSNamesystem fsnSpy =
        NameNodeAdapter.spyOnNamesystem(cluster.getNameNode());
    Mockito.when(fsnSpy.getServerDefaults())
        .thenThrow(new UnsupportedOperationException());
    try {
      webfs.getServerDefaults();
      Assert.fail("should have thrown UnSupportedOperationException.");
    } catch (UnsupportedOperationException uoe) {
      // Expected exception.
    }
  }

  /**
   * Tests that {@link WebHdfsFileSystem.AbstractRunner} propagates original
   * exception's stacktrace and cause during runWithRetry attempts.
   * @throws Exception
   */
  @Test
  public void testExceptionPropogationInAbstractRunner() throws Exception{
    final Configuration conf = WebHdfsTestUtil.createConf();
    final Path dir = new Path("/testExceptionPropogationInAbstractRunner");

    conf.setBoolean(HdfsClientConfigKeys.Retry.POLICY_ENABLED_KEY, true);

    final short numDatanodes = 1;
    cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(numDatanodes)
        .build();
    cluster.waitActive();
    final FileSystem fs = WebHdfsTestUtil.getWebHdfsFileSystem(conf,
        WebHdfsConstants.WEBHDFS_SCHEME);

    // create a file
    final long length = 1L << 20;
    final Path file1 = new Path(dir, "testFile");

    DFSTestUtil.createFile(fs, file1, length, numDatanodes, 20120406L);

    // get file status and check that it was written properly.
    final FileStatus s1 = fs.getFileStatus(file1);
    assertEquals("Write failed for file " + file1, length, s1.getLen());

    FSDataInputStream in = fs.open(file1);
    in.read(); // Connection is made only when the first read() occurs.
    final WebHdfsInputStream webIn =
        (WebHdfsInputStream) (in.getWrappedStream());

    final String msg = "Throwing dummy exception";
    IOException ioe = new IOException(msg, new DummyThrowable());

    WebHdfsFileSystem.ReadRunner readRunner = spy(webIn.getReadRunner());
    doThrow(ioe).when(readRunner).getResponse(any(HttpURLConnection.class));

    webIn.setReadRunner(readRunner);

    try {
      webIn.read();
      fail("Read should have thrown IOException.");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains(msg));
      assertTrue(e.getCause() instanceof DummyThrowable);
    }
  }

  /**
   * Tests that the LISTSTATUS ang GETFILESTATUS WebHDFS calls return the
   * ecPolicy for EC files.
   */
  @Test(timeout=300000)
  public void testECPolicyInFileStatus() throws Exception {
    final Configuration conf = WebHdfsTestUtil.createConf();
    final ErasureCodingPolicy ecPolicy = SystemErasureCodingPolicies
        .getByID(SystemErasureCodingPolicies.RS_3_2_POLICY_ID);
    final String ecPolicyName = ecPolicy.getName();
    cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(5)
        .build();
    cluster.waitActive();
    final DistributedFileSystem fs = cluster.getFileSystem();

    // Create an EC dir and write a test file in it
    final Path ecDir = new Path("/ec");
    Path ecFile = new Path(ecDir, "ec_file.txt");
    Path nonEcFile = new Path(ecDir, "non_ec_file.txt");
    fs.mkdirs(ecDir);

    // Create a non-EC file before enabling ec policy
    DFSTestUtil.createFile(fs, nonEcFile, 1024, (short) 1, 0);

    fs.enableErasureCodingPolicy(ecPolicyName);
    fs.setErasureCodingPolicy(ecDir, ecPolicyName);

    // Create a EC file
    DFSTestUtil.createFile(fs, ecFile, 1024, (short) 1, 0);

    // Query webhdfs REST API to list statuses of files/directories in ecDir
    InetSocketAddress addr = cluster.getNameNode().getHttpAddress();
    URL listStatusUrl = new URL("http", addr.getHostString(), addr.getPort(),
        WebHdfsFileSystem.PATH_PREFIX + ecDir.toString() + "?op=LISTSTATUS");

    HttpURLConnection conn = (HttpURLConnection) listStatusUrl.openConnection();
    conn.setRequestMethod("GET");
    conn.setInstanceFollowRedirects(false);
    String listStatusResponse = IOUtils.toString(conn.getInputStream(),
        StandardCharsets.UTF_8);
    Assert.assertEquals("Response wasn't " + HttpURLConnection.HTTP_OK,
        HttpURLConnection.HTTP_OK, conn.getResponseCode());

    // Verify that ecPolicy is set in the ListStatus response for ec file
    String ecpolicyForECfile = getECPolicyFromFileStatusJson(
        getFileStatusJson(listStatusResponse, ecFile.getName()));
    assertEquals("EC policy for ecFile should match the set EC policy",
        ecpolicyForECfile, ecPolicyName);

    // Verify that ecPolicy is not set in the ListStatus response for non-ec
    // file
    String ecPolicyForNonECfile = getECPolicyFromFileStatusJson(
        getFileStatusJson(listStatusResponse, nonEcFile.getName()));
    assertEquals("EC policy for nonEcFile should be null (not set)",
        ecPolicyForNonECfile, null);

    // Query webhdfs REST API to get fileStatus for ecFile
    URL getFileStatusUrl = new URL("http", addr.getHostString(), addr.getPort(),
        WebHdfsFileSystem.PATH_PREFIX + ecFile.toString() +
            "?op=GETFILESTATUS");

    conn = (HttpURLConnection) getFileStatusUrl.openConnection();
    conn.setRequestMethod("GET");
    conn.setInstanceFollowRedirects(false);
    String getFileStatusResponse = IOUtils.toString(conn.getInputStream(),
        StandardCharsets.UTF_8);
    Assert.assertEquals("Response wasn't " + HttpURLConnection.HTTP_OK,
        HttpURLConnection.HTTP_OK, conn.getResponseCode());

    // Verify that ecPolicy is set in getFileStatus response for ecFile
    JSONObject fileStatusObject = new JSONObject(getFileStatusResponse)
        .getJSONObject("FileStatus");
    ecpolicyForECfile = getECPolicyFromFileStatusJson(fileStatusObject);
    assertEquals("EC policy for ecFile should match the set EC policy",
        ecpolicyForECfile, ecPolicyName);
  }

  @Test
  public void testStatistics() throws Exception {
    final Configuration conf = new HdfsConfiguration();
    conf.set(DFSConfigKeys.DFS_STORAGE_POLICY_SATISFIER_MODE_KEY,
        StoragePolicySatisfierMode.EXTERNAL.toString());
    StoragePolicySatisfier sps = new StoragePolicySatisfier(conf);
    try {
      cluster = new MiniDFSCluster.Builder(conf).storageTypes(
          new StorageType[][] {{StorageType.DISK, StorageType.ARCHIVE}})
          .storagesPerDatanode(2).numDataNodes(1).build();
      cluster.waitActive();
      sps.init(new ExternalSPSContext(sps, DFSTestUtil
          .getNameNodeConnector(conf, HdfsServerConstants.MOVER_ID_PATH, 1,
              false)));
      sps.start(StoragePolicySatisfierMode.EXTERNAL);
      sps.start(StoragePolicySatisfierMode.EXTERNAL);
      final WebHdfsFileSystem webHdfs = WebHdfsTestUtil
          .getWebHdfsFileSystem(conf, WebHdfsConstants.WEBHDFS_SCHEME);
      Path dir = new Path("/test");
      webHdfs.mkdirs(dir);
      int readOps = 0;
      int writeOps = 0;
      FileSystem.clearStatistics();

      long opCount =
          getOpStatistics(DFSOpsCountStatistics.OpType.GET_STORAGE_POLICY);
      webHdfs.getStoragePolicy(dir);
      checkStatistics(webHdfs, ++readOps, writeOps, 0);
      checkOpStatistics(DFSOpsCountStatistics.OpType.GET_STORAGE_POLICY,
          opCount + 1);

      opCount =
          getOpStatistics(DFSOpsCountStatistics.OpType.GET_STORAGE_POLICIES);
      webHdfs.getAllStoragePolicies();
      checkStatistics(webHdfs, ++readOps, writeOps, 0);
      checkOpStatistics(DFSOpsCountStatistics.OpType.GET_STORAGE_POLICIES,
          opCount + 1);

      opCount =
          getOpStatistics(DFSOpsCountStatistics.OpType.SATISFY_STORAGE_POLICY);
      webHdfs.satisfyStoragePolicy(dir);
      checkStatistics(webHdfs, readOps, ++writeOps, 0);
      checkOpStatistics(DFSOpsCountStatistics.OpType.SATISFY_STORAGE_POLICY,
          opCount + 1);

      opCount = getOpStatistics(
          DFSOpsCountStatistics.OpType.GET_SNAPSHOTTABLE_DIRECTORY_LIST);
      webHdfs.getSnapshottableDirectoryList();
      checkStatistics(webHdfs, ++readOps, writeOps, 0);
      checkOpStatistics(
          DFSOpsCountStatistics.OpType.GET_SNAPSHOTTABLE_DIRECTORY_LIST,
          opCount + 1);
    } finally {
      cluster.shutdown();
    }
  }
  /**
   * Get FileStatus JSONObject from ListStatus response.
   */
  private JSONObject getFileStatusJson(String response, String fileName)
      throws JSONException {
    JSONObject listStatusResponseJson = new JSONObject(response);
    JSONArray fileStatusArray = listStatusResponseJson
        .getJSONObject("FileStatuses")
        .getJSONArray("FileStatus");
    for (int i = 0; i < fileStatusArray.length(); i++) {
      JSONObject fileStatusJsonObject = fileStatusArray.getJSONObject(i);
      if (fileName.equals(fileStatusJsonObject.get("pathSuffix"))) {
        return fileStatusJsonObject;
      }
    }
    return null;
  }

  /**
   * Get ECPolicy name from FileStatus JSONObject.
   */
  private String getECPolicyFromFileStatusJson(JSONObject fileStatusJsonObject)
      throws JSONException {
    if (fileStatusJsonObject.has("ecPolicy")) {
      return fileStatusJsonObject.getString("ecPolicy");
    } else {
      return null;
    }
  }

  final static class DummyThrowable extends Throwable {
  }
}
