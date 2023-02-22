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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.Test;

/**
 * A JUnit test for checking if restarting DFS preserves integrity.
 */
public class TestRestartDFS {
  public void runTests(Configuration conf, boolean serviceTest) throws Exception {
    MiniDFSCluster cluster = null;
    DFSTestUtil files = new DFSTestUtil.Builder().setName("TestRestartDFS").
        setNumFiles(20).build();

    final String dir = "/srcdat";
    final Path rootpath = new Path("/");
    final Path dirpath = new Path(dir);

    long rootmtime;
    FileStatus rootstatus;
    FileStatus dirstatus;

    try {
      if (serviceTest) {
        conf.set(DFSConfigKeys.DFS_NAMENODE_SERVICE_RPC_ADDRESS_KEY,
                 "localhost:0");
      }
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(4).build();
      FileSystem fs = cluster.getFileSystem();
      files.createFiles(fs, dir);

      rootmtime = fs.getFileStatus(rootpath).getModificationTime();
      rootstatus = fs.getFileStatus(dirpath);
      dirstatus = fs.getFileStatus(dirpath);

      fs.setOwner(rootpath, rootstatus.getOwner() + "_XXX", null);
      fs.setOwner(dirpath, null, dirstatus.getGroup() + "_XXX");
    } finally {
      if (cluster != null) { cluster.shutdown(); }
    }
    try {
      if (serviceTest) {
        conf.set(DFSConfigKeys.DFS_NAMENODE_SERVICE_RPC_ADDRESS_KEY,
                 "localhost:0");
      }
      // Here we restart the MiniDFScluster without formatting namenode
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(4).format(false).build(); 
      FileSystem fs = cluster.getFileSystem();
      assertTrue("Filesystem corrupted after restart.",
                 files.checkFiles(fs, dir));

      final FileStatus newrootstatus = fs.getFileStatus(rootpath);
      assertEquals(rootmtime, newrootstatus.getModificationTime());
      assertEquals(rootstatus.getOwner() + "_XXX", newrootstatus.getOwner());
      assertEquals(rootstatus.getGroup(), newrootstatus.getGroup());

      final FileStatus newdirstatus = fs.getFileStatus(dirpath);
      assertEquals(dirstatus.getOwner(), newdirstatus.getOwner());
      assertEquals(dirstatus.getGroup() + "_XXX", newdirstatus.getGroup());
      rootmtime = fs.getFileStatus(rootpath).getModificationTime();
    } finally {
      if (cluster != null) { cluster.shutdown(); }
    }
    try {
      if (serviceTest) {
        conf.set(DFSConfigKeys.DFS_NAMENODE_SERVICE_RPC_ADDRESS_KEY,
                 "localhost:0");
      }
      // This is a second restart to check that after the first restart
      // the image written in parallel to both places did not get corrupted
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(4).format(false).build();
      FileSystem fs = cluster.getFileSystem();
      assertTrue("Filesystem corrupted after restart.",
                 files.checkFiles(fs, dir));

      final FileStatus newrootstatus = fs.getFileStatus(rootpath);
      assertEquals(rootmtime, newrootstatus.getModificationTime());
      assertEquals(rootstatus.getOwner() + "_XXX", newrootstatus.getOwner());
      assertEquals(rootstatus.getGroup(), newrootstatus.getGroup());

      final FileStatus newdirstatus = fs.getFileStatus(dirpath);
      assertEquals(dirstatus.getOwner(), newdirstatus.getOwner());
      assertEquals(dirstatus.getGroup() + "_XXX", newdirstatus.getGroup());

      files.cleanup(fs, dir);
    } finally {
      if (cluster != null) { cluster.shutdown(); }
    }
  }
  /** check if DFS remains in proper condition after a restart */
  @Test
  public void testRestartDFS() throws Exception {
    final Configuration conf = new HdfsConfiguration();
    runTests(conf, false);
  }
  
  /** check if DFS remains in proper condition after a restart 
   * this rerun is with 2 ports enabled for RPC in the namenode
   */
  @Test
   public void testRestartDualPortDFS() throws Exception {
     final Configuration conf = new HdfsConfiguration();
     runTests(conf, true);
   }
}
