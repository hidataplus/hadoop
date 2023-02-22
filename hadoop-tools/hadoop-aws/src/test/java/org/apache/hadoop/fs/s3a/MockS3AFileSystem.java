/*
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

package org.apache.hadoop.fs.s3a;

import java.io.IOException;
import java.net.URI;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.services.s3.AmazonS3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.s3a.api.RequestFactory;
import org.apache.hadoop.fs.s3a.audit.AuditTestSupport;
import org.apache.hadoop.fs.s3a.auth.delegation.EncryptionSecrets;
import org.apache.hadoop.fs.s3a.commit.staging.StagingTestBase;
import org.apache.hadoop.fs.s3a.impl.RequestFactoryImpl;
import org.apache.hadoop.fs.s3a.statistics.CommitterStatistics;
import org.apache.hadoop.fs.s3a.statistics.impl.EmptyS3AStatisticsContext;
import org.apache.hadoop.fs.s3a.s3guard.BulkOperationState;
import org.apache.hadoop.fs.statistics.DurationTrackerFactory;
import org.apache.hadoop.util.Progressable;

import static org.apache.hadoop.fs.s3a.audit.AuditTestSupport.noopAuditor;
import static org.apache.hadoop.fs.statistics.IOStatisticsSupport.stubDurationTrackerFactory;
import static org.apache.hadoop.thirdparty.com.google.common.base.Preconditions.checkNotNull;

/**
 * Relays FS calls to the mocked FS, allows for some extra logging with
 * stack traces to be included, stubbing out other methods
 * where needed to avoid failures.
 *
 * The logging is useful for tracking
 * down why there are extra calls to a method than a test would expect:
 * changes in implementation details often trigger such false-positive
 * test failures.
 *
 * This class is in the s3a package so that it has access to methods
 */
public class MockS3AFileSystem extends S3AFileSystem {
  public static final String BUCKET = "bucket-name";
  public static final URI FS_URI = URI.create("s3a://" + BUCKET + "/");
  protected static final Logger LOG =
      LoggerFactory.getLogger(MockS3AFileSystem.class);

  private final S3AFileSystem mock;
  private final Pair<StagingTestBase.ClientResults,
      StagingTestBase.ClientErrors> outcome;

  /** Log nothing: {@value}. */
  public static final int LOG_NONE = 0;

  /** Log the name of the operation any arguments: {@value}.  */
  public static final int LOG_NAME = 1;

  /** Log the entire stack of where operations are called: {@value}.  */
  public static final int LOG_STACK = 2;

  private final Path root;

  /**
   * This is a request factory whose preparation is a no-op.
   */
  public static final RequestFactory REQUEST_FACTORY =
      RequestFactoryImpl.builder()
      .withRequestPreparer(MockS3AFileSystem::prepareRequest)
      .withBucket(BUCKET)
      .withEncryptionSecrets(new EncryptionSecrets())
      .build();

  /**
   * This can be edited to set the log level of events through the
   * mock FS.
   */
  private int logEvents = LOG_NAME;
  private Configuration conf;
  private WriteOperationHelper writeHelper;

  public MockS3AFileSystem(S3AFileSystem mock,
      Pair<StagingTestBase.ClientResults, StagingTestBase.ClientErrors> outcome) {
    this.mock = mock;
    this.outcome = outcome;
    setUri(FS_URI, false);
    setBucket(BUCKET);
    setEncryptionSecrets(new EncryptionSecrets());
    root = new Path(FS_URI.toString());
  }

  private static <T extends AmazonWebServiceRequest> T prepareRequest(T t) {
    return t;
  }

  @Override
  public RequestFactory getRequestFactory() {
    return REQUEST_FACTORY;
  }

  public Pair<StagingTestBase.ClientResults, StagingTestBase.ClientErrors>
      getOutcome() {
    return outcome;
  }

  public int getLogEvents() {
    return logEvents;
  }

  public void setLogEvents(int logEvents) {
    this.logEvents = logEvents;
  }

  private void event(String format, Object... args) {
    Throwable ex = null;
    String s = String.format(format, args);
    switch (logEvents) {
    case LOG_STACK:
      ex = new Exception(s);
        /* fall through */
    case LOG_NAME:
      LOG.info(s, ex);
      break;
    case LOG_NONE:
    default:
      //nothing
    }
  }

  @Override
  public URI getUri() {
    return FS_URI;
  }

  @Override
  public Path getWorkingDirectory() {
    return new Path(root, "work");
  }

  @Override
  public Path qualify(final Path path) {
    return path.makeQualified(FS_URI, getWorkingDirectory());
  }

  @Override
  public void initialize(URI name, Configuration originalConf)
      throws IOException {
    conf = originalConf;
    writeHelper = new WriteOperationHelper(this,
        conf,
        new EmptyS3AStatisticsContext(),
        noopAuditor(conf),
        AuditTestSupport.NOOP_SPAN);
  }

  @Override
  public void close() {
  }

  @Override
  public WriteOperationHelper getWriteOperationHelper() {
    return writeHelper;
  }

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  public boolean isMagicCommitEnabled() {
    return true;
  }

  /**
   * Make operation to set the s3 client public.
   * @param client client.
   */
  @Override
  public void setAmazonS3Client(AmazonS3 client) {
    LOG.debug("Setting S3 client to {}", client);
    super.setAmazonS3Client(client);
  }

  @Override
  public boolean exists(Path f) throws IOException {
    event("exists(%s)", f);
    return mock.exists(f);
  }

  @Override
  void finishedWrite(String key, long length, String eTag, String versionId,
          BulkOperationState operationState) {

  }

  @Override
  public FSDataInputStream open(Path f, int bufferSize) throws IOException {
    event("open(%s)", f);
    return mock.open(f, bufferSize);
  }

  @Override
  public FSDataOutputStream create(Path f,
      FsPermission permission,
      boolean overwrite,
      int bufferSize,
      short replication,
      long blockSize,
      Progressable progress) throws IOException {
    event("create(%s)", f);
    return mock.create(f, permission, overwrite, bufferSize, replication,
        blockSize, progress);
  }

  @Override
  public FSDataOutputStream append(Path f,
      int bufferSize,
      Progressable progress) throws IOException {
    return mock.append(f, bufferSize, progress);
  }

  @Override
  public boolean rename(Path src, Path dst) throws IOException {
    event("rename(%s, %s)", src, dst);
    return mock.rename(src, dst);
  }

  @Override
  public boolean delete(Path f, boolean recursive) throws IOException {
    event("delete(%s, %s)", f, recursive);
    return mock.delete(f, recursive);
  }

  @Override
  public FileStatus[] listStatus(Path f)
      throws IOException {
    event("listStatus(%s)", f);
    return mock.listStatus(f);
  }

  @Override
  public RemoteIterator<LocatedFileStatus> listFiles(Path f, boolean recursive)
      throws IOException {
    event("listFiles(%s, %s)", f, recursive);
    return new EmptyIterator();
  }

  @Override
  public void setWorkingDirectory(Path newDir) {
    mock.setWorkingDirectory(newDir);
  }

  @Override
  public boolean mkdirs(Path f) throws IOException {
    event("mkdirs(%s)", f);
    return mock.mkdirs(f);
  }

  @Override
  public boolean mkdirs(Path f, FsPermission permission) throws IOException {
    event("mkdirs(%s)", f);
    return mock.mkdirs(f, permission);
  }

  @Override
  public FileStatus getFileStatus(Path f) throws IOException {
    event("getFileStatus(%s)", f);
    return checkNotNull(mock.getFileStatus(f),
        "Mock getFileStatus(%s) returned null", f);
  }

  @Override
  public long getDefaultBlockSize(Path f) {
    return mock.getDefaultBlockSize(f);
  }

  @Override
  protected void incrementStatistic(Statistic statistic) {
  }

  @Override
  protected void incrementStatistic(Statistic statistic, long count) {
  }

  @Override
  protected void incrementGauge(Statistic statistic, long count) {
  }

  @Override
  public void incrementReadOperations() {
  }

  @Override
  public void incrementWriteOperations() {
  }

  @Override
  public void incrementPutStartStatistics(long bytes) {
  }

  @Override
  public void incrementPutCompletedStatistics(boolean success, long bytes) {
  }

  @Override
  public void incrementPutProgressStatistics(String key, long bytes) {
  }

  @Override
  @SuppressWarnings("deprecation")
  public long getDefaultBlockSize() {
    return mock.getDefaultBlockSize();
  }

  @Override
  void deleteObjectAtPath(Path f,
      String key,
      boolean isFile,
      final BulkOperationState operationState)
      throws AmazonClientException, IOException {
    deleteObject(key);
  }

  @Override
  protected void maybeCreateFakeParentDirectory(Path path)
      throws IOException, AmazonClientException {
    // no-op
  }

  private static class EmptyIterator implements
      RemoteIterator<LocatedFileStatus> {
    @Override
    public boolean hasNext() throws IOException {
      return false;
    }

    @Override
    public LocatedFileStatus next() throws IOException {
      return null;
    }
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(
        "MockS3AFileSystem{");
    sb.append("inner mockFS=").append(mock);
    sb.append('}');
    return sb.toString();
  }

  @Override
  public CommitterStatistics newCommitterStatistics() {
    return EmptyS3AStatisticsContext.EMPTY_COMMITTER_STATISTICS;
  }

  @Override
  public void operationRetried(Exception ex) {
    /** no-op */
  }

  @Override
  protected DurationTrackerFactory getDurationTrackerFactory() {
    return stubDurationTrackerFactory();
  }
}
