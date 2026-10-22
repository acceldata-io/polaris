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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HadoopKerberosLoginTest {
  private static final String PRINCIPAL = "polaris/host@EXAMPLE.COM";

  @TempDir Path tempDir;

  @Test
  void missingJaasEntrySkipsLogin() {
    RecordingLogin login = new RecordingLogin();
    HadoopKerberosLogin kerberos = new HadoopKerberosLogin(name -> null, login);

    kerberos.ensure();
    kerberos.ensure();

    assertThat(login.calls).isEmpty();
  }

  @Test
  void keytabWithoutPrincipalFails() {
    RecordingLogin login = new RecordingLogin();
    HadoopKerberosLogin kerberos =
        new HadoopKerberosLogin(
            name ->
                HadoopKerberosLogin.CLIENT_ENTRY.equals(name)
                    ? new AppConfigurationEntry[] {entry(Map.of("keyTab", "/tmp/polaris.keytab"))}
                    : null,
            login);

    assertThatThrownBy(kerberos::ensure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("without a principal");
    assertThat(login.calls).isEmpty();
  }

  @Test
  void missingKeytabFileFails() {
    Path missing = tempDir.resolve("missing.keytab");
    RecordingLogin login = new RecordingLogin();
    HadoopKerberosLogin kerberos = loginFor(missing, login);

    assertThatThrownBy(kerberos::ensure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not exist");
    assertThat(login.calls).isEmpty();
  }

  @Test
  void validKeytabLogsInOnce() throws IOException {
    Path keytab = Files.createFile(tempDir.resolve("polaris.keytab"));
    RecordingLogin login = new RecordingLogin();
    HadoopKerberosLogin kerberos = loginFor(keytab, login);

    kerberos.ensure();
    kerberos.ensure();

    assertThat(login.calls).containsExactly(PRINCIPAL + " " + keytab);
  }

  @Test
  void gssEntryIsUsedWhenClientHasNoKeytab() throws IOException {
    Path keytab = Files.createFile(tempDir.resolve("polaris.keytab"));
    RecordingLogin login = new RecordingLogin();
    HadoopKerberosLogin kerberos =
        new HadoopKerberosLogin(
            name -> {
              if (HadoopKerberosLogin.CLIENT_ENTRY.equals(name)) {
                return new AppConfigurationEntry[] {entry(Map.of("useKeyTab", "true"))};
              }
              if (HadoopKerberosLogin.GSS_INITIATE_ENTRY.equals(name)) {
                return new AppConfigurationEntry[] {keytabEntry(keytab)};
              }
              return null;
            },
            login);

    kerberos.ensure();

    assertThat(login.calls).containsExactly(PRINCIPAL + " " + keytab);
  }

  @Test
  void failedLoginCanBeRetried() throws IOException {
    Path keytab = Files.createFile(tempDir.resolve("polaris.keytab"));
    RecordingLogin login = new RecordingLogin();
    login.failure = new IOException("kdc unavailable");
    HadoopKerberosLogin kerberos = loginFor(keytab, login);

    assertThatThrownBy(kerberos::ensure)
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(IOException.class);
    login.failure = null;
    kerberos.ensure();

    assertThat(login.calls).containsExactly(PRINCIPAL + " " + keytab, PRINCIPAL + " " + keytab);
  }

  private HadoopKerberosLogin loginFor(Path keytab, RecordingLogin login) {
    return new HadoopKerberosLogin(
        name ->
            HadoopKerberosLogin.CLIENT_ENTRY.equals(name)
                ? new AppConfigurationEntry[] {keytabEntry(keytab)}
                : null,
        login);
  }

  private static AppConfigurationEntry keytabEntry(Path keytab) {
    Map<String, String> options = new HashMap<>();
    options.put("principal", PRINCIPAL);
    options.put("keyTab", keytab.toString());
    options.put("useKeyTab", "true");
    return entry(options);
  }

  private static AppConfigurationEntry entry(Map<String, String> options) {
    return new AppConfigurationEntry(
        "com.sun.security.auth.module.Krb5LoginModule", LoginModuleControlFlag.REQUIRED, options);
  }

  private static final class RecordingLogin implements HadoopKerberosLogin.KeytabLogin {
    private final List<String> calls = new ArrayList<>();
    private IOException failure;

    @Override
    public void login(String principal, String keytab) throws IOException {
      calls.add(principal + " " + keytab);
      if (failure != null) {
        throw failure;
      }
    }
  }
}
