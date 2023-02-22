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

package org.apache.hadoop.fs.azurebfs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Test;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.azurebfs.constants.FSOperationType;
import org.apache.hadoop.fs.azurebfs.utils.TracingHeaderValidator;
import org.apache.hadoop.fs.contract.ContractTestUtils;

import static org.apache.hadoop.fs.azurebfs.constants.ConfigurationKeys.AZURE_LIST_MAX_RESULTS;
import static org.apache.hadoop.fs.contract.ContractTestUtils.assertMkdirs;
import static org.apache.hadoop.fs.contract.ContractTestUtils.createFile;
import static org.apache.hadoop.fs.contract.ContractTestUtils.assertPathExists;
import static org.apache.hadoop.fs.contract.ContractTestUtils.rename;

import static org.apache.hadoop.test.LambdaTestUtils.intercept;

/**
 * Test listStatus operation.
 */
public class ITestAzureBlobFileSystemListStatus extends
    AbstractAbfsIntegrationTest {
  private static final int TEST_FILES_NUMBER = 6000;

  public ITestAzureBlobFileSystemListStatus() throws Exception {
    super();
  }

  @Test
  public void testListPath() throws Exception {
    Configuration config = new Configuration(this.getRawConfiguration());
    config.set(AZURE_LIST_MAX_RESULTS, "5000");
    final AzureBlobFileSystem fs = (AzureBlobFileSystem) FileSystem
        .newInstance(getFileSystem().getUri(), config);
    final List<Future<Void>> tasks = new ArrayList<>();

    ExecutorService es = Executors.newFixedThreadPool(10);
    for (int i = 0; i < TEST_FILES_NUMBER; i++) {
      final Path fileName = new Path("/test" + i);
      Callable<Void> callable = new Callable<Void>() {
        @Override
        public Void call() throws Exception {
          touch(fileName);
          return null;
        }
      };

      tasks.add(es.submit(callable));
    }

    for (Future<Void> task : tasks) {
      task.get();
    }

    es.shutdownNow();
    fs.registerListener(
        new TracingHeaderValidator(getConfiguration().getClientCorrelationId(),
            fs.getFileSystemId(), FSOperationType.LISTSTATUS, true, 0));
    FileStatus[] files = fs.listStatus(new Path("/"));
    assertEquals(TEST_FILES_NUMBER, files.length /* user directory */);
  }

  /**
   * Creates a file, verifies that listStatus returns it,
   * even while the file is still open for writing.
   */
  @Test
  public void testListFileVsListDir() throws Exception {
    final AzureBlobFileSystem fs = getFileSystem();
    Path path = new Path("/testFile");
    try(FSDataOutputStream ignored = fs.create(path)) {
      FileStatus[] testFiles = fs.listStatus(path);
      assertEquals("length of test files", 1, testFiles.length);
      FileStatus status = testFiles[0];
      assertIsFileReference(status);
    }
  }

  @Test
  public void testListFileVsListDir2() throws Exception {
    final AzureBlobFileSystem fs = getFileSystem();
    fs.mkdirs(new Path("/testFolder"));
    fs.mkdirs(new Path("/testFolder/testFolder2"));
    fs.mkdirs(new Path("/testFolder/testFolder2/testFolder3"));
    Path testFile0Path = new Path("/testFolder/testFolder2/testFolder3/testFile");
    ContractTestUtils.touch(fs, testFile0Path);

    FileStatus[] testFiles = fs.listStatus(testFile0Path);
    assertEquals("Wrong listing size of file " + testFile0Path,
        1, testFiles.length);
    FileStatus file0 = testFiles[0];
    assertEquals("Wrong path for " + file0,
        new Path(getTestUrl(), "/testFolder/testFolder2/testFolder3/testFile"),
        file0.getPath());
    assertIsFileReference(file0);
  }

  @Test(expected = FileNotFoundException.class)
  public void testListNonExistentDir() throws Exception {
    final AzureBlobFileSystem fs = getFileSystem();
    fs.listStatus(new Path("/testFile/"));
  }

  @Test
  public void testListFiles() throws Exception {
    final AzureBlobFileSystem fs = getFileSystem();
    Path testDir = new Path("/test");
    fs.mkdirs(testDir);

    FileStatus[] fileStatuses = fs.listStatus(new Path("/"));
    assertEquals(1, fileStatuses.length);

    fs.mkdirs(new Path("/test/sub"));
    fileStatuses = fs.listStatus(testDir);
    assertEquals(1, fileStatuses.length);
    assertEquals("sub", fileStatuses[0].getPath().getName());
    assertIsDirectoryReference(fileStatuses[0]);
    Path childF = fs.makeQualified(new Path("/test/f"));
    touch(childF);
    fileStatuses = fs.listStatus(testDir);
    assertEquals(2, fileStatuses.length);
    final FileStatus childStatus = fileStatuses[0];
    assertEquals(childF, childStatus.getPath());
    assertEquals("f", childStatus.getPath().getName());
    assertIsFileReference(childStatus);
    assertEquals(0, childStatus.getLen());
    final FileStatus status1 = fileStatuses[1];
    assertEquals("sub", status1.getPath().getName());
    assertIsDirectoryReference(status1);
    // look at the child through getFileStatus
    LocatedFileStatus locatedChildStatus = fs.listFiles(childF, false).next();
    assertIsFileReference(locatedChildStatus);

    fs.delete(testDir, true);
    intercept(FileNotFoundException.class,
        () -> fs.listFiles(childF, false).next());

    // do some final checks on the status (failing due to version checks)
    assertEquals("Path mismatch of " + locatedChildStatus,
        childF, locatedChildStatus.getPath());
    assertEquals("locatedstatus.equals(status)",
        locatedChildStatus, childStatus);
    assertEquals("status.equals(locatedstatus)",
        childStatus, locatedChildStatus);
  }

  private void assertIsDirectoryReference(FileStatus status) {
    assertTrue("Not a directory: " + status, status.isDirectory());
    assertFalse("Not a directory: " + status, status.isFile());
    assertEquals(0, status.getLen());
  }

  private void assertIsFileReference(FileStatus status) {
    assertFalse("Not a file: " + status, status.isDirectory());
    assertTrue("Not a file: " + status, status.isFile());
  }

  @Test
  public void testMkdirTrailingPeriodDirName() throws IOException {
    boolean exceptionThrown = false;
    final AzureBlobFileSystem fs = getFileSystem();

    Path nontrailingPeriodDir = path("testTrailingDir/dir");
    Path trailingPeriodDir = path("testTrailingDir/dir.");

    assertMkdirs(fs, nontrailingPeriodDir);

    try {
      fs.mkdirs(trailingPeriodDir);
    }
    catch(IllegalArgumentException e) {
      exceptionThrown = true;
    }
    assertTrue("Attempt to create file that ended with a dot should"
        + " throw IllegalArgumentException", exceptionThrown);
  }

  @Test
  public void testCreateTrailingPeriodFileName() throws IOException {
    boolean exceptionThrown = false;
    final AzureBlobFileSystem fs = getFileSystem();

    Path trailingPeriodFile = path("testTrailingDir/file.");
    Path nontrailingPeriodFile = path("testTrailingDir/file");

    createFile(fs, nontrailingPeriodFile, false, new byte[0]);
    assertPathExists(fs, "Trailing period file does not exist",
        nontrailingPeriodFile);

    try {
      createFile(fs, trailingPeriodFile, false, new byte[0]);
    }
    catch(IllegalArgumentException e) {
      exceptionThrown = true;
    }
    assertTrue("Attempt to create file that ended with a dot should"
        + " throw IllegalArgumentException", exceptionThrown);
  }

  @Test
  public void testRenameTrailingPeriodFile() throws IOException {
    boolean exceptionThrown = false;
    final AzureBlobFileSystem fs = getFileSystem();

    Path nonTrailingPeriodFile = path("testTrailingDir/file");
    Path trailingPeriodFile = path("testTrailingDir/file.");

    createFile(fs, nonTrailingPeriodFile, false, new byte[0]);
    try {
    rename(fs, nonTrailingPeriodFile, trailingPeriodFile);
    }
    catch(IllegalArgumentException e) {
      exceptionThrown = true;
    }
    assertTrue("Attempt to create file that ended with a dot should"
        + " throw IllegalArgumentException", exceptionThrown);
  }
}
