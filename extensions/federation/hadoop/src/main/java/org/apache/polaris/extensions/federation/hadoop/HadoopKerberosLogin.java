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
package org.apache.polaris.extensions.federation.hadoop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.security.auth.login.AppConfigurationEntry;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs the process in from the keytab named by the JVM JAAS configuration and enables Hadoop's
 * keytab renewal thread. JAAS supplies the principal and keytab; it does not renew tickets.
 */
public final class HadoopKerberosLogin {
  static final String CLIENT_ENTRY = "Client";
  static final String GSS_INITIATE_ENTRY = "com.sun.security.jgss.krb5.initiate";

  private static final Logger LOG = LoggerFactory.getLogger(HadoopKerberosLogin.class);
  private static final HadoopKerberosLogin INSTALLED =
      new HadoopKerberosLogin(
          HadoopKerberosLogin::installedEntries, HadoopKerberosLogin::loginFromKeytab);

  private final JaasEntries entries;
  private final KeytabLogin login;
  private boolean loggedIn;

  HadoopKerberosLogin(JaasEntries entries, KeytabLogin login) {
    this.entries = entries;
    this.login = login;
  }

  /** Logs in once when the installed JAAS configuration names a keytab. */
  public static void ensureLoggedIn() {
    INSTALLED.ensure();
  }

  synchronized void ensure() {
    if (loggedIn) {
      return;
    }
    KeytabIdentity identity = findKeytabIdentity(entries);
    if (identity == null) {
      return;
    }
    if (identity.principal == null) {
      throw new IllegalStateException(
          "Kerberos JAAS entry " + identity.entryName + " sets keyTab without a principal");
    }
    if (!Files.isRegularFile(Path.of(identity.keytab))) {
      throw new IllegalStateException("Kerberos keytab does not exist: " + identity.keytab);
    }
    try {
      login.login(identity.principal, identity.keytab);
    } catch (IOException e) {
      throw new IllegalStateException("Kerberos keytab login failed for " + identity.principal, e);
    }
    loggedIn = true;
    LOG.info("Logged in {} from keytab for automatic Kerberos renewal", identity.principal);
  }

  private static KeytabIdentity findKeytabIdentity(JaasEntries entries) {
    KeytabIdentity identity = identityFrom(CLIENT_ENTRY, entries.entries(CLIENT_ENTRY));
    if (identity == null) {
      identity = identityFrom(GSS_INITIATE_ENTRY, entries.entries(GSS_INITIATE_ENTRY));
    }
    return identity;
  }

  private static KeytabIdentity identityFrom(String entryName, AppConfigurationEntry[] configured) {
    if (configured == null) {
      return null;
    }
    for (AppConfigurationEntry entry : configured) {
      String keytab = option(entry.getOptions(), "keyTab");
      if (keytab == null) {
        continue;
      }
      return new KeytabIdentity(entryName, option(entry.getOptions(), "principal"), keytab);
    }
    return null;
  }

  private static String option(Map<String, ?> options, String name) {
    Object value = options.get(name);
    if (value == null) {
      return null;
    }
    String text = value.toString();
    if (text.isBlank()) {
      return null;
    }
    return text;
  }

  private static AppConfigurationEntry[] installedEntries(String name) {
    return javax.security.auth.login.Configuration.getConfiguration()
        .getAppConfigurationEntry(name);
  }

  private static void loginFromKeytab(String principal, String keytab) throws IOException {
    Configuration conf = new Configuration();
    conf.setBoolean(
        CommonConfigurationKeysPublic.HADOOP_KERBEROS_KEYTAB_LOGIN_AUTORENEWAL_ENABLED, true);
    UserGroupInformation.setConfiguration(conf);
    UserGroupInformation.loginUserFromKeytab(principal, keytab);
  }

  @FunctionalInterface
  interface JaasEntries {
    AppConfigurationEntry[] entries(String name);
  }

  @FunctionalInterface
  interface KeytabLogin {
    void login(String principal, String keytab) throws IOException;
  }

  private static final class KeytabIdentity {
    private final String entryName;
    private final String principal;
    private final String keytab;

    private KeytabIdentity(String entryName, String principal, String keytab) {
      this.entryName = entryName;
      this.principal = principal;
      this.keytab = keytab;
    }
  }
}
