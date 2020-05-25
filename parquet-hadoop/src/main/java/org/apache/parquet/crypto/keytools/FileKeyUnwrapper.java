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

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.crypto.DecryptionKeyRetriever;
import org.apache.parquet.crypto.ParquetCryptoRuntimeException;
import org.apache.parquet.crypto.keytools.KeyToolkit.KeyWithMasterID;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;


public class FileKeyUnwrapper implements DecryptionKeyRetriever {
  // For every token: a map of KEK_ID to KEK bytes
  private static final Map<String, ExpiringCacheEntry<Map<String,byte[]>>> KEKMapPerToken = new HashMap<>();
  private volatile static long lastKekCacheCleanupTimestamp = System.currentTimeMillis() + 60l * 1000; // grace period of 1 minute
  private final Map<String,byte[]> KEKPerKekID;

  private static final ObjectMapper objectMapper = new ObjectMapper();

  private KmsClient kmsClient = null;
  private final FileKeyMaterialStore keyMaterialStore;
  private final Configuration hadoopConfiguration;
  private final long cacheEntryLifetime;
  private final String accessToken;

  FileKeyUnwrapper(Configuration hadoopConfiguration, FileKeyMaterialStore keyStore) {
    this.hadoopConfiguration = hadoopConfiguration;
    this.keyMaterialStore = keyStore;

    cacheEntryLifetime = 1000l * hadoopConfiguration.getLong(KeyToolkit.TOKEN_LIFETIME_PROPERTY_NAME,
        KeyToolkit.DEFAULT_CACHE_ENTRY_LIFETIME);

    // Check cache upon each file reading (clean once in cacheEntryLifetime)
    KeyToolkit.checkCacheEntriesForExpiredTokens(KEKMapPerToken, lastKekCacheCleanupTimestamp, cacheEntryLifetime);
    KeyToolkit.checkKmsCacheForExpiredTokens(cacheEntryLifetime);
    
    accessToken = hadoopConfiguration.getTrimmed(KeyToolkit.KEY_ACCESS_TOKEN_PROPERTY_NAME, 
        KmsClient.DEFAULT_ACCESS_TOKEN);

    ExpiringCacheEntry<Map<String, byte[]>> KEKCacheEntry;
    synchronized (KEKMapPerToken) {
      KEKCacheEntry = KEKMapPerToken.get(accessToken);
      if (null == KEKCacheEntry || KEKCacheEntry.isExpired()) {
        KEKCacheEntry = new ExpiringCacheEntry<>(new HashMap<String,byte[]>(), cacheEntryLifetime);
        KEKMapPerToken.put(accessToken, KEKCacheEntry);
      }
    }
    
    KEKPerKekID = KEKCacheEntry.getCachedItem();
  }

  @Override
  public byte[] getKey(byte[] keyMetaData) {
    String keyMaterial;
    if (null != keyMaterialStore) {
      String keyReferenceMetadata = new String(keyMetaData, StandardCharsets.UTF_8);
      String keyIDinFile = getKeyReference(keyReferenceMetadata);
      keyMaterial = keyMaterialStore.getKeyMaterial(keyIDinFile);
      if (null == keyMaterial) {
        throw new ParquetCryptoRuntimeException("Null key material for keyIDinFile: " + keyIDinFile);
      }
    }  else {
      keyMaterial = new String(keyMetaData, StandardCharsets.UTF_8);
    }

    return getDEKandMasterID(keyMaterial).getDataKey();
  }

  KeyWithMasterID getDEKandMasterID(String keyMaterial)  {
    Map<String, String> keyMaterialJson = null;
    try {
      keyMaterialJson = objectMapper.readValue(new StringReader(keyMaterial),
          new TypeReference<Map<String, String>>() {});
    }  catch (IOException e) {
      throw new ParquetCryptoRuntimeException("Failed to parse key material " + keyMaterial, e);
    }

    String keyMaterialType = keyMaterialJson.get(KeyToolkit.KEY_MATERIAL_TYPE_FIELD);
    if (!KeyToolkit.KEY_MATERIAL_TYPE.equals(keyMaterialType)) {
      throw new ParquetCryptoRuntimeException("Wrong key material type: " + keyMaterialType + 
          " vs " + KeyToolkit.KEY_MATERIAL_TYPE);
    }

    if (null == kmsClient) {
      kmsClient = getKmsClientFromConfigOrKeyMaterial(keyMaterialJson);
    }

    boolean doubleWrapping = Boolean.valueOf(keyMaterialJson.get(KeyToolkit.DOUBLE_WRAPPING_FIELD));

    String masterKeyID = keyMaterialJson.get(KeyToolkit.MASTER_KEY_ID_FIELD);
    String encodedWrappedDatakey = keyMaterialJson.get(KeyToolkit.WRAPPED_DEK_FIELD);

    byte[] dataKey;
    if (!doubleWrapping) {
      dataKey = kmsClient.unwrapKey(encodedWrappedDatakey, masterKeyID);
    } else {
      // Get KEK
      String encodedKEK_ID = keyMaterialJson.get(KeyToolkit.KEK_ID_FIELD);
      final Map<String, String> keyMaterialJsonFinal = keyMaterialJson;
      
      byte[] kekBytes;
      synchronized (KEKPerKekID) {
        kekBytes = KEKPerKekID.get(encodedKEK_ID);
        if (null == kekBytes) {
          kekBytes = unwrapKek(keyMaterialJsonFinal, masterKeyID);
          KEKPerKekID.put(encodedKEK_ID, kekBytes);
        }
      }

      // Decrypt the data key
      byte[]  AAD = Base64.getDecoder().decode(encodedKEK_ID);
      dataKey =  KeyToolkit.unwrapKeyLocally(encodedWrappedDatakey, kekBytes, AAD);
    }

    return new KeyWithMasterID(dataKey, masterKeyID);
  }

  private byte[] unwrapKek(Map<String, String> keyMaterialJson, String masterKeyID) {
    byte[] kekBytes;
    String encodedWrappedKEK = keyMaterialJson.get(KeyToolkit.WRAPPED_KEK_FIELD);
    kekBytes = kmsClient.unwrapKey(encodedWrappedKEK, masterKeyID);

    if (null == kekBytes) {
      throw new ParquetCryptoRuntimeException("Null KEK, after unwrapping in KMS with master key " + masterKeyID);
    }
    return kekBytes;
  }

  private KmsClient getKmsClientFromConfigOrKeyMaterial(Map<String, String> keyMaterialJson) {
    String kmsInstanceID = hadoopConfiguration.getTrimmed(KeyToolkit.KMS_INSTANCE_ID_PROPERTY_NAME);
    if (StringUtils.isEmpty(kmsInstanceID)) {
      kmsInstanceID = keyMaterialJson.get(KeyToolkit.KMS_INSTANCE_ID_FIELD);
      if (null == kmsInstanceID) {
        throw new ParquetCryptoRuntimeException("KMS instance ID is missing both in properties and file key material");
      }
      hadoopConfiguration.set(KeyToolkit.KMS_INSTANCE_ID_PROPERTY_NAME, kmsInstanceID);
    }

    String kmsInstanceURL = hadoopConfiguration.getTrimmed(KeyToolkit.KMS_INSTANCE_URL_PROPERTY_NAME);
    if (StringUtils.isEmpty(kmsInstanceURL)) {
      kmsInstanceURL = keyMaterialJson.get(KeyToolkit.KMS_INSTANCE_URL_FIELD);
      if (null == kmsInstanceURL) {
        throw new ParquetCryptoRuntimeException("KMS instance URL is missing both in properties and file key material");
      }
      hadoopConfiguration.set(KeyToolkit.KMS_INSTANCE_URL_PROPERTY_NAME, kmsInstanceURL);
    }

    String accessToken = hadoopConfiguration.getTrimmed(KeyToolkit.KEY_ACCESS_TOKEN_PROPERTY_NAME, 
        KmsClient.DEFAULT_ACCESS_TOKEN);

    KmsClient kmsClient = KeyToolkit.getKmsClient(kmsInstanceID, hadoopConfiguration, accessToken, cacheEntryLifetime);
    if (null == kmsClient) {
      throw new ParquetCryptoRuntimeException("KMSClient was not successfully created for reading encrypted data.");
    }
    return kmsClient;
  }

  private static String getKeyReference(String keyReferenceMetadata) {
    Map<String, String> keyMetadataJson = null;
    try {
      keyMetadataJson = objectMapper.readValue(new StringReader(keyReferenceMetadata),
          new TypeReference<Map<String, String>>() {});
    } catch (Exception e) {
      throw new ParquetCryptoRuntimeException("Failed to parse key metadata " + keyReferenceMetadata, e);
    }

    return keyMetadataJson.get(KeyToolkit.KEY_REFERENCE_FIELD);
  }

  static void removeCacheEntriesForToken(String accessToken) {
    synchronized (KEKMapPerToken) { 
      KEKMapPerToken.remove(accessToken);
    }
  }

  static void removeCacheEntriesForAllTokens() {
    synchronized (KEKMapPerToken) {
      KEKMapPerToken.clear();
    }
  }
}