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
package org.apache.hadoop.hdfs.protocol.datatransfer.sasl;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Map;
import javax.security.auth.callback.CallbackHandler;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslClientFactory;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import javax.security.sasl.SaslServerFactory;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.protocol.datatransfer.IOStreamPair;
import org.apache.hadoop.security.FastSaslClientFactory;
import org.apache.hadoop.security.FastSaslServerFactory;
import org.apache.hadoop.security.SaslInputStream;
import org.apache.hadoop.security.SaslOutputStream;

/**
 * Strongly inspired by Thrift's TSaslTransport class.
 *
 * Used to abstract over the <code>SaslServer</code> and
 * <code>SaslClient</code> classes, which share a lot of their interface, but
 * unfortunately don't share a common superclass.
 */
@InterfaceAudience.Private
class SaslParticipant {

  // This has to be set as part of the SASL spec, but it don't matter for
  // our purposes, but may not be empty. It's sent over the wire, so use
  // a short string.
  private static final String SERVER_NAME = "0";
  private static final String PROTOCOL = "hdfs";
  private static final String MECHANISM = "DIGEST-MD5";

  // One of these will always be null.
  private final SaslServer saslServer;
  private final SaslClient saslClient;
  private static SaslServerFactory saslServerFactory;
  private static SaslClientFactory saslClientFactory;

  private static void initializeSaslServerFactory() {
    if (saslServerFactory == null) {
      saslServerFactory = new FastSaslServerFactory(null);
    }
  }

  private static void initializeSaslClientFactory() {
    if (saslClientFactory == null) {
      saslClientFactory = new FastSaslClientFactory(null);
    }
  }
  /**
   * Creates a SaslParticipant wrapping a SaslServer.
   *
   * @param saslProps properties of SASL negotiation
   * @param callbackHandler for handling all SASL callbacks
   * @return SaslParticipant wrapping SaslServer
   * @throws SaslException for any error
   */
  public static SaslParticipant createServerSaslParticipant(
      Map<String, String> saslProps, CallbackHandler callbackHandler)
      throws SaslException {
    initializeSaslServerFactory();
    return new SaslParticipant(saslServerFactory.createSaslServer(MECHANISM,
      PROTOCOL, SERVER_NAME, saslProps, callbackHandler));
  }

  /**
   * Creates a SaslParticipant wrapping a SaslClient.
   *
   * @param userName SASL user name
   * @param saslProps properties of SASL negotiation
   * @param callbackHandler for handling all SASL callbacks
   * @return SaslParticipant wrapping SaslClient
   * @throws SaslException for any error
   */
  public static SaslParticipant createClientSaslParticipant(String userName,
      Map<String, String> saslProps, CallbackHandler callbackHandler)
      throws SaslException {
    initializeSaslClientFactory();
    return new SaslParticipant(
        saslClientFactory.createSaslClient(new String[] {MECHANISM}, userName,
            PROTOCOL, SERVER_NAME, saslProps, callbackHandler));
  }

  /**
   * Private constructor wrapping a SaslServer.
   *
   * @param saslServer to wrap
   */
  private SaslParticipant(SaslServer saslServer) {
    this.saslServer = saslServer;
    this.saslClient = null;
  }

  /**
   * Private constructor wrapping a SaslClient.
   *
   * @param saslClient to wrap
   */
  private SaslParticipant(SaslClient saslClient) {
    this.saslServer = null;
    this.saslClient = saslClient;
  }

  /**
   * @see {@link SaslServer#evaluateResponse}
   * @see {@link SaslClient#evaluateChallenge}
   */
  public byte[] evaluateChallengeOrResponse(byte[] challengeOrResponse)
      throws SaslException {
    if (saslClient != null) {
      return saslClient.evaluateChallenge(challengeOrResponse);
    } else {
      return saslServer.evaluateResponse(challengeOrResponse);
    }
  }

  /**
   * After successful SASL negotation, returns the negotiated quality of
   * protection.
   *
   * @return negotiated quality of protection
   */
  public String getNegotiatedQop() {
    if (saslClient != null) {
      return (String) saslClient.getNegotiatedProperty(Sasl.QOP);
    } else {
      return (String) saslServer.getNegotiatedProperty(Sasl.QOP);
    }
  }

  /**
   * After successful SASL negotiation, returns whether it's QOP privacy
   *
   * @return boolean whether it's QOP privacy
   */
  public boolean isNegotiatedQopPrivacy() {
    String qop = getNegotiatedQop();
    return qop != null && "auth-conf".equalsIgnoreCase(qop);
  }

  /**
   * Wraps a byte array.
   *
   * @param bytes The array containing the bytes to wrap.
   * @param off The starting position at the array
   * @param len The number of bytes to wrap
   * @return byte[] wrapped bytes
   * @throws SaslException if the bytes cannot be successfully wrapped
   */
  public byte[] wrap(byte[] bytes, int off, int len) throws SaslException {
    if (saslClient != null) {
      return saslClient.wrap(bytes, off, len);
    } else {
      return saslServer.wrap(bytes, off, len);
    }
  }

  /**
   * Unwraps a byte array.
   *
   * @param bytes The array containing the bytes to unwrap.
   * @param off The starting position at the array
   * @param len The number of bytes to unwrap
   * @return byte[] unwrapped bytes
   * @throws SaslException if the bytes cannot be successfully unwrapped
   */
  public byte[] unwrap(byte[] bytes, int off, int len) throws SaslException {
    if (saslClient != null) {
      return saslClient.unwrap(bytes, off, len);
    } else {
      return saslServer.unwrap(bytes, off, len);
    }
  }

  /**
   * Returns true if SASL negotiation is complete.
   *
   * @return true if SASL negotiation is complete
   */
  public boolean isComplete() {
    if (saslClient != null) {
      return saslClient.isComplete();
    } else {
      return saslServer.isComplete();
    }
  }

  /**
   * Return some input/output streams that may henceforth have their
   * communication encrypted, depending on the negotiated quality of protection.
   *
   * @param out output stream to wrap
   * @param in input stream to wrap
   * @return IOStreamPair wrapping the streams
   */
  public IOStreamPair createStreamPair(DataOutputStream out,
      DataInputStream in) {
    if (saslClient != null) {
      return new IOStreamPair(
          new SaslInputStream(in, saslClient),
          new SaslOutputStream(out, saslClient));
    } else {
      return new IOStreamPair(
          new SaslInputStream(in, saslServer),
          new SaslOutputStream(out, saslServer));
    }
  }
}
