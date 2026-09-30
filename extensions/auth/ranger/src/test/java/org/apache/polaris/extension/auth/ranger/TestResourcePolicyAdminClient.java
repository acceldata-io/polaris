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
package org.apache.polaris.extension.auth.ranger;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.apache.hadoop.conf.Configuration;
import org.apache.ranger.admin.client.AbstractRangerAdminClient;
import org.apache.ranger.plugin.util.ServicePolicies;

/** Serves Ranger policies from a {@code <serviceName>.json} classpath resource, for tests. */
public class TestResourcePolicyAdminClient extends AbstractRangerAdminClient {
  public static final String RESOURCE_PATH_PROPERTY = "test.policy.resource.path";

  private String resource;

  @Override
  public void init(
      String serviceName, String appId, String configPropertyPrefix, Configuration config) {
    super.init(serviceName, appId, configPropertyPrefix, config);
    this.resource =
        config.get(configPropertyPrefix + "." + RESOURCE_PATH_PROPERTY)
            + "/"
            + serviceName
            + ".json";
  }

  @Override
  public ServicePolicies getServicePoliciesIfUpdated(
      long lastKnownVersion, long lastActivationTimeInMillis) throws Exception {
    try (Reader reader =
        new InputStreamReader(getClass().getResourceAsStream(resource), StandardCharsets.UTF_8)) {
      return new Gson().fromJson(reader, ServicePolicies.class);
    }
  }
}
