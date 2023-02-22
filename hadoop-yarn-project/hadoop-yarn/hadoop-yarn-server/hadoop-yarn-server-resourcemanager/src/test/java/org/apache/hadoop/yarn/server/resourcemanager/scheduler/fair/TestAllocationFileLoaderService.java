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
package org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.resourcemanager.RMContext;
import org.apache.hadoop.yarn.server.resourcemanager.placement.DefaultPlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.placement.FSPlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.placement.PlacementManager;
import org.apache.hadoop.yarn.server.resourcemanager.placement.PlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.placement.PrimaryGroupPlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.placement.SpecifiedPlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.placement.UserPlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.reservation.ReservationSchedulerConfiguration;

import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.allocationfile.AllocationFileQueue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.allocationfile.AllocationFileQueuePlacementPolicy;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.allocationfile.AllocationFileQueuePlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.allocationfile.AllocationFileWriter;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.allocationfile.UserSettings;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.policies.DominantResourceFairnessPolicy;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.policies.FairSharePolicy;
import org.apache.hadoop.yarn.util.ControlledClock;
import org.apache.hadoop.yarn.util.SystemClock;
import org.apache.hadoop.yarn.util.resource.CustomResourceTypesConfigurationProvider;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test loading the allocation file for the FairScheduler.
 */
public class TestAllocationFileLoaderService {

  private static final String A_CUSTOM_RESOURCE = "a-custom-resource";

  final static String TEST_DIR = new File(System.getProperty("test.build.data",
      "/tmp")).getAbsolutePath();

  final static String ALLOC_FILE = new File(TEST_DIR,
      "test-queues").getAbsolutePath();
  private static final String TEST_FAIRSCHED_XML = "test-fair-scheduler.xml";

  private FairScheduler scheduler;
  private Configuration conf;

  @Before
  public void setup() {
    SystemClock clock = SystemClock.getInstance();
    PlacementManager placementManager = new PlacementManager();
    FairSchedulerConfiguration fsConf = new FairSchedulerConfiguration();
    RMContext rmContext = mock(RMContext.class);
    when(rmContext.getQueuePlacementManager()).thenReturn(placementManager);

    scheduler = mock(FairScheduler.class);
    conf = new YarnConfiguration();
    when(scheduler.getClock()).thenReturn(clock);
    when(scheduler.getConf()).thenReturn(fsConf);
    when(scheduler.getConfig()).thenReturn(conf);
    when(scheduler.getRMContext()).thenReturn(rmContext);
  }

  @After
  public void teardown() {
    new File(ALLOC_FILE).delete();
  }

  @Test
  public void testGetAllocationFileFromFileSystem()
      throws IOException, URISyntaxException {
    File baseDir =
        new File(TEST_DIR + Path.SEPARATOR + "getAllocHDFS").getAbsoluteFile();
    FileUtil.fullyDelete(baseDir);
    conf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, baseDir.getAbsolutePath());
    MiniDFSCluster.Builder builder = new MiniDFSCluster.Builder(conf);
    MiniDFSCluster hdfsCluster = builder.build();
    String fsAllocPath = "hdfs://localhost:" + hdfsCluster.getNameNodePort()
        + Path.SEPARATOR + TEST_FAIRSCHED_XML;

    URL fschedURL = Thread.currentThread().getContextClassLoader()
        .getResource(TEST_FAIRSCHED_XML);
    FileSystem fs = FileSystem.get(conf);
    fs.copyFromLocalFile(new Path(fschedURL.toURI()), new Path(fsAllocPath));
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, fsAllocPath);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    Path allocationFile = allocLoader.getAllocationFile(conf);
    assertEquals(fsAllocPath, allocationFile.toString());
    assertTrue(fs.exists(allocationFile));

    hdfsCluster.shutdown(true);
  }

  @Test (expected = UnsupportedFileSystemException.class)
  public void testDenyGetAllocationFileFromUnsupportedFileSystem()
      throws UnsupportedFileSystemException {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, "badfs:///badfile");
    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);

    allocLoader.getAllocationFile(conf);
  }

  @Test
  public void testGetAllocationFileFromClasspath() {
    try {
      FileSystem fs = FileSystem.get(conf);
      conf.set(FairSchedulerConfiguration.ALLOCATION_FILE,
          TEST_FAIRSCHED_XML);
      AllocationFileLoaderService allocLoader =
          new AllocationFileLoaderService(scheduler);
      Path allocationFile = allocLoader.getAllocationFile(conf);
      assertEquals(TEST_FAIRSCHED_XML, allocationFile.getName());
      assertTrue(fs.exists(allocationFile));
    } catch (IOException e) {
      fail("Unable to access allocation file from classpath: " + e);
    }
  }

  @Test (timeout = 10000)
  public void testReload() throws Exception {
    AllocationFileWriter.create()
        .addQueue(new AllocationFileQueue.Builder("queueA")
            .maxRunningApps(1).build())
        .addQueue(new AllocationFileQueue.Builder("queueB").build())
        .queuePlacementPolicy(new AllocationFileQueuePlacementPolicy()
            .addRule(new AllocationFileQueuePlacementRule(
                AllocationFileQueuePlacementRule.RuleName.DEFAULT)))
        .writeToFile(ALLOC_FILE);

    ControlledClock clock = new ControlledClock();
    clock.setTime(0);
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileLoaderService allocLoader = new AllocationFileLoaderService(
        clock, scheduler);
    allocLoader.reloadIntervalMs = 5;
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
    AllocationConfiguration allocConf = confHolder.allocConf;

    // Verify conf
    List<PlacementRule> rules = scheduler.getRMContext()
        .getQueuePlacementManager().getPlacementRules();
    assertEquals(1, rules.size());
    assertEquals(DefaultPlacementRule.class, rules.get(0).getClass());
    assertEquals(1, allocConf.getQueueMaxApps("root.queueA"));
    assertEquals(2, allocConf.getConfiguredQueues().get(FSQueueType.LEAF)
        .size());
    assertTrue(allocConf.getConfiguredQueues().get(FSQueueType.LEAF)
        .contains("root.queueA"));
    assertTrue(allocConf.getConfiguredQueues().get(FSQueueType.LEAF)
        .contains("root.queueB"));

    // reset the conf so we can detect the reload
    confHolder.allocConf = null;

    // Modify file and advance the clock
    AllocationFileWriter.create()
        .addQueue(new AllocationFileQueue.Builder("queueB")
            .maxRunningApps(3).build())
        .queuePlacementPolicy(new AllocationFileQueuePlacementPolicy()
            .addRule(new AllocationFileQueuePlacementRule(
                AllocationFileQueuePlacementRule.RuleName.SPECIFIED))
            .addRule(new AllocationFileQueuePlacementRule(
                AllocationFileQueuePlacementRule.RuleName.NESTED)
            .addNestedRule(new AllocationFileQueuePlacementRule(
                AllocationFileQueuePlacementRule.RuleName.PRIMARY_GROUP))))
        .writeToFile(ALLOC_FILE);

    clock.tickMsec(System.currentTimeMillis()
        + AllocationFileLoaderService.ALLOC_RELOAD_WAIT_MS + 10000);
    allocLoader.start();

    while (confHolder.allocConf == null) {
      Thread.sleep(20);
    }

    // Verify conf
    allocConf = confHolder.allocConf;
    rules = scheduler.getRMContext().getQueuePlacementManager()
        .getPlacementRules();
    assertEquals(2, rules.size());
    assertEquals(SpecifiedPlacementRule.class, rules.get(0).getClass());
    assertEquals(UserPlacementRule.class, rules.get(1).getClass());
    assertEquals(PrimaryGroupPlacementRule.class,
        ((FSPlacementRule)(rules.get(1))).getParentRule().getClass());
    assertEquals(3, allocConf.getQueueMaxApps("root.queueB"));
    assertEquals(1, allocConf.getConfiguredQueues().get(FSQueueType.LEAF)
        .size());
    assertTrue(allocConf.getConfiguredQueues().get(FSQueueType.LEAF)
        .contains("root.queueB"));
  }

  @Test
  public void testAllocationFileParsing() throws Exception {
    CustomResourceTypesConfigurationProvider.
        initResourceTypes(A_CUSTOM_RESOURCE);
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);
    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);

    AllocationFileWriter.create()
            // Give queue A a minimum of 1024 M
            .addQueue(new AllocationFileQueue.Builder("queueA")
                .minResources("1024mb,0vcores")
                .maxResources("2048mb,10vcores")
                .build())
            // Give queue B a minimum of 2048 M
            .addQueue(new AllocationFileQueue.Builder("queueB")
                .minResources("2048mb,0vcores")
                .maxResources("5120mb,110vcores")
                .aclAdministerApps("alice,bob admins")
                .schedulingPolicy("fair")
                .build())
            // Give queue C no minimum
            .addQueue(new AllocationFileQueue.Builder("queueC")
                .minResources("5120mb,0vcores")
                .aclSubmitApps("alice,bob admins")
                .build())
            // Give queue D a limit of 3 running apps and 0.4f maxAMShare
            .addQueue(new AllocationFileQueue.Builder("queueD")
                .maxRunningApps(3)
                .maxAMShare(0.4)
                .build())
            // Give queue E a preemption timeout of one minute
            .addQueue(new AllocationFileQueue.Builder("queueE")
                .minSharePreemptionTimeout(60)
                .build())
            // Make queue F a parent queue without configured leaf queues
            // using the 'type' attribute
            .addQueue(new AllocationFileQueue.Builder("queueF")
                .parent(true)
                .maxChildResources("2048mb,64vcores")
                .build())
            .addQueue(new AllocationFileQueue.Builder("queueG")
                .maxChildResources("2048mb,64vcores")
                .fairSharePreemptionTimeout(120)
                .minSharePreemptionTimeout(50)
                .fairSharePreemptionThreshold(0.6)
                .maxContainerAllocation(
                      "vcores=16, memory-mb=512, " + A_CUSTOM_RESOURCE + "=10")
            // Create hierarchical queues G,H, with different min/fair
            // share preemption timeouts and preemption thresholds.
            // Also add a child default to make sure it doesn't impact queue H.
                .subQueue(new AllocationFileQueue.Builder("queueH")
                    .fairSharePreemptionTimeout(180)
                    .minSharePreemptionTimeout(40)
                    .fairSharePreemptionThreshold(0.7)
                    .maxContainerAllocation("1024mb,8vcores")
                    .build())
                .build())
            // Set default limit of apps per queue to 15
            .queueMaxAppsDefault(15)
            // Set default limit of max resource per queue to 4G and 100 cores
            .queueMaxResourcesDefault("4096mb,100vcores")
            // Set default limit of apps per user to 5
            .userMaxAppsDefault(5)
            // Set default limit of AMResourceShare to 0.5f
            .queueMaxAMShareDefault(0.5)
            // Set default min share preemption timeout to 2 minutes
            .defaultMinSharePreemptionTimeout(120)
            // Set default fair share preemption timeout to 5 minutes
            .defaultFairSharePreemptionTimeout(300)
            // Set default fair share preemption threshold to 0.4
            .defaultFairSharePreemptionThreshold(0.4)
            // Set default scheduling policy to DRF
            .drfDefaultQueueSchedulingPolicy()
            // Give user1 a limit of 10 jobs
            .userSettings(new UserSettings.Builder("user1")
              .maxRunningApps(10)
              .build())
            .writeToFile(ALLOC_FILE);

    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
    AllocationConfiguration queueConf = confHolder.allocConf;

    assertEquals(6, queueConf.getConfiguredQueues().get(FSQueueType.LEAF).size());
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root." + YarnConfiguration.DEFAULT_QUEUE_NAME));

    assertEquals(Resources.createResource(2048, 10),
        queueConf.getMaxResources("root.queueA").getResource());
    assertEquals(Resources.createResource(5120, 110),
        queueConf.getMaxResources("root.queueB").getResource());
    assertEquals(Resources.createResource(4096, 100),
        queueConf.getMaxResources("root.queueC").getResource());
    assertEquals(Resources.createResource(4096, 100),
        queueConf.getMaxResources("root.queueD").getResource());
    assertEquals(Resources.createResource(4096, 100),
        queueConf.getMaxResources("root.queueE").getResource());
    assertEquals(Resources.createResource(4096, 100),
        queueConf.getMaxResources("root.queueF").getResource());
    assertEquals(Resources.createResource(4096, 100),
        queueConf.getMaxResources("root.queueG").getResource());
    assertEquals(Resources.createResource(4096, 100),
        queueConf.getMaxResources("root.queueG.queueH").getResource());

    assertEquals(Resources.createResource(1024, 0),
        queueConf.getMinResources("root.queueA"));
    assertEquals(Resources.createResource(2048, 0),
        queueConf.getMinResources("root.queueB"));
    assertEquals(Resources.createResource(5120, 0),
        queueConf.getMinResources("root.queueC"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueD"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueE"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueF"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueG"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueG.queueH"));

    assertNull("Max child resources unexpectedly set for queue root.queueA",
        queueConf.getMaxChildResources("root.queueA"));
    assertNull("Max child resources unexpectedly set for queue root.queueB",
        queueConf.getMaxChildResources("root.queueB"));
    assertNull("Max child resources unexpectedly set for queue root.queueC",
        queueConf.getMaxChildResources("root.queueC"));
    assertNull("Max child resources unexpectedly set for queue root.queueD",
        queueConf.getMaxChildResources("root.queueD"));
    assertNull("Max child resources unexpectedly set for queue root.queueE",
        queueConf.getMaxChildResources("root.queueE"));
    assertEquals(Resources.createResource(2048, 64),
        queueConf.getMaxChildResources("root.queueF").getResource());
    assertEquals(Resources.createResource(2048, 64),
        queueConf.getMaxChildResources("root.queueG").getResource());
    assertNull("Max child resources unexpectedly set for "
        + "queue root.queueG.queueH",
        queueConf.getMaxChildResources("root.queueG.queueH"));

    assertEquals(15, queueConf.getQueueMaxApps("root."
        + YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueA"));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueB"));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueC"));
    assertEquals(3, queueConf.getQueueMaxApps("root.queueD"));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueE"));
    assertEquals(10, queueConf.getUserMaxApps("user1"));
    assertEquals(5, queueConf.getUserMaxApps("user2"));

    assertEquals(.5f, queueConf.getQueueMaxAMShare("root." + YarnConfiguration.DEFAULT_QUEUE_NAME), 0.01);
    assertEquals(.5f, queueConf.getQueueMaxAMShare("root.queueA"), 0.01);
    assertEquals(.5f, queueConf.getQueueMaxAMShare("root.queueB"), 0.01);
    assertEquals(.5f, queueConf.getQueueMaxAMShare("root.queueC"), 0.01);
    assertEquals(.4f, queueConf.getQueueMaxAMShare("root.queueD"), 0.01);
    assertEquals(.5f, queueConf.getQueueMaxAMShare("root.queueE"), 0.01);

    Resource expectedResourceWithCustomType = Resources.createResource(512, 16);
    expectedResourceWithCustomType.setResourceValue(A_CUSTOM_RESOURCE, 10);

    assertEquals(Resources.unbounded(),
        queueConf.getQueueMaxContainerAllocation(
            "root." + YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(Resources.unbounded(),
        queueConf.getQueueMaxContainerAllocation("root.queueA"));
    assertEquals(Resources.unbounded(),
        queueConf.getQueueMaxContainerAllocation("root.queueB"));
    assertEquals(Resources.unbounded(),
        queueConf.getQueueMaxContainerAllocation("root.queueC"));
    assertEquals(Resources.unbounded(),
        queueConf.getQueueMaxContainerAllocation("root.queueD"));
    assertEquals(Resources.unbounded(),
        queueConf.getQueueMaxContainerAllocation("root.queueE"));
    assertEquals(Resources.unbounded(),
        queueConf.getQueueMaxContainerAllocation("root.queueF"));
    assertEquals(expectedResourceWithCustomType,
        queueConf.getQueueMaxContainerAllocation("root.queueG"));
    assertEquals(Resources.createResource(1024, 8),
        queueConf.getQueueMaxContainerAllocation("root.queueG.queueH"));

    assertEquals(120000, queueConf.getMinSharePreemptionTimeout("root"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root." +
        YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueA"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueB"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueC"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueD"));
    assertEquals(60000, queueConf.getMinSharePreemptionTimeout("root.queueE"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueF"));
    assertEquals(50000, queueConf.getMinSharePreemptionTimeout("root.queueG"));
    assertEquals(40000, queueConf.getMinSharePreemptionTimeout("root.queueG.queueH"));

    assertEquals(300000, queueConf.getFairSharePreemptionTimeout("root"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root." +
        YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueA"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueB"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueC"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueD"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueE"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueF"));
    assertEquals(120000, queueConf.getFairSharePreemptionTimeout("root.queueG"));
    assertEquals(180000, queueConf.getFairSharePreemptionTimeout("root.queueG.queueH"));

    assertEquals(.4f, queueConf.getFairSharePreemptionThreshold("root"), 0.01);
    assertEquals(-1, queueConf.getFairSharePreemptionThreshold("root." +
        YarnConfiguration.DEFAULT_QUEUE_NAME), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueA"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueB"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueC"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueD"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueE"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueF"), 0.01);
    assertEquals(.6f,
        queueConf.getFairSharePreemptionThreshold("root.queueG"), 0.01);
    assertEquals(.7f,
        queueConf.getFairSharePreemptionThreshold("root.queueG.queueH"), 0.01);

    assertTrue(queueConf.getConfiguredQueues()
        .get(FSQueueType.PARENT)
        .contains("root.queueF"));
    assertTrue(queueConf.getConfiguredQueues().get(FSQueueType.PARENT)
        .contains("root.queueG"));
    assertTrue(queueConf.getConfiguredQueues().get(FSQueueType.LEAF)
        .contains("root.queueG.queueH"));

    // Verify existing queues have default scheduling policy
    assertEquals(DominantResourceFairnessPolicy.NAME,
        queueConf.getSchedulingPolicy("root").getName());
    assertEquals(DominantResourceFairnessPolicy.NAME,
        queueConf.getSchedulingPolicy("root.queueA").getName());
    // Verify default is overriden if specified explicitly
    assertEquals(FairSharePolicy.NAME,
        queueConf.getSchedulingPolicy("root.queueB").getName());
    // Verify new queue gets default scheduling policy
    assertEquals(DominantResourceFairnessPolicy.NAME,
        queueConf.getSchedulingPolicy("root.newqueue").getName());
  }

  @Test
  public void testBackwardsCompatibleAllocationFileParsing() throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);
    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);

    AllocationFileWriter.create()
        .useLegacyTagNameForQueues()
        // Give queue A a minimum of 1024 M
        .addQueue(new AllocationFileQueue.Builder("queueA")
            .minResources("1024mb,0vcores")
            .build())
        // Give queue B a minimum of 2048 M
        .addQueue(new AllocationFileQueue.Builder("queueB")
            .minResources("2048mb,0vcores")
            .aclAdministerApps("alice,bob admins")
            .build())
        // Give queue C no minimum
        .addQueue(new AllocationFileQueue.Builder("queueC")
            .aclAdministerApps("alice,bob admins")
            .build())
        // Give queue D a limit of 3 running apps
        .addQueue(new AllocationFileQueue.Builder("queueD")
            .maxRunningApps(3)
            .build())
        // Give queue E a preemption timeout of one minute and 0.3f threshold
        .addQueue(new AllocationFileQueue.Builder("queueE")
            .minSharePreemptionTimeout(60)
            .fairSharePreemptionThreshold(0.3)
            .build())
        // Set default limit of apps per queue to 15
        .queueMaxAppsDefault(15)
        // Set default limit of apps per user to 5
        .userMaxAppsDefault(5)
        // Set default limit of max resource per queue to 4G and 100 cores
        .queueMaxResourcesDefault("4096mb,100vcores")
        // Set default limit of AMResourceShare to 0.5f
        .queueMaxAMShareDefault(0.5)
        // Set default min share preemption timeout to 2 minutes
        .defaultMinSharePreemptionTimeout(120)
        // Set default fair share preemption timeout to 5 minutes
        .defaultFairSharePreemptionTimeout(300)
        // Set default fair share preemption threshold to 0.6
        .defaultFairSharePreemptionThreshold(0.6)
        // Set default scheduling policy to DRF
        .drfDefaultQueueSchedulingPolicy()
        // Give user1 a limit of 10 jobs
        .userSettings(new UserSettings.Builder("user1")
            .maxRunningApps(10)
            .build())
        .writeToFile(ALLOC_FILE);

    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
    AllocationConfiguration queueConf = confHolder.allocConf;

    assertEquals(5, queueConf.getConfiguredQueues().get(FSQueueType.LEAF).size());
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root." + YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root." + YarnConfiguration.DEFAULT_QUEUE_NAME));

    assertEquals(Resources.createResource(1024, 0),
        queueConf.getMinResources("root.queueA"));
    assertEquals(Resources.createResource(2048, 0),
        queueConf.getMinResources("root.queueB"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueC"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueD"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueE"));

    assertEquals(15, queueConf.getQueueMaxApps("root." + YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueA"));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueB"));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueC"));
    assertEquals(3, queueConf.getQueueMaxApps("root.queueD"));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueE"));
    assertEquals(10, queueConf.getUserMaxApps("user1"));
    assertEquals(5, queueConf.getUserMaxApps("user2"));

    assertEquals(120000, queueConf.getMinSharePreemptionTimeout("root"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root." +
        YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueA"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueB"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueC"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueD"));
    assertEquals(60000, queueConf.getMinSharePreemptionTimeout("root.queueE"));

    assertEquals(300000, queueConf.getFairSharePreemptionTimeout("root"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root." +
        YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueA"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueB"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueC"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueD"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueE"));

    assertEquals(.6f, queueConf.getFairSharePreemptionThreshold("root"), 0.01);
    assertEquals(-1, queueConf.getFairSharePreemptionThreshold("root."
        + YarnConfiguration.DEFAULT_QUEUE_NAME), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueA"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueB"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueC"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueD"), 0.01);
    assertEquals(.3f,
        queueConf.getFairSharePreemptionThreshold("root.queueE"), 0.01);
  }

  @Test
  public void testSimplePlacementPolicyFromConf() throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);
    conf.setBoolean(FairSchedulerConfiguration.ALLOW_UNDECLARED_POOLS, false);
    conf.setBoolean(FairSchedulerConfiguration.USER_AS_DEFAULT_QUEUE, false);

    AllocationFileWriter.create().writeToFile(ALLOC_FILE);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();

    List<PlacementRule> rules = scheduler.getRMContext()
        .getQueuePlacementManager().getPlacementRules();
    assertEquals(2, rules.size());
    assertEquals(SpecifiedPlacementRule.class, rules.get(0).getClass());
    assertFalse("Create flag was not set to false",
        ((FSPlacementRule)rules.get(0)).getCreateFlag());
    assertEquals(DefaultPlacementRule.class, rules.get(1).getClass());
  }

  /**
   * Verify that you can't place queues at the same level as the root queue in
   * the allocations file.
   */
  @Test (expected = AllocationConfigurationException.class)
  public void testQueueAlongsideRoot() throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileWriter.create()
        .addQueue(new AllocationFileQueue.Builder("root").build())
        .addQueue(new AllocationFileQueue.Builder("other").build())
        .writeToFile(ALLOC_FILE);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
  }

  /**
   * Verify that you can't include periods as the queue name in the allocations
   * file.
   */
  @Test (expected = AllocationConfigurationException.class)
  public void testQueueNameContainingPeriods() throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileWriter.create()
        .addQueue(new AllocationFileQueue.Builder("parent1.child").build())
        .writeToFile(ALLOC_FILE);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
  }

  /**
   * Verify that you can't have the queue name with whitespace only in the
   * allocations file.
   */
  @Test (expected = AllocationConfigurationException.class)
  public void testQueueNameContainingOnlyWhitespace() throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileWriter.create()
        .addQueue(new AllocationFileQueue.Builder("      ").build())
        .writeToFile(ALLOC_FILE);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
  }

  @Test
  public void testParentTagWithReservation() throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileWriter.create()
        .addQueue(new AllocationFileQueue.Builder("parent")
            .parent(true)
            .reservation()
            .build())
        .writeToFile(ALLOC_FILE);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    try {
      allocLoader.reloadAllocations();
    } catch (AllocationConfigurationException ex) {
      assertEquals(ex.getMessage(), "The configuration settings for root.parent"
          + " are invalid. A queue element that contains child queue elements"
          + " or that has the type='parent' attribute cannot also include a"
          + " reservation element.");
    }
  }

  @Test
  public void testParentWithReservation() throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileWriter.create()
        .addQueue(new AllocationFileQueue.Builder("parent")
            .parent(true)
            .subQueue(new AllocationFileQueue.Builder("child").build())
            .reservation()
            .build())
        .writeToFile(ALLOC_FILE);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    try {
      allocLoader.reloadAllocations();
    } catch (AllocationConfigurationException ex) {
      assertEquals(ex.getMessage(), "The configuration settings for root.parent"
          + " are invalid. A queue element that contains child queue elements"
          + " or that has the type='parent' attribute cannot also include a"
          + " reservation element.");
    }
  }

  /**
   * Verify that a parent queue (type = parent) cannot have a maxAMShare element
   * as dynamic queues won't be able to inherit this setting.
   */
  @Test
  public void testParentTagWithMaxAMShare() throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileWriter.create()
        .addQueue(new AllocationFileQueue.Builder("parent")
            .parent(true)
            .maxAMShare(0.75)
            .build())
        .writeToFile(ALLOC_FILE);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    try {
      allocLoader.reloadAllocations();
      fail("Expect allocation parsing to fail as maxAMShare cannot be set for"
          + " a parent queue.");
    } catch (AllocationConfigurationException ex) {
      assertEquals(ex.getMessage(), "The configuration settings for root.parent"
          + " are invalid. A queue element that contains child queue elements"
          + " or that has the type='parent' attribute cannot also include a"
          + " maxAMShare element.");
    }
  }

  /**
   * Verify that a parent queue that is not explicitly tagged with "type"
   * as "parent" but has a child queue (implicit parent) cannot have a
   * maxAMShare element.
   */
  @Test
  public void testParentWithMaxAMShare() throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileWriter.create()
        .addQueue(new AllocationFileQueue.Builder("parent")
            .parent(false)
            .maxAMShare(0.76)
            .subQueue(new AllocationFileQueue.Builder("child").build())
            .build())
        .writeToFile(ALLOC_FILE);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    try {
      allocLoader.reloadAllocations();
      fail("Expect allocation parsing to fail as maxAMShare cannot be set for"
          + " a parent queue.");
    } catch (AllocationConfigurationException ex) {
      assertEquals(ex.getMessage(), "The configuration settings for root.parent"
          + " are invalid. A queue element that contains child queue elements"
          + " or that has the type='parent' attribute cannot also include a"
          + " maxAMShare element.");
    }
  }

  @Test
  public void testParentTagWithChild() throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileWriter.create()
        .addQueue(new AllocationFileQueue.Builder("parent")
            .parent(true)
            .subQueue(new AllocationFileQueue.Builder("child").build())
            .build())
        .writeToFile(ALLOC_FILE);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
    AllocationConfiguration queueConf = confHolder.allocConf;
    // Check whether queue 'parent' and 'child' are loaded successfully
    assertTrue(queueConf.getConfiguredQueues().get(FSQueueType.PARENT)
        .contains("root.parent"));
    assertTrue(queueConf.getConfiguredQueues().get(FSQueueType.LEAF)
        .contains("root.parent.child"));
  }

  /**
   * Verify that you can't have the queue name with just a non breaking
   * whitespace in the allocations file.
   */
  @Test (expected = AllocationConfigurationException.class)
  public void testQueueNameContainingNBWhitespace() throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileWriter.create()
        .addQueue(new AllocationFileQueue.Builder("\u00a0").build())
        .writeToFile(ALLOC_FILE);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
  }

  /**
   * Verify that defaultQueueSchedulingMode can't accept FIFO as a value.
   */
  @Test (expected = AllocationConfigurationException.class)
  public void testDefaultQueueSchedulingModeIsFIFO() throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileWriter.create()
        .fifoDefaultQueueSchedulingPolicy()
        .writeToFile(ALLOC_FILE);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
  }

  @Test
  public void testReservableQueue() throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileWriter.create()
        .addQueue(new AllocationFileQueue.Builder("reservable")
            .reservation()
            .build())
        .addQueue(new AllocationFileQueue.Builder("other").build())
        .reservationAgent("DummyAgentName")
        .reservationPolicy("AnyAdmissionPolicy")
        .writeToFile(ALLOC_FILE);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();

    AllocationConfiguration allocConf = confHolder.allocConf;
    String reservableQueueName = "root.reservable";
    String nonreservableQueueName = "root.other";
    assertFalse(allocConf.isReservable(nonreservableQueueName));
    assertTrue(allocConf.isReservable(reservableQueueName));
    Map<FSQueueType, Set<String>> configuredQueues =
        allocConf.getConfiguredQueues();
    assertTrue("reservable queue is expected be to a parent queue",
        configuredQueues.get(FSQueueType.PARENT).contains(reservableQueueName));
    assertFalse("reservable queue should not be a leaf queue",
        configuredQueues.get(FSQueueType.LEAF)
          .contains(reservableQueueName));

    assertTrue(allocConf.getMoveOnExpiry(reservableQueueName));
    assertEquals(ReservationSchedulerConfiguration.DEFAULT_RESERVATION_WINDOW,
        allocConf.getReservationWindow(reservableQueueName));
    assertEquals(100,
        allocConf.getInstantaneousMaxCapacity(reservableQueueName), 0.0001);
    assertEquals("DummyAgentName",
        allocConf.getReservationAgent(reservableQueueName));
    assertEquals(100, allocConf.getAverageCapacity(reservableQueueName), 0.001);
    assertFalse(allocConf.getShowReservationAsQueues(reservableQueueName));
    assertEquals("AnyAdmissionPolicy",
        allocConf.getReservationAdmissionPolicy(reservableQueueName));
    assertEquals(ReservationSchedulerConfiguration
        .DEFAULT_RESERVATION_PLANNER_NAME,
        allocConf.getReplanner(reservableQueueName));
    assertEquals(ReservationSchedulerConfiguration
        .DEFAULT_RESERVATION_ENFORCEMENT_WINDOW,
        allocConf.getEnforcementWindow(reservableQueueName));
  }

  /**
   * Verify that you can't have dynamic user queue and reservable queue on
   * the same queue.
   */
  @Test (expected = AllocationConfigurationException.class)
  public void testReservableCannotBeCombinedWithDynamicUserQueue()
      throws Exception {
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileWriter.create()
        .addQueue(new AllocationFileQueue.Builder("notboth")
            .parent(true)
            .reservation()
            .build())
        .writeToFile(ALLOC_FILE);

    AllocationFileLoaderService allocLoader =
        new AllocationFileLoaderService(scheduler);
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
  }

  private class ReloadListener implements AllocationFileLoaderService.Listener {
    private AllocationConfiguration allocConf;

    @Override
    public void onReload(AllocationConfiguration info) {
      allocConf = info;
    }

    @Override
    public void onCheck() {
    }
  }
}
