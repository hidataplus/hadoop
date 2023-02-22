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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.server.datanode.metrics.DataNodePeerMetrics;
import org.apache.hadoop.metrics2.lib.MetricsTestHelper;
import org.apache.hadoop.metrics2.lib.MutableRollingAverages;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.conf.Configuration;
import org.junit.Test;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_PEER_METRICS_MIN_OUTLIER_DETECTION_SAMPLES_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_PEER_STATS_ENABLED_KEY;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/**
 * This class tests various cases of DataNode peer metrics.
 */
public class TestDataNodePeerMetrics {

  @Test(timeout = 30000)
  public void testGetSendPacketDownstreamAvgInfo() throws Exception {
    final int windowSize = 5; // 5s roll over interval
    final int numWindows = 2; // 2 rolling windows
    final int iterations = 3;
    final int numOpsPerIteration = 1000;

    final Configuration conf = new HdfsConfiguration();
    conf.setBoolean(DFS_DATANODE_PEER_STATS_ENABLED_KEY, true);

    final DataNodePeerMetrics peerMetrics = DataNodePeerMetrics.create(
        "Sample-DataNode", conf);
    MetricsTestHelper.replaceRollingAveragesScheduler(
        peerMetrics.getSendPacketDownstreamRollingAverages(),
        numWindows, windowSize, TimeUnit.SECONDS);
    final long start = Time.monotonicNow();
    for (int i = 1; i <= iterations; i++) {
      final String peerAddr = genPeerAddress();
      for (int j = 1; j <= numOpsPerIteration; j++) {
        /* simulate to get latency of 1 to 1000 ms */
        final long latency = ThreadLocalRandom.current().nextLong(1, 1000);
        peerMetrics.addSendPacketDownstream(peerAddr, latency);
      }

      /**
       * Sleep until 1s after the next windowSize seconds interval, to let the
       * metrics roll over
       */
      final long sleep = (start + (windowSize * 1000 * i) + 1000)
          - Time.monotonicNow();
      Thread.sleep(sleep);

      /* dump avg info */
      final String json = peerMetrics.dumpSendPacketDownstreamAvgInfoAsJson();

      /*
       * example json:
       * {"[185.164.159.81:9801]RollingAvgTime":504.867,
       *  "[49.236.149.246:9801]RollingAvgTime":504.463,
       *  "[84.125.113.65:9801]RollingAvgTime":497.954}
       */
      assertThat(json, containsString(peerAddr));
    }
  }

  @Test(timeout = 30000)
  public void testRemoveStaleRecord() throws Exception {
    final int numWindows = 5;
    final long scheduleInterval = 1000;
    final int iterations = 3;
    final int numSamples = 100;

    final Configuration conf = new HdfsConfiguration();
    conf.setLong(DFS_DATANODE_PEER_METRICS_MIN_OUTLIER_DETECTION_SAMPLES_KEY,
        numSamples);
    conf.setBoolean(DFS_DATANODE_PEER_STATS_ENABLED_KEY, true);

    final DataNodePeerMetrics peerMetrics =
        DataNodePeerMetrics.create("Sample-DataNode", conf);
    MutableRollingAverages rollingAverages =
        peerMetrics.getSendPacketDownstreamRollingAverages();
    rollingAverages.setRecordValidityMs(numWindows * scheduleInterval);
    MetricsTestHelper.replaceRollingAveragesScheduler(rollingAverages,
        numWindows, scheduleInterval, TimeUnit.MILLISECONDS);

    List<String> peerAddrList = new ArrayList<>();
    for (int i = 1; i <= iterations; i++) {
      peerAddrList.add(genPeerAddress());
    }
    for (String peerAddr : peerAddrList) {
      for (int j = 1; j <= numSamples; j++) {
        /* simulate to get latency of 1 to 1000 ms */
        final long latency = ThreadLocalRandom.current().nextLong(1, 1000);
        peerMetrics.addSendPacketDownstream(peerAddr, latency);
      }
    }

    GenericTestUtils.waitFor(
        () -> rollingAverages.getStats(numSamples).size() > 0, 500, 5000);
    assertEquals(3, rollingAverages.getStats(numSamples).size());
    /* wait for stale report to be removed */
    GenericTestUtils.waitFor(
        () -> rollingAverages.getStats(numSamples).isEmpty(), 500, 10000);
    assertEquals(0, rollingAverages.getStats(numSamples).size());

    /* dn can report peer metrics normally when it added back to cluster */
    for (String peerAddr : peerAddrList) {
      for (int j = 1; j <= numSamples; j++) {
        /* simulate to get latency of 1 to 1000 ms */
        final long latency = ThreadLocalRandom.current().nextLong(1, 1000);
        peerMetrics.addSendPacketDownstream(peerAddr, latency);
      }
    }
    GenericTestUtils.waitFor(
        () -> rollingAverages.getStats(numSamples).size() > 0, 500, 10000);
    assertEquals(3, rollingAverages.getStats(numSamples).size());
  }

  /**
   * Simulates to generate different peer addresses, e.g. [84.125.113.65:9801].
   */
  private String genPeerAddress() {
    final  ThreadLocalRandom r = ThreadLocalRandom.current();
    return String.format("[%d.%d.%d.%d:9801]",
        r.nextInt(1, 256), r.nextInt(1, 256),
        r.nextInt(1, 256), r.nextInt(1, 256));
  }
}
