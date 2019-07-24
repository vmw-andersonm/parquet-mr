/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */



package org.apache.parquet.crypto.keytools;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.crypto.KeyAccessDeniedException;

import java.io.IOException;


public interface KmsClient {
  /**
   * Pass configuration with client-specific parameters.
   * A KmsClient should be created when the KMS instance ID is known or if the default value is acceptable.
   * @param configuration Hadoop configuration
   * @param kmsInstanceID instance ID of the KMS managed by this KmsClient. When reading a parquet file, the KMS
   *                      instance ID can be either specified in configuration or read from parquet file metadata.
   *                      When writing a parquet file, the KMS instance ID has to be specified in configuration.
   *                      Specific KmsClient implementation should decide whether the default value is acceptable here.
   * @throws IOException
   */
  public void initialize(Configuration configuration, String kmsInstanceID) throws IOException;

  /**
   * Supports key wrapping (envelope encryption of data key by master key) inside 
   * KMS server.
   * @return
   */
  public boolean supportsServerSideWrapping();
  
  /**
   * Retrieves a key stored in KMS server. 
   * Implementation of this method is not required (can just return null) if KMS supports server side wrapping 
   * and application doesn't plan to use local (client-side) wrapping.
   * 
   * IMPORTANT: if implemented, must throw KeyAccessDeniedException when unauthorized to get the key.
   * If your KMS client code throws runtime exceptions related to access/permission problems
   * (such as Hadoop AccessControlException), make sure to catch them and throw the KeyAccessDeniedException.
   * 
   * @param keyIdentifier: a string that uniquely identifies the key in KMS: 
   * ranging from a simple key ID, to e.g. a JSON with key ID, KMS instance etc.
   * @return Base64 encoded data key 
   * @throws UnsupportedOperationException
   * @throws KeyAccessDeniedException
   * @throws IOException
   */
  public String getKeyFromServer(String keyIdentifier)
      throws UnsupportedOperationException, KeyAccessDeniedException, IOException;

   /**
   * Encrypts (wraps) data key in KMS server, using the master key. 
   * The result includes everything returned by KMS (often a JSON).
   * Implementation of this method must throw an UnsupportedOperationException if KMS doesn't support server side wrapping.
   * Implementation of this method is not required (can just return null) if applications plan to store data keys in KMS (no wrapping),
   * or plan to wrap data keys locally. 
   * 
   * IMPORTANT: if implemented, must throw KeyAccessDeniedException when unauthorized to wrap with the given master key.
   * If your KMS client code throws runtime exceptions related to access/permission problems
   * (such as Hadoop AccessControlException), make sure to catch them and throw the KeyAccessDeniedException.
   * 
   * @param dataKey Base64 encoded data key
   * @param masterKeyIdentifier: a string that uniquely identifies the wrapper (master) key in KMS: 
   * ranging from a simple key ID, to e.g. a JSON with key ID, KMS instance etc.
   * @return
   * @throws UnsupportedOperationException
   * @throws KeyAccessDeniedException
   * @throws IOException
   */
  public String wrapDataKeyInServer(String dataKey, String masterKeyIdentifier)
      throws UnsupportedOperationException, KeyAccessDeniedException, IOException;
  
  /**
   * Decrypts (unwraps) data key in KMS server, using the master key. 
   * Implementation of this method must throw an UnsupportedOperationException if KMS doesn't support server side wrapping.
   * Implementation of this method is not required (can just return null) if applications plan to store data keys in KMS (no wrapping),
   * or plan to wrap data keys locally. 
   * 
   * IMPORTANT: if implemented, must throw KeyAccessDeniedException when unauthorized to unwrap with the given master key.
   * If your KMS client code throws runtime exceptions related to access/permission problems
   * (such as Hadoop AccessControlException), make sure to catch them and throw the KeyAccessDeniedException.
   * 
   * @param wrappedDataKey includes everything returned by KMS upon wrapping.
   * @param masterKeyIdentifier: a string that uniquely identifies the wrapper (master) key in KMS: 
   * ranging from a simple key ID, to e.g. a JSON with key ID, KMS instance etc.
   * @return Base64 encoded data key
   * @throws UnsupportedOperationException
   * @throws KeyAccessDeniedException
   * @throws IOException
   */
  public String unwrapDataKeyInServer(String wrappedDataKey, String masterKeyIdentifier)
      throws UnsupportedOperationException, KeyAccessDeniedException, IOException;
}