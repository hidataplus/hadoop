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

package org.apache.hadoop.mapred;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.serializer.Deserializer;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.io.serializer.Serializer;
import org.apache.hadoop.util.GenericsUtil;
import org.junit.Test;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class TestWritableJobConf {

  private static final Configuration CONF = new Configuration();

  private <K> K serDeser(K conf) throws Exception {
    SerializationFactory factory = new SerializationFactory(CONF);
    Serializer<K> serializer =
      factory.getSerializer(GenericsUtil.getClass(conf));
    Deserializer<K> deserializer =
      factory.getDeserializer(GenericsUtil.getClass(conf));

    DataOutputBuffer out = new DataOutputBuffer();
    serializer.open(out);
    serializer.serialize(conf);
    serializer.close();

    DataInputBuffer in = new DataInputBuffer();
    in.reset(out.getData(), out.getLength());
    deserializer.open(in);
    K after = deserializer.deserialize(null);
    deserializer.close();
    return after;
  }

  private void assertEquals(Configuration conf1, Configuration conf2) {
    // We ignore deprecated keys because after deserializing, both the
    // deprecated and the non-deprecated versions of a config are set.
    // This is consistent with both the set and the get methods.
    Iterator<Map.Entry<String, String>> iterator1 = conf1.iterator();
    Map<String, String> map1 = new HashMap<String,String>();
    while (iterator1.hasNext()) {
      Map.Entry<String, String> entry = iterator1.next();
      if (!Configuration.isDeprecated(entry.getKey())) {
        map1.put(entry.getKey(), entry.getValue());
      }
    }

    Iterator<Map.Entry<String, String>> iterator2 = conf2.iterator();
    Map<String, String> map2 = new HashMap<String,String>();
    while (iterator2.hasNext()) {
      Map.Entry<String, String> entry = iterator2.next();
      if (!Configuration.isDeprecated(entry.getKey())) {
        map2.put(entry.getKey(), entry.getValue());
      }
    }

    assertTrue(map1.equals(map2));
  }

  @Test
  public void testEmptyConfiguration() throws Exception {
    JobConf conf = new JobConf();
    Configuration deser = serDeser(conf);
    assertEquals(conf, deser);
  }

  @Test
  public void testNonEmptyConfiguration() throws Exception {
    JobConf conf = new JobConf();
    conf.set("a", "A");
    conf.set("b", "B");
    Configuration deser = serDeser(conf);
    assertEquals(conf, deser);
  }

  @Test
  public void testConfigurationWithDefaults() throws Exception {
    JobConf conf = new JobConf(false);
    conf.set("a", "A");
    conf.set("b", "B");
    Configuration deser = serDeser(conf);
    assertEquals(conf, deser);
  }
  
}
