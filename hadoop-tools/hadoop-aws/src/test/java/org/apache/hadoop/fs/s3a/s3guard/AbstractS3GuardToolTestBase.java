/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.s3a.s3guard;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.fs.s3a.S3AUtils;
import org.apache.hadoop.fs.s3a.UnknownStoreException;
import org.apache.hadoop.util.StopWatch;
import org.apache.hadoop.thirdparty.com.google.common.base.Preconditions;
import org.apache.hadoop.fs.FileSystem;
import org.junit.Test;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.contract.ContractTestUtils;
import org.apache.hadoop.fs.s3a.AbstractS3ATestBase;
import org.apache.hadoop.fs.s3a.Constants;
import org.apache.hadoop.fs.s3a.S3AFileStatus;
import org.apache.hadoop.fs.s3a.S3AFileSystem;
import org.apache.hadoop.fs.s3a.S3ATestUtils;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.ExitUtil;
import org.apache.hadoop.util.StringUtils;

import static org.apache.hadoop.fs.s3a.Constants.S3GUARD_DDB_REGION_KEY;
import static org.apache.hadoop.fs.s3a.Constants.S3GUARD_DDB_TABLE_CREATE_KEY;
import static org.apache.hadoop.fs.s3a.Constants.S3GUARD_DDB_TABLE_NAME_KEY;
import static org.apache.hadoop.fs.s3a.Constants.S3GUARD_METASTORE_NULL;
import static org.apache.hadoop.fs.s3a.Constants.S3_METADATA_STORE_IMPL;
import static org.apache.hadoop.fs.s3a.S3AUtils.clearBucketOption;
import static org.apache.hadoop.fs.s3a.s3guard.S3GuardTool.BucketInfo.IS_MARKER_AWARE;
import static org.apache.hadoop.fs.s3a.s3guard.S3GuardTool.INVALID_ARGUMENT;
import static org.apache.hadoop.fs.s3a.s3guard.S3GuardTool.SUCCESS;
import static org.apache.hadoop.fs.s3a.s3guard.S3GuardToolTestHelper.exec;
import static org.apache.hadoop.fs.s3a.s3guard.S3GuardToolTestHelper.runS3GuardCommand;
import static org.apache.hadoop.fs.s3a.tools.MarkerTool.MARKERS;
import static org.apache.hadoop.service.launcher.LauncherExitCodes.EXIT_NOT_ACCEPTABLE;
import static org.apache.hadoop.test.LambdaTestUtils.intercept;

/**
 * Common functionality for S3GuardTool test cases.
 */
public abstract class AbstractS3GuardToolTestBase extends AbstractS3ATestBase {

  protected static final String OWNER = "hdfs";
  protected static final String DYNAMODB_TABLE = "ireland-team";
  protected static final String S3A_THIS_BUCKET_DOES_NOT_EXIST
      = "s3a://this-bucket-does-not-exist-00000000000";

  private static final int PRUNE_MAX_AGE_SECS = 2;

  private MetadataStore ms;
  private S3AFileSystem rawFs;

  /**
   * List of tools to close in test teardown.
   */
  private final List<S3GuardTool> toolsToClose = new ArrayList<>();

  /**
   * The test timeout is increased in case previous tests have created
   * many tombstone markers which now need to be purged.
   * @return the test timeout.
   */
  @Override
  protected int getTestTimeoutMillis() {
    return SCALE_TEST_TIMEOUT_SECONDS * 1000;
  }

  /**
   * Declare that the tool is to be closed in teardown.
   * @param tool tool to close
   * @return the tool.
   */
  protected <T extends S3GuardTool> T toClose(T tool) {
    toolsToClose.add(tool);
    return tool;
  }

  protected static void expectResult(int expected,
      String message,
      S3GuardTool tool,
      String... args) throws Exception {
    assertEquals(message, expected, tool.run(args));
  }

  /**
   * Expect a command to succeed.
   * @param message any extra text to include in the assertion error message
   * @param tool tool to run
   * @param args arguments to the command
   * @return the output of any successful run
   * @throws Exception failure
   */
  public static String expectSuccess(
      String message,
      S3GuardTool tool,
      Object... args) throws Exception {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    exec(SUCCESS, message, tool, buf, args);
    return buf.toString();
  }

  /**
   * Run a S3GuardTool command from a varags list.
   * @param conf configuration
   * @param args argument list
   * @return the return code
   * @throws Exception any exception
   */
  protected int run(Configuration conf, Object... args)
      throws Exception {
    return runS3GuardCommand(conf, args);
  }

  /**
   * Run a S3GuardTool command from a varags list and the
   * configuration returned by {@code getConfiguration()}.
   * @param args argument list
   * @return the return code
   * @throws Exception any exception
   */
  protected int run(Object... args) throws Exception {
    return runS3GuardCommand(getConfiguration(), args);
  }

  /**
   * Run a S3GuardTool command from a varags list, catch any raised
   * ExitException and verify the status code matches that expected.
   * @param status expected status code of the exception
   * @param args argument list
   * @throws Exception any exception
   */
  protected void runToFailure(int status, Object... args)
      throws Exception {
    final Configuration conf = getConfiguration();
    ExitUtil.ExitException ex =
        intercept(ExitUtil.ExitException.class, () ->
            runS3GuardCommand(conf, args));
    if (ex.status != status) {
      throw ex;
    }
  }

  protected MetadataStore getMetadataStore() {
    return ms;
  }

  @Override
  public void setup() throws Exception {
    super.setup();
    S3ATestUtils.assumeS3GuardState(true, getConfiguration());
    S3AFileSystem fs = getFileSystem();
    ms = fs.getMetadataStore();

    // Also create a "raw" fs without any MetadataStore configured
    Configuration conf = new Configuration(getConfiguration());
    clearBucketOption(conf, fs.getBucket(), S3_METADATA_STORE_IMPL);
    conf.set(S3_METADATA_STORE_IMPL, S3GUARD_METASTORE_NULL);
    URI fsUri = fs.getUri();
    S3AUtils.setBucketOption(conf,fsUri.getHost(),
        S3_METADATA_STORE_IMPL,
        S3GUARD_METASTORE_NULL);
    rawFs = (S3AFileSystem) FileSystem.newInstance(fsUri, conf);
  }

  @Override
  public void teardown() throws Exception {
    super.teardown();
    toolsToClose.forEach(t -> IOUtils.cleanupWithLogger(LOG, t));
    IOUtils.cleanupWithLogger(LOG, ms);
    IOUtils.closeStream(rawFs);
  }

  protected void mkdirs(Path path, boolean onS3, boolean onMetadataStore)
      throws IOException {
    Preconditions.checkArgument(onS3 || onMetadataStore);
    // getFileSystem() returns an fs with MetadataStore configured
    S3AFileSystem fs = onMetadataStore ? getFileSystem() : rawFs;
    if (onS3) {
      fs.mkdirs(path);
    } else if (onMetadataStore) {
      S3AFileStatus status = new S3AFileStatus(true, path, OWNER);
      ms.put(new PathMetadata(status), null);
    }
  }

  protected static void putFile(MetadataStore ms, S3AFileStatus f)
      throws IOException {
    assertNotNull(f);
    try (BulkOperationState bulkWrite =
             ms.initiateBulkWrite(
                 BulkOperationState.OperationType.Put,
                 f.getPath())) {
      ms.put(new PathMetadata(f), bulkWrite);
      Path parent = f.getPath().getParent();
      while (parent != null) {
        S3AFileStatus dir = new S3AFileStatus(false, parent, f.getOwner());
        ms.put(new PathMetadata(dir), bulkWrite);
        parent = parent.getParent();
      }
    }
  }

  /**
   * Create file either on S3 or in metadata store.
   * @param path the file path.
   * @param onS3 set to true to create the file on S3.
   * @param onMetadataStore set to true to create the file on the
   *                        metadata store.
   * @throws IOException IO problem
   */
  protected void createFile(Path path, boolean onS3, boolean onMetadataStore)
      throws IOException {
    Preconditions.checkArgument(onS3 || onMetadataStore);
    // getFileSystem() returns an fs with MetadataStore configured
    S3AFileSystem fs = onMetadataStore ? getFileSystem() : rawFs;
    if (onS3) {
      ContractTestUtils.touch(fs, path);
    } else if (onMetadataStore) {
      S3AFileStatus status = new S3AFileStatus(100L, System.currentTimeMillis(),
          fs.makeQualified(path), 512L, "hdfs", null, null);
      putFile(ms, status);
    }
  }

  /**
   * Attempt to test prune() with sleep() without having flaky tests
   * when things run slowly. Test is basically:
   * 1. Set max path age to X seconds
   * 2. Create some files (which writes entries to MetadataStore)
   * 3. Sleep X+2 seconds (all files from above are now "stale")
   * 4. Create some other files (these are "fresh").
   * 5. Run prune on MetadataStore.
   * 6. Assert that only files that were created before the sleep() were pruned.
   *
   * Problem is: #6 can fail if X seconds elapse between steps 4 and 5, since
   * the newer files also become stale and get pruned.  This is easy to
   * reproduce by running all integration tests in parallel with a ton of
   * threads, or anything else that slows down execution a lot.
   *
   * Solution: Keep track of time elapsed between #4 and #5, and if it
   * exceeds X, just print a warn() message instead of failing.
   *
   * @param cmdConf configuration for command
   * @param parent path
   * @param args command args
   * @throws Exception
   */
  private void testPruneCommand(Configuration cmdConf, Path parent,
      String...args) throws Exception {
    Path keepParent = path("prune-cli-keep");
    StopWatch timer = new StopWatch();
    final S3AFileSystem fs = getFileSystem();
    S3GuardTool.Prune cmd = toClose(new S3GuardTool.Prune(cmdConf));
    cmd.setMetadataStore(ms);
    try {

      fs.mkdirs(parent);
      fs.mkdirs(keepParent);
      createFile(new Path(parent, "stale"), true, true);
      createFile(new Path(keepParent, "stale-to-keep"), true, true);

      Thread.sleep(TimeUnit.SECONDS.toMillis(PRUNE_MAX_AGE_SECS + 2));

      timer.start();
      createFile(new Path(parent, "fresh"), true, true);

      assertMetastoreListingCount(parent, "Children count before pruning", 2);
      exec(cmd, args);
      long msecElapsed = timer.now(TimeUnit.MILLISECONDS);
      if (msecElapsed >= PRUNE_MAX_AGE_SECS * 1000) {
        LOG.warn("Skipping an assertion: Test running too slowly ({} msec)",
            msecElapsed);
      } else {
        assertMetastoreListingCount(parent, "Pruned children count remaining",
            1);
      }
      assertMetastoreListingCount(keepParent,
          "This child should have been kept (prefix restriction).", 1);
    } finally {
      fs.delete(parent, true);
      fs.delete(keepParent, true);
      ms.prune(MetadataStore.PruneMode.ALL_BY_MODTIME,
          Long.MAX_VALUE,
          fs.pathToKey(parent));
      ms.prune(MetadataStore.PruneMode.ALL_BY_MODTIME,
          Long.MAX_VALUE,
          fs.pathToKey(keepParent));
      // reset the store before we close the tool.
      cmd.setMetadataStore(new NullMetadataStore());
    }
  }

  private void assertMetastoreListingCount(Path parent,
      String message,
      int expected) throws IOException {
    Collection<PathMetadata> listing = ms.listChildren(parent).getListing();
    assertEquals(message +" [" + StringUtils.join(", ", listing) + "]",
        expected, listing.size());
  }

  @Test
  public void testPruneCommandCLI() throws Exception {
    Path testPath = path("testPruneCommandCLI");
    testPruneCommand(getFileSystem().getConf(), testPath,
        "prune", "-seconds", String.valueOf(PRUNE_MAX_AGE_SECS),
        testPath.toString());
  }

  @Test
  public void testPruneCommandTombstones() throws Exception {
    Path testPath = path("testPruneCommandTombstones");
    getFileSystem().mkdirs(testPath);
    getFileSystem().delete(testPath, true);
    S3GuardTool.Prune cmd = toClose(
        new S3GuardTool.Prune(getFileSystem().getConf()));
    cmd.setMetadataStore(ms);
    try {
      exec(cmd,
          "prune", "-" + S3GuardTool.Prune.TOMBSTONE,
          "-seconds", "0",
          testPath.toString());
      assertNotNull("Command did not create a filesystem",
          cmd.getFilesystem());
    } finally {
      // reset the store before we close the tool.
      cmd.setMetadataStore(new NullMetadataStore());
    }
  }

  /**
   * HADOOP-16457. In certain cases prune doesn't create an FS.
   */
  @Test
  public void testMaybeInitFilesystem() throws Exception {
    Path testPath = path("maybeInitFilesystem");
    try (S3GuardTool.Prune cmd =
             new S3GuardTool.Prune(getFileSystem().getConf())) {
      cmd.maybeInitFilesystem(Collections.singletonList(testPath.toString()));
      assertNotNull("Command did not create a filesystem",
          cmd.getFilesystem());
    }
  }

  /**
   * HADOOP-16457. In certain cases prune doesn't create an FS.
   */
  @Test
  public void testMaybeInitFilesystemNoPath() throws Exception {
    try (S3GuardTool.Prune cmd = new S3GuardTool.Prune(
        getFileSystem().getConf())) {
      cmd.maybeInitFilesystem(Collections.emptyList());
      assertNull("Command should not have created a filesystem",
          cmd.getFilesystem());
    }
  }

  @Test
  public void testPruneCommandNoPath() throws Exception {
    runToFailure(INVALID_ARGUMENT,
        S3GuardTool.Prune.NAME,
        "-" + S3GuardTool.Prune.TOMBSTONE,
        "-seconds", "0");
  }

  @Test
  public void testPruneCommandConf() throws Exception {
    getConfiguration().setLong(Constants.S3GUARD_CLI_PRUNE_AGE,
        TimeUnit.SECONDS.toMillis(PRUNE_MAX_AGE_SECS));
    Path testPath = path("testPruneCommandConf");
    testPruneCommand(getConfiguration(), testPath,
        "prune", testPath.toString());
  }

  @Test
  public void testSetCapacityFailFastOnReadWriteOfZero() throws Exception{
    Configuration conf = getConfiguration();
    String bucket = getFileSystem().getBucket();
    conf.set(S3GUARD_DDB_TABLE_NAME_KEY, getFileSystem().getBucket());

    S3GuardTool.SetCapacity cmdR = toClose(new S3GuardTool.SetCapacity(conf));
    String[] argsR =
        new String[]{cmdR.getName(), "-read", "0", "s3a://" + bucket};
    intercept(IllegalArgumentException.class,
        S3GuardTool.SetCapacity.READ_CAP_INVALID, () -> cmdR.run(argsR));

    S3GuardTool.SetCapacity cmdW = toClose(new S3GuardTool.SetCapacity(conf));
    String[] argsW =
        new String[]{cmdW.getName(), "-write", "0", "s3a://" + bucket};
    intercept(IllegalArgumentException.class,
        S3GuardTool.SetCapacity.WRITE_CAP_INVALID, () -> cmdW.run(argsW));
  }

  @Test
  public void testBucketInfoUnguarded() throws Exception {
    final Configuration conf = getConfiguration();
    URI fsUri = getFileSystem().getUri();
    conf.set(S3GUARD_DDB_TABLE_CREATE_KEY, Boolean.FALSE.toString());
    String bucket = fsUri.getHost();
    clearBucketOption(conf, bucket,
        S3GUARD_DDB_TABLE_CREATE_KEY);
    clearBucketOption(conf, bucket, S3_METADATA_STORE_IMPL);
    clearBucketOption(conf, bucket, S3GUARD_DDB_TABLE_NAME_KEY);
    conf.set(S3_METADATA_STORE_IMPL, S3GUARD_METASTORE_NULL);
    conf.set(S3GUARD_DDB_TABLE_NAME_KEY,
        "testBucketInfoUnguarded-" + UUID.randomUUID());

    // run a bucket info command and look for
    // confirmation that it got the output from DDB diags
    S3GuardTool.BucketInfo infocmd = toClose(new S3GuardTool.BucketInfo(conf));
    String info = exec(infocmd, S3GuardTool.BucketInfo.NAME,
        "-" + S3GuardTool.BucketInfo.UNGUARDED_FLAG,
        fsUri.toString());

    assertTrue("Output should contain information about S3A client " + info,
        info.contains("S3A Client"));
  }

  /**
   * Verify that the {@code -markers aware} option works.
   * This test case is in this class for ease of backporting.
   */
  @Test
  public void testBucketInfoMarkerAware() throws Throwable {
    final Configuration conf = getConfiguration();
    URI fsUri = getFileSystem().getUri();

    // run a bucket info command and look for
    // confirmation that it got the output from DDB diags
    S3GuardTool.BucketInfo infocmd = toClose(new S3GuardTool.BucketInfo(conf));
    String info = exec(infocmd, S3GuardTool.BucketInfo.NAME,
        "-" + MARKERS, S3GuardTool.BucketInfo.MARKERS_AWARE,
        fsUri.toString());

    assertTrue("Output should contain information about S3A client " + info,
        info.contains(IS_MARKER_AWARE));
  }

  /**
   * Verify that the {@code -markers} option fails on unknown options.
   * This test case is in this class for ease of backporting.
   */
  @Test
  public void testBucketInfoMarkerPolicyUnknown() throws Throwable {
    final Configuration conf = getConfiguration();
    URI fsUri = getFileSystem().getUri();

    // run a bucket info command and look for
    // confirmation that it got the output from DDB diags
    S3GuardTool.BucketInfo infocmd = toClose(new S3GuardTool.BucketInfo(conf));
    intercept(ExitUtil.ExitException.class, ""+ EXIT_NOT_ACCEPTABLE, () ->
        exec(infocmd, S3GuardTool.BucketInfo.NAME,
            "-" + MARKERS, "unknown",
            fsUri.toString()));
  }

  @Test
  public void testSetCapacityFailFastIfNotGuarded() throws Exception{
    Configuration conf = getConfiguration();
    bindToNonexistentTable(conf);
    String bucket = rawFs.getBucket();
    clearBucketOption(conf, bucket, S3_METADATA_STORE_IMPL);
    clearBucketOption(conf, bucket, S3GUARD_DDB_TABLE_NAME_KEY);
    clearBucketOption(conf, bucket, S3GUARD_DDB_TABLE_CREATE_KEY);
    conf.set(S3_METADATA_STORE_IMPL, S3GUARD_METASTORE_NULL);

    S3GuardTool.SetCapacity cmdR = toClose(new S3GuardTool.SetCapacity(conf));
    String[] argsR = new String[]{
        cmdR.getName(),
        "s3a://" + getFileSystem().getBucket()
    };

    intercept(IllegalStateException.class, "unguarded",
        () -> cmdR.run(argsR));
  }

  /**
   * Binds the configuration to a nonexistent table.
   * @param conf
   */
  private void bindToNonexistentTable(final Configuration conf) {
    conf.set(S3GUARD_DDB_TABLE_NAME_KEY, UUID.randomUUID().toString());
    conf.unset(S3GUARD_DDB_REGION_KEY);
    conf.setBoolean(S3GUARD_DDB_TABLE_CREATE_KEY, false);
  }

  /**
   * Make an S3GuardTool of the specific subtype with binded configuration
   * to a nonexistent table.
   * @param tool
   */
  private S3GuardTool makeBindedTool(Class<? extends S3GuardTool> tool)
      throws Exception {
    Configuration conf = getConfiguration();
    // set a table as a safety check in case the test goes wrong
    // and deletes it.
    bindToNonexistentTable(conf);
    return tool.getDeclaredConstructor(Configuration.class).newInstance(conf);
  }

  @Test
  public void testToolsNoBucket() throws Throwable {
    List<Class<? extends S3GuardTool>> tools =
        Arrays.asList(S3GuardTool.Destroy.class, S3GuardTool.BucketInfo.class,
            S3GuardTool.Diff.class, S3GuardTool.Import.class,
            S3GuardTool.Prune.class, S3GuardTool.SetCapacity.class,
            S3GuardTool.Uploads.class,
            S3GuardTool.Authoritative.class);

    for (Class<? extends S3GuardTool> tool : tools) {
      S3GuardTool cmdR = makeBindedTool(tool);
      describe("Calling " + cmdR.getName() + " on a bucket that does not exist.");
      String[] argsR = new String[]{
          cmdR.getName(),
          S3A_THIS_BUCKET_DOES_NOT_EXIST
      };
      intercept(UnknownStoreException.class,
          () -> cmdR.run(argsR));
    }
  }

  @Test
  public void testToolsNoArgsForBucketAndDDBTable() throws Throwable {
    List<Class<? extends S3GuardTool>> tools =
        Arrays.asList(S3GuardTool.Destroy.class, S3GuardTool.Init.class);

    for (Class<? extends S3GuardTool> tool : tools) {
      S3GuardTool cmdR = makeBindedTool(tool);
      describe("Calling " + cmdR.getName() + " without any arguments.");
      intercept(ExitUtil.ExitException.class,
          "S3 bucket url or DDB table name have to be provided explicitly",
          () -> cmdR.run(new String[]{tool.getName()}));
    }
  }

  @Test
  public void testToolsNoArgsForBucket() throws Throwable {
    List<Class<? extends S3GuardTool>> tools =
        Arrays.asList(S3GuardTool.BucketInfo.class, S3GuardTool.Diff.class,
            S3GuardTool.Import.class, S3GuardTool.Prune.class,
            S3GuardTool.SetCapacity.class, S3GuardTool.Uploads.class,
            S3GuardTool.Authoritative.class);

    for (Class<? extends S3GuardTool> tool : tools) {
      S3GuardTool cmdR = makeBindedTool(tool);
      describe("Calling " + cmdR.getName() + " without any arguments.");
      assertExitCode(INVALID_ARGUMENT,
          intercept(ExitUtil.ExitException.class,
              () -> cmdR.run(new String[]{tool.getName()})));
    }
  }

  @Test
  public void testProbeForMagic() throws Throwable {
    S3AFileSystem fs = getFileSystem();
    String name = fs.getUri().toString();
    S3GuardTool.BucketInfo cmd = new S3GuardTool.BucketInfo(
        getConfiguration());
    // this must always work
      exec(cmd, S3GuardTool.BucketInfo.MAGIC_FLAG, name);
  }

  /**
   * Assert that an exit exception had a specific error code.
   * @param expectedErrorCode expected code.
   * @param e exit exception
   * @throws AssertionError with the exit exception nested inside
   */
  protected void assertExitCode(final int expectedErrorCode,
      final ExitUtil.ExitException e) {
    if (e.getExitCode() != expectedErrorCode) {
      throw new AssertionError("Expected error code " +
          expectedErrorCode + " in " + e, e);
    }
  }

  @Test
  public void testDestroyFailsIfNoBucketNameOrDDBTableSet()
      throws Exception {
    intercept(ExitUtil.ExitException.class,
        () -> run(S3GuardTool.Destroy.NAME));
  }

  @Test
  public void testInitFailsIfNoBucketNameOrDDBTableSet() throws Exception {
    intercept(ExitUtil.ExitException.class,
        () -> run(S3GuardTool.Init.NAME));
  }

  @Test
  public void
  testDiffCommand() throws Exception {
    S3AFileSystem fs = getFileSystem();
    ms = getMetadataStore();
    Set<Path> filesOnS3 = new HashSet<>(); // files on S3.
    Set<Path> filesOnMS = new HashSet<>(); // files on metadata store.

    Path testPath = path("test-diff");
    // clean up through the store and behind it.
    fs.delete(testPath, true);
    rawFs.delete(testPath, true);
    mkdirs(testPath, true, true);

    Path msOnlyPath = new Path(testPath, "ms_only");
    mkdirs(msOnlyPath, false, true);
    filesOnMS.add(msOnlyPath);
    for (int i = 0; i < 5; i++) {
      Path file = new Path(msOnlyPath, String.format("file-%d", i));
      createFile(file, false, true);
      filesOnMS.add(file);
    }

    Path s3OnlyPath = new Path(testPath, "s3_only");
    mkdirs(s3OnlyPath, true, false);
    filesOnS3.add(s3OnlyPath);
    for (int i = 0; i < 5; i++) {
      Path file = new Path(s3OnlyPath, String.format("file-%d", i));
      createFile(file, true, false);
      filesOnS3.add(file);
    }

    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    S3GuardTool.Diff cmd = toClose(new S3GuardTool.Diff(fs.getConf()));
    cmd.setStore(ms);
    String table = "dynamo://" + getTestTableName(DYNAMODB_TABLE);
    exec(0, "", cmd, buf, "diff", "-meta", table, testPath.toString());

    Set<Path> actualOnS3 = new HashSet<>();
    Set<Path> actualOnMS = new HashSet<>();
    boolean duplicates = false;
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(
            new ByteArrayInputStream(buf.toByteArray())))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] fields = line.split("\\s");
        assertEquals("[" + line + "] does not have enough fields",
            4, fields.length);
        String where = fields[0];
        Path path = new Path(fields[3]);
        if (S3GuardTool.Diff.S3_PREFIX.equals(where)) {
          duplicates = duplicates || actualOnS3.contains(path);
          actualOnS3.add(path);
        } else if (S3GuardTool.Diff.MS_PREFIX.equals(where)) {
          duplicates = duplicates || actualOnMS.contains(path);
          actualOnMS.add(path);
        } else {
          fail("Unknown prefix: " + where);
        }
      }
    }
    String actualOut = buf.toString();
    assertEquals("Mismatched metadata store outputs: " + actualOut,
        filesOnMS, actualOnMS);
    assertEquals("Mismatched s3 outputs: " + actualOut, filesOnS3, actualOnS3);
    assertFalse("Diff contained duplicates", duplicates);
  }

}
