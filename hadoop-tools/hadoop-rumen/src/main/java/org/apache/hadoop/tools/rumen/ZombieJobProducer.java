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
package org.apache.hadoop.tools.rumen;

import java.io.IOException;
import java.io.InputStream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;

/**
 * Producing {@link JobStory}s from job trace.
 */
public class ZombieJobProducer implements JobStoryProducer {
  private final JobTraceReader reader;
  private final ZombieCluster cluster;

  private boolean hasRandomSeed = false;
  private long randomSeed = 0;
      
  private ZombieJobProducer(JobTraceReader reader, ZombieCluster cluster,
      boolean hasRandomSeed, long randomSeed) {
    this.reader = reader;
    this.cluster = cluster;
    this.hasRandomSeed = hasRandomSeed;
    this.randomSeed = (hasRandomSeed) ? randomSeed : System.nanoTime();
  }

  /**
   * Constructor
   * 
   * @param path
   *          Path to the JSON trace file, possibly compressed.
   * @param cluster
   *          The topology of the cluster that corresponds to the jobs in the
   *          trace. The argument can be null if we do not have knowledge of the
   *          cluster topology.
   * @param conf
   * @throws IOException
   */
  public ZombieJobProducer(Path path, ZombieCluster cluster, Configuration conf)
      throws IOException {
    this(new JobTraceReader(path, conf), cluster, false, -1);
  }

  
  /**
   * Constructor
   * 
   * @param path
   *          Path to the JSON trace file, possibly compressed.
   * @param cluster
   *          The topology of the cluster that corresponds to the jobs in the
   *          trace. The argument can be null if we do not have knowledge of the
   *          cluster topology.
   * @param conf
   * @param randomSeed
   *          use a deterministic seed.
   * @throws IOException
   */
  public ZombieJobProducer(Path path, ZombieCluster cluster,
      Configuration conf, long randomSeed) throws IOException {
    this(new JobTraceReader(path, conf), cluster, true, randomSeed);
  }
  
  /**
   * Constructor
   * 
   * @param input
   *          The input stream for the JSON trace.
   * @param cluster
   *          The topology of the cluster that corresponds to the jobs in the
   *          trace. The argument can be null if we do not have knowledge of the
   *          cluster topology.
   * @throws IOException
   */
  public ZombieJobProducer(InputStream input, ZombieCluster cluster)
      throws IOException {
    this(new JobTraceReader(input), cluster, false, -1);
  }

  /**
   * Constructor
   * 
   * @param input
   *          The input stream for the JSON trace.
   * @param cluster
   *          The topology of the cluster that corresponds to the jobs in the
   *          trace. The argument can be null if we do not have knowledge of the
   *          cluster topology.
   * @param randomSeed
   *          use a deterministic seed.
   * @throws IOException
   */
  public ZombieJobProducer(InputStream input, ZombieCluster cluster,
      long randomSeed) throws IOException {
    this(new JobTraceReader(input), cluster, true, randomSeed);
  }

  @Override
  public ZombieJob getNextJob() throws IOException {
    LoggedJob job = reader.getNext();
    if (job == null) {
      return null;
    } else if (hasRandomSeed) {
      long subRandomSeed = RandomSeedGenerator.getSeed(
            "forZombieJob" + job.getJobID(), randomSeed);
      return new ZombieJob(job, cluster, subRandomSeed);
    } else {
      return new ZombieJob(job, cluster);
    }
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }
}
