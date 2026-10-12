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
package org.apache.polaris.extension.auth.ranger.test;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.apache.hadoop.conf.Configuration;
import org.apache.ranger.admin.client.AbstractRangerAdminClient;
import org.apache.ranger.plugin.util.RangerUserStore;
import org.apache.ranger.plugin.util.ServicePolicies;

/**
 * Serves Ranger policies from a classpath resource named after the Ranger service. Lives on the
 * Quarkus application classpath because Ranger loads the policy source with {@code Class.forName}
 * from the application class loader. Without this, Ranger builds {@code RangerAdminRESTClient} with
 * an empty URL list and {@code Random.nextInt(0)} fails the request.
 */
public class ClasspathRangerPolicySource extends AbstractRangerAdminClient {
  static final String RESOURCE_PATH_PROPERTY = "test.policy.resource.path";

  private String policiesResource;
  private String userStoreResource;

  @Override
  public void init(
      String serviceName, String appId, String configPropertyPrefix, Configuration config) {
    super.init(serviceName, appId, configPropertyPrefix, config);
    String resourcePath = config.get(configPropertyPrefix + "." + RESOURCE_PATH_PROPERTY);
    this.policiesResource = resourcePath + "/" + serviceName + ".json";
    this.userStoreResource = resourcePath + "/" + serviceName + "_userstore.json";
  }

  @Override
  public ServicePolicies getServicePoliciesIfUpdated(
      long lastKnownVersion, long lastActivationTimeInMillis) throws Exception {
    try (Reader reader = reader(policiesResource)) {
      return new Gson().fromJson(reader, ServicePolicies.class);
    }
  }

  @Override
  public RangerUserStore getUserStoreIfUpdated(
      long lastKnownVersion, long lastActivationTimeInMillis) throws Exception {
    InputStream in = getClass().getResourceAsStream(userStoreResource);
    if (in == null) {
      return null;
    }
    try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      RangerUserStore userStore = new Gson().fromJson(reader, RangerUserStore.class);
      if (userStore == null
          || userStore.getUserStoreVersion() == null
          || userStore.getUserStoreVersion() == lastKnownVersion) {
        return null;
      }
      return userStore;
    }
  }

  private Reader reader(String resource) {
    InputStream in = getClass().getResourceAsStream(resource);
    if (in == null) {
      throw new IllegalStateException("Missing Ranger policy resource " + resource);
    }
    return new InputStreamReader(in, StandardCharsets.UTF_8);
  }
}
