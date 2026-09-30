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

package org.apache.polaris.extension.auth.ranger.utils;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.polaris.core.auth.PolarisAuthorizableOperation;
import org.apache.polaris.core.auth.PolarisPrincipal;
import org.apache.polaris.core.entity.PolarisEntityType;
import org.apache.polaris.core.persistence.PolarisResolvedPathWrapper;
import org.apache.polaris.core.persistence.ResolvedPolarisEntity;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;

public class RangerUtils {
  private static final String RESOURCE_TYPE_SEP = ":";
  private static final String RESOURCE_SEP = ".";
  private static final String ROOT_RESOURCE = "root";

  public static String toResourceType(PolarisEntityType entityType) {
    return switch (entityType) {
      case ROOT -> "root";
      case PRINCIPAL -> "principal";
      case CATALOG -> "catalog";
      case NAMESPACE -> "namespace";
      case TABLE_LIKE -> "table";
      case POLICY -> "policy";
      default -> throw new UnsupportedOperationException(entityType + ": unsupported entity type");
    };
  }

  /**
   * Builds one Ranger access request per privilege. Ranger 2.5 evaluates a single access type per
   * request, so callers must require every returned request to be allowed.
   */
  public static List<RangerAccessRequestImpl> toAccessRequests(
      PolarisPrincipal principal,
      PolarisResolvedPathWrapper entity,
      PolarisAuthorizableOperation authzOp,
      Set<String> privileges,
      String realmContextId) {
    RangerAccessResourceImpl resource = toResource(entity, realmContextId);

    return privileges.stream()
        .map(
            privilege -> {
              RangerAccessRequestImpl request = new RangerAccessRequestImpl();
              request.setResource(resource);
              request.setAccessType(privilege);
              request.setAction(authzOp.name());
              request.setUser(principal.getName());
              request.setUserGroups(Collections.emptySet());
              request.setUserRoles(principal.getRoles());
              return request;
            })
        .collect(Collectors.toList());
  }

  public static String toResourcePath(
      List<PolarisResolvedPathWrapper> resolvedPaths, String realmContextId) {
    return resolvedPaths.stream()
        .map(s -> RangerUtils.toResourcePath(s, realmContextId))
        .collect(Collectors.joining(","));
  }

  public static String toResourcePath(
      PolarisResolvedPathWrapper resolvedPath, String realmContextId) {
    StringBuilder sb = new StringBuilder();
    String resourceType =
        toResourceType(resolvedPath.getResolvedLeafEntity().getEntity().getType());

    sb.append(resourceType).append(RESOURCE_TYPE_SEP);

    boolean isFirst = true;
    for (ResolvedPolarisEntity entity : resolvedPath.getResolvedFullPath()) {
      if (isFirst) {
        sb.append(realmContextId);
        isFirst = false;
        if (entity.getEntity().getType() != PolarisEntityType.ROOT) {
          sb.append(RESOURCE_SEP).append(entity.getEntity().getName());
        }
      } else {
        sb.append(RESOURCE_SEP).append(entity.getEntity().getName());
      }
    }
    return sb.toString();
  }

  private static RangerAccessResourceImpl toResource(
      PolarisResolvedPathWrapper resourcePath, String realmContextId) {
    RangerAccessResourceImpl ret = new RangerAccessResourceImpl();
    StringBuilder namespace = null;

    ret.setValue(ROOT_RESOURCE, realmContextId);

    for (ResolvedPolarisEntity resolvedEntity : resourcePath.getResolvedFullPath()) {
      PolarisEntityType type = resolvedEntity.getEntity().getType();
      String name = resolvedEntity.getEntity().getName();

      if (type == PolarisEntityType.ROOT) {
        continue;
      }

      if (type == PolarisEntityType.NAMESPACE) {
        // a nested namespace is a single Ranger resource element, with levels joined by '.'
        namespace =
            namespace == null
                ? new StringBuilder(name)
                : namespace.append(RESOURCE_SEP).append(name);
        ret.setValue(toResourceType(type), namespace.toString());
      } else {
        ret.setValue(toResourceType(type), name);
      }
    }

    return ret;
  }
}
