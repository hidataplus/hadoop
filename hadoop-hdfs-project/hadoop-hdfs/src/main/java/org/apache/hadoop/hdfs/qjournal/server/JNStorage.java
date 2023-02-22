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
package org.apache.hadoop.hdfs.qjournal.server;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.NodeType;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.StartupOption;
import org.apache.hadoop.hdfs.server.common.InconsistentFSStateException;
import org.apache.hadoop.hdfs.server.common.IncorrectVersionException;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.common.StorageErrorReporter;
import org.apache.hadoop.hdfs.server.namenode.FileJournalManager;
import org.apache.hadoop.hdfs.server.namenode.NNStorage;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;

import org.apache.hadoop.thirdparty.com.google.common.collect.ImmutableList;

/**
 * A {@link Storage} implementation for the {@link JournalNode}.
 * 
 * The JN has a storage directory for each namespace for which it stores
 * metadata. There is only a single directory per JN in the current design.
 */
class JNStorage extends Storage {

  private final FileJournalManager fjm;
  private final StorageDirectory sd;
  private StorageState state;

  private static final List<Pattern> PAXOS_DIR_PURGE_REGEXES =
      ImmutableList.of(Pattern.compile("(\\d+)"));

  private static final String STORAGE_EDITS_SYNC = "edits.sync";

  /**
   * @param conf Configuration object
   * @param logDir the path to the directory in which data will be stored
   * @param errorReporter a callback to report errors
   * @throws IOException 
   */
  protected JNStorage(Configuration conf, File logDir, StartupOption startOpt,
      StorageErrorReporter errorReporter) throws IOException {
    super(NodeType.JOURNAL_NODE);
    
    sd = new StorageDirectory(logDir, null, false, new FsPermission(conf.get(
        DFSConfigKeys.DFS_JOURNAL_EDITS_DIR_PERMISSION_KEY,
        DFSConfigKeys.DFS_JOURNAL_EDITS_DIR_PERMISSION_DEFAULT)));
    this.addStorageDir(sd);
    this.fjm = new FileJournalManager(conf, sd, errorReporter);

    analyzeAndRecoverStorage(startOpt);
  }
  
  FileJournalManager getJournalManager() {
    return fjm;
  }

  @Override
  public boolean isPreUpgradableLayout(StorageDirectory sd)
      throws IOException {
    return false;
  }

  /**
   * Find an edits file spanning the given transaction ID range.
   * If no such file exists, an exception is thrown.
   */
  File findFinalizedEditsFile(long startTxId, long endTxId)
      throws IOException {
    File ret = new File(sd.getCurrentDir(),
        NNStorage.getFinalizedEditsFileName(startTxId, endTxId));
    if (!ret.exists()) {
      throw new IOException(
          "No edits file for range " + startTxId + "-" + endTxId);
    }
    return ret;
  }

  /**
   * @return the path for an in-progress edits file starting at the given
   * transaction ID. This does not verify existence of the file. 
   */
  File getInProgressEditLog(long startTxId) {
    return new File(sd.getCurrentDir(),
        NNStorage.getInProgressEditsFileName(startTxId));
  }
  
  /**
   * @param segmentTxId the first txid of the segment
   * @param epoch the epoch number of the writer which is coordinating
   * recovery
   * @return the temporary path in which an edits log should be stored
   * while it is being downloaded from a remote JournalNode
   */
  File getSyncLogTemporaryFile(long segmentTxId, long epoch) {
    String name = NNStorage.getInProgressEditsFileName(segmentTxId) +
        ".epoch=" + epoch; 
    return new File(sd.getCurrentDir(), name);
  }

  File getCurrentDir() {
    return sd.getCurrentDir();
  }

  /**
   * Directory {@code edits.sync} temporarily holds the log segments
   * downloaded through {@link JournalNodeSyncer} before they are moved to
   * {@code current} directory.
   *
   * @return the directory path
   */
  File getEditsSyncDir() {
    return new File(sd.getRoot(), STORAGE_EDITS_SYNC);
  }

  File getTemporaryEditsFile(long startTxId, long endTxId) {
    return new File(getEditsSyncDir(), String.format("%s_%019d-%019d",
            NNStorage.NameNodeFile.EDITS.getName(), startTxId, endTxId));
  }

  File getFinalizedEditsFile(long startTxId, long endTxId) {
    return new File(sd.getCurrentDir(), String.format("%s_%019d-%019d",
            NNStorage.NameNodeFile.EDITS.getName(), startTxId, endTxId));
  }

  /**
   * @return the path for the file which contains persisted data for the
   * paxos-like recovery process for the given log segment.
   */
  File getPaxosFile(long segmentTxId) {
    return new File(getOrCreatePaxosDir(), String.valueOf(segmentTxId));
  }
  
  File getOrCreatePaxosDir() {
    File paxosDir = new File(sd.getCurrentDir(), "paxos");
    if(!paxosDir.exists()) {
      LOG.info("Creating paxos dir: {}", paxosDir.toPath());
      if(!paxosDir.mkdir()) {
        LOG.error("Could not create paxos dir: {}", paxosDir.toPath());
      }
    }
    return paxosDir;
  }
  
  File getRoot() {
    return sd.getRoot();
  }
  
  /**
   * Remove any log files and associated paxos files which are older than
   * the given txid.
   */
  void purgeDataOlderThan(long minTxIdToKeep) throws IOException {
    fjm.purgeLogsOlderThan(minTxIdToKeep);

    purgeMatching(getOrCreatePaxosDir(),
        PAXOS_DIR_PURGE_REGEXES, minTxIdToKeep);
  }
  
  /**
   * Purge files in the given directory which match any of the set of patterns.
   * The patterns must have a single numeric capture group which determines
   * the associated transaction ID of the file. Only those files for which
   * the transaction ID is less than the <code>minTxIdToKeep</code> parameter
   * are removed.
   */
  private static void purgeMatching(File dir, List<Pattern> patterns,
      long minTxIdToKeep) throws IOException {

    for (File f : FileUtil.listFiles(dir)) {
      if (!f.isFile()) continue;
      
      for (Pattern p : patterns) {
        Matcher matcher = p.matcher(f.getName());
        if (matcher.matches()) {
          // This parsing will always succeed since the group(1) is
          // /\d+/ in the regex itself.
          long txid = Long.parseLong(matcher.group(1));
          if (txid < minTxIdToKeep) {
            LOG.info("Purging no-longer needed file {}", txid);
            if (!f.delete()) {
              LOG.warn("Unable to delete no-longer-needed data {}", f);
            }
            break;
          }
        }
      }
    }
  }

  void format(NamespaceInfo nsInfo, boolean force) throws IOException {
    unlockAll();
    try {
      sd.analyzeStorage(StartupOption.FORMAT, this, !force);
    } finally {
      sd.unlock();
    }
    setStorageInfo(nsInfo);

    LOG.info("Formatting journal {} with nsid: {}", sd, getNamespaceID());
    // Unlock the directory before formatting, because we will
    // re-analyze it after format(). The analyzeStorage() call
    // below is reponsible for re-locking it. This is a no-op
    // if the storage is not currently locked.
    unlockAll();
    sd.clearDirectory();
    writeProperties(sd);
    getOrCreatePaxosDir();
    analyzeStorage();
  }
  
  void analyzeStorage() throws IOException {
    this.state = sd.analyzeStorage(StartupOption.REGULAR, this);
    refreshStorage();
  }

  void refreshStorage() throws IOException {
    if (state == StorageState.NORMAL) {
      readProperties(sd);
    }
  }

  @Override
  protected void setLayoutVersion(Properties props, StorageDirectory sd)
      throws IncorrectVersionException, InconsistentFSStateException {
    int lv = Integer.parseInt(getProperty(props, sd, "layoutVersion"));
    // For journal node, since it now does not decode but just scan through the
    // edits, it can handle edits with future version in most of the cases.
    // Thus currently we may skip the layoutVersion check here.
    layoutVersion = lv;
  }

  void analyzeAndRecoverStorage(StartupOption startOpt) throws IOException {
    this.state = sd.analyzeStorage(startOpt, this);
    final boolean needRecover = state != StorageState.NORMAL
        && state != StorageState.NON_EXISTENT
        && state != StorageState.NOT_FORMATTED;
    if (state == StorageState.NORMAL && startOpt != StartupOption.ROLLBACK) {
      readProperties(sd);
    } else if (needRecover) {
      sd.doRecover(state);
    }
  }

  void checkConsistentNamespace(NamespaceInfo nsInfo)
      throws IOException {
    if (nsInfo.getNamespaceID() != getNamespaceID()) {
      throw new IOException("Incompatible namespaceID for journal " +
          this.sd + ": NameNode has nsId " + nsInfo.getNamespaceID() +
          " but storage has nsId " + getNamespaceID());
    }
    
    if (!nsInfo.getClusterID().equals(getClusterID())) {
      throw new IOException("Incompatible clusterID for journal " +
          this.sd + ": NameNode has clusterId '" + nsInfo.getClusterID() +
          "' but storage has clusterId '" + getClusterID() + "'");
      
    }
  }

  public void close() throws IOException {
    LOG.info("Closing journal storage for {}", sd);
    unlockAll();
  }

  public boolean isFormatted() {
    return state == StorageState.NORMAL;
  }
}
