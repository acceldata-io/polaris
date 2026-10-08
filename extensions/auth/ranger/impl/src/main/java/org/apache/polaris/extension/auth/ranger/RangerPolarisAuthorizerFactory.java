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

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.polaris.core.auth.PolarisAuthorizerFactory;
import org.apache.polaris.core.config.RealmConfig;
import org.apache.polaris.core.context.RealmContext;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.plugin.audit.RangerDefaultAuditHandler;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@Identifier("ranger")
public class RangerPolarisAuthorizerFactory implements PolarisAuthorizerFactory {
  private static final Logger LOG = LoggerFactory.getLogger(RangerPolarisAuthorizerFactory.class);

  public static final String SERVICE_TYPE = "polaris";
  private static final String APP_ID = "polaris";

  private static final String ERR_AUTHORIZER_FACTORY_NOT_INITIALIZED =
      "Ranger authorizer factory was not initialized successfully";

  private final RangerPolarisAuthorizerConfig config;
  private RangerBasePlugin plugin;
  private String serviceName;
  @Inject private RealmContext realmContext;

  @Inject
  RangerPolarisAuthorizerFactory(RangerPolarisAuthorizerConfig config) {
    this.config = config;
    config.validate();
    LOG.info("Initializing RangerAuthorizer");
    String serviceName = config.serviceName().get();
    RangerPluginConfig pluginConfig =
        new RangerPluginConfig(
            SERVICE_TYPE,
            serviceName,
            APP_ID,
            null,
            null,
            List.of(config.writeRangerConfig()),
            null);
    RangerBasePlugin plugin = new RangerBasePlugin(pluginConfig);
    plugin.init();
    plugin.setResultProcessor(new RangerDefaultAuditHandler(plugin.getConfig()));
    this.plugin = plugin;
    this.serviceName = serviceName;
    LOG.info("RangerAuthorizer initialized successfully");
    LOG.debug("RangerPolarisAuthorizerFactory has been activated.");
  }

  RangerBasePlugin plugin() {
    return plugin;
  }

  @Override
  public RangerPolarisAuthorizer create(RealmConfig realmConfig) {
    LOG.debug("Creating RangerPolarisAuthorizer");

    if (plugin == null || StringUtils.isBlank(serviceName)) {
      throw new IllegalStateException(ERR_AUTHORIZER_FACTORY_NOT_INITIALIZED);
    }

    RangerPolarisAuthorizer polarisAuthorizer =
        new RangerPolarisAuthorizer(plugin, serviceName, realmConfig);

    if (realmContext != null) {
      polarisAuthorizer.setRealmContext(realmContext);
    }

    return polarisAuthorizer;
  }
}
