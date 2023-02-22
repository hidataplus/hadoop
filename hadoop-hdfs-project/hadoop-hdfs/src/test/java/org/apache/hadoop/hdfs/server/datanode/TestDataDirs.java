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

package org.apache.hadoop.hdfs.server.datanode;

import java.io.*;
import java.util.*;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.StorageType;
import org.junit.Test;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class TestDataDirs {

  @Test(timeout = 30000)
  public void testDataDirParsing() throws Throwable {
    Configuration conf = new Configuration();
    List<StorageLocation> locations;
    File dir0 = new File("/dir0");
    File dir1 = new File("/dir1");
    File dir2 = new File("/dir2");
    File dir3 = new File("/dir3");
    File dir4 = new File("/dir4");

    File dir5 = new File("/dir5");
    File dir6 = new File("/dir6");
    // Verify that a valid string is correctly parsed, and that storage
    // type is not case-sensitive and we are able to handle white-space between
    // storage type and URI.
    String locations1 = "[disk]/dir0,[DISK]/dir1,[sSd]/dir2,[disK]/dir3," +
            "[ram_disk]/dir4,[disk]/dir5, [disk] /dir6, [disk] ";
    conf.set(DFS_DATANODE_DATA_DIR_KEY, locations1);
    locations = DataNode.getStorageLocations(conf);
    assertThat(locations.size(), is(8));
    assertThat(locations.get(0).getStorageType(), is(StorageType.DISK));
    assertThat(locations.get(0).getUri(), is(dir0.toURI()));
    assertThat(locations.get(1).getStorageType(), is(StorageType.DISK));
    assertThat(locations.get(1).getUri(), is(dir1.toURI()));
    assertThat(locations.get(2).getStorageType(), is(StorageType.SSD));
    assertThat(locations.get(2).getUri(), is(dir2.toURI()));
    assertThat(locations.get(3).getStorageType(), is(StorageType.DISK));
    assertThat(locations.get(3).getUri(), is(dir3.toURI()));
    assertThat(locations.get(4).getStorageType(), is(StorageType.RAM_DISK));
    assertThat(locations.get(4).getUri(), is(dir4.toURI()));
    assertThat(locations.get(5).getStorageType(), is(StorageType.DISK));
    assertThat(locations.get(5).getUri(), is(dir5.toURI()));
    assertThat(locations.get(6).getStorageType(), is(StorageType.DISK));
    assertThat(locations.get(6).getUri(), is(dir6.toURI()));

    // not asserting the 8th URI since it is incomplete and it in the
    // test set to make sure that we don't fail if we get URIs like that.
    assertThat(locations.get(7).getStorageType(), is(StorageType.DISK));

    // Verify that an unrecognized storage type result in an exception.
    String locations2 = "[BadMediaType]/dir0,[ssd]/dir1,[disk]/dir2";
    conf.set(DFS_DATANODE_DATA_DIR_KEY, locations2);
    try {
      locations = DataNode.getStorageLocations(conf);
      fail();
    } catch (IllegalArgumentException iae) {
      DataNode.LOG.info("The exception is expected.", iae);
    }

    // Assert that a string with no storage type specified is
    // correctly parsed and the default storage type is picked up.
    String locations3 = "/dir0,/dir1";
    conf.set(DFS_DATANODE_DATA_DIR_KEY, locations3);
    locations = DataNode.getStorageLocations(conf);
    assertThat(locations.size(), is(2));
    assertThat(locations.get(0).getStorageType(), is(StorageType.DISK));
    assertThat(locations.get(0).getUri(), is(dir0.toURI()));
    assertThat(locations.get(1).getStorageType(), is(StorageType.DISK));
    assertThat(locations.get(1).getUri(), is(dir1.toURI()));
  }
}
