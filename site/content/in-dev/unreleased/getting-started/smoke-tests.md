---
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
title: Standalone Smoke Tests
linkTitle: Smoke Tests
type: docs
weight: 110
---

Manual smoke-test checklist for a **standalone Polaris server** co-located with supporting services
(Postgres, Hive Metastore, HDFS, Ozone, MinIO/S3, Ranger, Spark 3 / Spark 4).

Covers environment setup, auth/metastore/storage matrix (**FILE**, **AWS S3**, **MinIO**, **Ozone**),
**Iceberg** engine flows, and **non-Iceberg** flows (generic tables via REST, Delta, Hudi via the
Polaris Spark client).

Each step lists **purpose**, **commands**, and **expected results**. Mark a combo green only when
every step in that combo’s section passes.

> **Important storage note.** Polaris INTERNAL catalogs support storage types `FILE`, `S3`, `GCS`,
> and `AZURE` only. There is **no** first-class `hdfs://` INTERNAL catalog type. Use:
>
> - `FILE` for local filesystem warehouses
> - `S3` for AWS S3, MinIO, Apache Ozone, and other S3-compatible stores
> - **Hive Metastore federation** or **Hadoop catalog federation** when the warehouse lives on HDFS
>   and ambient Hadoop config + process identity can reach it

---

## 1. Prerequisites

Polaris server modules require **Java 21+**. On a shared host (RHEL 8/9 or Ubuntu 20/22) other
Apache components (Hadoop, Hive, Spark, Ranger, Ozone) often need **Java 8 or 11**. Install Java 21
**side by side** and point only the Polaris process at it via `JAVA_HOME` — do not change the
system-wide default JDK used by those other services.

`bin/server` (and `bin/admin`) resolve Java as `${JAVA_HOME}/bin/java` when `JAVA_HOME` is set,
otherwise the first `java` on `PATH`.

### 1.1 Install Java 21 (side by side)

#### Option A — Distro OpenJDK packages

**RHEL 8 / RHEL 9 (and compatible):**

```shell
# RHEL 9 / recent AppStream
sudo dnf install -y java-21-openjdk java-21-openjdk-devel

# If java-21-* is not in your enabled repos on RHEL 8, use Option B (Temurin) instead.
rpm -q java-21-openjdk
ls /usr/lib/jvm/
```

**Ubuntu 22.04:**

```shell
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk
update-java-alternatives -l | grep 21 || true
ls /usr/lib/jvm/
```

**Ubuntu 20.04:** `openjdk-21` is often unavailable in the default archives. Prefer **Option B**
(Temurin) or another vendor JDK 21 build.

#### Option B — Eclipse Temurin 21 (portable; recommended on mixed hosts)

Works the same on RHEL 8/9 and Ubuntu 20/22 without changing the default `java` alternatives.

```shell
# Example: unpack a Temurin 21 JDK under /opt (adjust URL/version to a current build)
sudo mkdir -p /opt/java
cd /tmp
# Download Temurin 21 Linux x64 JDK from https://adoptium.net/ (or your mirror), then:
# sudo tar -xzf OpenJDK21U-jdk_x64_linux_hotspot_*.tar.gz -C /opt/java
# sudo mv /opt/java/jdk-21* /opt/java/jdk-21

# Or use the Adoptium RPM/DEB packages if you manage hosts with package managers.
```

Leave Hadoop/Hive/Spark on their existing JDK (8/11/17). Do **not** run
`alternatives --set java ...` / `update-alternatives --config java` to Java 21 unless you intend
every service on the box to use 21.

#### Verify Java 21 for Polaris only

```shell
# Pick the JDK 21 home for this shell (examples — use the path that exists on your host)
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk   # RHEL package layout (may be java-21-openjdk-21.*)
# export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # Ubuntu 22.04 package layout
# export JAVA_HOME=/opt/java/jdk-21                     # Temurin under /opt

export PATH="${JAVA_HOME}/bin:${PATH}"

"${JAVA_HOME}/bin/java" -version
# Expected: openjdk version "21...." (or Temurin 21)

# Confirm other services still see their own Java when JAVA_HOME is unset in a fresh shell:
# (hadoop/hive/spark scripts should keep using their configured JAVA_HOME)
```

**Expected:** `java -version` in the Polaris shell reports 21.x. A login shell without this
`JAVA_HOME` still runs the cluster’s previous default for other components.

### 1.2 Services on this host

Confirm the co-located stack is up before starting Polaris:

| Service | Typical check | Notes |
|---|---|---|
| Postgres | `psql -h localhost -U postgres -d POLARIS -c 'SELECT 1'` | Needed for JDBC metastore combos |
| Hive Metastore | `nc -zv <hms-host> 9083` (or your Thrift port) | Needed for Hive federation |
| HDFS | `hdfs dfs -ls /` | Needed when warehouse is on HDFS |
| Ozone S3 gateway | `curl -sS http://127.0.0.1:9878` (adjust port) | Needed for Ozone catalogs |
| MinIO / S3 gateway | `curl -sS http://127.0.0.1:9000/minio/health/live` (adjust) | Needed for MinIO catalogs |
| AWS S3 | `aws s3 ls s3://${S3_BUCKET}/` (or console) | Needed for AWS S3 catalogs |
| Ranger Admin | `curl -sS http://<ranger-host>:6080` | Needed for Ranger authZ combos |
| Spark 3 / Spark 4 | `$SPARK3_HOME/bin/spark-sql --version`, `$SPARK4_HOME/bin/spark-sql --version` | Iceberg SQL smoke |

### 1.3 Client tools

```shell
# jq for token parsing
command -v jq

# Polaris CLI
python -m pip install apache-polaris==1.6.0
polaris --help
```

### 1.4 Environment variables

Export once per **Polaris** shell. Adjust hostnames, paths, and secrets for your cluster.

```shell
# Java 21 for Polaris only (required when the host default JDK is 8/11/17)
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk   # or Temurin / Ubuntu path from §1.1
export PATH="${JAVA_HOME}/bin:${PATH}"

export POLARIS_HOST=localhost
export POLARIS_API=http://${POLARIS_HOST}:8181
export POLARIS_MGMT=http://${POLARIS_HOST}:8182

# Bootstrap / root principal (change if you bootstrap different credentials)
export CLIENT_ID=root
export CLIENT_SECRET=s3cr3t
export REALM=POLARIS

# Postgres (JDBC metastore combos)
export PG_HOST=localhost
export PG_PORT=5432
export PG_DB=POLARIS
export PG_USER=postgres
export PG_PASSWORD=postgres
export QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://${PG_HOST}:${PG_PORT}/${PG_DB}"
export QUARKUS_DATASOURCE_USERNAME="${PG_USER}"
export QUARKUS_DATASOURCE_PASSWORD="${PG_PASSWORD}"

# Hive / HDFS (federation combos)
export HIVE_METASTORE_URI=thrift://localhost:9083
export HDFS_WAREHOUSE=hdfs://localhost:8020/warehouse/polaris
export HADOOP_CONF_DIR=${HADOOP_CONF_DIR:-/etc/hadoop/conf}
export HIVE_CONF_DIR=${HIVE_CONF_DIR:-/etc/hive/conf}

# AWS S3 (native)
export S3_BUCKET=s3://polaris-smoke-bucket
export AWS_ROLE_ARN=arn:aws:iam::123456789012:role/polaris-warehouse-access
export AWS_EXTERNAL_ID=${AWS_EXTERNAL_ID:-}   # optional trust external ID
export AWS_REGION=${AWS_REGION:-us-west-2}
# Polaris process needs credentials that can AssumeRole into AWS_ROLE_ARN
# (instance profile, env keys, or polaris.storage.aws.access-key / secret-key)

# MinIO (S3-compatible)
export MINIO_ENDPOINT=http://127.0.0.1:9000
export MINIO_BUCKET=s3://polaris-smoke
export MINIO_ACCESS_KEY=${MINIO_ACCESS_KEY:-minioadmin}
export MINIO_SECRET_KEY=${MINIO_SECRET_KEY:-minioadmin}

# Ozone (S3-compatible; often no STS)
export OZONE_S3_ENDPOINT=http://127.0.0.1:9878
export OZONE_BUCKET=s3://polaris-smoke
export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID:-ozone}
export AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY:-ozone}

# Ranger
export RANGER_URL=http://localhost:6080
export RANGER_SERVICE_NAME=dev_polaris

# Spark homes
export SPARK3_HOME=${SPARK3_HOME:-/opt/spark3}
export SPARK4_HOME=${SPARK4_HOME:-/opt/spark4}

# Iceberg runtime versions (match your Spark / Iceberg install)
export ICEBERG_VERSION=${ICEBERG_VERSION:-1.10.0}
```

### 1.5 Build Polaris (standalone)

From the Polaris source tree (same Java 21 shell as §1.1 / §1.4):

```shell
cd ~/polaris   # or your checkout path

# Base server + admin tool (no Docker image required for standalone)
./gradlew \
  :polaris-server:assemble \
  :polaris-server:quarkusAppPartsBuild --rerun \
  :polaris-admin:assemble \
  :polaris-admin:quarkusAppPartsBuild --rerun
```

If you will run **Hive Metastore federation**, rebuild with the Hive extension:

```shell
./gradlew \
  :polaris-server:assemble \
  :polaris-server:quarkusAppPartsBuild --rerun \
  -PNonRESTCatalogs=HIVE
```

Binaries without `-PNonRESTCatalogs=HIVE` reject `connectionType: HIVE`.

Locate the runnable bits under `runtime/server/build/` and `runtime/admin/build/` (Quarkus app
layout) or use `./gradlew run` / `bin/server` from a binary distribution.

### 1.6 Start `bin/server` with Java 21

From an extracted Polaris binary/distribution directory (or the assembled distro layout):

```shell
# Ensure this shell uses Java 21 — critical on hosts where `java` defaults to 8/11
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk   # adjust per §1.1
export PATH="${JAVA_HOME}/bin:${PATH}"
"${JAVA_HOME}/bin/java" -version   # must show 21.x

# Optional JVM / Polaris flags (metastore, FILE storage, ports, etc.)
export POLARIS_JAVA_OPTS='
  -Xms512m -Xmx2g
  -Dpolaris.persistence.type=in-memory
  -Dpolaris.authentication.type=internal
  -Dpolaris.authorization.type=internal
  -Dpolaris.bootstrap.credentials=POLARIS,root,s3cr3t
  -Dpolaris.features."ALLOW_INSECURE_STORAGE_TYPES"=true
  -Dpolaris.features."SUPPORTED_CATALOG_STORAGE_TYPES"=["FILE","S3","GCS","AZURE"]
  -Dpolaris.readiness.ignore-severe-issues=true
'

# Run from the distribution root (directory that contains bin/ and server/)
cd /path/to/polaris-bin-<version>   # or your installed distro path
bin/server
```

Admin tool (bootstrap) must use the **same** Java 21:

```shell
export JAVA_HOME=...   # same as above
POLARIS_JAVA_OPTS="-Dpolaris.persistence.type=relational-jdbc ..." \
  bin/admin bootstrap -r POLARIS -c POLARIS,root,s3cr3t
```

**Expected:** Process starts; logs show Quarkus listening on **8181** (API) and **8182** (management).
If startup fails with unsupported class-file / `UnsupportedClassVersionError`, `JAVA_HOME` still
points at an older JDK — fix §1.1 and retry.

**systemd tip (optional):** set `Environment=JAVA_HOME=/usr/lib/jvm/java-21-openjdk` (or Temurin path)
in the Polaris unit file only, so Hadoop/Hive units keep their own `JAVA_HOME`.

---

## 2. Environment matrix

Run combos independently. Prefer a fresh Postgres schema or distinct catalog names per combo so
failures do not cascade.

| ID | AuthN | AuthZ | Metastore | Storage / federation | Build notes |
|---|---|---|---|---|---|
| **A** | internal | internal | in-memory | FILE (local FS) | `./gradlew run` defaults |
| **B** | internal | internal | Postgres (relational-jdbc) | FILE (local FS) | Bootstrap required |
| **C1** | internal | internal | Postgres | **AWS S3** (role ARN + vended credentials) | IAM role + STS |
| **C2** | internal | internal | Postgres | **MinIO** (S3-compatible endpoint) | Path-style + keys |
| **C3** | internal | internal | Postgres | **Ozone** (S3 endpoint, often `--no-sts`) | Bootstrap + Ozone keys |
| **D** | internal | internal | Postgres | Hive federation → HMS + HDFS warehouse | `-PNonRESTCatalogs=HIVE` |
| **E** | internal | internal | Postgres | Hadoop catalog federation | Federation flags |
| **F** | internal | internal | Postgres | Iceberg REST federation | Second REST catalog / same Polaris |
| **G** | internal | **Ranger** | Postgres | FILE or S3 (AWS/MinIO/Ozone) | Ranger ≥ 2.8.0 |
| **H** | internal | Ranger | Postgres | Hive federation + HDFS | Combines D + G |

Suggested order: **A → B → C1 → C2 → C3 → D → E → F → G → H**.

### Core ordered checklist (minimal standalone)

For a first green run, execute **combo A** (in-memory + FILE + internal auth/authZ) in this order.
Each step: purpose → command → expected result.

| Step | Purpose | Where |
|---|---|---|
| 1 | Prerequisites (Java 21, ports, credentials, CLI) | §1 |
| 2 | Health (`/q/health`, ready, metrics) | §3.1 |
| 3 | Auth (OAuth token + bad-secret negative) | §3.2 |
| 4 | Management API (principal-roles, catalogs) | §3.3 |
| 5 | Create FILE catalog (CLI create / list / get) | §4.2 |
| 6 | Catalog config (`GET .../config?warehouse=`) | §3.5 |
| 7 | Principal + roles + privileges + user token | §3.4 |
| 8 | Namespaces (CLI create / list) | §3.6 |
| 9 | Spark Iceberg round-trip (optional but recommended) | §12 |
| 10 | AuthZ revoke / ForbiddenException check | §12.4 rows 14–15 |
| 11 | Cleanup (tables, namespaces, catalog, principal) | §3.7 |
| 12 | Pass criteria summary | §14 |

Extended combos (Postgres, AWS S3 / MinIO / Ozone, Hive/Hadoop/REST federation, Ranger) and
non-Iceberg (Delta/Hudi/generic tables) build on the same helpers after the core path is green.

---

## 3. Shared helpers (all combos)

### 3.1 Health

```shell
curl -sf "${POLARIS_MGMT}/q/health"
curl -sf "${POLARIS_MGMT}/q/health/ready"
curl -sf "${POLARIS_MGMT}/q/metrics" | head
```

**Expected:** HTTP 200; health JSON shows overall status `UP`.

### 3.2 Obtain root token

```shell
export POLARIS_TOKEN=$(
  curl -sS "${POLARIS_API}/api/catalog/v1/oauth/tokens" \
    --user "${CLIENT_ID}:${CLIENT_SECRET}" \
    -d 'grant_type=client_credentials' \
    -d 'scope=PRINCIPAL_ROLE:ALL' | jq -r .access_token
)
test -n "${POLARIS_TOKEN}" && test "${POLARIS_TOKEN}" != "null"
echo "token length: ${#POLARIS_TOKEN}"
```

**Expected:** Non-empty bearer token.

**Negative:**

```shell
curl -sS -o /tmp/bad-auth.json -w "%{http_code}\n" \
  "${POLARIS_API}/api/catalog/v1/oauth/tokens" \
  --user "root:wrong-secret" \
  -d 'grant_type=client_credentials' \
  -d 'scope=PRINCIPAL_ROLE:ALL'
# Expect 401 (or non-200) and no usable access_token
```

### 3.3 Management API sanity

```shell
curl -sf -H "Authorization: Bearer ${POLARIS_TOKEN}" \
  "${POLARIS_API}/api/management/v1/principal-roles" | jq .
curl -sf -H "Authorization: Bearer ${POLARIS_TOKEN}" \
  "${POLARIS_API}/api/management/v1/catalogs" | jq .
```

**Expected:** HTTP 200 JSON lists (may be empty catalogs on a fresh metastore).

### 3.4 Create principal, roles, and privileges

Replace `CATALOG_NAME` with the catalog under test.

```shell
CATALOG_NAME=smoke_file_catalog   # change per combo

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  principals create smoke_user

# Capture credentials printed by the CLI, then:
# export USER_CLIENT_ID=...
# export USER_CLIENT_SECRET=...

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  principal-roles create smoke_user_role

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  catalog-roles create --catalog "${CATALOG_NAME}" smoke_catalog_role

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  principal-roles grant --principal smoke_user smoke_user_role

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  catalog-roles grant \
    --catalog "${CATALOG_NAME}" \
    --principal-role smoke_user_role \
    smoke_catalog_role

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  privileges catalog grant \
    --catalog "${CATALOG_NAME}" \
    --catalog-role smoke_catalog_role \
    CATALOG_MANAGE_CONTENT
```

**Expected:** Each command succeeds; `principals create` returns `clientId` / `clientSecret`.

User token:

```shell
export USER_TOKEN=$(
  curl -sS "${POLARIS_API}/api/catalog/v1/oauth/tokens" \
    --user "${USER_CLIENT_ID}:${USER_CLIENT_SECRET}" \
    -d 'grant_type=client_credentials' \
    -d 'scope=PRINCIPAL_ROLE:ALL' | jq -r .access_token
)
test -n "${USER_TOKEN}" && test "${USER_TOKEN}" != "null"
```

### 3.5 Catalog config check

```shell
curl -sf -H "Authorization: Bearer ${POLARIS_TOKEN}" \
  "${POLARIS_API}/api/catalog/v1/config?warehouse=${CATALOG_NAME}" | jq .
```

**Expected:** HTTP 200 with catalog configuration overrides.

### 3.6 Namespaces (CLI)

```shell
polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  namespaces create --catalog "${CATALOG_NAME}" smoke_ns

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  namespaces create --catalog "${CATALOG_NAME}" smoke_ns.schema1

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  namespaces list --catalog "${CATALOG_NAME}"

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  namespaces list --catalog "${CATALOG_NAME}" --parent smoke_ns

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  namespaces get --catalog "${CATALOG_NAME}" smoke_ns.schema1
```

**Expected:** Create succeeds; top-level list shows `smoke_ns`; `--parent smoke_ns` lists `schema1`;
`get` returns the nested namespace.

### 3.7 Cleanup

Drop engine objects first (Iceberg / Delta / Hudi tables and views — §§12–13), then:

```shell
# Namespaces (child before parent)
polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  namespaces delete --catalog "${CATALOG_NAME}" smoke_ns.schema1 2>/dev/null || true

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  namespaces delete --catalog "${CATALOG_NAME}" smoke_ns 2>/dev/null || true

# Catalog
polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  catalogs delete "${CATALOG_NAME}"

# Principal / roles (order may require revoking grants first on some builds)
polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  catalog-roles delete --catalog "${CATALOG_NAME}" smoke_catalog_role 2>/dev/null || true

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  principal-roles delete smoke_user_role 2>/dev/null || true

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  principals delete smoke_user 2>/dev/null || true
```

**Expected:** Entities removed (or already gone). For **in-memory** persistence, a server restart
also wipes all state.

---

## 4. Combo A — Internal auth + internal authZ + in-memory + local FILE

### 4.1 Setup / start

In-memory persistence needs no Postgres bootstrap. Enable FILE storage. Use the Java 21 shell from
§1.1 / §1.6 (`JAVA_HOME` pointing at JDK 21):

```shell
# Preferred on shared hosts: binary distro with explicit JAVA_HOME
# export JAVA_HOME=...   # §1.1
# export POLARIS_JAVA_OPTS='...'  # see §1.6
# bin/server

./gradlew run
# Gradle run already sets:
#   polaris.bootstrap.credentials=POLARIS,root,s3cr3t
#   ALLOW_INSECURE_STORAGE_TYPES=true
#   SUPPORTED_CATALOG_STORAGE_TYPES including FILE
```

Or standalone JVM via `bin/server` (see §1.6 for the full Java 21 + `POLARIS_JAVA_OPTS` example):

```shell
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk   # adjust per §1.1
export POLARIS_JAVA_OPTS='
  -Dpolaris.persistence.type=in-memory
  -Dpolaris.authentication.type=internal
  -Dpolaris.authorization.type=internal
  -Dpolaris.bootstrap.credentials=POLARIS,root,s3cr3t
  -Dpolaris.features."ALLOW_INSECURE_STORAGE_TYPES"=true
  -Dpolaris.features."SUPPORTED_CATALOG_STORAGE_TYPES"=["FILE","S3","GCS","AZURE"]
  -Dpolaris.features."DROP_WITH_PURGE_ENABLED"=true
  -Dpolaris.readiness.ignore-severe-issues=true
'
# then: bin/server
```

**Expected:** Ports 8181 / 8182 listening; section 3.1–3.3 pass.

### 4.2 Create FILE catalog

```shell
export CATALOG_NAME=smoke_file_im
mkdir -p /tmp/polaris-smoke/${CATALOG_NAME}

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  catalogs create \
  --storage-type file \
  --default-base-location "file:///tmp/polaris-smoke/${CATALOG_NAME}" \
  --allowed-location "file:///tmp/polaris-smoke/${CATALOG_NAME}" \
  "${CATALOG_NAME}"

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  catalogs get "${CATALOG_NAME}"
```

**Expected:** Catalog created; `get` shows `storageType` FILE and the base location.

### 4.3 AuthZ bootstrap + Iceberg use cases

Run sections **3.4–3.6** (principal/roles/privileges, catalog config, **CLI namespaces**), then
section **12 (Iceberg)** and section **13 (non-Iceberg)** against Spark 3 and Spark 4 with
`warehouse=${CATALOG_NAME}` and FILE-friendly packages (no AWS bundle required for pure `file://`).

§3.6 creates `smoke_ns` / `smoke_ns.schema1` via the Polaris CLI. §12.4 uses the same names with
`CREATE NAMESPACE IF NOT EXISTS`, so Spark is idempotent if the CLI step already ran — but do **not**
skip §3.6; it is the CLI namespace smoke on the core path.

### 4.4 Cleanup / notes

In-memory state is lost on process restart. Optional delete:

```shell
# Drop Iceberg / generic objects first (sections 12–13 cleanup), then:
polaris ... catalogs delete "${CATALOG_NAME}"
```

---

## 5. Combo B — Internal auth + internal authZ + Postgres + local FILE

### 5.1 Setup Postgres

```shell
createdb -h "${PG_HOST}" -U "${PG_USER}" "${PG_DB}"   # if not already present
psql -h "${PG_HOST}" -U "${PG_USER}" -d "${PG_DB}" -c 'SELECT version();'
```

### 5.2 Bootstrap metastore

```shell
java \
  -Dpolaris.persistence.type=relational-jdbc \
  -Dquarkus.datasource.username="${PG_USER}" \
  -Dquarkus.datasource.password="${PG_PASSWORD}" \
  -Dquarkus.datasource.jdbc.url="${QUARKUS_DATASOURCE_JDBC_URL}" \
  -jar path/to/polaris-admin-tool.jar \
  bootstrap -r "${REALM}" -c "${REALM},${CLIENT_ID},${CLIENT_SECRET}"
```

Or with the distribution:

```shell
export POLARIS_JAVA_OPTS="
  -Dpolaris.persistence.type=relational-jdbc
  -Dquarkus.datasource.username=${PG_USER}
  -Dquarkus.datasource.password=${PG_PASSWORD}
  -Dquarkus.datasource.jdbc.url=${QUARKUS_DATASOURCE_JDBC_URL}
"
bin/admin bootstrap -r "${REALM}" -c "${REALM},${CLIENT_ID},${CLIENT_SECRET}"
```

**Expected:** Bootstrap completes; schema `polaris_schema` exists in `${PG_DB}`.

### 5.3 Start Polaris

```shell
export POLARIS_PERSISTENCE_TYPE=relational-jdbc
export QUARKUS_DATASOURCE_USERNAME="${PG_USER}"
export QUARKUS_DATASOURCE_PASSWORD="${PG_PASSWORD}"
export QUARKUS_DATASOURCE_JDBC_URL="${QUARKUS_DATASOURCE_JDBC_URL}"

export POLARIS_JAVA_OPTS='
  -Dpolaris.authentication.type=internal
  -Dpolaris.authorization.type=internal
  -Dpolaris.features."ALLOW_INSECURE_STORAGE_TYPES"=true
  -Dpolaris.features."SUPPORTED_CATALOG_STORAGE_TYPES"=["FILE","S3","GCS","AZURE"]
  -Dpolaris.features."DROP_WITH_PURGE_ENABLED"=true
  -Dpolaris.readiness.ignore-severe-issues=true
'
# start server (bin/server or Quarkus runner)
```

**Expected:** Health UP; token works across a server restart (state survives in Postgres).

### 5.4 Catalog + Iceberg

Same as combo A with a distinct name:

```shell
export CATALOG_NAME=smoke_file_pg
# create FILE catalog → §3.4–3.6 → sections 12–13
```

**Restart durability check:** Restart Polaris, re-fetch token, `catalogs get ${CATALOG_NAME}` still
succeeds; Spark `SELECT` still sees prior data.

---

## 6. Combos C1–C3 — S3 storage (AWS, MinIO, Ozone)

All three use `--storage-type s3`. Differences are endpoint, STS / credential vending, and how
Spark reaches object storage. Prefer Postgres metastore (combo B bootstrap) so catalogs survive
restarts while you iterate on storage.

Shared Polaris start notes:

- Ensure `S3` is in `SUPPORTED_CATALOG_STORAGE_TYPES` (default includes S3).
- Pass storage credentials into the **Polaris server process** as required by each backend.
- After catalog create: §3.4–3.6 (roles, config, CLI namespaces), then §12 (Iceberg) and §13
  (non-Iceberg / Delta on `s3://`).

### 6.1 Combo C1 — AWS S3 (IAM role + vended credentials)

#### Setup

- Bucket `${S3_BUCKET}` exists; Polaris service identity can `sts:AssumeRole` on `${AWS_ROLE_ARN}`.
- Role trust policy allows Polaris; role permissions cover `s3:GetObject` / `PutObject` / `ListBucket`
  (and delete if using purge) under the warehouse prefix.
- Optional: set `AWS_EXTERNAL_ID` if the trust policy requires it.
- Server-side AWS credentials: instance profile, env `AWS_ACCESS_KEY_ID` /
  `AWS_SECRET_ACCESS_KEY`, or `polaris.storage.aws.access-key` / `polaris.storage.aws.secret-key`.

#### Create catalog

```shell
export CATALOG_NAME=smoke_aws_s3_pg

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  catalogs create \
  --storage-type s3 \
  --default-base-location "${S3_BUCKET}/smoke/aws" \
  --role-arn "${AWS_ROLE_ARN}" \
  --region "${AWS_REGION}" \
  --external-id "${AWS_EXTERNAL_ID}" \
  "${CATALOG_NAME}"

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  catalogs get "${CATALOG_NAME}"
```

Omit `--external-id` when your IAM trust policy does not require one.

**Expected:** Catalog created; `storageType` S3; `roleArn` / region present.

#### Iceberg / Spark (vended credentials)

Use §12.1 / §12.3 **with** `X-Iceberg-Access-Delegation=vended-credentials` and
`iceberg-aws-bundle` on the Spark classpath. Engines should **not** need static bucket keys when
vending works.

```shell
# Spark packages example (add to §12 session):
# --packages ...,org.apache.iceberg:iceberg-aws-bundle:${ICEBERG_VERSION}
```

#### S3-specific Iceberg checks

| # | Use case | Action | Expected |
|---|---|---|---|
| 1 | Warehouse prefix | After `CREATE TABLE`, list `${S3_BUCKET}/smoke/aws/...` via `aws s3 ls` | Metadata/data objects under catalog prefix |
| 2 | Insert / select | §12.4 insert/select | Rows round-trip; new data files in S3 |
| 3 | Drop with purge | `DROP TABLE ... PURGE` (requires `DROP_WITH_PURGE_ENABLED`) | Table gone; objects removed or marked for purge per config |
| 4 | Cross-engine | Optional second Spark/Trino session same warehouse | Sees committed data |
| 5 | Cred vending failure | Break role trust / deny `AssumeRole`, retry insert | Clear auth/storage error (not silent success) |

#### Delta on AWS S3 (non-Iceberg)

Use Polaris Spark client (§13.3) with Delta locations under `${S3_BUCKET}/smoke/aws/delta/...` and
vended credentials when supported; otherwise configure Spark S3A with credentials that can access
the bucket.

---

### 6.2 Combo C2 — MinIO (S3-compatible)

#### Setup

- MinIO listening at `${MINIO_ENDPOINT}`; bucket in `${MINIO_BUCKET}` exists.
- Export MinIO keys for the Polaris process:

```shell
export AWS_ACCESS_KEY_ID="${MINIO_ACCESS_KEY}"
export AWS_SECRET_ACCESS_KEY="${MINIO_SECRET_KEY}"
export AWS_REGION="${AWS_REGION}"
```

#### Create catalog

```shell
export CATALOG_NAME=smoke_minio_pg

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  catalogs create \
  --storage-type s3 \
  --endpoint "${MINIO_ENDPOINT}" \
  --path-style-access \
  --default-base-location "${MINIO_BUCKET}/smoke" \
  --region "${AWS_REGION}" \
  "${CATALOG_NAME}"
```

If Polaris and clients need different URLs (Docker vs host), add `--endpoint-internal` for the
server-side endpoint. If your MinIO setup supports STS against the same endpoint, you may set
`--sts-endpoint "${MINIO_ENDPOINT}"` and try vended credentials; otherwise use static keys (§12.2).

**Expected:** Catalog create succeeds; `catalogs get` shows custom endpoint + path-style access.

#### Iceberg / Spark

1. Prefer vended credentials if STS is available on MinIO.
2. Otherwise use §12.2-style static config:

```shell
# --packages ... iceberg-aws-bundle ...
# --conf spark.hadoop.fs.s3a.endpoint=${MINIO_ENDPOINT}
# --conf spark.hadoop.fs.s3a.path.style.access=true
# --conf spark.hadoop.fs.s3a.access.key=${MINIO_ACCESS_KEY}
# --conf spark.hadoop.fs.s3a.secret.key=${MINIO_SECRET_KEY}
# Do NOT set X-Iceberg-Access-Delegation=vended-credentials when STS is unavailable
```

#### MinIO-specific checks

| # | Use case | Action | Expected |
|---|---|---|---|
| 1 | Wrong endpoint | Create catalog without `--endpoint` (points at real AWS) | Fail or unusable; fixing endpoint restores access |
| 2 | Path-style | Create table + insert | Objects visible in MinIO console under bucket prefix |
| 3 | Iceberg CRUD | §12.4 on `${CATALOG_NAME}` | Pass |
| 4 | Delta on MinIO | §13 with `LOCATION 's3://.../delta_t'` | Create/insert/select/drop |
| 5 | Regtest cousin | Optional: `S3_TEST_BACKEND=minio` + `regtests/t_spark_sql` S3 script | Matches automated path |

---

### 6.3 Combo C3 — Ozone (S3-compatible, typically no STS)

#### Setup

- Ozone S3 gateway at `${OZONE_S3_ENDPOINT}`; bucket exists.
- Export `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` for Polaris and Spark (any values if Ozone S3
  auth is disabled in your cluster).

#### Create catalog

```shell
export CATALOG_NAME=smoke_ozone_pg

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  catalogs create \
  --storage-type s3 \
  --endpoint "${OZONE_S3_ENDPOINT}" \
  --no-sts \
  --path-style-access \
  --default-base-location "${OZONE_BUCKET}/smoke" \
  --region "${AWS_REGION}" \
  "${CATALOG_NAME}"
```

**Expected:** Catalog create succeeds; `catalogs get` shows S3 storage with your endpoint.

> With `--no-sts`, engines must **not** send `X-Iceberg-Access-Delegation=vended-credentials`.
> Configure Spark with static Ozone credentials instead (section 12.2).

#### AuthZ + Iceberg / non-Iceberg

Section 3.4–3.6, then sections 12–13 with Ozone packages (`iceberg-aws-bundle` or equivalent) and
**without** vended-credentials header. Repeat key §12.4 and §13 Delta checks against Ozone paths.

---

### 6.4 S3 use-case matrix (quick compare)

| Use case | AWS S3 (C1) | MinIO (C2) | Ozone (C3) |
|---|---|---|---|
| Catalog `--storage-type s3` | Yes | Yes | Yes |
| `--role-arn` / STS assume | Yes (typical) | Optional / mock ARN | Usually `--no-sts` |
| `--endpoint` + path-style | No (AWS public) | Yes | Yes |
| Vended credentials header | Yes | If STS configured | No |
| Iceberg create/insert/select | §12 | §12 | §12 |
| Drop purge | Yes | Yes | Yes |
| Delta generic table on `s3://` | §13 | §13 | §13 |
| Verify objects in store | `aws s3 ls` | MinIO console / `mc ls` | Ozone / S3 API |

---

## 7. Combo D — Hive Metastore federation (HDFS warehouse)

### 7.1 Build / feature flags

Rebuild with Hive support (section 1.4), then start Polaris (Postgres recommended) with:

```properties
polaris.persistence.type=relational-jdbc
polaris.authentication.type=internal
polaris.authorization.type=internal
polaris.features."ENABLE_CATALOG_FEDERATION"=true
polaris.features."SUPPORTED_CATALOG_CONNECTION_TYPES"=["ICEBERG_REST","HIVE"]
polaris.features."SUPPORTED_EXTERNAL_CATALOG_AUTHENTICATION_TYPES"=["OAUTH","IMPLICIT"]
polaris.features."ALLOW_OVERLAPPING_CATALOG_URLS"=true
polaris.features."ENABLE_SUB_CATALOG_RBAC_FOR_FEDERATED_CATALOGS"=true
```

### 7.2 Hadoop / Hive client config for the Polaris process

```shell
export HADOOP_CONF_DIR=...   # core-site.xml, hdfs-site.xml
export HIVE_CONF_DIR=...     # hive-site.xml pointing at HMS
# If Kerberos:
# export KRB5_CONFIG=...
# kinit -kt /path/to/polaris.keytab polaris/service@REALM
```

Ensure the Polaris OS/Kerberos identity can:

1. Talk Thrift to `${HIVE_METASTORE_URI}`
2. Read/write `${HDFS_WAREHOUSE}` on HDFS

**Expected:** No HMS connection errors in Polaris logs after creating the federated catalog.

### 7.3 Create EXTERNAL Hive-federated catalog

CLI:

```shell
export CATALOG_NAME=smoke_hive_fed

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  catalogs create \
  --type external \
  --storage-type file \
  --default-base-location "${HDFS_WAREHOUSE}" \
  --catalog-connection-type hive \
  --catalog-uri "${HIVE_METASTORE_URI}" \
  --hive-warehouse "${HDFS_WAREHOUSE}" \
  --catalog-authentication-type implicit \
  "${CATALOG_NAME}"
```

If your CLI/build requires S3-shaped `storageConfigInfo` for EXTERNAL catalogs, use Management API
JSON instead (see [Hive Metastore Federation]({{% relref "../../federation/hive-metastore-federation" %}}))
and set `storageConfigInfo` / warehouse to match how Polaris reaches HDFS via ambient Hadoop config.

**Expected:** Catalog create returns success; `config?warehouse=` works with a bearer token.

### 7.4 Prove federation

1. Section 3.4 grants on `${CATALOG_NAME}`.
2. Pre-create (or use existing) Iceberg table in HMS under the warehouse.
3. From Spark via Polaris REST (`warehouse=${CATALOG_NAME}`): `SHOW NAMESPACES`, `SHOW TABLES`,
   `SELECT`, and if grants allow, `INSERT`.
4. Confirm the same change is visible through native Hive/HMS tooling.

**Expected:** Polaris brokers metadata; HMS remains source of truth; Spark round-trip works.

---

## 8. Combo E — Hadoop catalog federation

Hadoop federation is always on the server classpath (no `NonRESTCatalogs` flag). Enable:

```properties
polaris.features."ENABLE_CATALOG_FEDERATION"=true
polaris.features."SUPPORTED_CATALOG_CONNECTION_TYPES"=["ICEBERG_REST","HADOOP"]
polaris.features."SUPPORTED_EXTERNAL_CATALOG_AUTHENTICATION_TYPES"=["IMPLICIT","OAUTH","BEARER","SIGV4"]
```

Create:

```shell
export CATALOG_NAME=smoke_hadoop_fed
# HADOOP_URI / HADOOP_WAREHOUSE: Iceberg HadoopCatalog warehouse root on HDFS or local path
export HADOOP_CATALOG_URI="file:///tmp/hadoop-catalog-warehouse"
export HADOOP_WAREHOUSE="file:///tmp/hadoop-catalog-warehouse"

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  catalogs create \
  --type external \
  --storage-type file \
  --default-base-location "${HADOOP_WAREHOUSE}" \
  --catalog-connection-type hadoop \
  --catalog-uri "${HADOOP_CATALOG_URI}" \
  --hadoop-warehouse "${HADOOP_WAREHOUSE}" \
  --catalog-authentication-type implicit \
  "${CATALOG_NAME}"
```

For an HDFS-backed Hadoop catalog, point `--catalog-uri` / `--hadoop-warehouse` at `hdfs://...`
paths and ensure Polaris has `HADOOP_CONF_DIR` + identity that can access them (same ambient model
as Hive federation).

**Expected:** Catalog create succeeds; Spark can list/load tables known to that Hadoop catalog
warehouse through Polaris.

---

## 9. Combo F — Iceberg REST federation

Use when a second Iceberg REST catalog (another Polaris, Glue REST, etc.) is available. For a
same-host self-federation smoke, create an INTERNAL catalog first, then an EXTERNAL REST facade
pointing at this Polaris (see `regtests/t_catalog_federation`).

```properties
polaris.features."ENABLE_CATALOG_FEDERATION"=true
polaris.features."SUPPORTED_CATALOG_CONNECTION_TYPES"=["ICEBERG_REST"]
polaris.features."ALLOW_OVERLAPPING_CATALOG_URLS"=true
```

Example EXTERNAL create (adjust remote URI/credentials):

```shell
export CATALOG_NAME=smoke_rest_fed
export REMOTE_CATALOG=smoke_file_pg   # existing INTERNAL catalog name on remote/same server

polaris --host "${POLARIS_HOST}" --port 8181 \
  --client-id "${CLIENT_ID}" --client-secret "${CLIENT_SECRET}" \
  catalogs create \
  --type EXTERNAL \
  --storage-type file \
  --default-base-location "file:///tmp/polaris-smoke/${REMOTE_CATALOG}" \
  --catalog-connection-type iceberg-rest \
  --iceberg-remote-catalog-name "${REMOTE_CATALOG}" \
  --catalog-uri "${POLARIS_API}/api/catalog" \
  --catalog-authentication-type OAUTH \
  --catalog-token-uri "${POLARIS_API}/api/catalog/v1/oauth/tokens" \
  --catalog-client-id "${CLIENT_ID}" \
  --catalog-client-secret "${CLIENT_SECRET}" \
  --catalog-client-scope "PRINCIPAL_ROLE:ALL" \
  "${CATALOG_NAME}"
```

**Expected:** `SHOW NAMESPACES` / `SELECT` via `warehouse=${CATALOG_NAME}` mirrors the remote
catalog; writes (if permitted) appear when querying the remote warehouse directly.

---

## 10. Combo G — Internal auth + Ranger authZ

Requires Apache Ranger **2.8.0+** and a Polaris service definition in Ranger Admin.

### 10.1 Setup

1. In Ranger Admin, create / enable service `${RANGER_SERVICE_NAME}` for Polaris.
2. Define policies that allow `root` (and later `smoke_user`) the catalog operations you will test.
3. Start Polaris with Postgres (combo B) plus:

```properties
polaris.authorization.type=ranger
polaris.authorization.ranger.service-name=dev_polaris
polaris.authorization.ranger.authz.default.policy.source.impl=org.apache.ranger.admin.client.RangerAdminRESTClient
polaris.authorization.ranger.authz.default.policy.rest.url=http://localhost:6080
# Optional audit:
# polaris.authorization.ranger.authz.audit.destination.solr=enabled
# polaris.authorization.ranger.authz.audit.destination.solr.urls=http://solr:8983/solr/ranger_audits
```

AuthN stays `polaris.authentication.type=internal`.

### 10.2 Smoke checks

| Step | Action | Expected |
|---|---|---|
| Health / token | Section 3.1–3.2 | Pass |
| Allowed op | Create FILE/S3 catalog + Spark SELECT as policy-allowed principal | Success |
| Denied op | Principal **without** Ranger policy tries `CREATE TABLE` / `INSERT` | `ForbiddenException` / HTTP 403 |
| Audit | Open Ranger audit UI | Access events recorded (if audit enabled) |

Internal Polaris privilege grants (section 3.4) may still be created, but **Ranger is the
authorizer** when `polaris.authorization.type=ranger` — policies in Ranger Admin decide allow/deny.

---

## 11. Combo H — Ranger + Hive federation

1. Build with `-PNonRESTCatalogs=HIVE`.
2. Configure federation flags from combo D **and** Ranger props from combo G.
3. Create Hive-federated catalog (7.3).
4. Ensure Ranger policies cover the federated catalog / namespaces / tables under test.
5. Run Spark read/write and a deliberate deny case.

**Expected:** HMS federation works and unauthorized principals are blocked by Ranger.

---

## 12. Iceberg use cases (Spark 3 and Spark 4)

Run these against each combo’s `${CATALOG_NAME}` after principal/role setup. Repeat with Spark 3
and Spark 4.

### 12.1 Spark 3 session (FILE / AWS S3 with vended credentials)

```shell
${SPARK3_HOME}/bin/spark-sql \
  --packages org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:${ICEBERG_VERSION},org.apache.iceberg:iceberg-aws-bundle:${ICEBERG_VERSION} \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
  --conf spark.sql.catalog.polaris=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.polaris.catalog-impl=org.apache.iceberg.rest.RESTCatalog \
  --conf spark.sql.catalog.polaris.uri=${POLARIS_API}/api/catalog \
  --conf spark.sql.catalog.polaris.warehouse=${CATALOG_NAME} \
  --conf spark.sql.catalog.polaris.credential=${USER_CLIENT_ID}:${USER_CLIENT_SECRET} \
  --conf spark.sql.catalog.polaris.scope='PRINCIPAL_ROLE:ALL' \
  --conf spark.sql.catalog.polaris.token-refresh-enabled=true \
  --conf spark.sql.catalog.polaris.client.region=${AWS_REGION} \
  --conf spark.sql.catalog.polaris.header.X-Iceberg-Access-Delegation=vended-credentials
```

- **FILE catalogs:** `iceberg-aws-bundle` and `client.region` may be omitted.
- **AWS S3 (C1):** keep vended-credentials + aws-bundle as above.
- **MinIO / Ozone without STS:** omit the `X-Iceberg-Access-Delegation` line; use §12.2 static
  endpoint config instead.

### 12.2 Spark session for MinIO / Ozone (static credentials, no vended delegation)

```shell
${SPARK3_HOME}/bin/spark-sql \
  --packages org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:${ICEBERG_VERSION},org.apache.iceberg:iceberg-aws-bundle:${ICEBERG_VERSION} \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
  --conf spark.sql.catalog.polaris=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.polaris.catalog-impl=org.apache.iceberg.rest.RESTCatalog \
  --conf spark.sql.catalog.polaris.uri=${POLARIS_API}/api/catalog \
  --conf spark.sql.catalog.polaris.warehouse=${CATALOG_NAME} \
  --conf spark.sql.catalog.polaris.credential=${USER_CLIENT_ID}:${USER_CLIENT_SECRET} \
  --conf spark.sql.catalog.polaris.scope='PRINCIPAL_ROLE:ALL' \
  --conf spark.sql.catalog.polaris.token-refresh-enabled=true \
  --conf spark.sql.catalog.polaris.client.region=${AWS_REGION} \
  --conf spark.hadoop.fs.s3a.endpoint=${MINIO_ENDPOINT:-$OZONE_S3_ENDPOINT} \
  --conf spark.hadoop.fs.s3a.path.style.access=true \
  --conf spark.hadoop.fs.s3a.access.key=${MINIO_ACCESS_KEY:-$AWS_ACCESS_KEY_ID} \
  --conf spark.hadoop.fs.s3a.secret.key=${MINIO_SECRET_KEY:-$AWS_SECRET_ACCESS_KEY}
# Do NOT set X-Iceberg-Access-Delegation=vended-credentials when using --no-sts catalogs
```

### 12.3 Spark 4 session

Use the Iceberg Spark runtime artifact that matches your Spark 4 / Scala line (commonly Scala
2.13). Example shape:

```shell
${SPARK4_HOME}/bin/spark-sql \
  --packages org.apache.iceberg:iceberg-spark-runtime-4.0_2.13:${ICEBERG_VERSION} \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
  --conf spark.sql.catalog.polaris=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.polaris.catalog-impl=org.apache.iceberg.rest.RESTCatalog \
  --conf spark.sql.catalog.polaris.uri=${POLARIS_API}/api/catalog \
  --conf spark.sql.catalog.polaris.warehouse=${CATALOG_NAME} \
  --conf spark.sql.catalog.polaris.credential=${USER_CLIENT_ID}:${USER_CLIENT_SECRET} \
  --conf spark.sql.catalog.polaris.scope='PRINCIPAL_ROLE:ALL' \
  --conf spark.sql.catalog.polaris.token-refresh-enabled=true
```

Adjust the `iceberg-spark-runtime-*` coordinate to the exact artifact published for your Iceberg
version if the coordinate above differs.

### 12.4 SQL checklist

Run inside `spark-sql` after `USE polaris;`.

If you already ran **§3.6**, `smoke_ns` / `smoke_ns.schema1` exist and rows 2–3 below are no-ops
(`IF NOT EXISTS`). If you skipped the CLI step, rows 2–3 create them here — prefer running §3.6
first so the CLI namespace path is covered.

| # | Use case | SQL / action | Expected |
|---|---|---|---|
| 1 | List namespaces | `SHOW NAMESPACES;` | Succeeds; after §3.6 includes `smoke_ns` |
| 2 | Create namespace | `CREATE NAMESPACE IF NOT EXISTS smoke_ns;` | OK |
| 3 | Nested namespace | `CREATE NAMESPACE IF NOT EXISTS smoke_ns.schema1;` | OK |
| 4 | Create table | `CREATE TABLE smoke_ns.schema1.t1 (id BIGINT, data STRING) USING iceberg;` | OK |
| 5 | Insert | `INSERT INTO smoke_ns.schema1.t1 VALUES (1, 'a'), (2, 'b');` | OK |
| 6 | Select | `SELECT * FROM smoke_ns.schema1.t1 ORDER BY id;` | Two rows |
| 7 | Filter / count | `SELECT count(*) FROM smoke_ns.schema1.t1 WHERE id = 1;` | `1` |
| 8 | Schema evolution | `ALTER TABLE smoke_ns.schema1.t1 ADD COLUMN ts TIMESTAMP;` then `DESCRIBE TABLE smoke_ns.schema1.t1;` | New column present |
| 9 | Insert after evolve | `INSERT INTO smoke_ns.schema1.t1 VALUES (3, 'c', NULL);` | OK |
| 10 | View | `CREATE VIEW smoke_ns.schema1.v1 AS SELECT id, data FROM smoke_ns.schema1.t1;` `SELECT * FROM smoke_ns.schema1.v1;` | Rows visible |
| 11 | Time travel (Spark Iceberg) | Inspect snapshots then `SELECT * FROM smoke_ns.schema1.t1 VERSION AS OF <snapshot_id>;` or `... TIMESTAMP AS OF '<ts>';` | Prior snapshot rows |
| 12 | Partitioned table | `CREATE TABLE smoke_ns.schema1.t_part (id BIGINT, day STRING) USING iceberg PARTITIONED BY (day);` `INSERT INTO ...` `SELECT ...` | OK |
| 13 | Drop view / table / ns | `DROP VIEW ...;` `DROP TABLE ... PURGE;` `DROP NAMESPACE ...` | Clean removal |
| 14 | AuthZ revoke (internal authZ only) | Revoke `CATALOG_MANAGE_CONTENT`, retry `INSERT` | `ForbiddenException` |
| 15 | AuthZ restore | Re-grant privilege / fix Ranger policy, retry | Insert works again |

CLI cross-check (optional):

```shell
polaris ... namespaces list --catalog "${CATALOG_NAME}"
polaris ... tables list --catalog "${CATALOG_NAME}" --namespace smoke_ns.schema1
```

---

## 13. Non-Iceberg use cases (generic tables, Delta, Hudi)

Polaris models non-Iceberg tables as **generic tables** (beta). Formats include **Delta**, **Hudi**,
**CSV**, and others the engine understands. Generic-table endpoints are enabled by default
(`polaris.features."ENABLE_GENERIC_TABLES"=true`).

> **Scope notes**
>
> - Prefer **INTERNAL** catalogs (combos A–C, G) for these tests. Iceberg REST federation currently
>   surfaces Iceberg tables only — not generic tables.
> - Generic tables share namespaces with Iceberg tables; **names must be unique** within a namespace.
> - Polaris does **not** coordinate commits for generic tables; the engine owns data consistency.
> - There is **no update API** for generic table metadata — change via drop + recreate.
> - For Spark Delta/Hudi, use the **Polaris Spark client** (`org.apache.polaris.spark.SparkCatalog`),
>   not the Iceberg-only `RESTCatalog` session from section 12.

### 13.1 Feature / build prerequisites

```properties
polaris.features."ENABLE_GENERIC_TABLES"=true
```

Build or obtain the Polaris Spark plugin for your Spark line:

```shell
# Spark 3.5 (Scala 2.12 example)
./gradlew :polaris-spark-3.5_2.12:assemble
# Spark 4.0 (Scala 2.13)
./gradlew :polaris-spark-4.0_2.13:assemble
```

Export package coordinates (released Maven coords or local jars via `--jars`):

```shell
# Examples — replace with your built/published version
export POLARIS_SPARK3_PKG=org.apache.polaris:polaris-spark-3.5_2.12:1.6.0
export POLARIS_SPARK4_PKG=org.apache.polaris:polaris-spark-4.0_2.13:1.6.0
export DELTA_SPARK3_PKG=io.delta:delta-spark_2.12:3.3.1   # need >= 3.2.1; Spark >= 3.5.3
export DELTA_SPARK4_PKG=io.delta:delta-spark_2.13:4.2.0   # need >= 4.2.0; Spark >= 4.0.2
```

Ensure `${CATALOG_NAME}` exists and the smoke principal has `CATALOG_MANAGE_CONTENT` (section 3.4).
Create a dedicated namespace for non-Iceberg tests:

```shell
# Via Spark (Polaris Spark client) or Iceberg REST session:
# CREATE NAMESPACE IF NOT EXISTS generic_ns;
```

### 13.2 Generic table REST API (curl)

Use a bearer token (`${POLARIS_TOKEN}` or `${USER_TOKEN}`). Namespace levels are joined with
unit separator `%1F` in the path when nested; for a single-level namespace `generic_ns`:

#### Create (Delta)

```shell
mkdir -p /tmp/polaris-smoke/generic/delta_table

curl -sS -X POST \
  -H "Authorization: Bearer ${POLARIS_TOKEN}" \
  -H "Content-Type: application/json" \
  "${POLARIS_API}/api/catalog/polaris/v1/${CATALOG_NAME}/namespaces/generic_ns/generic-tables" \
  -d "{
    \"name\": \"delta_table\",
    \"format\": \"delta\",
    \"base-location\": \"file:///tmp/polaris-smoke/generic/delta_table\",
    \"doc\": \"smoke delta generic table\",
    \"properties\": { \"smoke\": \"true\" }
  }" | jq .
```

**Expected:** HTTP 200/201 with the generic table entity.

#### Create (CSV)

```shell
mkdir -p /tmp/polaris-smoke/generic/csv_table

curl -sS -X POST \
  -H "Authorization: Bearer ${POLARIS_TOKEN}" \
  -H "Content-Type: application/json" \
  "${POLARIS_API}/api/catalog/polaris/v1/${CATALOG_NAME}/namespaces/generic_ns/generic-tables" \
  -d "{
    \"name\": \"csv_table\",
    \"format\": \"csv\",
    \"base-location\": \"file:///tmp/polaris-smoke/generic/csv_table\",
    \"doc\": \"smoke csv generic table\"
  }" | jq .
```

**Expected:** Success. Engine-side CSV read/write is out of band; Polaris only stores metadata.

#### Load / list / drop

```shell
curl -sf -H "Authorization: Bearer ${POLARIS_TOKEN}" \
  "${POLARIS_API}/api/catalog/polaris/v1/${CATALOG_NAME}/namespaces/generic_ns/generic-tables/delta_table" | jq .

curl -sf -H "Authorization: Bearer ${POLARIS_TOKEN}" \
  "${POLARIS_API}/api/catalog/polaris/v1/${CATALOG_NAME}/namespaces/generic_ns/generic-tables" | jq .

curl -sf -X DELETE -H "Authorization: Bearer ${POLARIS_TOKEN}" \
  "${POLARIS_API}/api/catalog/polaris/v1/${CATALOG_NAME}/namespaces/generic_ns/generic-tables/csv_table"
```

**Expected:** Load returns the Delta table; list includes `delta_table` (and `csv_table` before drop);
delete returns success.

#### Isolation vs Iceberg APIs

| Check | Action | Expected |
|---|---|---|
| Iceberg load of generic name | `GET .../v1/{catalog}/namespaces/generic_ns/tables/delta_table` | Not found / error (not an Iceberg table) |
| Generic load of Iceberg name | After creating Iceberg `ice_t`, `GET .../generic-tables/ice_t` | Not found |
| Duplicate name | Create Iceberg table `dup` then generic `dup` (or reverse) | Second create fails |

### 13.3 Delta Lake via Polaris Spark client (Spark 3)

```shell
BASE_LOC=file:///tmp/polaris-smoke/${CATALOG_NAME}/delta
mkdir -p /tmp/polaris-smoke/${CATALOG_NAME}/delta

${SPARK3_HOME}/bin/spark-sql \
  --packages ${POLARIS_SPARK3_PKG},${DELTA_SPARK3_PKG} \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,io.delta.sql.DeltaSparkSessionExtension \
  --conf spark.sql.catalog.spark_catalog=org.apache.spark.sql.delta.catalog.DeltaCatalog \
  --conf spark.sql.catalog.polaris=org.apache.polaris.spark.SparkCatalog \
  --conf spark.sql.catalog.polaris.uri=${POLARIS_API}/api/catalog \
  --conf spark.sql.catalog.polaris.warehouse=${CATALOG_NAME} \
  --conf spark.sql.catalog.polaris.credential=${USER_CLIENT_ID}:${USER_CLIENT_SECRET} \
  --conf spark.sql.catalog.polaris.scope='PRINCIPAL_ROLE:ALL' \
  --conf spark.sql.catalog.polaris.token-refresh-enabled=true \
  --conf spark.sql.catalog.polaris.header.X-Iceberg-Access-Delegation=vended-credentials
```

Omit vended-credentials for Ozone `--no-sts` catalogs; supply static credentials as in 12.2.

SQL checklist (after `USE polaris;`):

| # | Use case | SQL / action | Expected |
|---|---|---|---|
| 1 | Namespace | `CREATE NAMESPACE IF NOT EXISTS delta_ns;` `CREATE NAMESPACE IF NOT EXISTS delta_ns.public;` `USE NAMESPACE delta_ns.public;` | OK |
| 2 | Create Delta table | `CREATE TABLE people (id INT, name STRING) USING delta LOCATION '${BASE_LOC}/people';` | OK; registered as generic table in Polaris |
| 3 | Insert / select | `INSERT INTO people VALUES (1, 'alice'), (2, 'bob');` `SELECT * FROM people ORDER BY id;` | Two rows |
| 4 | Second Delta table | `CREATE TABLE metrics (col1 INT) USING delta LOCATION '${BASE_LOC}/metrics';` insert/select | OK |
| 5 | Coexist with Iceberg | `CREATE TABLE ice_tb (col1 INT) USING iceberg;` insert/select | OK in same catalog |
| 6 | List tables | `SHOW TABLES;` | Shows Delta + Iceberg names |
| 7 | REST verify | `GET .../generic-tables/people` with bearer token | `format` is `delta`, base-location matches |
| 8 | Unsupported alter | `ALTER TABLE people SET LOCATION '...'` (if attempted) | Not supported for Delta via Polaris Spark client |
| 9 | Drop | `DROP TABLE people;` `DROP TABLE metrics;` `DROP TABLE ice_tb;` drop namespaces | Clean removal |

### 13.4 Delta Lake via Polaris Spark client (Spark 4)

Same flow as 13.3 with Spark 4 packages (`${POLARIS_SPARK4_PKG}`, `${DELTA_SPARK4_PKG}`, Scala 2.13
artifacts). Delta for Spark 4 requires **delta-io ≥ 4.2.0** and **Spark ≥ 4.0.2**.

### 13.5 Apache Hudi via Polaris Spark client

Configure `spark_catalog` with Hudi’s catalog (see `plugins/spark/v3.5/regtests/setup.sh --tableFormat hudi`)
and use `org.apache.polaris.spark.SparkCatalog` for the Polaris catalog name.

```shell
HUDI_LOC=file:///tmp/polaris-smoke/${CATALOG_NAME}/hudi
mkdir -p /tmp/polaris-smoke/${CATALOG_NAME}/hudi

# Include Hudi Spark bundle + Polaris Spark client in --packages / --jars per your Hudi version.
# spark.sql.catalog.spark_catalog=org.apache.spark.sql.hudi.catalog.HoodieCatalog
# spark.sql.catalog.polaris=org.apache.polaris.spark.SparkCatalog
# ... same uri / warehouse / credential conf as Delta ...
```

| # | Use case | SQL / action | Expected |
|---|---|---|---|
| 1 | Create Hudi table | `CREATE TABLE hudi_tb1 (id INT, name STRING) USING hudi LOCATION '${HUDI_LOC}/hudi_tb1';` | OK |
| 2 | Insert / select | `INSERT INTO hudi_tb1 VALUES (1, 'alice'), (2, 'bob');` `SELECT * FROM hudi_tb1 ORDER BY id;` | Two rows |
| 3 | Partitioned Hudi | `CREATE TABLE hudi_tb2 (name STRING, age INT, country STRING) USING hudi PARTITIONED BY (country) LOCATION '${HUDI_LOC}/hudi_tb2';` insert/select | OK |
| 4 | Mix with Iceberg | Create/insert Iceberg table in same catalog | OK |
| 5 | REST verify | List/load via `.../generic-tables` | Hudi tables visible as generic entities |
| 6 | Drop | Drop Hudi + Iceberg tables and namespaces | Clean removal |

Automated reference: `plugins/spark/v3.5/regtests/suites/spark_sql_hudi.sh`.

### 13.6 Hive-format tables outside Polaris generic tables

Native Hive SerDe / ORC / Parquet tables that live only in HMS are **not** Polaris generic tables.
For those:

1. Use **combo D (Hive federation)** and validate discovery of Iceberg tables registered in HMS.
2. Treat classic Hive-managed tables as a **Hive/HMS smoke** (Beeline / `HiveCatalog` directly), not
   as Polaris generic-table coverage, unless you explicitly register them through the generic-table
   API or the Polaris Spark client.

### 13.7 AuthZ for non-Iceberg

| Check | Action | Expected |
|---|---|---|
| Internal authZ allow | Principal with `CATALOG_MANAGE_CONTENT` creates Delta/Hudi table | Success |
| Internal authZ deny | Revoke privilege; retry `CREATE TABLE ... USING delta` | `ForbiddenException` |
| Ranger allow/deny | Combo G/H with policies covering generic table ops | Allow succeeds; missing policy → 403 |

---

## 14. Pass / fail summary sheet

Copy per run:

```text
Date / operator: ____________________
Polaris build / commit: ______________
Iceberg version: ____________________
Delta / Hudi / Spark client versions: ____________________

[ ] A  in-memory + FILE + internal/internal
[ ] B  Postgres + FILE + internal/internal (+ restart durability)
[ ] C1 AWS S3 (role ARN + vended credentials) + Iceberg/Delta
[ ] C2 MinIO S3-compatible + Iceberg/Delta
[ ] C3 Ozone S3-compatible (--no-sts) + Iceberg/Delta
[ ] D  Hive federation + HDFS warehouse
[ ] E  Hadoop federation
[ ] F  Iceberg REST federation
[ ] G  Ranger authZ (allow + deny)
[ ] H  Ranger + Hive federation

S3 storage checks:
[ ] Objects appear under warehouse prefix after insert
[ ] Vended credentials work (AWS) / static keys work (MinIO/Ozone)
[ ] DROP TABLE PURGE (or equivalent cleanup) behaves as configured
[ ] Delta LOCATION on s3:// for at least one of C1/C2/C3

Iceberg (per catalog under test):
[ ] Spark 3: create / insert / select / evolve / view / time travel / cleanup
[ ] Spark 4: create / insert / select / evolve / view / time travel / cleanup

Non-Iceberg:
[ ] Generic table REST: create (delta + csv) / load / list / drop
[ ] Generic vs Iceberg API isolation + duplicate-name rejection
[ ] Spark 3 Delta via Polaris Spark client (create / insert / select / coexist / drop)
[ ] Spark 4 Delta via Polaris Spark client
[ ] Spark Hudi via Polaris Spark client (create / insert / partitioned / drop)
[ ] Non-Iceberg authZ allow + deny (internal and/or Ranger)

Health:
[ ] Java 21 via JAVA_HOME for Polaris (`java -version` shows 21.x)
[ ] bin/server (or gradle run) started with that JDK
[ ] /q/health UP
[ ] OAuth root token
[ ] OAuth bad-secret rejected
```

---

## 15. Related documentation

- [Local deployment]({{% relref "deploying-polaris/local-deploy" %}})
- [Relational JDBC metastore]({{% relref "../../metastores/relational-jdbc" %}})
- [Admin tool / bootstrap]({{% relref "../../admin-tool" %}})
- [Using Polaris]({{% relref "using-polaris" %}})
- [Ozone catalog]({{% relref "creating-a-catalog/s3/catalog-ozone" %}})
- [AWS S3 catalog]({{% relref "creating-a-catalog/s3/catalog-aws" %}})
- [MinIO catalog]({{% relref "creating-a-catalog/s3/catalog-minio" %}})
- [Hive Metastore federation]({{% relref "../../federation/hive-metastore-federation" %}})
- [Iceberg REST federation]({{% relref "../../federation/iceberg-rest-federation" %}})
- [Generic tables]({{% relref "../../generic-table" %}})
- [Polaris Spark client]({{% relref "../../polaris-spark-client" %}})
- [Command-line interface]({{% relref "../../command-line-interface" %}})
- Ranger extension README: `extensions/auth/ranger/impl/README.md`
- Automated cousins: `regtests/t_spark_sql` (including `spark_sql_s3.sh`), `regtests/t_catalog_federation`, `regtests/t_cli`,
  `plugins/spark/v3.5/regtests/suites/spark_sql_delta.sh`,
  `plugins/spark/v3.5/regtests/suites/spark_sql_hudi.sh`
