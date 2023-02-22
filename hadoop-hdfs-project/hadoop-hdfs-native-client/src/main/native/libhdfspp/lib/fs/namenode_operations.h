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
#ifndef LIBHDFSPP_LIB_FS_NAMENODEOPERATIONS_H_
#define LIBHDFSPP_LIB_FS_NAMENODEOPERATIONS_H_

#include "rpc/rpc_engine.h"
#include "hdfspp/statinfo.h"
#include "hdfspp/fsinfo.h"
#include "hdfspp/content_summary.h"
#include "common/namenode_info.h"
#include "ClientNamenodeProtocol.pb.h"
#include "ClientNamenodeProtocol.hrpc.inl"


namespace hdfs {

/**
* NameNodeConnection: abstracts the details of communicating with a NameNode
* and the implementation of the communications protocol.
*
* Will eventually handle retry and failover.
*
* Threading model: thread-safe; all operations can be called concurrently
* Lifetime: owned by a FileSystemImpl
*/

class NameNodeOperations {
public:
  MEMCHECKED_CLASS(NameNodeOperations)
  NameNodeOperations(std::shared_ptr<IoService> io_service, const Options &options,
            const std::string &client_name, const std::string &user_name,
            const char *protocol_name, int protocol_version) :
  io_service_(io_service),
  engine_(std::make_shared<RpcEngine>(io_service, options, client_name, user_name, protocol_name, protocol_version)),
  namenode_(engine_), options_(options) {}


  void Connect(const std::string &cluster_name,
               const std::vector<ResolvedNamenodeInfo> &servers,
               std::function<void(const Status &)> &&handler);

  bool CancelPendingConnect();

  void GetBlockLocations(const std::string & path, uint64_t offset, uint64_t length,
    std::function<void(const Status &, std::shared_ptr<const struct FileInfo>)> handler);

  void GetPreferredBlockSize(const std::string & path,
    std::function<void(const Status &, const uint64_t)> handler);

  void SetReplication(const std::string & path, int16_t replication,
    std::function<void(const Status &)> handler);

  void SetTimes(const std::string & path, uint64_t mtime, uint64_t atime,
    std::function<void(const Status &)> handler);

  void GetFileInfo(const std::string & path,
    std::function<void(const Status &, const StatInfo &)> handler);

  void GetContentSummary(const std::string & path,
    std::function<void(const Status &, const ContentSummary &)> handler);

  void GetFsStats(std::function<void(const Status &, const FsInfo &)> handler);

  // start_after="" for initial call
  void GetListing(const std::string & path,
        std::function<void(const Status &, const std::vector<StatInfo> &, bool)> handler,
        const std::string & start_after = "");

  void Mkdirs(const std::string & path, uint16_t permissions, bool createparent,
    std::function<void(const Status &)> handler);

  void Delete(const std::string & path, bool recursive,
      std::function<void(const Status &)> handler);

  void Rename(const std::string & oldPath, const std::string & newPath,
      std::function<void(const Status &)> handler);

  void SetPermission(const std::string & path, uint16_t permissions,
      std::function<void(const Status &)> handler);

  void SetOwner(const std::string & path, const std::string & username,
      const std::string & groupname, std::function<void(const Status &)> handler);

  void CreateSnapshot(const std::string & path, const std::string & name,
      std::function<void(const Status &)> handler);

  void DeleteSnapshot(const std::string & path, const std::string & name,
      std::function<void(const Status &)> handler);

  void RenameSnapshot(const std::string & path, const std::string & old_name, const std::string & new_name,
      std::function<void(const Status &)> handler);

  void AllowSnapshot(const std::string & path,
      std::function<void(const Status &)> handler);

  void DisallowSnapshot(const std::string & path,
      std::function<void(const Status &)> handler);

  void SetFsEventCallback(fs_event_callback callback);

private:
  static void HdfsFileStatusProtoToStatInfo(hdfs::StatInfo & si, const ::hadoop::hdfs::HdfsFileStatusProto & fs);
  static void ContentSummaryProtoToContentSummary(hdfs::ContentSummary & content_summary, const ::hadoop::hdfs::ContentSummaryProto & csp);
  static void DirectoryListingProtoToStatInfo(std::shared_ptr<std::vector<StatInfo>> stat_infos, const ::hadoop::hdfs::DirectoryListingProto & dl);
  static void GetFsStatsResponseProtoToFsInfo(hdfs::FsInfo & fs_info, const std::shared_ptr<::hadoop::hdfs::GetFsStatsResponseProto> & fs);

  std::shared_ptr<IoService> io_service_;

  // This is the only permanent owner of the RpcEngine, however the RPC layer
  // needs to reference count it prevent races during FileSystem destruction.
  // In order to do this they hold weak_ptrs and promote them to shared_ptr
  // when calling non-blocking RpcEngine methods e.g. get_client_id().
  std::shared_ptr<RpcEngine> engine_;

  // Automatically generated methods for RPC calls.  See protoc_gen_hrpc.cc
  ClientNamenodeProtocol namenode_;
  const Options options_;
};
}

#endif
