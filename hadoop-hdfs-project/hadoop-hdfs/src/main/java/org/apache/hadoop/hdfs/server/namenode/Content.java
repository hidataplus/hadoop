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
package org.apache.hadoop.hdfs.server.namenode;

/**
 * The content types such as file, directory and symlink to be computed.
 */
public enum Content {
  /** The number of files. */
  FILE,
  /** The number of directories. */
  DIRECTORY,
  /** The number of symlinks. */
  SYMLINK,

  /** The total of file length in bytes. */
  LENGTH,
  /** The total of disk space usage in bytes including replication. */
  DISKSPACE,

  /** The number of snapshots. */
  SNAPSHOT,
  /** The number of snapshottable directories. */
  SNAPSHOTTABLE_DIRECTORY;
}
