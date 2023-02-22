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

#include "datanodeconnection.h"
#include "common/util.h"

namespace hdfs {

DataNodeConnection::~DataNodeConnection(){}
DataNodeConnectionImpl::~DataNodeConnectionImpl(){}

DataNodeConnectionImpl::DataNodeConnectionImpl(std::shared_ptr<IoService> io_service,
                                               const ::hadoop::hdfs::DatanodeInfoProto &dn_proto,
                                               const hadoop::common::TokenProto *token,
                                               LibhdfsEvents *event_handlers) : event_handlers_(event_handlers)
{
  using namespace ::asio::ip;

  conn_.reset(new tcp::socket(io_service->GetRaw()));
  auto datanode_addr = dn_proto.id();
  endpoints_[0] = tcp::endpoint(address::from_string(datanode_addr.ipaddr()),
                                  datanode_addr.xferport());
  uuid_ = dn_proto.id().datanodeuuid();

  if (token) {
    token_.reset(new hadoop::common::TokenProto());
    token_->CheckTypeAndMergeFrom(*token);
  }
}


void DataNodeConnectionImpl::Connect(
             std::function<void(Status status, std::shared_ptr<DataNodeConnection> dn)> handler) {
  // Keep the DN from being freed until we're done
  mutex_guard state_lock(state_lock_);
  auto shared_this = shared_from_this();
  asio::async_connect(*conn_, endpoints_.begin(), endpoints_.end(),
          [shared_this, handler](const asio::error_code &ec, std::array<asio::ip::tcp::endpoint, 1>::iterator it) {
            (void)it;
            handler(ToStatus(ec), shared_this); });
}

void DataNodeConnectionImpl::Cancel() {
  std::string err;

  { // scope the lock for disconnect only, log has it's own lock
    mutex_guard state_lock(state_lock_);
    err = SafeDisconnect(conn_.get());
  }

  if(!err.empty()) {
    LOG_WARN(kBlockReader, << "Error disconnecting socket in DataNodeConnectionImpl::Cancel, " << err);
  }
}

void DataNodeConnectionImpl::async_read_some(const MutableBuffer &buf,
             std::function<void (const asio::error_code & error, std::size_t bytes_transferred) > handler)
{
  event_handlers_->call("DN_read_req", "", "", buf.end() - buf.begin());

  mutex_guard state_lock(state_lock_);
  conn_->async_read_some(buf, handler);
}

void DataNodeConnectionImpl::async_write_some(const ConstBuffer &buf,
             std::function<void (const asio::error_code & error, std::size_t bytes_transferred) > handler)
{
  event_handlers_->call("DN_write_req", "", "", buf.end() - buf.begin());

  mutex_guard state_lock(state_lock_);
  conn_->async_write_some(buf, handler);
}

}
