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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.apache.hadoop.conf.Configuration;
import org.junit.Test;

public class TestDeprecatedKeys {
 
  //Tests a deprecated key
  @Test
  public void testDeprecatedKeys() throws Exception {
    Configuration conf = new HdfsConfiguration();
    conf.set("topology.script.file.name", "xyz");
    String scriptFile = conf.get(DFSConfigKeys.NET_TOPOLOGY_SCRIPT_FILE_NAME_KEY);
    assertTrue(scriptFile.equals("xyz")) ;
    conf.setInt("dfs.replication.interval", 1);
    int redundancyInterval = conf
        .getInt(DFSConfigKeys.DFS_NAMENODE_REDUNDANCY_INTERVAL_SECONDS_KEY, 3);
    assertTrue(redundancyInterval == 1);
    int repInterval = conf.getInt("dfs.replication.interval", 3);
    assertTrue(repInterval == 1);
    repInterval = conf.getInt("dfs.namenode.replication.interval", 3);
    assertTrue(repInterval == 1);

    conf.setBoolean("dfs.replication.considerLoad", false);
    assertFalse(conf.getBoolean(
        DFSConfigKeys.DFS_NAMENODE_REDUNDANCY_CONSIDERLOAD_KEY, true));
    assertFalse(conf.getBoolean("dfs.replication.considerLoad", true));

    conf.setDouble("dfs.namenode.replication.considerLoad.factor", 5.0);
    assertTrue(5.0 == conf.getDouble(
        DFSConfigKeys.DFS_NAMENODE_REDUNDANCY_CONSIDERLOAD_FACTOR, 2.0));
    assertTrue(5.0 == conf
        .getDouble("dfs.namenode.replication.considerLoad.factor", 2.0));
  }
}
