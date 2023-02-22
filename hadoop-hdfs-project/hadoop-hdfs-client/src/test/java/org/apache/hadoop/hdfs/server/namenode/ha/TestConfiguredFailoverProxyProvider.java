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
package org.apache.hadoop.hdfs.server.namenode.ha;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.client.HdfsClientConfigKeys;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.net.MockDomainNameResolver;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.util.Shell;
import org.apache.hadoop.util.Time;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.event.Level;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test {@link ConfiguredFailoverProxyProvider}.
 * This manages failover logic for a given set of nameservices/namenodes
 * (aka proxies).
 */
public class TestConfiguredFailoverProxyProvider {
  private Configuration conf;
  private int rpcPort = 8020;
  private URI ns1Uri;
  private URI ns2Uri;
  private URI ns3Uri;
  private String ns1;
  private String ns1nn1Hostname = "machine1.foo.bar";
  private InetSocketAddress ns1nn1 =
      new InetSocketAddress(ns1nn1Hostname, rpcPort);
  private String ns1nn2Hostname = "machine2.foo.bar";
  private InetSocketAddress ns1nn2 =
      new InetSocketAddress(ns1nn2Hostname, rpcPort);
  private String ns2;
  private String ns2nn1Hostname = "router1.foo.bar";
  private InetSocketAddress ns2nn1 =
      new InetSocketAddress(ns2nn1Hostname, rpcPort);
  private String ns2nn2Hostname = "router2.foo.bar";
  private InetSocketAddress ns2nn2 =
      new InetSocketAddress(ns2nn2Hostname, rpcPort);
  private String ns2nn3Hostname = "router3.foo.bar";
  private InetSocketAddress ns2nn3 =
      new InetSocketAddress(ns2nn3Hostname, rpcPort);
  private String ns3;
  private static final int NUM_ITERATIONS = 50;

  @Rule
  public final ExpectedException exception = ExpectedException.none();

  @BeforeClass
  public static void setupClass() throws Exception {
    GenericTestUtils.setLogLevel(RequestHedgingProxyProvider.LOG, Level.TRACE);
  }

  @Before
  public void setup() throws URISyntaxException {
    ns1 = "mycluster-1-" + Time.monotonicNow();
    ns1Uri = new URI("hdfs://" + ns1);
    conf = new Configuration();
    conf.set(
        HdfsClientConfigKeys.DFS_HA_NAMENODES_KEY_PREFIX + "." + ns1,
        "nn1,nn2,nn3");
    conf.set(
        HdfsClientConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY + "." + ns1 + ".nn1",
        ns1nn1Hostname + ":" + rpcPort);
    conf.set(
        HdfsClientConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY + "." + ns1 + ".nn2",
        ns1nn2Hostname + ":" + rpcPort);
    conf.set(
        HdfsClientConfigKeys.Failover.PROXY_PROVIDER_KEY_PREFIX + "." + ns1,
        ConfiguredFailoverProxyProvider.class.getName());
    conf.setBoolean(
        HdfsClientConfigKeys.Failover.RANDOM_ORDER + "." + ns1,
        false);

    ns2 = "myroutercluster-2-" + Time.monotonicNow();
    ns2Uri = new URI("hdfs://" + ns2);
    conf.set(
        HdfsClientConfigKeys.DFS_HA_NAMENODES_KEY_PREFIX + "." + ns2,
        "nn1,nn2,nn3");
    conf.set(
        HdfsClientConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY + "." + ns2 + ".nn1",
        ns2nn1Hostname + ":" + rpcPort);
    conf.set(
        HdfsClientConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY + "." + ns2 + ".nn2",
        ns2nn2Hostname + ":" + rpcPort);
    conf.set(
        HdfsClientConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY + "." + ns2 + ".nn3",
        ns2nn3Hostname + ":" + rpcPort);
    conf.set(
        HdfsClientConfigKeys.Failover.PROXY_PROVIDER_KEY_PREFIX + "." + ns2,
        ConfiguredFailoverProxyProvider.class.getName());
    conf.setBoolean(
        HdfsClientConfigKeys.Failover.RANDOM_ORDER + "." + ns2,
        true);

    ns3 = "mycluster-3-" + Time.monotonicNow();
    ns3Uri = new URI("hdfs://" + ns3);

    conf.set(HdfsClientConfigKeys.DFS_NAMESERVICES,
        String.join(",", ns1, ns2, ns3));
    conf.set("fs.defaultFS", "hdfs://" + ns1);
  }

  /**
   * Add more DNS related settings to the passed in configuration.
   * @param config Configuration file to add settings to.
   */
  private void addDNSSettings(Configuration config,
                              boolean hostResolvable, boolean useFQDN) {
    config.set(
        HdfsClientConfigKeys.DFS_HA_NAMENODES_KEY_PREFIX + "." + ns3, "nn");
    String domain = hostResolvable
        ? MockDomainNameResolver.DOMAIN
        : MockDomainNameResolver.UNKNOW_DOMAIN;
    config.set(
        HdfsClientConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY + "." + ns3 + ".nn",
        domain + ":" + rpcPort);
    config.set(
        HdfsClientConfigKeys.Failover.PROXY_PROVIDER_KEY_PREFIX + "." + ns3,
        ConfiguredFailoverProxyProvider.class.getName());
    config.setBoolean(
        HdfsClientConfigKeys.Failover.RESOLVE_ADDRESS_NEEDED_KEY + "." + ns3,
        true);
    config.set(
        HdfsClientConfigKeys.Failover.RESOLVE_SERVICE_KEY + "." + ns3,
        MockDomainNameResolver.class.getName());
    config.setBoolean(
        HdfsClientConfigKeys.Failover.RANDOM_ORDER + "." + ns3,
        true);
    config.setBoolean(
        HdfsClientConfigKeys.Failover.RESOLVE_ADDRESS_TO_FQDN + "." + ns3,
        useFQDN
    );
  }

  /**
   * Tests getProxy with random.order configuration set to false.
   * This expects the proxy order to be consistent every time a new
   * ConfiguredFailoverProxyProvider is created.
   */
  @Test
  public void testNonRandomGetProxy() throws Exception {
    final AtomicInteger nn1Count = new AtomicInteger(0);
    final AtomicInteger nn2Count = new AtomicInteger(0);

    Map<InetSocketAddress, ClientProtocol> proxyMap = new HashMap<>();

    final ClientProtocol nn1Mock = mock(ClientProtocol.class);
    when(nn1Mock.getStats()).thenAnswer(createAnswer(nn1Count, 1));
    proxyMap.put(ns1nn1, nn1Mock);

    final ClientProtocol nn2Mock = mock(ClientProtocol.class);
    when(nn2Mock.getStats()).thenAnswer(createAnswer(nn2Count, 2));
    proxyMap.put(ns1nn2, nn2Mock);

    ConfiguredFailoverProxyProvider<ClientProtocol> provider1 =
        new ConfiguredFailoverProxyProvider<>(conf, ns1Uri,
            ClientProtocol.class, createFactory(proxyMap));
    ClientProtocol proxy1 = provider1.getProxy().proxy;
    proxy1.getStats();
    assertEquals(1, nn1Count.get());
    assertEquals(0, nn2Count.get());
    proxy1.getStats();
    assertEquals(2, nn1Count.get());
    assertEquals(0, nn2Count.get());
    nn1Count.set(0);
    nn2Count.set(0);

    for (int i = 0; i < NUM_ITERATIONS; i++) {
      ConfiguredFailoverProxyProvider<ClientProtocol> provider2 =
          new ConfiguredFailoverProxyProvider<>(conf, ns1Uri,
              ClientProtocol.class, createFactory(proxyMap));
      ClientProtocol proxy2 = provider2.getProxy().proxy;
      proxy2.getStats();
    }
    assertEquals(NUM_ITERATIONS, nn1Count.get());
    assertEquals(0, nn2Count.get());
  }

  /**
   * Tests getProxy with random.order configuration set to true.
   * This expects the proxy order to be random every time a new
   * ConfiguredFailoverProxyProvider is created.
   */
  @Test
  public void testRandomGetProxy() throws Exception {
    final AtomicInteger nn1Count = new AtomicInteger(0);
    final AtomicInteger nn2Count = new AtomicInteger(0);
    final AtomicInteger nn3Count = new AtomicInteger(0);

    Map<InetSocketAddress, ClientProtocol> proxyMap = new HashMap<>();

    final ClientProtocol nn1Mock = mock(ClientProtocol.class);
    when(nn1Mock.getStats()).thenAnswer(createAnswer(nn1Count, 1));
    proxyMap.put(ns2nn1, nn1Mock);

    final ClientProtocol nn2Mock = mock(ClientProtocol.class);
    when(nn2Mock.getStats()).thenAnswer(createAnswer(nn2Count, 2));
    proxyMap.put(ns2nn2, nn2Mock);

    final ClientProtocol nn3Mock = mock(ClientProtocol.class);
    when(nn3Mock.getStats()).thenAnswer(createAnswer(nn3Count, 3));
    proxyMap.put(ns2nn3, nn3Mock);


    for (int i = 0; i < NUM_ITERATIONS; i++) {
      ConfiguredFailoverProxyProvider<ClientProtocol> provider =
          new ConfiguredFailoverProxyProvider<>(conf, ns2Uri,
              ClientProtocol.class, createFactory(proxyMap));
      ClientProtocol proxy = provider.getProxy().proxy;
      proxy.getStats();
    }

    assertTrue(nn1Count.get() < NUM_ITERATIONS && nn1Count.get() > 0);
    assertTrue(nn2Count.get() < NUM_ITERATIONS && nn2Count.get() > 0);
    assertTrue(nn3Count.get() < NUM_ITERATIONS && nn3Count.get() > 0);
    assertEquals(NUM_ITERATIONS,
        nn1Count.get() + nn2Count.get() + nn3Count.get());
  }

  private void testResolveDomainNameUsingDNS(boolean useFQDN) throws Exception {
    Configuration dnsConf = new Configuration(conf);
    addDNSSettings(dnsConf, true, useFQDN);

    // Mock ClientProtocol
    Map<InetSocketAddress, ClientProtocol> proxyMap = new HashMap<>();
    final AtomicInteger nn1Count = addClientMock(
        useFQDN ? MockDomainNameResolver.FQDN_1 : MockDomainNameResolver.ADDR_1,
        proxyMap);
    final AtomicInteger nn2Count = addClientMock(
        useFQDN ? MockDomainNameResolver.FQDN_2 : MockDomainNameResolver.ADDR_2,
        proxyMap);

    // Get a client multiple times
    final Map<String, AtomicInteger> proxyResults = new HashMap<>();
    for (int i = 0; i < NUM_ITERATIONS; i++) {
      @SuppressWarnings("resource")
      ConfiguredFailoverProxyProvider<ClientProtocol> provider =
          new ConfiguredFailoverProxyProvider<>(
              dnsConf, ns3Uri, ClientProtocol.class, createFactory(proxyMap));
      ClientProtocol proxy = provider.getProxy().proxy;
      String proxyAddress = provider.getProxy().proxyInfo;

      if (proxyResults.containsKey(proxyAddress)) {
        proxyResults.get(proxyAddress).incrementAndGet();
      } else {
        proxyResults.put(proxyAddress, new AtomicInteger(1));
      }
      proxy.getStats();
    }

    String resolvedHost1 = useFQDN ?
        MockDomainNameResolver.FQDN_1 : "/" + MockDomainNameResolver.ADDR_1;
    String resolvedHost2 = useFQDN ?
        MockDomainNameResolver.FQDN_2 : "/" + MockDomainNameResolver.ADDR_2;
    // Check we got the proper addresses
    assertEquals(2, proxyResults.size());
    if (Shell.isJavaVersionAtLeast(14) && useFQDN) {
      // JDK-8225499. The string format of unresolved address has been changed.
      assertTrue(
          "nn1 wasn't returned: " + proxyResults,
          proxyResults.containsKey(resolvedHost1 + "/<unresolved>:8020"));
      assertTrue(
          "nn2 wasn't returned: " + proxyResults,
          proxyResults.containsKey(resolvedHost2 + "/<unresolved>:8020"));
    } else {
      assertTrue(
          "nn1 wasn't returned: " + proxyResults,
          proxyResults.containsKey(resolvedHost1 + ":8020"));
      assertTrue(
          "nn2 wasn't returned: " + proxyResults,
          proxyResults.containsKey(resolvedHost2 + ":8020"));
    }

    // Check that the Namenodes were invoked
    assertEquals(NUM_ITERATIONS, nn1Count.get() + nn2Count.get());
    assertTrue("nn1 was selected too much:" + nn1Count.get(),
        nn1Count.get() < NUM_ITERATIONS);
    assertTrue("nn1 should have been selected: " + nn1Count.get(),
        nn1Count.get() > 0);
    assertTrue("nn2 was selected too much:" + nn2Count.get(),
        nn2Count.get() < NUM_ITERATIONS);
    assertTrue(
        "nn2 should have been selected: " + nn2Count.get(),
        nn2Count.get() > 0);
  }

  @Test
  public void testResolveDomainNameUsingDNS() throws Exception {
    // test resolving to IP
    testResolveDomainNameUsingDNS(false);
    // test resolving to FQDN
    testResolveDomainNameUsingDNS(true);
  }

  @Test
  public void testResolveDomainNameUsingDNSUnknownHost() throws Exception {
    Configuration dnsConf = new Configuration(conf);
    addDNSSettings(dnsConf, false, false);

    Map<InetSocketAddress, ClientProtocol> proxyMap = new HashMap<>();
    exception.expect(RuntimeException.class);
    ConfiguredFailoverProxyProvider<ClientProtocol> provider =
        new ConfiguredFailoverProxyProvider<>(
            dnsConf, ns3Uri, ClientProtocol.class, createFactory(proxyMap));

    assertNull("failover proxy cannot be created due to unknownhost",
        provider);
  }

  /**
   * Add a ClientProtocol mock for the proxy.
   * @param host host name for the destination.
   * @param proxyMap Map containing the client for each target address.
   * @return The counter for the number of calls to this target.
   * @throws Exception If the client cannot be created.
   */
  private AtomicInteger addClientMock(
      String host, Map<InetSocketAddress, ClientProtocol> proxyMap)
      throws Exception {

    final AtomicInteger counter = new AtomicInteger(0);
    InetSocketAddress inetSockerAddr =
        new InetSocketAddress(host, rpcPort);

    final ClientProtocol cpMock = mock(ClientProtocol.class);
    when(cpMock.getStats()).thenAnswer(createAnswer(counter, 1));
    proxyMap.put(inetSockerAddr, cpMock);

    return counter;
  }

  /**
   * createAnswer creates an Answer for using with the ClientProtocol mocks.
   * @param counter counter to increment
   * @param retVal return value from answer
   * @return
   */
  private Answer<long[]> createAnswer(final AtomicInteger counter,
      final long retVal) {
    return new Answer<long[]>() {
      @Override
      public long[] answer(InvocationOnMock invocation) throws Throwable {
        counter.incrementAndGet();
        return new long[]{retVal};
      }
    };
  }

  /**
   * createFactory returns a HAProxyFactory for tests.
   * This uses a map of name node address to ClientProtocol to route calls to
   * different ClientProtocol objects. The tests could create ClientProtocol
   * mocks and create name node mappings to use with
   * ConfiguredFailoverProxyProvider.
   */
  private HAProxyFactory<ClientProtocol> createFactory(
      final Map<InetSocketAddress, ClientProtocol> proxies) {
    final Map<InetSocketAddress, ClientProtocol> proxyMap = proxies;
    return new HAProxyFactory<ClientProtocol>() {
      @Override
      public ClientProtocol createProxy(Configuration cfg,
          InetSocketAddress nnAddr, Class<ClientProtocol> xface,
          UserGroupInformation ugi, boolean withRetries,
          AtomicBoolean fallbackToSimpleAuth) throws IOException {
        if (proxyMap.containsKey(nnAddr)) {
          return proxyMap.get(nnAddr);
        } else {
          throw new IOException("Name node address not found");
        }
      }

      @Override
      public ClientProtocol createProxy(Configuration cfg,
          InetSocketAddress nnAddr, Class<ClientProtocol> xface,
          UserGroupInformation ugi, boolean withRetries) throws IOException {
        if (proxyMap.containsKey(nnAddr)) {
          return proxyMap.get(nnAddr);
        } else {
          throw new IOException("Name node address not found");
        }
      }
    };
  }
}