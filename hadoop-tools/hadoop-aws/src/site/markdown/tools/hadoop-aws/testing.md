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

# Testing the S3A filesystem client and its features, including S3Guard

<!-- MACRO{toc|fromDepth=0|toDepth=3} -->

This module includes both unit tests, which can run in isolation without
connecting to the S3 service, and integration tests, which require a working
connection to S3 to interact with a bucket.  Unit test suites follow the naming
convention `Test*.java`.  Integration tests follow the naming convention
`ITest*.java`.

Due to eventual consistency, integration tests may fail without reason.
Transient failures, which no longer occur upon rerunning the test, should thus
be ignored.

## <a name="policy"></a> Policy for submitting patches which affect the `hadoop-aws` module.

The Apache Jenkins infrastructure does not run any S3 integration tests,
due to the need to keep credentials secure.

### The submitter of any patch is required to run all the integration tests and declare which S3 region/implementation they used.

This is important: **patches which do not include this declaration will be ignored**

This policy has proven to be the only mechanism to guarantee full regression
testing of code changes. Why the declaration of region? Two reasons

1. It helps us identify regressions which only surface against specific endpoints
or third-party implementations of the S3 protocol.
1. It forces the submitters to be more honest about their testing. It's easy
to lie, "yes, I tested this". To say "yes, I tested this against S3 US-west"
is a more specific lie and harder to make. And, if you get caught out: you
lose all credibility with the project.

You don't need to test from a VM within the AWS infrastructure; with the
`-Dparallel=tests` option the non-scale tests complete in under ten minutes.
Because the tests clean up after themselves, they are also designed to be low
cost. It's neither hard nor expensive to run the tests; if you can't,
there's no guarantee your patch works. The reviewers have enough to do, and
don't have the time to do these tests, especially as every failure will simply
make for a slow iterative development.

Please: run the tests. And if you don't, we are sorry for declining your
patch, but we have to.


### What if there's an intermittent failure of a test?

Some of the tests do fail intermittently, especially in parallel runs.
If this happens, try to run the test on its own to see if the test succeeds.

If it still fails, include this fact in your declaration. We know some tests
are intermittently unreliable.

### What if the tests are timing out or failing over my network connection?

The tests and the S3A client are designed to be configurable for different
timeouts. If you are seeing problems and this configuration isn't working,
that's a sign of the configuration mechanism isn't complete. If it's happening
in the production code, that could be a sign of a problem which may surface
over long-haul connections. Please help us identify and fix these problems
&mdash; especially as you are the one best placed to verify the fixes work.

## <a name="setting-up"></a> Setting up the tests

To integration test the S3* filesystem clients, you need to provide
`auth-keys.xml` which passes in authentication details to the test runner.

It is a Hadoop XML configuration file, which must be placed into
`hadoop-tools/hadoop-aws/src/test/resources`.

### File `core-site.xml`

This file pre-exists and sources the configurations created
under `auth-keys.xml`.

For most purposes you will not need to edit this file unless you
need to apply a specific, non-default property change during the tests.

### File `auth-keys.xml`

The presence of this file triggers the testing of the S3 classes.

Without this file, *none of the integration tests in this module will be
executed*.

The XML file must contain all the ID/key information needed to connect
each of the filesystem clients to the object stores, and a URL for
each filesystem for its testing.

1. `test.fs.s3a.name` : the URL of the bucket for S3a tests
1. `fs.contract.test.fs.s3a` : the URL of the bucket for S3a filesystem contract tests


The contents of the bucket will be destroyed during the test process:
do not use the bucket for any purpose other than testing. Furthermore, for
s3a, all in-progress multi-part uploads to the bucket will be aborted at the
start of a test (by forcing `fs.s3a.multipart.purge=true`) to clean up the
temporary state of previously failed tests.

Example:

```xml
<configuration>

  <property>
    <name>test.fs.s3a.name</name>
    <value>s3a://test-aws-s3a/</value>
  </property>

  <property>
    <name>fs.contract.test.fs.s3a</name>
    <value>${test.fs.s3a.name}</value>
  </property>

  <property>
    <name>fs.s3a.access.key</name>
    <description>AWS access key ID. Omit for IAM role-based authentication.</description>
    <value>DONOTCOMMITTHISKEYTOSCM</value>
  </property>

  <property>
    <name>fs.s3a.secret.key</name>
    <description>AWS secret key. Omit for IAM role-based authentication.</description>
    <value>DONOTEVERSHARETHISSECRETKEY!</value>
  </property>

  <property>
    <name>test.sts.endpoint</name>
    <description>Specific endpoint to use for STS requests.</description>
    <value>sts.amazonaws.com</value>
  </property>

</configuration>
```

### <a name="encryption"></a> Configuring S3a Encryption

For S3a encryption tests to run correctly, the
`fs.s3a.encryption.key` must be configured in the s3a contract xml
file or `auth-keys.xml` file with a AWS KMS encryption key arn as this value is
different for each AWS KMS. Please note this KMS key should be created in the
same region as your S3 bucket. Otherwise, you may get `KMS.NotFoundException`.

Example:

```xml
<property>
  <name>fs.s3a.encryption.key</name>
  <value>arn:aws:kms:us-west-2:360379543683:key/071a86ff-8881-4ba0-9230-95af6d01ca01</value>
</property>
```

You can also force all the tests to run with a specific SSE encryption method
by configuring the property `fs.s3a.encryption.algorithm` in the s3a
contract file.

### <a name="default_encyption"></a> Default Encryption

Buckets can be configured with [default encryption](https://docs.aws.amazon.com/AmazonS3/latest/dev/bucket-encryption.html)
on the AWS side. Some S3AFileSystem tests are skipped when default encryption is
enabled due to unpredictability in how [ETags](https://docs.aws.amazon.com/AmazonS3/latest/API/RESTCommonResponseHeaders.html)
are generated.

## <a name="running"></a> Running the Tests

After completing the configuration, execute the test run through Maven.

```bash
mvn clean verify
```

It's also possible to execute multiple test suites in parallel by passing the
`parallel-tests` property on the command line.  The tests spend most of their
time blocked on network I/O with the S3 service, so running in parallel tends to
complete full test runs faster.

```bash
mvn -Dparallel-tests clean verify
```

Some tests must run with exclusive access to the S3 bucket, so even with the
`parallel-tests` property, several test suites will run in serial in a separate
Maven execution step after the parallel tests.

By default, `parallel-tests` runs 4 test suites concurrently.  This can be tuned
by passing the `testsThreadCount` property.

```bash
mvn -Dparallel-tests -DtestsThreadCount=8 clean verify
```

To run just unit tests, which do not require S3 connectivity or AWS credentials,
use any of the above invocations, but switch the goal to `test` instead of
`verify`.

```bash
mvn clean test

mvn -Dparallel-tests clean test

mvn -Dparallel-tests -DtestsThreadCount=8 clean test
```

To run only a specific named subset of tests, pass the `test` property for unit
tests or the `it.test` property for integration tests.

```bash
mvn clean test -Dtest=TestS3AInputPolicies

mvn clean verify -Dit.test=ITestS3AFileContextStatistics -Dtest=none

mvn clean verify -Dtest=TestS3A* -Dit.test=ITestS3A*
```

Note that when running a specific subset of tests, the patterns passed in `test`
and `it.test` override the configuration of which tests need to run in isolation
in a separate serial phase (mentioned above).  This can cause unpredictable
results, so the recommendation is to avoid passing `parallel-tests` in
combination with `test` or `it.test`.  If you know that you are specifying only
tests that can run safely in parallel, then it will work.  For wide patterns,
like `ITestS3A*` shown above, it may cause unpredictable test failures.

### <a name="regions"></a> Testing against different regions

S3A can connect to different regions —the tests support this. Simply
define the target region in `auth-keys.xml`.

```xml
<property>
  <name>fs.s3a.endpoint</name>
  <value>s3.eu-central-1.amazonaws.com</value>
</property>
```

Alternatively you can use endpoints defined in [core-site.xml](../../../../test/resources/core-site.xml).

```xml
<property>
  <name>fs.s3a.endpoint</name>
  <value>${frankfurt.endpoint}</value>
</property>
```

This is used for all tests expect for scale tests using a Public CSV.gz file
(see below)

### <a name="csv"></a> CSV Data Tests

The `TestS3AInputStreamPerformance` tests require read access to a multi-MB
text file. The default file for these tests is one published by amazon,
[s3a://landsat-pds.s3.amazonaws.com/scene_list.gz](http://landsat-pds.s3.amazonaws.com/scene_list.gz).
This is a gzipped CSV index of other files which amazon serves for open use.

The path to this object is set in the option `fs.s3a.scale.test.csvfile`,

```xml
<property>
  <name>fs.s3a.scale.test.csvfile</name>
  <value>s3a://landsat-pds/scene_list.gz</value>
</property>
```

1. If the option is not overridden, the default value is used. This
is hosted in Amazon's US-east datacenter.
1. If `fs.s3a.scale.test.csvfile` is empty, tests which require it will be skipped.
1. If the data cannot be read for any reason then the test will fail.
1. If the property is set to a different path, then that data must be readable
and "sufficiently" large.

(the reason the space or newline is needed is to add "an empty entry"; an empty
`<value/>` would be considered undefined and pick up the default)

Of using a test file in an S3 region requiring a different endpoint value
set in `fs.s3a.endpoint`, a bucket-specific endpoint must be defined.
For the default test dataset, hosted in the `landsat-pds` bucket, this is:

```xml
<property>
  <name>fs.s3a.bucket.landsat-pds.endpoint</name>
  <value>s3.amazonaws.com</value>
  <description>The endpoint for s3a://landsat-pds URLs</description>
</property>
```

### <a name="csv"></a> Testing Access Point Integration
S3a supports using Access Point ARNs to access data in S3. If you think your changes affect VPC
integration, request signing, ARN manipulation, or any code path that deals with the actual
sending and retrieving of data to/from S3, make sure you run the entire integration test suite with
this feature enabled.

Check out [our documentation](./index.html#accesspoints) for steps on how to enable this feature. To
create access points for your S3 bucket you can use the AWS Console or CLI.

## <a name="reporting"></a> Viewing Integration Test Reports


Integration test results and logs are stored in `target/failsafe-reports/`.
An HTML report can be generated during site generation, or with the `surefire-report`
plugin:

```bash
mvn surefire-report:failsafe-report-only
```
## <a name="versioning"></a> Testing Versioned Stores

Some tests (specifically some in `ITestS3ARemoteFileChanged`) require
a versioned bucket for full test coverage as well as S3Guard being enabled.

To enable versioning in a bucket.

1. In the AWS S3 Management console find and select the bucket.
1. In the Properties "tab", set it as versioned.
1. <i>Important</i> Create a lifecycle rule to automatically clean up old versions
after 24h. This avoids running up bills for objects which tests runs create and
then delete.
1. Run the tests again.

Once a bucket is converted to being versioned, it cannot be converted back
to being unversioned.


## <a name="marker"></a> Testing Different Marker Retention Policy

Hadoop supports [different policies for directory marker retention](directory_markers.html)
-essentially the classic "delete" and the higher-performance "keep" options; "authoritative"
is just "keep" restricted to a part of the bucket.

Example: test with `markers=delete`

```
mvn verify -Dparallel-tests -DtestsThreadCount=4 -Dmarkers=delete
```

Example: test with `markers=keep`

```
mvn verify -Dparallel-tests -DtestsThreadCount=4 -Dmarkers=keep
```

Example: test with `markers=authoritative`

```
mvn verify -Dparallel-tests -DtestsThreadCount=4 -Dmarkers=authoritative
```

This final option is of limited use unless paths in the bucket have actually been configured to be
of mixed status; unless anything is set up then the outcome should equal that of "delete"

### Enabling auditing of markers

To enable an audit of the output directory of every test suite,
enable the option `fs.s3a.directory.marker.audit`

```
-Dfs.s3a.directory.marker.audit=true
```

When set, if the marker policy is to delete markers under the test output directory, then
the marker tool audit command will be run. This will fail if a marker was found.

This adds extra overhead to every operation, but helps verify that the connector is
not keeping markers where it needs to be deleting them -and hence backwards compatibility
is maintained.

## <a name="scale"></a> Scale Tests

There are a set of tests designed to measure the scalability and performance
at scale of the S3A tests, *Scale Tests*. Tests include: creating
and traversing directory trees, uploading large files, renaming them,
deleting them, seeking through the files, performing random IO, and others.
This makes them a foundational part of the benchmarking.

By their very nature they are slow. And, as their execution time is often
limited by bandwidth between the computer running the tests and the S3 endpoint,
parallel execution does not speed these tests up.

***Note: Running scale tests with `-Ds3guard` and `-Ddynamo` requires that
you use a private, testing-only DynamoDB table.*** The tests do disruptive
things such as deleting metadata and setting the provisioned throughput
to very low values.

### <a name="enabling-scale"></a> Enabling the Scale Tests

The tests are enabled if the `scale` property is set in the maven build
this can be done regardless of whether or not the parallel test profile
is used

```bash
mvn verify -Dscale

mvn verify -Dparallel-tests -Dscale -DtestsThreadCount=8
```

The most bandwidth intensive tests (those which upload data) always run
sequentially; those which are slow due to HTTPS setup costs or server-side
actions are included in the set of parallelized tests.


### <a name="tuning_scale"></a> Tuning scale options from Maven


Some of the tests can be tuned from the maven build or from the
configuration file used to run the tests.

```bash
mvn verify -Dparallel-tests -Dscale -DtestsThreadCount=8 -Dfs.s3a.scale.test.huge.filesize=128M
```

The algorithm is

1. The value is queried from the configuration file, using a default value if
it is not set.
1. The value is queried from the JVM System Properties, where it is passed
down by maven.
1. If the system property is null, an empty string, or it has the value `unset`,
then the configuration value is used. The `unset` option is used to
[work round a quirk in maven property propagation](http://stackoverflow.com/questions/7773134/null-versus-empty-arguments-in-maven).

Only a few properties can be set this way; more will be added.

| Property | Meaning |
|-----------|-------------|
| `fs.s3a.scale.test.timeout`| Timeout in seconds for scale tests |
| `fs.s3a.scale.test.huge.filesize`| Size for huge file uploads |
| `fs.s3a.scale.test.huge.huge.partitionsize`| Size for partitions in huge file uploads |

The file and partition sizes are numeric values with a k/m/g/t/p suffix depending
on the desired size. For example: 128M, 128m, 2G, 2G, 4T or even 1P.

### <a name="scale-config"></a> Scale test configuration options

Some scale tests perform multiple operations (such as creating many directories).

The exact number of operations to perform is configurable in the option
`scale.test.operation.count`

```xml
<property>
  <name>scale.test.operation.count</name>
  <value>10</value>
</property>
```

Larger values generate more load, and are recommended when testing locally,
or in batch runs.

Smaller values results in faster test runs, especially when the object
store is a long way away.

Operations which work on directories have a separate option: this controls
the width and depth of tests creating recursive directories. Larger
values create exponentially more directories, with consequent performance
impact.

```xml
<property>
  <name>scale.test.directory.count</name>
  <value>2</value>
</property>
```

DistCp tests targeting S3A support a configurable file size.  The default is
10 MB, but the configuration value is expressed in KB so that it can be tuned
smaller to achieve faster test runs.

```xml
<property>
  <name>scale.test.distcp.file.size.kb</name>
  <value>10240</value>
</property>
```

S3A specific scale test properties are

*`fs.s3a.scale.test.huge.filesize`: size in MB for "Huge file tests".*

The Huge File tests validate S3A's ability to handle large files —the property
`fs.s3a.scale.test.huge.filesize` declares the file size to use.

```xml
<property>
  <name>fs.s3a.scale.test.huge.filesize</name>
  <value>200M</value>
</property>
```

Amazon S3 handles files larger than 5GB differently than smaller ones.
Setting the huge filesize to a number greater than that) validates support
for huge files.

```xml
<property>
  <name>fs.s3a.scale.test.huge.filesize</name>
  <value>6G</value>
</property>
```

Tests at this scale are slow: they are best executed from hosts running in
the cloud infrastructure where the S3 endpoint is based.
Otherwise, set a large timeout in `fs.s3a.scale.test.timeout`

```xml
<property>
  <name>fs.s3a.scale.test.timeout</name>
  <value>432000</value>
</property>
```

The tests are executed in an order to only clean up created files after
the end of all the tests. If the tests are interrupted, the test data will remain.

## <a name="alternate_s3"></a> Load tests.

Some are designed to overload AWS services with more
requests per second than an AWS account is permitted.

The operation of these test maybe observable to other users of the same
account -especially if they are working in the AWS region to which the
tests are targeted.

There may also run up larger bills.

These tests all have the prefix `ILoadTest`

They do not run automatically: they must be explicitly run from the command line or an IDE.

Look in the source for these and reads the Javadocs before executing.

## <a name="alternate_s3"></a> Testing against non AWS S3 endpoints.

The S3A filesystem is designed to work with storage endpoints which implement
the S3 protocols to the extent that the amazon S3 SDK is capable of talking
to it. We encourage testing against other filesystems and submissions of patches
which address issues. In particular, we encourage testing of Hadoop release
candidates, as these third-party endpoints get even less testing than the
S3 endpoint itself.


### Disabling the encryption tests

If the endpoint doesn't support server-side-encryption, these will fail. They
can be turned off.

```xml
<property>
  <name>test.fs.s3a.encryption.enabled</name>
  <value>false</value>
</property>
```

Encryption is only used for those specific test suites with `Encryption` in
their classname.

### Configuring the CSV file read tests**

To test on alternate infrastructures supporting
the same APIs, the option `fs.s3a.scale.test.csvfile` must either be
set to " ", or an object of at least 10MB is uploaded to the object store, and
the `fs.s3a.scale.test.csvfile` option set to its path.

```xml
<property>
  <name>fs.s3a.scale.test.csvfile</name>
  <value> </value>
</property>
```

(yes, the space is necessary. The Hadoop `Configuration` class treats an empty
value as "do not override the default").

### Turning off S3 Select

The S3 select tests are skipped when the S3 endpoint doesn't support S3 Select.

```xml
<property>
  <name>fs.s3a.select.enabled</name>
  <value>false</value>
</property>
```

If your endpoint doesn't support that feature, this option should be in
your `core-site.xml` file, so that trying to use S3 select fails fast with
a meaningful error ("S3 Select not supported") rather than a generic Bad Request
exception.


### Testing Session Credentials

Some tests requests a session credentials and assumed role credentials from the
AWS Secure Token Service, then use them to authenticate with S3 either directly
or via delegation tokens.

If an S3 implementation does not support STS, then these functional test
cases must be disabled:

```xml
<property>
  <name>test.fs.s3a.sts.enabled</name>
  <value>false</value>
</property>

```
These tests request a temporary set of credentials from the STS service endpoint.
An alternate endpoint may be defined in `fs.s3a.assumed.role.sts.endpoint`.
If this is set, a delegation token region must also be defined:
in `fs.s3a.assumed.role.sts.endpoint.region`.
This is useful not just for testing alternative infrastructures,
but to reduce latency on tests executed away from the central
service.

```xml
<property>
  <name>fs.s3a.delegation.token.endpoint</name>
  <value>fs.s3a.assumed.role.sts.endpoint</value>
</property>
<property>
  <name>fs.s3a.assumed.role.sts.endpoint.region</name>
  <value>eu-west-2</value>
</property>
```
The default is ""; meaning "use the amazon default endpoint" (`sts.amazonaws.com`).

Consult the [AWS documentation](https://docs.aws.amazon.com/general/latest/gr/rande.html#sts_region)
for the full list of locations.

## <a name="debugging"></a> Debugging Test failures

Logging at debug level is the standard way to provide more diagnostics output;
after setting this rerun the tests

```properties
log4j.logger.org.apache.hadoop.fs.s3a=DEBUG
```

There are also some logging options for debug logging of the AWS client
```properties
log4j.logger.com.amazonaws=DEBUG
log4j.logger.com.amazonaws.http.conn.ssl=INFO
log4j.logger.com.amazonaws.internal=INFO
```

There is also the option of enabling logging on a bucket; this could perhaps
be used to diagnose problems from that end. This isn't something actively
used, but remains an option. If you are forced to debug this way, consider
setting the `fs.s3a.user.agent.prefix` to a unique prefix for a specific
test run, which will enable the specific log entries to be more easily
located.

## <a name="new_tests"></a> Adding new tests

New tests are always welcome. Bear in mind that we need to keep costs
and test time down, which is done by

* Not duplicating tests.
* Being efficient in your use of Hadoop API calls.
* Isolating large/slow tests into the "scale" test group.
* Designing all tests to execute in parallel (where possible).
* Adding new probes and predicates into existing tests, albeit carefully.

*No duplication*: if an operation is tested elsewhere, don't repeat it. This
applies as much for metadata operations as it does for bulk IO. If a new
test case is added which completely obsoletes an existing test, it is OK
to cut the previous one —after showing that coverage is not worsened.

*Efficient*: prefer the `getFileStatus()` and examining the results, rather than
call to `exists()`, `isFile()`, etc.

*Isolating Scale tests*. Any S3A test doing large amounts of IO MUST extend the
class `S3AScaleTestBase`, so only running if `scale` is defined on a build,
supporting test timeouts configurable by the user. Scale tests should also
support configurability as to the actual size of objects/number of operations,
so that behavior at different scale can be verified.

*Designed for parallel execution*. A key need here is for each test suite to work
on isolated parts of the filesystem. Subclasses of `AbstractS3ATestBase`
SHOULD use the `path()` method, with a base path of the test suite name, to
build isolated paths. Tests MUST NOT assume that they have exclusive access
to a bucket.

*Extending existing tests where appropriate*. This recommendation goes
against normal testing best practise of "test one thing per method".
Because it is so slow to create directory trees or upload large files, we do
not have that luxury. All the tests against real S3 endpoints are integration
tests where sharing test setup and teardown saves time and money.

A standard way to do this is to extend existing tests with some extra predicates,
rather than write new tests. When doing this, make sure that the new predicates
fail with meaningful diagnostics, so any new problems can be easily debugged
from test logs.

***Effective use of FS instances during S3A integration tests.*** Tests using
`FileSystem` instances are fastest if they can recycle the existing FS
instance from the same JVM.

If you do that, you MUST NOT close or do unique configuration on them.
If you want a guarantee of 100% isolation or an instance with unique config,
create a new instance which you MUST close in the teardown to avoid leakage
of resources.

Do NOT add `FileSystem` instances manually
(with e.g `org.apache.hadoop.fs.FileSystem#addFileSystemForTesting`) to the
cache that will be modified or closed during the test runs. This can cause
other tests to fail when using the same modified or closed FS instance.
For more details see HADOOP-15819.

## <a name="requirements"></a> Requirements of new Tests

This is what we expect from new tests; they're an extension of the normal
Hadoop requirements, based on the need to work with remote servers whose
use requires the presence of secret credentials, where tests may be slow,
and where finding out why something failed from nothing but the test output
is critical.

### Subclasses Existing Shared Base Classes

Extend `AbstractS3ATestBase` or `AbstractSTestS3AHugeFiles` unless justifiable.
These set things up for testing against the object stores, provide good threadnames,
help generate isolated paths, and for `AbstractSTestS3AHugeFiles` subclasses,
only run if `-Dscale` is set.

Key features of `AbstractS3ATestBase`

* `getFileSystem()` returns the S3A Filesystem bonded to the contract test Filesystem
defined in `fs.s3a.contract.test`
* will automatically skip all tests if that URL is unset.
* Extends  `AbstractFSContractTestBase` and `Assert` for all their methods.

Having shared base classes may help reduce future maintenance too. Please
use them/

### Secure

Don't ever log credentials. The credential tests go out of their way to
not provide meaningful logs or assertion messages precisely to avoid this.

### Efficient of Time and Money

This means efficient in test setup/teardown, and, ideally, making use of
existing public datasets to save setup time and tester cost.

Strategies of particular note are:

1. `ITestS3ADirectoryPerformance`: a single test case sets up the directory
tree then performs different list operations, measuring the time taken.
1. `AbstractSTestS3AHugeFiles`: marks the test suite as
`@FixMethodOrder(MethodSorters.NAME_ASCENDING)` then orders the test cases such
that each test case expects the previous test to have completed (here: uploaded a file,
renamed a file, ...). This provides for independent tests in the reports, yet still
permits an ordered sequence of operations. Do note the use of `Assume.assume()`
to detect when the preconditions for a single test case are not met, hence,
the tests become skipped, rather than fail with a trace which is really a false alarm.

The ordered test case mechanism of `AbstractSTestS3AHugeFiles` is probably
the most elegant way of chaining test setup/teardown.

Regarding reusing existing data, we tend to use the landsat archive of
AWS US-East for our testing of input stream operations. This doesn't work
against other regions, or with third party S3 implementations. Thus the
URL can be overridden for testing elsewhere.


### Works With Other S3 Endpoints

Don't assume AWS S3 US-East only, do allow for working with external S3 implementations.
Those may be behind the latest S3 API features, not support encryption, session
APIs, etc.

They won't have the same CSV test files as some of the input tests rely on.
Look at `ITestS3AInputStreamPerformance` to see how tests can be written
to support the declaration of a specific large test file on alternate filesystems.


### Works Over Long-haul Links

As well as making file size and operation counts scalable, this includes
making test timeouts adequate. The Scale tests make this configurable; it's
hard coded to ten minutes in `AbstractS3ATestBase()`; subclasses can
change this by overriding `getTestTimeoutMillis()`.

Equally importantly: support proxies, as some testers need them.


### Provides Diagnostics and timing information

1. Give threads useful names.
1. Create logs, log things. Know that the `S3AFileSystem` and its input
and output streams *all* provide useful statistics in their {{toString()}}
calls; logging them is useful on its own.
1. you can use `AbstractS3ATestBase.describe(format-stringm, args)` here.; it
adds some newlines so as to be easier to spot.
1. Use `ContractTestUtils.NanoTimer` to measure the duration of operations,
and log the output.

### Fails Meaningfully

The `ContractTestUtils` class contains a whole set of assertions for making
statements about the expected state of a filesystem, e.g.
`assertPathExists(FS, path)`, `assertPathDoesNotExists(FS, path)`, and others.
These do their best to provide meaningful diagnostics on failures (e.g. directory
listings, file status, ...), so help make failures easier to understand.

At the very least, do not use `assertTrue()` or `assertFalse()` without
including error messages.

### Sets up its filesystem and checks for those settings

Tests can overrun `createConfiguration()` to add new options to the configuration
file for the S3A Filesystem instance used in their tests.

However, filesystem caching may mean that a test suite may get a cached
instance created with an different configuration. For tests which don't need
specific configurations caching is good: it reduces test setup time.

For those tests which do need unique options (encryption, magic files),
things can break, and they will do so in hard-to-replicate ways.

Use `S3ATestUtils.disableFilesystemCaching(conf)` to disable caching when
modifying the config. As an example from `AbstractTestS3AEncryption`:

```java
@Override
protected Configuration createConfiguration() {
  Configuration conf = super.createConfiguration();
  S3ATestUtils.disableFilesystemCaching(conf);
  conf.set(Constants.SERVER_SIDE_ENCRYPTION_ALGORITHM,
          getSSEAlgorithm().getMethod());
  return conf;
}
```

Then verify in the setup method or test cases that their filesystem actually has
the desired feature (`fs.getConf().getProperty(...)`). This not only
catches filesystem reuse problems, it catches the situation where the
filesystem configuration in `auth-keys.xml` has explicit per-bucket settings
which override the test suite's general option settings.

### Cleans Up Afterwards

Keeps costs down.

1. Do not only cleanup if a test case completes successfully; test suite
teardown must do it.
1. That teardown code must check for the filesystem and other fields being
null before the cleanup. Why? If test setup fails, the teardown methods still
get called.

### Works Reliably

We really appreciate this &mdash; you will too.

### Runs in parallel unless this is unworkable.

Tests must be designed to run in parallel with other tests, all working
with the same shared S3 bucket. This means

* Uses relative and JVM-fork-unique paths provided by the method
  `AbstractFSContractTestBase.path(String filepath)`.
* Doesn't manipulate the root directory or make assertions about its contents
(for example: delete its contents and assert that it is now empty).
* Doesn't have a specific requirement of all active clients of the bucket
(example: SSE-C tests which require all files, even directory markers,
to be encrypted with the same key).
* Doesn't use so much bandwidth that all other tests will be starved of IO and
start timing out (e.g. the scale tests).

Tests such as these can only be run as sequential tests. When adding one,
exclude it in the POM file. from the parallel failsafe run and add to the
sequential one afterwards. The IO heavy ones must also be subclasses of
`S3AScaleTestBase` and so only run if the system/maven property
`fs.s3a.scale.test.enabled` is true.

## Individual test cases can be run in an IDE

This is invaluable for debugging test failures.

How to set test options in your hadoop configuration rather
than on the maven command line:

As an example let's assume you want to run S3Guard integration tests using IDE.
Please add the following properties in
`hadoop-tools/hadoop-aws/src/test/resources/auth-keys.xml` file.
 Local configuration is stored in auth-keys.xml. The changes to this file won't be committed,
 so it's safe to store local config here.
```xml
<property>
  <name>fs.s3a.s3guard.test.enabled</name>
  <value>true</value>
</property>
```

```xml
<property>
  <name>fs.s3a.s3guard.test.implementation</name>
  <value>dynamo</value>
</property>
```

Warning : Although this is easier for IDE debugging setups, once you do this,
you cannot change configurations on the mvn command line, such as testing without s3guard.

### Keeping AWS Costs down

Most of the base S3 tests are designed to use public AWS data
(the landsat-pds bucket) for read IO, so you don't have to pay for bytes
downloaded or long term storage costs. The scale tests do work with more data
so will cost more as well as generally take more time to execute.

You are however billed for

1. Data left in S3 after test runs.
2. DynamoDB capacity reserved by S3Guard tables.
3. HTTP operations on files (HEAD, LIST, GET).
4. In-progress multipart uploads from bulk IO or S3A committer tests.
5. Encryption/decryption using AWS KMS keys.

The GET/decrypt costs are incurred on each partial read of a file,
so random IO can cost more than sequential IO; the speedup of queries with
columnar data usually justifies this.

The DynamoDB costs come from the number of entries stores and the allocated capacity.

How to keep costs down

* Don't run the scale tests with large datasets; keep `fs.s3a.scale.test.huge.filesize` unset, or a few MB (minimum: 5).
* Remove all files in the filesystem. The root tests usually do this, but
it can be manually done:

      hadoop fs -rm -r -f -skipTrash s3a://test-bucket/\*
* Abort all outstanding uploads:

      hadoop s3guard uploads -abort -force s3a://test-bucket/
* If you don't need it, destroy the S3Guard DDB table.

      hadoop s3guard destroy s3a://test-bucket/

The S3Guard tests will automatically create the Dynamo DB table in runs with
`-Ds3guard -Ddynamo` set; default capacity of these buckets
tests is very small; it keeps costs down at the expense of IO performance
and, for test runs in or near the S3/DDB stores, throttling events.

If you want to manage capacity, use `s3guard set-capacity` to increase it
(performance) or decrease it (costs).
For remote `hadoop-aws` test runs, the read/write capacities of "0" each should suffice;
increase it if parallel test run logs warn of throttling.

## <a name="tips"></a> Tips

### How to keep your credentials really safe

Although the `auth-keys.xml` file is marked as ignored in git and subversion,
it is still in your source tree, and there's always that risk that it may
creep out.

You can avoid this by keeping your keys outside the source tree and
using an absolute XInclude reference to it.

```xml
<configuration>

  <include xmlns="http://www.w3.org/2001/XInclude"
    href="file:///users/ubuntu/.auth-keys.xml" />

</configuration>
```

## <a name="failure-injection"></a>Failure Injection

**Warning do not enable any type of failure injection in production.  The
following settings are for testing only.**

One of the challenges with S3A integration tests is the fact that S3 was an
eventually-consistent storage system. To simulate inconsistencies more
frequently than they would normally surface, S3A supports a shim layer on top of the `AmazonS3Client`
class which artificially delays certain paths from appearing in listings.
This is implemented in the class `InconsistentAmazonS3Client`.

Now that S3 is consistent, injecting failures during integration and
functional testing is less important.
There's no need to enable it to verify that S3Guard can recover
from consistencies, given that in production such consistencies
will never surface.

## Simulating List Inconsistencies

### Enabling the InconsistentAmazonS3CClient

There are two ways of enabling the `InconsistentAmazonS3Client`: at
config-time, or programmatically. For an example of programmatic test usage,
see `ITestS3GuardListConsistency`.

To enable the fault-injecting client via configuration, switch the
S3A client to use the "Inconsistent S3 Client Factory" when connecting to
S3:

```xml
<property>
  <name>fs.s3a.s3.client.factory.impl</name>
  <value>org.apache.hadoop.fs.s3a.InconsistentS3ClientFactory</value>
</property>
```

The inconsistent client works by:

1. Choosing which objects will be "inconsistent" at the time the object is
created or deleted.
2. When `listObjects()` is called, any keys that we have marked as
inconsistent above will not be returned in the results (until the
configured delay has elapsed). Similarly, deleted items may be *added* to
missing results to delay the visibility of the delete.

There are two ways of choosing which keys (filenames) will be affected: By
substring, and by random probability.

```xml
<property>
  <name>fs.s3a.failinject.inconsistency.key.substring</name>
  <value>DELAY_LISTING_ME</value>
</property>

<property>
  <name>fs.s3a.failinject.inconsistency.probability</name>
  <value>1.0</value>
</property>
```

By default, any object which has the substring "DELAY_LISTING_ME" in its key
will subject to delayed visibility. For example, the path
`s3a://my-bucket/test/DELAY_LISTING_ME/file.txt` would match this condition.
To match all keys use the value "\*" (a single asterisk). This is a special
value: *We don't support arbitrary wildcards.*

The default probability of delaying an object is 1.0. This means that *all*
keys that match the substring will get delayed visibility. Note that we take
the logical *and* of the two conditions (substring matches *and* probability
random chance occurs). Here are some example configurations:

```
| substring | probability |  behavior                                  |
|-----------|-------------|--------------------------------------------|
|           | 0.001       | An empty <value> tag in .xml config will   |
|           |             | be interpreted as unset and revert to the  |
|           |             | default value, "DELAY_LISTING_ME"          |
|           |             |                                            |
| *         | 0.001       | 1/1000 chance of *any* key being delayed.  |
|           |             |                                            |
| delay     | 0.01        | 1/100 chance of any key containing "delay" |
|           |             |                                            |
| delay     | 1.0         | All keys containing substring "delay" ..   |
```

You can also configure how long you want the delay in visibility to last.
The default is 5000 milliseconds (five seconds).

```xml
<property>
  <name>fs.s3a.failinject.inconsistency.msec</name>
  <value>5000</value>
</property>
```


### Limitations of Inconsistency Injection

Although `InconsistentAmazonS3Client` can delay the visibility of an object
or parent directory, it does not prevent the key of that object from
appearing in all prefix searches. For example, if we create the following
object with the default configuration above, in an otherwise empty bucket:

```
s3a://bucket/a/b/c/DELAY_LISTING_ME
```

Then the following paths will still be visible as directories (ignoring
possible real-world inconsistencies):

```
s3a://bucket/a
s3a://bucket/a/b
```

Whereas `getFileStatus()` on the following *will* be subject to delayed
visibility (`FileNotFoundException` until delay has elapsed):

```
s3a://bucket/a/b/c
s3a://bucket/a/b/c/DELAY_LISTING_ME
```

In real-life S3 inconsistency, however, we expect that all the above paths
(including `a` and `b`) will be subject to delayed visibility.

### Using the `InconsistentAmazonS3CClient` in downstream integration tests

The inconsistent client is shipped in the `hadoop-aws` JAR, so it can
be used in applications which work with S3 to see how they handle
inconsistent directory listings.

## <a name="s3guard"></a> Testing S3Guard

[S3Guard](./s3guard.html) is an extension to S3A which added consistent metadata
listings to the S3A client. 

It has not been needed for applications to work safely with AWS S3 since November
2020. However, it is currently still part of the codebase, and so something which
needs to be tested.

The basic strategy for testing S3Guard correctness consists of:

1. MetadataStore Contract tests.

    The MetadataStore contract tests are inspired by the Hadoop FileSystem and
    `FileContext` contract tests.  Each implementation of the `MetadataStore` interface
    subclasses the `MetadataStoreTestBase` class and customizes it to initialize
    their MetadataStore.  This test ensures that the different implementations
    all satisfy the semantics of the MetadataStore API.

2. Running existing S3A unit and integration tests with S3Guard enabled.

    You can run the S3A integration tests on top of S3Guard by configuring your
    `MetadataStore` in your
    `hadoop-tools/hadoop-aws/src/test/resources/core-site.xml` or
    `hadoop-tools/hadoop-aws/src/test/resources/auth-keys.xml` files.
    Next run the S3A integration tests as outlined in the *Running the Tests* section
    of the [S3A documentation](./index.html)

3. Running fault-injection tests that test S3Guard's consistency features.

    The `ITestS3GuardListConsistency` uses failure injection to ensure
    that list consistency logic is correct even when the underlying storage is
    eventually consistent.

    The integration test adds a shim above the Amazon S3 Client layer that injects
    delays in object visibility.

    All of these tests will be run if you follow the steps listed in step 2 above.

    No charges are incurred for using this store, and its consistency
    guarantees are that of the underlying object store instance. <!-- :) -->

### Testing S3A with S3Guard Enabled

All the S3A tests which work with a private repository can be configured to
run with S3Guard by using the `s3guard` profile. When set, this will run
all the tests with local memory for the metadata set to "non-authoritative" mode.

```bash
mvn -T 1C verify -Dparallel-tests -DtestsThreadCount=6 -Ds3guard
```

When the `s3guard` profile is enabled, following profiles can be specified:

* `dynamo`: use an AWS-hosted DynamoDB table; creating the table if it does
  not exist. You will have to pay the bills for DynamoDB web service.
* `auth`: treat the S3Guard metadata as authoritative.

```bash
mvn -T 1C verify -Dparallel-tests -DtestsThreadCount=6 -Ds3guard -Ddynamo -Dauth
```

When experimenting with options, it is usually best to run a single test suite
at a time until the operations appear to be working.

```bash
mvn -T 1C verify -Dtest=skip -Dit.test=ITestS3AMiscOperations -Ds3guard -Ddynamo
```

### Notes

1. If the `s3guard` profile is not set, then the S3Guard properties are those
of the test configuration set in s3a contract xml file or `auth-keys.xml`

If the `s3guard` profile *is* set:
1. The S3Guard options from maven (the dynamo and authoritative flags)
  overwrite any previously set in the configuration files.
1. DynamoDB will be configured to create any missing tables.
1. When using DynamoDB and running `ITestDynamoDBMetadataStore`,
  the `fs.s3a.s3guard.ddb.test.table`
property MUST be configured, and the name of that table MUST be different
 than what is used for `fs.s3a.s3guard.ddb.table`. The test table is destroyed
 and modified multiple times during the test.
 1. Several of the tests create and destroy DynamoDB tables. The table names
 are prefixed with the value defined by
 `fs.s3a.s3guard.test.dynamo.table.prefix` (default="s3guard.test."). The user
 executing the tests will need sufficient privilege to create and destroy such
 tables. If the tests abort uncleanly, these tables may be left behind,
 incurring AWS charges.


### How to Dump the Table and Metastore State

There's an unstable entry point to list the contents of a table
and S3 filesystem ot a set of Tab Separated Value files:

```
hadoop org.apache.hadoop.fs.s3a.s3guard.DumpS3GuardDynamoTable s3a://bucket/ dir/out
```

This generates a set of files prefixed `dir/out-` with different views of the
world which can then be viewed on the command line or editor:

```
"type" "deleted" "path" "is_auth_dir" "is_empty_dir" "len" "updated" "updated_s" "last_modified" "last_modified_s" "etag" "version"
"file" "true" "s3a://bucket/fork-0001/test/ITestS3AContractDistCp/testDirectWrite/remote" "false" "UNKNOWN" 0 1562171244451 "Wed Jul 03 17:27:24 BST 2019" 1562171244451 "Wed Jul 03 17:27:24 BST 2019" "" ""
"file" "true" "s3a://bucket/Users/stevel/Projects/hadoop-trunk/hadoop-tools/hadoop-aws/target/test-dir/1/5xlPpalRwv/test/new/newdir/file1" "false" "UNKNOWN" 0 1562171518435 "Wed Jul 03 17:31:58 BST 2019" 1562171518435 "Wed Jul 03 17:31:58 BST 2019" "" ""
"file" "true" "s3a://bucket/Users/stevel/Projects/hadoop-trunk/hadoop-tools/hadoop-aws/target/test-dir/1/5xlPpalRwv/test/new/newdir/subdir" "false" "UNKNOWN" 0 1562171518535 "Wed Jul 03 17:31:58 BST 2019" 1562171518535 "Wed Jul 03 17:31:58 BST 2019" "" ""
"file" "true" "s3a://bucket/test/DELAY_LISTING_ME/testMRJob" "false" "UNKNOWN" 0 1562172036299 "Wed Jul 03 17:40:36 BST 2019" 1562172036299 "Wed Jul 03 17:40:36 BST 2019" "" ""
```

This is unstable: the output format may change without warning.
To understand the meaning of the fields, consult the documentation.
They are, currently:

| field | meaning | source |
|-------|---------| -------|
| `type` | type | filestatus |
| `deleted` | tombstone marker | metadata |
| `path` | path of an entry | filestatus |
| `is_auth_dir` | directory entry authoritative status | metadata |
| `is_empty_dir` | does the entry represent an empty directory | metadata |
| `len` | file length | filestatus |
| `last_modified` | file status last modified | filestatus |
| `last_modified_s` | file status last modified as string | filestatus |
| `updated` | time (millis) metadata was updated | metadata |
| `updated_s` | updated time as a string | metadata |
| `etag` | any etag | filestatus |
| `version` |  any version| filestatus |

Files generated

| suffix        | content |
|---------------|---------|
| `-scan.csv`   | Full scan/dump of the metastore |
| `-store.csv`  | Recursive walk through the metastore |
| `-tree.csv`   | Treewalk through filesystem `listStatus("/")` calls |
| `-flat.csv`   | Flat listing through filesystem `listFiles("/", recursive)` |
| `-s3.csv`     | Dump of the S3 Store *only* |
| `-scan-2.csv` | Scan of the store after the previous operations |

Why the two scan entries? The S3A listing and treewalk operations
may add new entries to the metastore/DynamoDB table.

Note 1: this is unstable; entry list and meaning may change, sorting of output,
the listing algorithm, representation of types, etc. It's expected
uses are: diagnostics, support calls and helping us developers
work out what we've just broken.

Note 2: This *is* safe to use against an active store; the tables may appear
to be inconsistent due to changes taking place during the dump sequence.

### Resetting the Metastore: `PurgeS3GuardDynamoTable`

The `PurgeS3GuardDynamoTable` entry point
`org.apache.hadoop.fs.s3a.s3guard.PurgeS3GuardDynamoTable` can
list all entries in a store for a specific filesystem, and delete them.
It *only* deletes those entries in the store for that specific filesystem,
even if the store is shared.

```bash
hadoop org.apache.hadoop.fs.s3a.s3guard.PurgeS3GuardDynamoTable \
  -force s3a://bucket/
```

Without the `-force` option the table is scanned, but no entries deleted;
with it then all entries for that filesystem are deleted.
No attempt is made to order the deletion; while the operation is under way
the store is not fully connected (i.e. there may be entries whose parent has
already been deleted).

Needless to say: *it is not safe to use this against a table in active use.*

### Scale Testing MetadataStore Directly

There are some scale tests that exercise Metadata Store implementations
directly. These ensure that S3Guard is are robust to things like DynamoDB
throttling, and compare performance for different implementations. These
are included in the scale tests executed when `-Dscale` is passed to
the maven command line.

The two S3Guard scale tests are `ITestDynamoDBMetadataStoreScale` and
`ITestLocalMetadataStoreScale`.

To run these tests, your DynamoDB table needs to be of limited capacity;
the values in `ITestDynamoDBMetadataStoreScale` currently require a read capacity
of 10 or less. a write capacity of 15 or more.

The following settings allow us to run `ITestDynamoDBMetadataStoreScale` with
artificially low read and write capacity provisioned, so we can judge the
effects of being throttled by the DynamoDB service:

```xml
<property>
  <name>scale.test.operation.count</name>
  <value>10</value>
</property>
<property>
  <name>scale.test.directory.count</name>
  <value>3</value>
</property>
<property>
  <name>fs.s3a.scale.test.enabled</name>
  <value>true</value>
</property>
<property>
  <name>fs.s3a.s3guard.ddb.table</name>
  <value>my-scale-test</value>
</property>
<property>
  <name>fs.s3a.s3guard.ddb.table.create</name>
  <value>true</value>
</property>
<property>
  <name>fs.s3a.s3guard.ddb.table.capacity.read</name>
  <value>5</value>
</property>
<property>
  <name>fs.s3a.s3guard.ddb.table.capacity.write</name>
  <value>5</value>
</property>
```

These tests verify that the invoked operations can trigger retries in the
S3Guard code, rather than just in the AWS SDK level, so showing that if
SDK operations fail, they get retried. They also verify that the filesystem
statistics are updated to record that throttling took place.

*Do not panic if these tests fail to detect throttling!*

These tests are unreliable as they need certain conditions to be met
to repeatedly fail:

1. You must have a low-enough latency connection to the DynamoDB store that,
for the capacity allocated, you can overload it.
1. The AWS Console can give you a view of what is happening here.
1. Running a single test on its own is less likely to trigger an overload
than trying to run the whole test suite.
1. And running the test suite more than once, back-to-back, can also help
overload the cluster.
1. Stepping through with a debugger will reduce load, so may not trigger
failures.

If the tests fail, it *probably* just means you aren't putting enough load
on the table.

These tests do not verify that the entire set of DynamoDB calls made
during the use of a S3Guarded S3A filesystem are wrapped by retry logic.

*The best way to verify resilience is to run the entire `hadoop-aws` test suite,
or even a real application, with throttling enabled.

### Testing encrypted DynamoDB tables

By default, a DynamoDB table is encrypted using AWS owned customer master key
(CMK). You can enable server side encryption (SSE) using AWS managed CMK or
customer managed CMK in KMS before running the S3Guard tests.
1. To enable AWS managed CMK, set the config
`fs.s3a.s3guard.ddb.table.sse.enabled` to true in `auth-keys.xml`.
1. To enable customer managed CMK, you need to create a KMS key and set the
config in `auth-keys.xml`. The value can be the key ARN or alias. Example:
```
  <property>
    <name>fs.s3a.s3guard.ddb.table.sse.enabled</name>
    <value>true</value>
  </property>
  <property>
    <name>fs.s3a.s3guard.ddb.table.sse.cmk</name>
    <value>arn:aws:kms:us-west-2:360379543683:key/071a86ff-8881-4ba0-9230-95af6d01ca01</value>
  </property>
```
For more details about SSE on DynamoDB table, please see [S3Guard doc](./s3guard.html).

### Testing only: Local Metadata Store

There is an in-memory Metadata Store for testing.

```xml
<property>
  <name>fs.s3a.metadatastore.impl</name>
  <value>org.apache.hadoop.fs.s3a.s3guard.LocalMetadataStore</value>
</property>
```

This is not for use in production.

##<a name="assumed_roles"></a> Testing Assumed Roles

Tests for the AWS Assumed Role credential provider require an assumed
role to request.

If this role is not declared in `fs.s3a.assumed.role.arn`,
the tests which require it will be skipped.

The specific tests an Assumed Role ARN is required for are

- `ITestAssumeRole`.
- `ITestRoleDelegationTokens`.
- One of the parameterized test cases in `ITestDelegatedMRJob`.

To run these tests you need:

1. A role in your AWS account will full read and write access rights to
the S3 bucket used in the tests, DynamoDB, for S3Guard, and KMS for any
SSE-KMS tests.

If your bucket is set up by default to use S3Guard, the role must have access
to that service.

1. Your IAM User to have the permissions to "assume" that role.

1. The role ARN must be set in `fs.s3a.assumed.role.arn`.

```xml
<property>
  <name>fs.s3a.assumed.role.arn</name>
  <value>arn:aws:iam::9878543210123:role/role-s3-restricted</value>
</property>
```

The tests assume the role with different subsets of permissions and verify
that the S3A client (mostly) works when the caller has only write access
to part of the directory tree.

You can also run the entire test suite in an assumed role, a more
thorough test, by switching to the credentials provider.

```xml
<property>
  <name>fs.s3a.aws.credentials.provider</name>
  <value>org.apache.hadoop.fs.s3a.auth.AssumedRoleCredentialProvider</value>
</property>
```

The usual credentials needed to log in to the bucket will be used, but now
the credentials used to interact with S3 and DynamoDB will be temporary
role credentials, rather than the full credentials.

## <a name="qualifiying_sdk_updates"></a> Qualifying an AWS SDK Update

Updating the AWS SDK is something which does need to be done regularly,
but is rarely without complications, major or minor.

Assume that the version of the SDK will remain constant for an X.Y release,
excluding security fixes, so it's good to have an update before each release
&mdash; as long as that update works doesn't trigger any regressions.


1. Don't make this a last minute action.
1. The upgrade patch should focus purely on the SDK update, so it can be cherry
picked and reverted easily.
1. Do not mix in an SDK update with any other piece of work, for the same reason.
1. Plan for an afternoon's work, including before/after testing, log analysis
and any manual tests.
1. Make sure all the integration tests are running (including s3guard, ARN, encryption, scale)
  *before you start the upgrade*.
1. Create a JIRA for updating the SDK. Don't include the version (yet),
as it may take a couple of SDK updates before it is ready.
1. Identify the latest AWS SDK [available for download](https://aws.amazon.com/sdk-for-java/).
1. Create a private git branch of trunk for JIRA, and in
  `hadoop-project/pom.xml` update the `aws-java-sdk.version` to the new SDK version.
1. Update AWS SDK versions in NOTICE.txt.
1. Do a clean build and rerun all the `hadoop-aws` tests, with and without the `-Ds3guard -Ddynamo` options.
  This includes the `-Pscale` set, with a role defined for the assumed role tests.
  in `fs.s3a.assumed.role.arn` for testing assumed roles,
  and `fs.s3a.encryption.key` for encryption, for full coverage.
  If you can, scale up the scale tests.
1. Create an Access Point for your bucket (using the AWS Console or CLI), update S3a configuration
to use it ([docs for help](./index.html#accesspoints)) and re-run the `ITest*` integration tests from
your IDE or via maven.
1. Run the `ILoadTest*` load tests from your IDE or via maven through
      `mvn verify -Dtest=skip -Dit.test=ILoadTest\*`  ; look for regressions in performance
      as much as failures.
1. Create the site with `mvn site -DskipTests`; look in `target/site` for the report.
1. Review *every single `-output.txt` file in `hadoop-tools/hadoop-aws/target/failsafe-reports`,
  paying particular attention to
  `org.apache.hadoop.fs.s3a.scale.ITestS3AInputStreamPerformance-output.txt`,
  as that is where changes in stream close/abort logic will surface.
1. Run `mvn install` to install the artifacts, then in
  `hadoop-cloud-storage-project/hadoop-cloud-storage` run
  `mvn dependency:tree -Dverbose > target/dependencies.txt`.
  Examine the `target/dependencies.txt` file to verify that no new
  artifacts have unintentionally been declared as dependencies
  of the shaded `aws-java-sdk-bundle` artifact.
1. Run a full AWS-test suite with S3 client-side encryption enabled by
 setting `fs.s3a.encryption.algorithm` to 'CSE-KMS' and setting up AWS-KMS
  Key ID in `fs.s3a.encryption.key`.

### Basic command line regression testing

We need a run through of the CLI to see if there have been changes there
which cause problems, especially whether new log messages have surfaced,
or whether some packaging change breaks that CLI

It is always interesting when doing this to enable IOStatistics reporting
```xml
<property>
  <name>fs.iostatistics.logging.level</name>
  <value>info</value>
</property>
```

From the root of the project, create a command line release `mvn package -Pdist -DskipTests -Dmaven.javadoc.skip=true  -DskipShade`;

1. Change into the `hadoop-dist/target/hadoop-x.y.z-SNAPSHOT` dir.
1. Copy a `core-site.xml` file into `etc/hadoop`.
1. Set the `HADOOP_OPTIONAL_TOOLS` env var on the command line or `~/.hadoop-env`.

```bash
export HADOOP_OPTIONAL_TOOLS="hadoop-aws"
```

Run some basic s3guard commands as well as file operations.

```bash
export BUCKETNAME=example-bucket-name
export BUCKET=s3a://$BUCKETNAME

bin/hadoop s3guard bucket-info $BUCKET

bin/hadoop s3guard uploads $BUCKET
# repeat twice, once with "no" and once with "yes" as responses
bin/hadoop s3guard uploads -abort $BUCKET

# ---------------------------------------------------
# assuming s3guard is enabled
# if on pay-by-request, expect an error message and exit code of -1
bin/hadoop s3guard set-capacity $BUCKET

# skip for PAY_PER_REQUEST
bin/hadoop s3guard set-capacity -read 15 -write 15 $BUCKET

bin/hadoop s3guard bucket-info -guarded $BUCKET 

bin/hadoop s3guard diff $BUCKET/
bin/hadoop s3guard prune -minutes 10 $BUCKET/
bin/hadoop s3guard import -verbose $BUCKET/
bin/hadoop s3guard authoritative -verbose $BUCKET

# ---------------------------------------------------
# root filesystem operatios
# ---------------------------------------------------

bin/hadoop fs -ls $BUCKET/
# assuming file is not yet created, expect error and status code of 1
bin/hadoop fs -ls $BUCKET/file

# exit code of 0 even when path doesn't exist
bin/hadoop fs -rm -R -f $BUCKET/dir-no-trailing
bin/hadoop fs -rm -R -f $BUCKET/dir-trailing/

# error because it is a directory
bin/hadoop fs -rm $BUCKET/

bin/hadoop fs -touchz $BUCKET/file
# expect I/O error as it is the root directory
bin/hadoop fs -rm -r $BUCKET/

# succeeds
bin/hadoop fs -rm -r $BUCKET/\*

# ---------------------------------------------------
# File operations
# ---------------------------------------------------

bin/hadoop fs -mkdir $BUCKET/dir-no-trailing
# used to fail with S3Guard
bin/hadoop fs -mkdir $BUCKET/dir-trailing/
bin/hadoop fs -touchz $BUCKET/file
bin/hadoop fs -ls $BUCKET/
bin/hadoop fs -mv $BUCKET/file $BUCKET/file2
# expect "No such file or directory"
bin/hadoop fs -stat $BUCKET/file

# expect success
bin/hadoop fs -stat $BUCKET/file2

# expect "file exists"
bin/hadoop fs -mkdir $BUCKET/dir-no-trailing
bin/hadoop fs -mv $BUCKET/file2 $BUCKET/dir-no-trailing
bin/hadoop fs -stat $BUCKET/dir-no-trailing/file2
# treated the same as the file stat
bin/hadoop fs -stat $BUCKET/dir-no-trailing/file2/
bin/hadoop fs -ls $BUCKET/dir-no-trailing/file2/
bin/hadoop fs -ls $BUCKET/dir-no-trailing
# expect a "0" here:
bin/hadoop fs -test -d  $BUCKET/dir-no-trailing ; echo $?
# expect a "1" here:
bin/hadoop fs -test -d  $BUCKET/dir-no-trailing/file2 ; echo $?
# will return NONE unless bucket has checksums enabled
bin/hadoop fs -checksum $BUCKET/dir-no-trailing/file2
# expect "etag" + a long string
bin/hadoop fs -D fs.s3a.etag.checksum.enabled=true -checksum $BUCKET/dir-no-trailing/file2
bin/hadoop fs -expunge -immediate -fs $BUCKET

# ---------------------------------------------------
# Delegation Token support
# ---------------------------------------------------

# failure unless delegation tokens are enabled
bin/hdfs fetchdt --webservice $BUCKET secrets.bin
# success
bin/hdfs fetchdt -D fs.s3a.delegation.token.binding=org.apache.hadoop.fs.s3a.auth.delegation.SessionTokenBinding --webservice $BUCKET secrets.bin
bin/hdfs fetchdt -print secrets.bin

# expect warning "No TokenRenewer defined for token kind S3ADelegationToken/Session"
bin/hdfs fetchdt -renew secrets.bin

# ---------------------------------------------------
# Directory markers
# ---------------------------------------------------

# require success
bin/hadoop s3guard bucket-info -markers aware $BUCKET
# expect failure unless bucket policy is keep
bin/hadoop s3guard bucket-info -markers keep $BUCKET/path

# you may need to set this on a per-bucket basis if you have already been
# playing with options
bin/hadoop s3guard -D fs.s3a.directory.marker.retention=keep bucket-info -markers keep $BUCKET/path
bin/hadoop s3guard -D fs.s3a.bucket.$BUCKETNAME.directory.marker.retention=keep bucket-info -markers keep $BUCKET/path

# expect to see "Directory markers will be kept" messages and status code of "46"
bin/hadoop fs -D fs.s3a.bucket.$BUCKETNAME.directory.marker.retention=keep -mkdir $BUCKET/p1
bin/hadoop fs -D fs.s3a.bucket.$BUCKETNAME.directory.marker.retention=keep -mkdir $BUCKET/p1/p2
bin/hadoop fs -D fs.s3a.bucket.$BUCKETNAME.directory.marker.retention=keep -touchz $BUCKET/p1/p2/file

# expect failure as markers will be found for /p1/ and /p1/p2/
bin/hadoop s3guard markers -audit -verbose $BUCKET

# clean will remove markers
bin/hadoop s3guard markers -clean -verbose $BUCKET

# expect success and exit code of 0
bin/hadoop s3guard markers -audit -verbose $BUCKET

# ---------------------------------------------------
# Copy to from local
# ---------------------------------------------------

time bin/hadoop fs -copyFromLocal -t 10  share/hadoop/tools/lib/*aws*jar $BUCKET/

# expect the iostatistics object_list_request value to be O(directories)
bin/hadoop fs -ls -R $BUCKET/

# expect the iostatistics object_list_request and op_get_content_summary values to be 1
bin/hadoop fs -du -h -s $BUCKET/

mkdir tmp
time bin/hadoop fs -copyToLocal -t 10  $BUCKET/\*aws\* tmp

# ---------------------------------------------------
# S3 Select on Landsat
# ---------------------------------------------------

export LANDSATGZ=s3a://landsat-pds/scene_list.gz

bin/hadoop s3guard select -header use -compression gzip $LANDSATGZ \
 "SELECT s.entityId,s.cloudCover FROM S3OBJECT s WHERE s.cloudCover < '0.0' LIMIT 100"


# ---------------------------------------------------
# Cloudstore
# check out and build https://github.com/steveloughran/cloudstore
# then for these tests, set CLOUDSTORE env var to point to the JAR
# ---------------------------------------------------

bin/hadoop jar $CLOUDSTORE storediag $BUCKET

time bin/hadoop jar $CLOUDSTORE bandwidth 64M $BUCKET/testfile

```

### Other tests

* Whatever applications you have which use S3A: build and run them before the upgrade,
Then see if complete successfully in roughly the same time once the upgrade is applied.
* Test any third-party endpoints you have access to.
* Try different regions (especially a v4 only region), and encryption settings.
* Any performance tests you have can identify slowdowns, which can be a sign
  of changed behavior in the SDK (especially on stream reads and writes).
* If you can, try to test in an environment where a proxy is needed to talk
to AWS services.
* Try and get other people, especially anyone with their own endpoints,
  apps or different deployment environments, to run their own tests.
* Run the load tests, especially `ILoadTestS3ABulkDeleteThrottling`.
* Checkout cloudstore, build it against your version of hadoop, then use its CLI to run some commands (`storediag` etc)

### Dealing with Deprecated APIs and New Features

A Jenkins run should tell you if there are new deprecations.
If so, you should think about how to deal with them.

Moving to methods and APIs which weren't in the previous SDK release makes it
harder to roll back if there is a problem; but there may be good reasons
for the deprecation.

At the same time, there may be good reasons for staying with the old code.

* AWS have embraced the builder pattern for new operations; note that objects
constructed this way often have their (existing) setter methods disabled; this
may break existing code.
* New versions of S3 calls (list v2, bucket existence checks, bulk operations)
may be better than the previous HTTP operations & APIs, but they may not work with
third-party endpoints, so can only be adopted if made optional, which then
adds a new configuration option (with docs, testing, ...). A change like that
must be done in its own patch, with its new tests which compare the old
vs new operations.

### Committing the patch

When the patch is committed: update the JIRA to the version number actually
used; use that title in the commit message.

Be prepared to roll-back, re-iterate or code your way out of a regression.

There may be some problem which surfaces with wider use, which can get
fixed in a new AWS release, rolling back to an older one,
or just worked around [HADOOP-14596](https://issues.apache.org/jira/browse/HADOOP-14596).

Don't be surprised if this happens, don't worry too much, and,
while that rollback option is there to be used, ideally try to work forwards.

If the problem is with the SDK, file issues with the
 [AWS SDK Bug tracker](https://github.com/aws/aws-sdk-java/issues).
If the problem can be fixed or worked around in the Hadoop code, do it there too.
