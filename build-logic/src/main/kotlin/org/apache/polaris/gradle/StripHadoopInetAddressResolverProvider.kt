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

package org.apache.polaris.gradle

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath

/**
 * hadoop-client-runtime advertises
 * org.apache.hadoop.shaded.org.xbill.DNS.spi.DnsjavaInetAddressResolverProvider, but that class is
 * not in the jar. ServiceLoader treats the missing class as fatal, so a flat test classpath aborts
 * the JVM on the first hostname lookup. The rest of the jar is required at runtime.
 */
@CacheableTransform
abstract class StripHadoopInetAddressResolverProvider : TransformAction<TransformParameters.None> {
  companion object {
    const val SERVICE_ENTRY = "META-INF/services/java.net.spi.InetAddressResolverProvider"
    private const val JAR_NAME = "hadoop-client-runtime"
  }

  @get:Classpath @get:InputArtifact abstract val inputArtifact: Provider<FileSystemLocation>

  override fun transform(outputs: TransformOutputs) {
    val input = inputArtifact.get().asFile
    if (!input.isFile || !input.name.contains(JAR_NAME) || !containsServiceEntry(input)) {
      outputs.file(input)
      return
    }

    val output = outputs.file(input.name)
    Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
    FileSystems.newFileSystem(output.toPath(), emptyMap<String, Any>()).use { fileSystem ->
      Files.deleteIfExists(fileSystem.getPath(SERVICE_ENTRY))
    }
  }

  private fun containsServiceEntry(jar: File): Boolean =
    ZipFile(jar).use { zip -> zip.getEntry(SERVICE_ENTRY) != null }
}
