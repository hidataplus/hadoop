<!---
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License. See accompanying LICENSE file.
-->

HDFS Short-Circuit Local Reads
==============================

<!-- MACRO{toc|fromDepth=0|toDepth=3} -->

Short-Circuit Local Reads
-------------------------

### Background

In `HDFS`, reads normally go through the `DataNode`. Thus, when the client asks the `DataNode` to read a file, the `DataNode` reads that file off of the disk and sends the data to the client over a TCP socket. So-called "short-circuit" reads bypass the `DataNode`, allowing the client to read the file directly. Obviously, this is only possible in cases where the client is co-located with the data. Short-circuit reads provide a substantial performance boost to many applications.

### Setup

To configure short-circuit local reads, you will need to enable `libhadoop.so`. See [Native Libraries](../hadoop-common/NativeLibraries.html) for details on enabling this library.

Short-circuit reads make use of a UNIX domain socket. This is a special path in the filesystem that allows the client and the `DataNode`s to communicate. You will need to set a path to this socket. The `DataNode` needs to be able to create this path. On the other hand, it should not be possible for any user except the HDFS user or root to create this path. For this reason, paths under `/var/run` or `/var/lib` are often used.

The client and the `DataNode` exchange information via a shared memory segment on `/dev/shm`.

Short-circuit local reads need to be configured on both the `DataNode` and the client.

### Example Configuration

Here is an example configuration.

```xml
<configuration>
  <property>
    <name>dfs.client.read.shortcircuit</name>
    <value>true</value>
  </property>
  <property>
    <name>dfs.domain.socket.path</name>
    <value>/var/lib/hadoop-hdfs/dn_socket</value>
  </property>
</configuration>
```

Legacy HDFS Short-Circuit Local Reads
-------------------------------------

Legacy implementation of short-circuit local reads on which the clients directly open the HDFS block files is still available for platforms other than the Linux. Setting the value of `dfs.client.use.legacy.blockreader.local` in addition to `dfs.client.read.shortcircuit` to true enables this feature.

You also need to set the value of `dfs.datanode.data.dir.perm` to `750` instead of the default `700` and chmod/chown the directory tree under `dfs.datanode.data.dir` as readable to the client and the `DataNode`. You must take caution because this means that the client can read all of the block files bypassing HDFS permission.

Because Legacy short-circuit local reads is insecure, access to this feature is limited to the users listed in the value of `dfs.block.local-path-access.user`.

```xml
<configuration>
  <property>
    <name>dfs.client.read.shortcircuit</name>
    <value>true</value>
  </property>
  <property>
    <name>dfs.client.use.legacy.blockreader.local</name>
    <value>true</value>
  </property>
  <property>
    <name>dfs.datanode.data.dir.perm</name>
    <value>750</value>
  </property>
  <property>
    <name>dfs.block.local-path-access.user</name>
    <value>foo,bar</value>
  </property>
</configuration>
```
