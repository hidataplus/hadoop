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

package org.apache.hadoop.yarn.server.nodemanager.containermanager.resourceplugin.gpu;

import static org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.resources.ResourcesExceptionUtil.throwIfNecessary;

import org.apache.hadoop.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.thirdparty.com.google.common.collect.ImmutableSet;
import org.apache.hadoop.thirdparty.com.google.common.collect.Lists;
import org.apache.hadoop.thirdparty.com.google.common.collect.Sets;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.server.nodemanager.webapp.dao.gpu.GpuDeviceInformation;
import org.apache.hadoop.yarn.server.nodemanager.webapp.dao.gpu.GpuDeviceInformationParser;
import org.apache.hadoop.yarn.server.nodemanager.webapp.dao.gpu.PerGpuDeviceInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@InterfaceAudience.Private
@InterfaceStability.Unstable
public class GpuDiscoverer extends Configured {
  public static final Logger LOG = LoggerFactory.getLogger(
      GpuDiscoverer.class);
  @VisibleForTesting
  static final String DEFAULT_BINARY_NAME = "nvidia-smi";

  // When executable path not set, try to search default dirs
  // By default search /usr/bin, /bin, and /usr/local/nvidia/bin (when
  // launched by nvidia-docker.
  private static final Set<String> DEFAULT_BINARY_SEARCH_DIRS = ImmutableSet.of(
      "/usr/bin", "/bin", "/usr/local/nvidia/bin");

  private static final int MAX_REPEATED_ERROR_ALLOWED = 10;

  private NvidiaBinaryHelper nvidiaBinaryHelper;
  private String pathOfGpuBinary = null;
  private Map<String, String> environment = new HashMap<>();

  private int numOfErrorExecutionSinceLastSucceed = 0;
  private GpuDeviceInformation lastDiscoveredGpuInformation = null;

  private List<GpuDevice> gpuDevicesFromUser;

  private void validateConfOrThrowException() throws YarnException {
    if (getConf() == null) {
      throw new YarnException("Please initialize (call initialize) before use "
          + GpuDiscoverer.class.getSimpleName());
    }
  }

  private String getErrorMessageOfScriptExecution(String msg) {
    return getFailedToExecuteScriptMessage() +
        "! Exception message: " + msg;
  }

  private String getErrorMessageOfScriptExecutionThresholdReached() {
    return getFailedToExecuteScriptMessage() + " for " +
        MAX_REPEATED_ERROR_ALLOWED + " times, " +
        "skipping following executions!";
  }

  private String getFailedToExecuteScriptMessage() {
    return "Failed to execute " +
        GpuDeviceInformationParser.GPU_SCRIPT_REFERENCE +
        " (" + pathOfGpuBinary + ")";
  }

  private String getFailedToParseErrorMessage(String msg) {
    return "Failed to parse XML output of " +
        GpuDeviceInformationParser.GPU_SCRIPT_REFERENCE
        + "( " + pathOfGpuBinary + ")" + msg;
  }

  /**
   * Get GPU device information from system.
   * This need to be called after initialize.
   *
   * Please note that this only works on *NIX platform, so external caller
   * need to make sure this.
   *
   * @return GpuDeviceInformation
   * @throws YarnException when any error happens
   */
  public synchronized GpuDeviceInformation getGpuDeviceInformation()
      throws YarnException {
    if (numOfErrorExecutionSinceLastSucceed == MAX_REPEATED_ERROR_ALLOWED) {
      String msg = getErrorMessageOfScriptExecutionThresholdReached();
      LOG.error(msg);
      throw new YarnException(msg);
    }

    try {
      lastDiscoveredGpuInformation =
          nvidiaBinaryHelper.getGpuDeviceInformation(pathOfGpuBinary);
    } catch (IOException e) {
      numOfErrorExecutionSinceLastSucceed++;
      String msg = getErrorMessageOfScriptExecution(e.getMessage());
      LOG.debug(msg);
      throw new YarnException(msg, e);
    } catch (YarnException e) {
      numOfErrorExecutionSinceLastSucceed++;
      String msg = getFailedToParseErrorMessage(e.getMessage());
      LOG.debug(msg, e);
      throw e;
    }

    return lastDiscoveredGpuInformation;
  }

  boolean isAutoDiscoveryEnabled() {
    String allowedDevicesStr = getConf().get(
        YarnConfiguration.NM_GPU_ALLOWED_DEVICES,
        YarnConfiguration.AUTOMATICALLY_DISCOVER_GPU_DEVICES);
    return allowedDevicesStr.equals(
        YarnConfiguration.AUTOMATICALLY_DISCOVER_GPU_DEVICES);
  }

  /**
   * Get list of GPU devices usable by YARN.
   *
   * @return List of GPU devices
   */
  public synchronized List<GpuDevice> getGpusUsableByYarn()
      throws YarnException {
    validateConfOrThrowException();

    if (isAutoDiscoveryEnabled()) {
      return parseGpuDevicesFromAutoDiscoveredGpuInfo();
    } else {
      if (gpuDevicesFromUser == null) {
        gpuDevicesFromUser = parseGpuDevicesFromUserDefinedValues();
      }
      return gpuDevicesFromUser;
    }
  }

  private List<GpuDevice> parseGpuDevicesFromAutoDiscoveredGpuInfo()
          throws YarnException {
    if (lastDiscoveredGpuInformation == null) {
      String msg = YarnConfiguration.NM_GPU_ALLOWED_DEVICES + " is set to "
          + YarnConfiguration.AUTOMATICALLY_DISCOVER_GPU_DEVICES
          + ", however automatically discovering "
          + "GPU information failed, please check NodeManager log for more"
          + " details, as an alternative, admin can specify "
          + YarnConfiguration.NM_GPU_ALLOWED_DEVICES
          + " manually to enable GPU isolation.";
      LOG.error(msg);
      throw new YarnException(msg);
    }

    List<GpuDevice> gpuDevices = new ArrayList<>();
    if (lastDiscoveredGpuInformation.getGpus() != null) {
      int numberOfGpus = lastDiscoveredGpuInformation.getGpus().size();
      LOG.debug("Found {} GPU devices", numberOfGpus);
      for (int i = 0; i < numberOfGpus; i++) {
        List<PerGpuDeviceInformation> gpuInfos =
            lastDiscoveredGpuInformation.getGpus();
        gpuDevices.add(new GpuDevice(i, gpuInfos.get(i).getMinorNumber()));
      }
    }
    return gpuDevices;
  }

  /**
   * @return List of GpuDevices
   * @throws YarnException when a GPU device is defined as a duplicate.
   * The first duplicate GPU device will be added to the exception message.
   */
  private List<GpuDevice> parseGpuDevicesFromUserDefinedValues()
      throws YarnException {
    String devices = getConf().get(
        YarnConfiguration.NM_GPU_ALLOWED_DEVICES,
        YarnConfiguration.AUTOMATICALLY_DISCOVER_GPU_DEVICES);

    if (devices.trim().isEmpty()) {
      throw GpuDeviceSpecificationException.createWithEmptyValueSpecified();
    }
    List<GpuDevice> gpuDevices = Lists.newArrayList();
    for (String device : devices.split(",")) {
      if (device.trim().length() > 0) {
        String[] splitByColon = device.trim().split(":");
        if (splitByColon.length != 2) {
          throwIfNecessary(GpuDeviceSpecificationException
              .createWithWrongValueSpecified(device, devices), getConf());
          LOG.warn("Wrong GPU specification string {}, ignored", device);
        }

        GpuDevice gpuDevice;
        try {
          gpuDevice = parseGpuDevice(splitByColon);
        } catch (NumberFormatException e) {
          throwIfNecessary(GpuDeviceSpecificationException
              .createWithWrongValueSpecified(device, devices, e), getConf());
          LOG.warn("Cannot parse GPU device numbers: {}", device);
          continue;
        }

        if (!gpuDevices.contains(gpuDevice)) {
          gpuDevices.add(gpuDevice);
        } else {
          throwIfNecessary(GpuDeviceSpecificationException
              .createWithDuplicateValueSpecified(device, devices), getConf());
          LOG.warn("CPU device is duplicated: {}", device);
        }
      }
    }
    LOG.info("Allowed GPU devices:" + gpuDevices);

    return gpuDevices;
  }

  private GpuDevice parseGpuDevice(String[] splitByColon) {
    int index = Integer.parseInt(splitByColon[0]);
    int minorNumber = Integer.parseInt(splitByColon[1]);
    return new GpuDevice(index, minorNumber);
  }

  public synchronized void initialize(Configuration config,
      NvidiaBinaryHelper nvidiaHelper) throws YarnException {
    setConf(config);
    this.nvidiaBinaryHelper = nvidiaHelper;
    if (isAutoDiscoveryEnabled()) {
      numOfErrorExecutionSinceLastSucceed = 0;
      lookUpAutoDiscoveryBinary(config);

      // Try to discover GPU information once and print
      try {
        LOG.info("Trying to discover GPU information ...");
        GpuDeviceInformation info = getGpuDeviceInformation();
        LOG.info("Discovered GPU information: " + info.toString());
      } catch (YarnException e) {
        String msg =
                "Failed to discover GPU information from system, exception message:"
                        + e.getMessage() + " continue...";
        LOG.warn(msg);
      }
    }
  }

  private void lookUpAutoDiscoveryBinary(Configuration config)
      throws YarnException {
    String configuredBinaryPath = config.get(
        YarnConfiguration.NM_GPU_PATH_TO_EXEC, DEFAULT_BINARY_NAME);
    if (configuredBinaryPath.isEmpty()) {
      configuredBinaryPath = DEFAULT_BINARY_NAME;
    }

    File binaryPath;
    File configuredBinaryFile = new File(configuredBinaryPath);
    if (!configuredBinaryFile.exists()) {
      binaryPath = lookupBinaryInDefaultDirs();
    } else if (configuredBinaryFile.isDirectory()) {
      binaryPath = handleConfiguredBinaryPathIsDirectory(configuredBinaryFile);
    } else {
      binaryPath = configuredBinaryFile;
      // If path exists but file name is incorrect don't execute the file
      String fileName = binaryPath.getName();
      if (DEFAULT_BINARY_NAME.equals(fileName)) {
        String msg = String.format("Please check the configuration value of"
             +" %s. It should point to an %s binary.",
             YarnConfiguration.NM_GPU_PATH_TO_EXEC,
             DEFAULT_BINARY_NAME);
        throwIfNecessary(new YarnException(msg), config);
        LOG.warn(msg);
      }
    }

    pathOfGpuBinary = binaryPath.getAbsolutePath();
  }

  private File handleConfiguredBinaryPathIsDirectory(File configuredBinaryFile)
      throws YarnException {
    File binaryPath = new File(configuredBinaryFile, DEFAULT_BINARY_NAME);
    if (!binaryPath.exists()) {
      throw new YarnException("Failed to find GPU discovery executable, " +
          "please double check "+ YarnConfiguration.NM_GPU_PATH_TO_EXEC +
          " setting. The setting points to a directory but " +
          "no file found in the directory with name:" + DEFAULT_BINARY_NAME);
    } else {
      LOG.warn("Specified path is a directory, use " + DEFAULT_BINARY_NAME
          + " under the directory, updated path-to-executable:"
          + binaryPath.getAbsolutePath());
    }
    return binaryPath;
  }

  private File lookupBinaryInDefaultDirs() throws YarnException {
    final File lookedUpBinary = lookupBinaryInDefaultDirsInternal();
    if (lookedUpBinary == null) {
      throw new YarnException("Failed to find GPU discovery executable, " +
          "please double check " + YarnConfiguration.NM_GPU_PATH_TO_EXEC +
          " setting. Also tried to find the executable " +
          "in the default directories: " + DEFAULT_BINARY_SEARCH_DIRS);
    }
    return lookedUpBinary;
  }

  private File lookupBinaryInDefaultDirsInternal() {
    Set<String> triedBinaryPaths = Sets.newHashSet();
    for (String dir : DEFAULT_BINARY_SEARCH_DIRS) {
      File binaryPath = new File(dir, DEFAULT_BINARY_NAME);
      if (binaryPath.exists()) {
        return binaryPath;
      } else {
        triedBinaryPaths.add(binaryPath.getAbsolutePath());
      }
    }
    LOG.warn("Failed to locate GPU device discovery binary, tried paths: "
        + triedBinaryPaths + "! Please double check the value of config "
        + YarnConfiguration.NM_GPU_PATH_TO_EXEC +
        ". Using default binary: " + DEFAULT_BINARY_NAME);

    return null;
  }

  @VisibleForTesting
  Map<String, String> getEnvironmentToRunCommand() {
    return environment;
  }

  @VisibleForTesting
  String getPathOfGpuBinary() {
    return pathOfGpuBinary;
  }
}
