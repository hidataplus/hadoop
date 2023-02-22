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
package org.apache.hadoop.net;

import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.NET_TOPOLOGY_TABLE_MAPPING_FILE_KEY;

import static org.junit.Assert.assertEquals;

import org.apache.hadoop.thirdparty.com.google.common.base.Charsets;
import org.apache.hadoop.thirdparty.com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.junit.Before;
import org.junit.Test;

public class TestTableMapping {
  private String hostName1 = "1.2.3.4";
  private String hostName2 = "5.6.7.8";

  @Test
  public void testResolve() throws IOException {
    File mapFile = File.createTempFile(getClass().getSimpleName() +
        ".testResolve", ".txt");
    Files.asCharSink(mapFile, Charsets.UTF_8).write(
        hostName1 + " /rack1\n" + hostName2 + "\t/rack2\n");
    mapFile.deleteOnExit();
    TableMapping mapping = new TableMapping();

    Configuration conf = new Configuration();
    conf.set(NET_TOPOLOGY_TABLE_MAPPING_FILE_KEY, mapFile.getCanonicalPath());
    mapping.setConf(conf);

    List<String> names = new ArrayList<String>();
    names.add(hostName1);
    names.add(hostName2);

    List<String> result = mapping.resolve(names);
    assertEquals(names.size(), result.size());
    assertEquals("/rack1", result.get(0));
    assertEquals("/rack2", result.get(1));
  }

  @Test
  public void testTableCaching() throws IOException {
    File mapFile = File.createTempFile(getClass().getSimpleName() +
        ".testTableCaching", ".txt");
    Files.asCharSink(mapFile, Charsets.UTF_8).write(
        hostName1 + " /rack1\n" + hostName2 + "\t/rack2\n");
    mapFile.deleteOnExit();
    TableMapping mapping = new TableMapping();

    Configuration conf = new Configuration();
    conf.set(NET_TOPOLOGY_TABLE_MAPPING_FILE_KEY, mapFile.getCanonicalPath());
    mapping.setConf(conf);

    List<String> names = new ArrayList<String>();
    names.add(hostName1);
    names.add(hostName2);

    List<String> result1 = mapping.resolve(names);
    assertEquals(names.size(), result1.size());
    assertEquals("/rack1", result1.get(0));
    assertEquals("/rack2", result1.get(1));

    // unset the file, see if it gets read again
    conf.set(NET_TOPOLOGY_TABLE_MAPPING_FILE_KEY, "some bad value for a file");

    List<String> result2 = mapping.resolve(names);
    assertEquals(result1, result2);
  }

  @Test
  public void testNoFile() {
    TableMapping mapping = new TableMapping();

    Configuration conf = new Configuration();
    mapping.setConf(conf);

    List<String> names = new ArrayList<String>();
    names.add(hostName1);
    names.add(hostName2);

    List<String> result = mapping.resolve(names);
    assertEquals(names.size(), result.size());
    assertEquals(NetworkTopology.DEFAULT_RACK, result.get(0));
    assertEquals(NetworkTopology.DEFAULT_RACK, result.get(1));
  }

  @Test
  public void testFileDoesNotExist() {
    TableMapping mapping = new TableMapping();

    Configuration conf = new Configuration();
    conf.set(NET_TOPOLOGY_TABLE_MAPPING_FILE_KEY, "/this/file/does/not/exist");
    mapping.setConf(conf);

    List<String> names = new ArrayList<String>();
    names.add(hostName1);
    names.add(hostName2);

    List<String> result = mapping.resolve(names);
    assertEquals(names.size(), result.size());
    assertEquals(result.get(0), NetworkTopology.DEFAULT_RACK);
    assertEquals(result.get(1), NetworkTopology.DEFAULT_RACK);
  }

  @Test
  public void testClearingCachedMappings() throws IOException {
    File mapFile = File.createTempFile(getClass().getSimpleName() +
        ".testClearingCachedMappings", ".txt");
    Files.asCharSink(mapFile, Charsets.UTF_8).write(
        hostName1 + " /rack1\n" + hostName2 + "\t/rack2\n");
    mapFile.deleteOnExit();

    TableMapping mapping = new TableMapping();

    Configuration conf = new Configuration();
    conf.set(NET_TOPOLOGY_TABLE_MAPPING_FILE_KEY, mapFile.getCanonicalPath());
    mapping.setConf(conf);

    List<String> names = new ArrayList<String>();
    names.add(hostName1);
    names.add(hostName2);

    List<String> result = mapping.resolve(names);
    assertEquals(names.size(), result.size());
    assertEquals("/rack1", result.get(0));
    assertEquals("/rack2", result.get(1));

    Files.asCharSink(mapFile, Charsets.UTF_8).write("");

    mapping.reloadCachedMappings();

    names = new ArrayList<String>();
    names.add(hostName1);
    names.add(hostName2);

    result = mapping.resolve(names);
    assertEquals(names.size(), result.size());
    assertEquals(NetworkTopology.DEFAULT_RACK, result.get(0));
    assertEquals(NetworkTopology.DEFAULT_RACK, result.get(1));
  }


  @Test(timeout=60000)
  public void testBadFile() throws IOException {
    File mapFile = File.createTempFile(getClass().getSimpleName() +
        ".testBadFile", ".txt");
    Files.asCharSink(mapFile, Charsets.UTF_8).write("bad contents");
    mapFile.deleteOnExit();
    TableMapping mapping = new TableMapping();

    Configuration conf = new Configuration();
    conf.set(NET_TOPOLOGY_TABLE_MAPPING_FILE_KEY, mapFile.getCanonicalPath());
    mapping.setConf(conf);

    List<String> names = new ArrayList<String>();
    names.add(hostName1);
    names.add(hostName2);

    List<String> result = mapping.resolve(names);
    assertEquals(names.size(), result.size());
    assertEquals(result.get(0), NetworkTopology.DEFAULT_RACK);
    assertEquals(result.get(1), NetworkTopology.DEFAULT_RACK);
  }
}
