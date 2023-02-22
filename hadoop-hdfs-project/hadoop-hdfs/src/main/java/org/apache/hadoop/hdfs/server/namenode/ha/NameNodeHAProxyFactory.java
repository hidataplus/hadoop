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
import org.apache.hadoop.hdfs.NameNodeProxies;
import org.apache.hadoop.ipc.AlignmentContext;
import org.apache.hadoop.security.UserGroupInformation;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

public class NameNodeHAProxyFactory<T> implements HAProxyFactory<T> {

  private AlignmentContext alignmentContext;

  @Override
  public T createProxy(Configuration conf, InetSocketAddress nnAddr,
      Class<T> xface, UserGroupInformation ugi, boolean withRetries,
      AtomicBoolean fallbackToSimpleAuth) throws IOException {
    return NameNodeProxies.createNonHAProxy(conf, nnAddr, xface,
        ugi, withRetries, fallbackToSimpleAuth, alignmentContext).getProxy();
  }

  @Override
  public T createProxy(Configuration conf, InetSocketAddress nnAddr,
      Class<T> xface, UserGroupInformation ugi, boolean withRetries)
      throws IOException {
    return NameNodeProxies.createNonHAProxy(conf, nnAddr, xface,
      ugi, withRetries).getProxy();
  }

  public void setAlignmentContext(AlignmentContext alignmentContext) {
    this.alignmentContext = alignmentContext;
  }
}
