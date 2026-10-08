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

import static org.apache.polaris.extension.auth.ranger.RangerTestUtils.createConfig;
import static org.apache.polaris.extension.auth.ranger.RangerTestUtils.createRealmConfig;
import static org.apache.polaris.extension.auth.ranger.RangerTestUtils.createRealmContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.polaris.core.auth.PolarisAuthorizableOperation;
import org.apache.polaris.core.auth.PolarisPrincipal;
import org.apache.polaris.core.entity.PolarisEntity;
import org.apache.polaris.core.entity.PolarisEntityType;
import org.apache.polaris.core.persistence.PolarisResolvedPathWrapper;
import org.apache.polaris.core.persistence.ResolvedPolarisEntity;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.plugin.audit.RangerDefaultAuditHandler;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerAccessResultProcessor;
import org.junit.jupiter.api.Test;

public class RangerPolarisAuthorizerFactoryTest {
  @Test
  public void testAuthorizerInstantiation() {
    RangerPolarisAuthorizerFactory factory = new RangerPolarisAuthorizerFactory(createConfig());
    RangerPolarisAuthorizer authorizer = factory.create(createRealmConfig());
    assertNotNull(authorizer);
    authorizer.setRealmContext(createRealmContext());
  }

  @Test
  public void testAuthorizerInitMissingServiceName() {
    RangerPolarisAuthorizerConfig config = createConfig(null, Collections.emptyMap());
    assertThrows(IllegalStateException.class, config::validate);
  }

  @Test
  public void testPluginConfigHonorsGroupsAndSuperUsers() {
    Map<String, String> properties = new HashMap<>(createConfig().properties());
    properties.put("plugin.polaris.use.rangerGroups", "true");
    properties.put("plugin.polaris.use.only.rangerGroups", "true");
    properties.put("plugin.polaris.super.users", "alice");

    RangerPolarisAuthorizerFactory factory =
        new RangerPolarisAuthorizerFactory(createConfig("dev_polaris", properties));
    RangerPluginConfig pluginConfig = factory.plugin().getConfig();

    assertTrue(pluginConfig.isUseRangerGroups());
    assertTrue(pluginConfig.isUseOnlyRangerGroups());
    assertTrue(pluginConfig.isSuperUser("alice"));
    assertFalse(pluginConfig.isSuperUser("bob"));
  }

  @Test
  public void testAuthorizerInstallsAuditHandler() {
    RangerPolarisAuthorizerFactory factory = new RangerPolarisAuthorizerFactory(createConfig());

    assertInstanceOf(RangerDefaultAuditHandler.class, factory.plugin().getResultProcessor());
  }

  @Test
  public void testAuthorizeOrThrowAuditsAllowAndDeny() {
    RangerPolarisAuthorizerFactory factory = new RangerPolarisAuthorizerFactory(createConfig());
    List<RangerAccessResult> audited = new ArrayList<>();
    factory.plugin().setResultProcessor(recordingProcessor(audited));

    RangerPolarisAuthorizer authorizer = factory.create(createRealmConfig());
    authorizer.setRealmContext(createRealmContext());
    PolarisResolvedPathWrapper root = rootPath();

    authorizer.authorizeOrThrow(
        principal("admin1"),
        Collections.emptySet(),
        PolarisAuthorizableOperation.CREATE_CATALOG,
        root,
        null);

    assertEquals(1, audited.size());
    assertEquals("admin1", audited.get(0).getAccessRequest().getUser());
    assertTrue(audited.get(0).getIsAllowed());

    audited.clear();
    assertThrows(
        ForbiddenException.class,
        () ->
            authorizer.authorizeOrThrow(
                principal("user1"),
                Collections.emptySet(),
                PolarisAuthorizableOperation.CREATE_CATALOG,
                root,
                null));

    assertEquals(1, audited.size());
    assertEquals("user1", audited.get(0).getAccessRequest().getUser());
    assertFalse(audited.get(0).getIsAllowed());
  }

  private static PolarisPrincipal principal(String name) {
    return PolarisPrincipal.of(name, Collections.emptyMap(), Collections.emptySet());
  }

  private static PolarisResolvedPathWrapper rootPath() {
    PolarisEntity root =
        new PolarisEntity.Builder().setName("POLARIS").setType(PolarisEntityType.ROOT).build();

    return new PolarisResolvedPathWrapper(
        List.of(new ResolvedPolarisEntity(root, Collections.emptyList(), Collections.emptyList())));
  }

  private static RangerAccessResultProcessor recordingProcessor(List<RangerAccessResult> audited) {
    return new RangerAccessResultProcessor() {
      @Override
      public void processResult(RangerAccessResult result) {
        audited.add(result);
      }

      @Override
      public void processResults(Collection<RangerAccessResult> results) {
        audited.addAll(results);
      }
    };
  }
}
