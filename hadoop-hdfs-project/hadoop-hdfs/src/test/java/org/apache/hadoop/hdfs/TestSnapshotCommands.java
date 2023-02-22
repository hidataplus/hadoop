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

package org.apache.hadoop.hdfs;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.SnapshotDiffReport;
import org.apache.hadoop.hdfs.tools.snapshot.SnapshotDiff;
import org.apache.hadoop.util.ChunkedArrayList;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * This class includes end-to-end tests for snapshot related FsShell and
 * DFSAdmin commands.
 */
public class TestSnapshotCommands {

  private static Configuration conf;
  private static MiniDFSCluster cluster;
  private static DistributedFileSystem fs;
  
  @BeforeClass
  public static void clusterSetUp() throws IOException {
    conf = new HdfsConfiguration();
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_SNAPSHOT_MAX_LIMIT, 3);
    cluster = new MiniDFSCluster.Builder(conf).build();
    cluster.waitActive();
    fs = cluster.getFileSystem();
  }

  @AfterClass
  public static void clusterShutdown() throws IOException{
    if(fs != null){
      fs.close();
    }
    if(cluster != null){
      cluster.shutdown();
    }
  }

  @Before
  public void setUp() throws IOException {
    fs.mkdirs(new Path("/sub1"));
    fs.mkdirs(new Path("/Fully/QPath"));
    fs.allowSnapshot(new Path("/sub1"));
    fs.mkdirs(new Path("/sub1/sub1sub1"));
    fs.mkdirs(new Path("/sub1/sub1sub2"));
  }

  @After
  public void tearDown() throws IOException {
    if (fs.exists(new Path("/sub1"))) {
      if (fs.exists(new Path("/sub1/.snapshot"))) {
        for (FileStatus st : fs.listStatus(new Path("/sub1/.snapshot"))) {
          fs.deleteSnapshot(new Path("/sub1"), st.getPath().getName());
        }
        fs.disallowSnapshot(new Path("/sub1"));
      }
      fs.delete(new Path("/sub1"), true);
    }
  }

  @Test
  public void testAllowSnapshot() throws Exception {
    // Idempotent test
    DFSTestUtil.DFSAdminRun("-allowSnapshot /sub1", 0,
        "Allowing snapshot " + "on /sub1 succeeded", conf);
    // allow normal dir success
    DFSTestUtil.FsShellRun("-mkdir /sub2", conf);
    DFSTestUtil.DFSAdminRun("-allowSnapshot /sub2", 0,
        "Allowing snapshot " + "on /sub2 succeeded", conf);
    // allow non-exists dir failed
    DFSTestUtil.DFSAdminRun("-allowSnapshot /sub3", -1, null, conf);
  }

  @Test
  public void testCreateSnapshot() throws Exception {
    // test createSnapshot
    DFSTestUtil.FsShellRun("-createSnapshot /sub1 sn0", 0, "Created snapshot /sub1/.snapshot/sn0", conf);
    DFSTestUtil.FsShellRun("-createSnapshot /sub1 sn0", 1, "there is already a snapshot with the same name \"sn0\"", conf);
    DFSTestUtil.FsShellRun("-rmr /sub1/sub1sub2", conf);
    DFSTestUtil.FsShellRun("-mkdir /sub1/sub1sub3", conf);
    DFSTestUtil.FsShellRun("-createSnapshot /sub1 sn1", 0, "Created snapshot /sub1/.snapshot/sn1", conf);
    // check snapshot contents
    DFSTestUtil.FsShellRun("-ls /sub1", 0, "/sub1/sub1sub1", conf);
    DFSTestUtil.FsShellRun("-ls /sub1", 0, "/sub1/sub1sub3", conf);
    DFSTestUtil.FsShellRun("-ls /sub1/.snapshot", 0, "/sub1/.snapshot/sn0", conf);
    DFSTestUtil.FsShellRun("-ls /sub1/.snapshot", 0, "/sub1/.snapshot/sn1", conf);
    DFSTestUtil.FsShellRun("-ls /sub1/.snapshot/sn0", 0, "/sub1/.snapshot/sn0/sub1sub1", conf);
    DFSTestUtil.FsShellRun("-ls /sub1/.snapshot/sn0", 0, "/sub1/.snapshot/sn0/sub1sub2", conf);
    DFSTestUtil.FsShellRun("-ls /sub1/.snapshot/sn1", 0, "/sub1/.snapshot/sn1/sub1sub1", conf);
    DFSTestUtil.FsShellRun("-ls /sub1/.snapshot/sn1", 0, "/sub1/.snapshot/sn1/sub1sub3", conf);
  }

  @Test
  public void testMaxSnapshotLimit() throws Exception {
    DFSTestUtil.FsShellRun("-mkdir /sub3", conf);
    DFSTestUtil.DFSAdminRun("-allowSnapshot /sub3", 0,
        "Allowing snapshot " + "on /sub3 succeeded", conf);
    // test createSnapshot
    DFSTestUtil.FsShellRun("-createSnapshot /sub3 sn0", 0,
        "Created snapshot /sub3/.snapshot/sn0", conf);
    DFSTestUtil.FsShellRun("-createSnapshot /sub3 sn1", 0,
        "Created snapshot /sub3/.snapshot/sn1", conf);
    DFSTestUtil.FsShellRun("-createSnapshot /sub3 sn2", 0,
        "Created snapshot /sub3/.snapshot/sn2", conf);
    DFSTestUtil.FsShellRun("-createSnapshot /sub3 sn3", 1,
        "Failed to add snapshot: there are already 3 snapshot(s) and "
            + "the max snapshot limit is 3", conf);
  }

  @Test
  public void testMkdirUsingReservedName() throws Exception {
    // test can not create dir with reserved name: .snapshot
    DFSTestUtil.FsShellRun("-ls /", conf);
    DFSTestUtil.FsShellRun("-mkdir /.snapshot", 1, "File exists", conf);
    DFSTestUtil.FsShellRun("-mkdir /sub1/.snapshot", 1, "File exists", conf);
    // mkdir -p ignore reserved name check if dir already exists
    DFSTestUtil.FsShellRun("-mkdir -p /sub1/.snapshot", conf);
    DFSTestUtil.FsShellRun("-mkdir -p /sub1/sub1sub1/.snapshot", 1, "mkdir: \".snapshot\" is a reserved name.", conf);
  }

  @Test
  public void testRenameSnapshot() throws Exception {
    DFSTestUtil.FsShellRun("-createSnapshot /sub1 sn.orig", conf);
    DFSTestUtil.FsShellRun("-renameSnapshot /sub1 sn.orig sn.rename", conf);
    DFSTestUtil.FsShellRun("-ls /sub1/.snapshot", 0, "/sub1/.snapshot/sn.rename", conf);
    DFSTestUtil.FsShellRun("-ls /sub1/.snapshot/sn.rename", 0, "/sub1/.snapshot/sn.rename/sub1sub1", conf);
    DFSTestUtil.FsShellRun("-ls /sub1/.snapshot/sn.rename", 0, "/sub1/.snapshot/sn.rename/sub1sub2", conf);

    //try renaming from a non-existing snapshot
    DFSTestUtil.FsShellRun("-renameSnapshot /sub1 sn.nonexist sn.rename", 1,
        "renameSnapshot: The snapshot sn.nonexist does not exist for directory /sub1", conf);

    //try renaming a non-existing snapshot to itself
    DFSTestUtil.FsShellRun("-renameSnapshot /sub1 sn.nonexist sn.nonexist", 1,
        "renameSnapshot: The snapshot sn.nonexist " +
            "does not exist for directory /sub1", conf);

    //try renaming to existing snapshots
    DFSTestUtil.FsShellRun("-createSnapshot /sub1 sn.new", conf);
    DFSTestUtil.FsShellRun("-renameSnapshot /sub1 sn.new sn.rename", 1,
        "renameSnapshot: The snapshot sn.rename already exists for directory /sub1", conf);
    DFSTestUtil.FsShellRun("-renameSnapshot /sub1 sn.rename sn.new", 1,
        "renameSnapshot: The snapshot sn.new already exists for directory /sub1", conf);
  }

  @Test
  public void testDeleteSnapshot() throws Exception {
    DFSTestUtil.FsShellRun("-createSnapshot /sub1 sn1", conf);
    DFSTestUtil.FsShellRun("-deleteSnapshot /sub1 sn1", conf);
    DFSTestUtil.FsShellRun("-deleteSnapshot /sub1 sn1", 1,
        "deleteSnapshot: Cannot delete snapshot sn1 from path /sub1: the snapshot does not exist.", conf);
  }

  @Test
  public void testDisallowSnapshot() throws Exception {
    DFSTestUtil.FsShellRun("-createSnapshot /sub1 sn1", conf);
    // cannot delete snapshotable dir
    DFSTestUtil.FsShellRun("-rmr /sub1", 1, "The directory /sub1 cannot be deleted since /sub1 is snapshottable and already has snapshots", conf);
    DFSTestUtil.DFSAdminRun("-disallowSnapshot /sub1", -1,
        "disallowSnapshot: The directory /sub1 has snapshot(s). Please redo the operation after removing all the snapshots.", conf);
    DFSTestUtil.FsShellRun("-deleteSnapshot /sub1 sn1", conf);
    DFSTestUtil.DFSAdminRun("-disallowSnapshot /sub1", 0,
        "Disallowing snapshot on /sub1 succeeded", conf);
    // Idempotent test
    DFSTestUtil.DFSAdminRun("-disallowSnapshot /sub1", 0,
        "Disallowing snapshot on /sub1 succeeded", conf);
    // now it can be deleted
    DFSTestUtil.FsShellRun("-rmr /sub1", conf);
  }

  @Test (timeout=60000)
  public void testSnapshotCommandsWithURI()throws Exception {
    Configuration config = new HdfsConfiguration();
    //fs.defaultFS should not be used, when path is fully qualified.
    config.set("fs.defaultFS", "hdfs://127.0.0.1:1024");
    String path = fs.getUri() + "/Fully/QPath";
    DFSTestUtil.DFSAdminRun("-allowSnapshot " + path, 0,
        "Allowing snapshot on " + path + " succeeded", config);
    DFSTestUtil.FsShellRun("-createSnapshot " + path + " sn1", config);
    // create file1
    DFSTestUtil
        .createFile(fs, new Path(fs.getUri() + "/Fully/QPath/File1"), 1024,
            (short) 1, 100);
    // create file2
    DFSTestUtil
        .createFile(fs, new Path(fs.getUri() + "/Fully/QPath/File2"), 1024,
            (short) 1, 100);
    DFSTestUtil.FsShellRun("-createSnapshot " + path + " sn2", config);
    // verify the snapshotdiff using api and command line
    SnapshotDiffReport report =
        fs.getSnapshotDiffReport(new Path(path), "sn1", "sn2");
    DFSTestUtil.toolRun(new SnapshotDiff(config), path + " sn1 sn2", 0,
        report.toString());
    DFSTestUtil.FsShellRun("-renameSnapshot " + path + " sn2 sn3", config);
    DFSTestUtil.FsShellRun("-deleteSnapshot " + path + " sn1", config);
    DFSTestUtil.FsShellRun("-deleteSnapshot " + path + " sn3", config);
    DFSTestUtil.DFSAdminRun("-disallowSnapshot " + path, 0,
        "Disallowing snapshot on " + path + " succeeded", config);
    fs.delete(new Path("/Fully/QPath"), true);
  }

  @Test (timeout=60000)
  public void testSnapshotDiff()throws Exception {
    Configuration config = new HdfsConfiguration();
    Path snapDirPath = new Path(fs.getUri().toString() + "/snap_dir");
    String snapDir = snapDirPath.toString();
    fs.mkdirs(snapDirPath);

    DFSTestUtil.DFSAdminRun("-allowSnapshot " + snapDirPath, 0,
        "Allowing snapshot on " + snapDirPath + " succeeded", config);
    DFSTestUtil.createFile(fs, new Path(snapDirPath, "file1"),
        1024, (short) 1, 100);
    DFSTestUtil.FsShellRun("-createSnapshot " + snapDirPath + " sn1", config);
    DFSTestUtil.createFile(fs, new Path(snapDirPath, "file2"),
        1024, (short) 1, 100);
    DFSTestUtil.createFile(fs, new Path(snapDirPath, "file3"),
        1024, (short) 1, 100);
    DFSTestUtil.FsShellRun("-createSnapshot " + snapDirPath + " sn2", config);

    // verify the snapshot diff using api and command line
    SnapshotDiffReport report_s1_s2 =
        fs.getSnapshotDiffReport(snapDirPath, "sn1", "sn2");
    DFSTestUtil.toolRun(new SnapshotDiff(config), snapDir +
        " sn1 sn2", 0, report_s1_s2.toString());
    DFSTestUtil.FsShellRun("-renameSnapshot " + snapDirPath + " sn2 sn3",
        config);

    SnapshotDiffReport report_s1_s3 =
        fs.getSnapshotDiffReport(snapDirPath, "sn1", "sn3");
    DFSTestUtil.toolRun(new SnapshotDiff(config), snapDir +
        " sn1 sn3", 0, report_s1_s3.toString());

    // Creating 100 more files so as to force DiffReport generation
    // backend ChunkedArrayList to create multiple chunks.
    for (int i = 0; i < 100; i++) {
      DFSTestUtil.createFile(fs, new Path(snapDirPath, "file_" + i),
          1, (short) 1, 100);
    }
    DFSTestUtil.FsShellRun("-createSnapshot " + snapDirPath + " sn4", config);
    DFSTestUtil.toolRun(new SnapshotDiff(config), snapDir +
        " sn1 sn4", 0, null);

    DFSTestUtil.FsShellRun("-deleteSnapshot " + snapDir + " sn1", config);
    DFSTestUtil.FsShellRun("-deleteSnapshot " + snapDir + " sn3", config);
    DFSTestUtil.FsShellRun("-deleteSnapshot " + snapDir + " sn4", config);
    DFSTestUtil.DFSAdminRun("-disallowSnapshot " + snapDir, 0,
        "Disallowing snapshot on " + snapDirPath + " succeeded", config);
    fs.delete(new Path("/Fully/QPath"), true);
  }
}
