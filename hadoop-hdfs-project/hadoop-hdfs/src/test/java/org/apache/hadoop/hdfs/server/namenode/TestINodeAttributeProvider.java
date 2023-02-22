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

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.thirdparty.com.google.common.collect.ImmutableList;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.XAttr;
import org.apache.hadoop.fs.permission.*;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.thirdparty.com.google.common.collect.Lists;
import static org.junit.Assert.fail;

public class TestINodeAttributeProvider {
  private static final Logger LOG =
      LoggerFactory.getLogger(TestINodeAttributeProvider.class);

  private MiniDFSCluster miniDFS;
  private static final Set<String> CALLED = new HashSet<String>();
  private static final short HDFS_PERMISSION = 0777;
  private static final short PROVIDER_PERMISSION = 0770;
  private static boolean runPermissionCheck = false;
  private static boolean shouldThrowAccessException = false;

  public static class MyAuthorizationProviderAccessException
      extends AccessControlException {

    public MyAuthorizationProviderAccessException() {
      super();
    }
  };

  public static class MyAuthorizationProvider extends INodeAttributeProvider {

    public static class MyAccessControlEnforcer implements AccessControlEnforcer {
      AccessControlEnforcer ace;

      public MyAccessControlEnforcer(AccessControlEnforcer defaultEnforcer) {
        this.ace = defaultEnforcer;
      }

      @Override
      public void checkPermission(String fsOwner, String supergroup,
          UserGroupInformation ugi, INodeAttributes[] inodeAttrs,
          INode[] inodes, byte[][] pathByNameArr, int snapshotId, String path,
          int ancestorIndex, boolean doCheckOwner, FsAction ancestorAccess,
          FsAction parentAccess, FsAction access, FsAction subAccess,
          boolean ignoreEmptyDir) throws AccessControlException {
        if ((ancestorIndex > 1
            && inodes[1].getLocalName().equals("user")
            && inodes[2].getLocalName().equals("acl")) || runPermissionCheck) {
          this.ace.checkPermission(fsOwner, supergroup, ugi, inodeAttrs, inodes,
              pathByNameArr, snapshotId, path, ancestorIndex, doCheckOwner,
              ancestorAccess, parentAccess, access, subAccess, ignoreEmptyDir);
        }
        CALLED.add("checkPermission|" + ancestorAccess + "|" + parentAccess + "|" + access);
        if (shouldThrowAccessException) {
          throw new MyAuthorizationProviderAccessException();
        }
      }

      @Override
      public void checkPermissionWithContext(
          AuthorizationContext authzContext) throws AccessControlException {
        if (authzContext.getAncestorIndex() > 1
            && authzContext.getInodes()[1].getLocalName().equals("user")
            && authzContext.getInodes()[2].getLocalName().equals("acl")
            || runPermissionCheck) {
          this.ace.checkPermissionWithContext(authzContext);
        }
        CALLED.add("checkPermission|" + authzContext.getAncestorAccess()
            + "|" + authzContext.getParentAccess() + "|" + authzContext
            .getAccess());
        if (shouldThrowAccessException) {
          throw new MyAuthorizationProviderAccessException();
        }
      }
    }

    @Override
    public void start() {
      CALLED.add("start");
    }

    @Override
    public void stop() {
      CALLED.add("stop");
    }

    @Override
    public INodeAttributes getAttributes(String[] pathElements,
        final INodeAttributes inode) {
      CALLED.add("getAttributes");
      final boolean useDefault = useDefault(pathElements);
      final boolean useNullAcl = useNullAclFeature(pathElements);
      return new INodeAttributes() {
        @Override
        public boolean isDirectory() {
          return inode.isDirectory();
        }

        @Override
        public byte[] getLocalNameBytes() {
          return inode.getLocalNameBytes();
        }

        @Override
        public String getUserName() {
          return (useDefault) ? inode.getUserName() : "foo";
        }

        @Override
        public String getGroupName() {
          return (useDefault) ? inode.getGroupName() : "bar";
        }

        @Override
        public FsPermission getFsPermission() {
          return (useDefault) ? inode.getFsPermission()
                              : new FsPermission(getFsPermissionShort());
        }

        @Override
        public short getFsPermissionShort() {
          return (useDefault) ? inode.getFsPermissionShort()
                              : (short) getPermissionLong();
        }

        @Override
        public long getPermissionLong() {
          return (useDefault) ? inode.getPermissionLong() :
            (long)PROVIDER_PERMISSION;
        }

        @Override
        public AclFeature getAclFeature() {
          AclFeature f;
          if (useNullAcl) {
            int[] entries = new int[0];
            f = new AclFeature(entries);
          } else if (useDefault) {
            f = inode.getAclFeature();
          } else {
            AclEntry acl = new AclEntry.Builder().setType(AclEntryType.GROUP).
                setPermission(FsAction.ALL).setName("xxx").build();
            f = new AclFeature(AclEntryStatusFormat.toInt(
                Lists.newArrayList(acl)));
          }
          return f;
        }

        @Override
        public XAttrFeature getXAttrFeature() {
          XAttrFeature x;
          if (useDefault) {
            x = inode.getXAttrFeature();
          } else {
            x = new XAttrFeature(ImmutableList.copyOf(
                    Lists.newArrayList(
                            new XAttr.Builder().setName("test")
                                    .setValue(new byte[] {1, 2})
                                    .build())));
          }
          return x;
        }

        @Override
        public long getModificationTime() {
          return (useDefault) ? inode.getModificationTime() : 0;
        }

        @Override
        public long getAccessTime() {
          return (useDefault) ? inode.getAccessTime() : 0;
        }
      };

    }

    @Override
    public AccessControlEnforcer getExternalAccessControlEnforcer(
        AccessControlEnforcer defaultEnforcer) {
      return new MyAccessControlEnforcer(defaultEnforcer);
    }

    private boolean useDefault(String[] pathElements) {
      return !Arrays.stream(pathElements).anyMatch("authz"::equals);
    }

    private boolean useNullAclFeature(String[] pathElements) {
      return (pathElements.length > 2)
          && pathElements[1].equals("user")
          && pathElements[2].equals("acl");
    }
  }

  @Before
  public void setUp() throws IOException {
    CALLED.clear();
    Configuration conf = new HdfsConfiguration();
    conf.set(DFSConfigKeys.DFS_NAMENODE_INODE_ATTRIBUTES_PROVIDER_KEY,
        MyAuthorizationProvider.class.getName());
    conf.setBoolean(DFSConfigKeys.DFS_NAMENODE_ACLS_ENABLED_KEY, true);
    conf.set(
        DFSConfigKeys.DFS_NAMENODE_INODE_ATTRIBUTES_PROVIDER_BYPASS_USERS_KEY,
        " u2,, ,u3, ");
    EditLogFileOutputStream.setShouldSkipFsyncForTesting(true);
    miniDFS = new MiniDFSCluster.Builder(conf).build();
  }

  @After
  public void cleanUp() throws IOException {
    CALLED.clear();
    if (miniDFS != null) {
      miniDFS.shutdown();
      miniDFS = null;
    }
    runPermissionCheck = false;
    shouldThrowAccessException = false;
    Assert.assertTrue(CALLED.contains("stop"));
  }

  @Test
  public void testDelegationToProvider() throws Exception {
    Assert.assertTrue(CALLED.contains("start"));
    FileSystem fs = FileSystem.get(miniDFS.getConfiguration(0));
    final Path tmpPath = new Path("/tmp");
    final Path fooPath = new Path("/tmp/foo");

    fs.mkdirs(tmpPath);
    fs.setPermission(tmpPath, new FsPermission(HDFS_PERMISSION));
    UserGroupInformation ugi = UserGroupInformation.createUserForTesting("u1",
        new String[]{"g1"});
    ugi.doAs(new PrivilegedExceptionAction<Void>() {
      @Override
      public Void run() throws Exception {
        FileSystem fs = FileSystem.get(miniDFS.getConfiguration(0));
        CALLED.clear();
        fs.mkdirs(fooPath);
        Assert.assertTrue(CALLED.contains("getAttributes"));
        Assert.assertTrue(CALLED.contains("checkPermission|null|null|null"));
        Assert.assertTrue(CALLED.contains("checkPermission|WRITE|null|null"));

        CALLED.clear();
        fs.listStatus(fooPath);
        Assert.assertTrue(CALLED.contains("getAttributes"));
        Assert.assertTrue(
            CALLED.contains("checkPermission|null|null|READ_EXECUTE"));

        CALLED.clear();
        fs.getAclStatus(fooPath);
        Assert.assertTrue(CALLED.contains("getAttributes"));
        Assert.assertTrue(CALLED.contains("checkPermission|null|null|null"));
        return null;
      }
    });
  }

  private class AssertHelper {
    private boolean bypass = true;
    AssertHelper(boolean bp) {
      bypass = bp;
    }
    public void doAssert(boolean x) {
      if (bypass) {
        Assert.assertFalse(x);
      } else {
        Assert.assertTrue(x);
      }
    }
  }

  private void testBypassProviderHelper(final String[] users,
      final short expectedPermission, final boolean bypass) throws Exception {
    final AssertHelper asserter = new AssertHelper(bypass);

    Assert.assertTrue(CALLED.contains("start"));

    FileSystem fs = FileSystem.get(miniDFS.getConfiguration(0));
    final Path userPath = new Path("/user");
    final Path authz = new Path("/user/authz");
    final Path authzChild = new Path("/user/authz/child2");

    fs.mkdirs(userPath);
    fs.setPermission(userPath, new FsPermission(HDFS_PERMISSION));
    fs.mkdirs(authz);
    fs.setPermission(authz, new FsPermission(HDFS_PERMISSION));
    fs.mkdirs(authzChild);
    fs.setPermission(authzChild, new FsPermission(HDFS_PERMISSION));
    for(String user : users) {
      UserGroupInformation ugiBypass =
          UserGroupInformation.createUserForTesting(user,
              new String[]{"g1"});
      ugiBypass.doAs(new PrivilegedExceptionAction<Void>() {
        @Override
        public Void run() throws Exception {
          FileSystem fs = FileSystem.get(miniDFS.getConfiguration(0));
          Assert.assertEquals(expectedPermission,
              fs.getFileStatus(authzChild).getPermission().toShort());
          asserter.doAssert(CALLED.contains("getAttributes"));
          asserter.doAssert(CALLED.contains("checkPermission|null|null|null"));

          CALLED.clear();
          Assert.assertEquals(expectedPermission,
              fs.listStatus(userPath)[0].getPermission().toShort());
          asserter.doAssert(CALLED.contains("getAttributes"));
          asserter.doAssert(
              CALLED.contains("checkPermission|null|null|READ_EXECUTE"));

          CALLED.clear();
          fs.getAclStatus(authzChild);
          asserter.doAssert(CALLED.contains("getAttributes"));
          asserter.doAssert(CALLED.contains("checkPermission|null|null|null"));
          return null;
        }
      });
    }
  }

  @Test
  public void testAuthzDelegationToProvider() throws Exception {
    LOG.info("Test not bypassing provider");
    String[] users = {"u1"};
    testBypassProviderHelper(users, PROVIDER_PERMISSION, false);
  }

  @Test
  public void testAuthzBypassingProvider() throws Exception {
    LOG.info("Test bypassing provider");
    String[] users = {"u2", "u3"};
    testBypassProviderHelper(users, HDFS_PERMISSION, true);
  }

  private void verifyFileStatus(UserGroupInformation ugi) throws IOException {
    FileSystem fs = FileSystem.get(miniDFS.getConfiguration(0));

    FileStatus status = fs.getFileStatus(new Path("/"));
    LOG.info("Path '/' is owned by: "
        + status.getOwner() + ":" + status.getGroup());

    Path userDir = new Path("/user/" + ugi.getShortUserName());
    fs.mkdirs(userDir);
    status = fs.getFileStatus(userDir);
    Assert.assertEquals(ugi.getShortUserName(), status.getOwner());
    Assert.assertEquals("supergroup", status.getGroup());
    Assert.assertEquals(new FsPermission((short) 0755), status.getPermission());

    Path authzDir = new Path("/user/authz");
    fs.mkdirs(authzDir);
    status = fs.getFileStatus(authzDir);
    Assert.assertEquals("foo", status.getOwner());
    Assert.assertEquals("bar", status.getGroup());
    Assert.assertEquals(new FsPermission((short) 0770), status.getPermission());

    AclStatus aclStatus = fs.getAclStatus(authzDir);
    Assert.assertEquals(1, aclStatus.getEntries().size());
    Assert.assertEquals(AclEntryType.GROUP,
        aclStatus.getEntries().get(0).getType());
    Assert.assertEquals("xxx",
        aclStatus.getEntries().get(0).getName());
    Assert.assertEquals(FsAction.ALL,
        aclStatus.getEntries().get(0).getPermission());
    Map<String, byte[]> xAttrs = fs.getXAttrs(authzDir);
    Assert.assertTrue(xAttrs.containsKey("user.test"));
    Assert.assertEquals(2, xAttrs.get("user.test").length);
  }

  /**
   * With the custom provider configured, verify file status attributes.
   * A superuser can bypass permission check while resolving paths. So,
   * verify file status for both superuser and non-superuser.
   */
  @Test
  public void testCustomProvider() throws Exception {
    final UserGroupInformation[] users = new UserGroupInformation[]{
        UserGroupInformation.createUserForTesting(
            System.getProperty("user.name"), new String[]{"supergroup"}),
        UserGroupInformation.createUserForTesting(
            "normaluser", new String[]{"normalusergroup"}),
    };

    for (final UserGroupInformation user : users) {
      user.doAs((PrivilegedExceptionAction<Object>) () -> {
        verifyFileStatus(user);
        return null;
      });
    }
  }

  @Test
  public void testAclFeature() throws Exception {
    UserGroupInformation ugi = UserGroupInformation.createUserForTesting(
            "testuser", new String[]{"testgroup"});
    ugi.doAs((PrivilegedExceptionAction<Object>) () -> {
      FileSystem fs = miniDFS.getFileSystem();
      Path aclDir = new Path("/user/acl");
      fs.mkdirs(aclDir);
      Path aclChildDir = new Path(aclDir, "subdir");
      fs.mkdirs(aclChildDir);
      AclStatus aclStatus = fs.getAclStatus(aclDir);
      Assert.assertEquals(0, aclStatus.getEntries().size());
      return null;
    });
  }

  @Test
  // HDFS-14389 - Ensure getAclStatus returns the owner, group and permissions
  // from the Attribute Provider, and not from HDFS.
  public void testGetAclStatusReturnsProviderOwnerPerms() throws Exception {
    FileSystem fs = FileSystem.get(miniDFS.getConfiguration(0));
    final Path userPath = new Path("/user");
    final Path authz = new Path("/user/authz");
    final Path authzChild = new Path("/user/authz/child2");

    fs.mkdirs(userPath);
    fs.setPermission(userPath, new FsPermission(HDFS_PERMISSION));
    fs.mkdirs(authz);
    fs.setPermission(authz, new FsPermission(HDFS_PERMISSION));
    fs.mkdirs(authzChild);
    fs.setPermission(authzChild, new FsPermission(HDFS_PERMISSION));
    UserGroupInformation ugi = UserGroupInformation.createUserForTesting("u1",
        new String[]{"g1"});
    ugi.doAs(new PrivilegedExceptionAction<Void>() {
      @Override
      public Void run() throws Exception {
        FileSystem fs = FileSystem.get(miniDFS.getConfiguration(0));
        Assert.assertEquals(PROVIDER_PERMISSION,
            fs.getFileStatus(authzChild).getPermission().toShort());

        Assert.assertEquals("foo", fs.getAclStatus(authzChild).getOwner());
        Assert.assertEquals("bar", fs.getAclStatus(authzChild).getGroup());
        Assert.assertEquals(PROVIDER_PERMISSION,
            fs.getAclStatus(authzChild).getPermission().toShort());
        return null;
      }
    });
  }

  @Test
  // HDFS-16529 - Ensure enforcer AccessControlException subclass are caught
  // and re-thrown as plain ACE exceptions.
  public void testSubClassedAccessControlExceptions() throws Exception {
    FileSystem fs = FileSystem.get(miniDFS.getConfiguration(0));
    shouldThrowAccessException = true;
    final Path userPath = new Path("/user");
    final Path authz = new Path("/user/authz");
    final Path authzChild = new Path("/user/authz/child2");

    fs.mkdirs(userPath);
    fs.setPermission(userPath, new FsPermission(HDFS_PERMISSION));
    fs.mkdirs(authz);
    fs.setPermission(authz, new FsPermission(HDFS_PERMISSION));
    fs.mkdirs(authzChild);
    fs.setPermission(authzChild, new FsPermission(HDFS_PERMISSION));
    UserGroupInformation ugi = UserGroupInformation.createUserForTesting("u1",
        new String[]{"g1"});
    ugi.doAs(new PrivilegedExceptionAction<Void>() {
      @Override
      public Void run() throws Exception {
        FileSystem fs = FileSystem.get(miniDFS.getConfiguration(0));
        try {
          fs.access(authzChild, FsAction.ALL);
          fail("Exception should be thrown");
          // The DFS Client will get a RemoteException containing an
          // AccessControlException (ACE). If the ACE is a subclass of ACE then
          // the client does not unwrap it correctly. The change in HDFS-16529
          // is to ensure ACE is always thrown rather than a sub class to avoid
          // this issue.
        } catch (AccessControlException ace) {
          Assert.assertEquals(AccessControlException.class, ace.getClass());
        }
        return null;
      }
    });
  }


  @Test
  // HDFS-15165 - ContentSummary calls should use the provider permissions(if
  // attribute provider is configured) and not the underlying HDFS permissions.
  public void testContentSummary() throws Exception {
    runPermissionCheck = true;
    FileSystem fs = FileSystem.get(miniDFS.getConfiguration(0));
    final Path userPath = new Path("/user");
    final Path authz = new Path("/user/authz");
    final Path authzChild = new Path("/user/authz/child2");
    // Create the path /user/authz/child2 where the HDFS permissions are
    // 777, 700, 700.
    // The permission provider will give permissions 770 to authz and child2
    // with the owner foo, group bar.
    fs.mkdirs(userPath);
    fs.setPermission(userPath, new FsPermission(0777));
    fs.mkdirs(authz);
    fs.setPermission(authz, new FsPermission(0700));
    fs.mkdirs(authzChild);
    fs.setPermission(authzChild, new FsPermission(0700));
    UserGroupInformation ugi = UserGroupInformation.createUserForTesting("foo",
        new String[]{"g1"});
    ugi.doAs(new PrivilegedExceptionAction<Void>() {
      @Override
      public Void run() throws Exception {
        FileSystem fs = FileSystem.get(miniDFS.getConfiguration(0));
        fs.getContentSummary(authz);
        return null;
      }
    });
  }

  @Test
  // See HDFS-16132 where an issue was reported after HDFS-15372. The sequence
  // of operations here causes that change to break and the test fails with:
  // org.apache.hadoop.ipc.RemoteException(java.lang.AssertionError):
  //     Absolute path required, but got 'foo'
  //  at org.apache.hadoop.hdfs.server.namenode.INode.checkAbsolutePath
  //    (INode.java:838)
  //  at org.apache.hadoop.hdfs.server.namenode.INode.getPathComponents
  //    (INode.java:813)
  // After reverting HDFS-15372 the test passes, so including this test in the
  // revert for future reference.
  public void testAttrProviderWorksCorrectlyOnRenamedSnapshotPaths()
      throws Exception {
    runPermissionCheck = true;
    FileSystem fs = FileSystem.get(miniDFS.getConfiguration(0));
    DistributedFileSystem hdfs = miniDFS.getFileSystem();
    final Path parent = new Path("/user");
    hdfs.mkdirs(parent);
    fs.setPermission(parent, new FsPermission(HDFS_PERMISSION));
    final Path sub1 = new Path(parent, "sub1");
    final Path sub1foo = new Path(sub1, "foo");
    hdfs.mkdirs(sub1);
    hdfs.mkdirs(sub1foo);
    Path f = new Path(sub1foo, "file0");
    DFSTestUtil.createFile(hdfs, f, 0, (short) 1, 0);
    hdfs.allowSnapshot(parent);
    hdfs.createSnapshot(parent, "s0");

    f = new Path(sub1foo, "file1");
    DFSTestUtil.createFile(hdfs, f, 0, (short) 1, 0);
    f = new Path(sub1foo, "file2");
    DFSTestUtil.createFile(hdfs, f, 0, (short) 1, 0);

    final Path sub2 = new Path(parent, "sub2");
    hdfs.mkdirs(sub2);
    final Path sub2foo = new Path(sub2, "foo");
    // mv /parent/sub1/foo to /parent/sub2/foo
    hdfs.rename(sub1foo, sub2foo);

    hdfs.createSnapshot(parent, "s1");
    hdfs.createSnapshot(parent, "s2");

    final Path sub3 = new Path(parent, "sub3");
    hdfs.mkdirs(sub3);
    // mv /parent/sub2/foo to /parent/sub3/foo
    hdfs.rename(sub2foo, sub3);

    hdfs.delete(sub3, true);
    UserGroupInformation ugi =
        UserGroupInformation.createUserForTesting("u1", new String[] {"g1"});
    ugi.doAs(new PrivilegedExceptionAction<Void>() {
      @Override
      public Void run() throws Exception {
        FileSystem fs = FileSystem.get(miniDFS.getConfiguration(0));
        ((DistributedFileSystem)fs).getSnapshotDiffReport(parent, "s1", "s2");
        CALLED.clear();
        return null;
      }
    });
  }
}
