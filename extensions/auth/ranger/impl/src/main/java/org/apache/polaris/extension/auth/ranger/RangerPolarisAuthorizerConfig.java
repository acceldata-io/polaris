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

import static com.google.common.base.Preconditions.checkState;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithParentName;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

@ConfigMapping(prefix = "polaris.authorization.ranger")
public interface RangerPolarisAuthorizerConfig {

  static final String RANGER_KEY_PREFIX = "ranger.";
  static final String XASECURE_KEY_PREFIX = "xasecure.";

  Optional<String> serviceName();

  @WithParentName
  Map<String, String> properties();

  default Properties toRangerProperties() {
    Properties props = new Properties();
    for (String key : properties().keySet()) {
      if (key.startsWith(XASECURE_KEY_PREFIX)) {
        props.setProperty(key, properties().get(key));
      } else {
        props.setProperty(RANGER_KEY_PREFIX + key, properties().get(key));
      }
    }
    return props;
  }

  default File writeRangerConfig() {
    try {
      File file = Files.createTempFile("ranger-polaris-", ".xml").toFile();
      file.deleteOnExit();
      Files.writeString(file.toPath(), toHadoopXml(toRangerProperties()), StandardCharsets.UTF_8);
      return file;
    } catch (IOException e) {
      throw new IllegalStateException("Could not write Ranger configuration", e);
    }
  }

  private static String toHadoopXml(Properties properties) {
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<configuration>\n");
    for (String name : properties.stringPropertyNames()) {
      xml.append("  <property>\n    <name>")
          .append(xmlEscape(name))
          .append("</name>\n    <value>")
          .append(xmlEscape(properties.getProperty(name)))
          .append("</value>\n  </property>\n");
    }
    xml.append("</configuration>\n");
    return xml.toString();
  }

  private static String xmlEscape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  default void validate() {
    checkState(serviceName().isPresent(), "serviceName is not defined for ranger authorizer");
  }
}
