# Aeron Cluster Configuration Guide
## Complete Reference for aeron-io-samples Cluster Application

> **Purpose**: This document provides comprehensive, production-ready configuration guidance for the Aeron Cluster application. Use this as a baseline for new projects and reference for all configuration decisions.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Configuration Parameters](#2-configuration-parameters)
3. [Cluster Member Build Process](#3-cluster-member-build-process)
4. [Transport Configuration (UDP vs IPC)](#4-transport-configuration-udp-vs-ipc)
5. [Port Allocation Strategy](#5-port-allocation-strategy)
6. [Deployment Scenarios](#6-deployment-scenarios)
7. [Kubernetes Configuration](#7-kubernetes-configuration)
8. [DNS Resolution Strategy](#8-dns-resolution-strategy)
9. [Performance Tuning](#9-performance-tuning)
10. [Best Practices](#10-best-practices)
11. [Troubleshooting](#11-troubleshooting)

---

## 1. Architecture Overview

### Core Components

The Aeron Cluster application consists of:

- **ClusteredMediaDriver**: Embedded media driver for message transport
- **Archive**: Persistent storage for messages and snapshots
- **ConsensusModule**: Raft-based consensus protocol implementation
- **ClusteredServiceContainer**: Application business logic container

### Files Location

```
cluster/
├── src/main/java/io/aeron/samples/
│   ├── ClusterApp.java                    # Main application entry point
│   └── infra/
│       ├── AppClusteredService.java       # Business logic implementation
│       ├── ClientSessions.java            # Client session management
│       └── ...
├── entrypoint.sh                           # Container startup script
└── build.gradle.kts                        # Build configuration
```

### Entry Point: ClusterApp.java

Location: `/cluster/src/main/java/io/aeron/samples/ClusterApp.java`

**Key Configuration Flow:**
```java
// Line 51-59: Load configuration from environment/properties
final int portBase = getBasePort();           // Default: 9000
final int nodeId = getClusterNode();          // Default: 0
final String hosts = getClusterAddresses();   // Default: "localhost"

final List<String> hostAddresses = List.of(hosts.split(","));

// Build cluster configuration (uses ClusterConfig utility)
final ClusterConfig clusterConfig = ClusterConfig.create(
    nodeId, hostAddresses, hostAddresses, portBase,
    new AppClusteredService());

// Configure ingress channel (UDP by default)
clusterConfig.consensusModuleContext().ingressChannel("aeron:udp");

// Set base directory for cluster data
clusterConfig.baseDir(getBaseDir(nodeId));

// Tune consensus timeouts for your environment
clusterConfig.consensusModuleContext()
    .leaderHeartbeatTimeoutNs(TimeUnit.SECONDS.toNanos(3));

// Await DNS resolution for all cluster members
hostAddresses.forEach(ClusterApp::awaitDnsResolution);

// Launch cluster components
try (ClusteredMediaDriver ignored = ClusteredMediaDriver.launch(...);
     ClusteredServiceContainer ignored1 = ClusteredServiceContainer.launch(...)) {
    barrier.await();
}
```

---

## 2. Configuration Parameters

### 2.1 Environment Variables

All configuration can be provided via environment variables (preferred for containers) or JVM system properties (preferred for local development).

| Environment Variable | System Property | Default Value | Description | Example |
|---------------------|-----------------|---------------|-------------|---------|
| `CLUSTER_ADDRESSES` | `cluster.addresses` | `"localhost"` | Comma-separated list of cluster member hostnames | `"node0,node1,node2"` |
| `CLUSTER_NODE` | `node.id` | `"0"` | Node ID (0-indexed) for this cluster member | `"0"`, `"1"`, `"2"` |
| `CLUSTER_PORT_BASE` | `port.base` | `"9000"` | Base port for calculating all cluster ports | `"9000"` |
| `BASE_DIR` | N/A | `"./node{nodeId}"` | Base directory for cluster data and logs | `"/data/cluster"` |
| `DNS_DELAY` | N/A | `"false"` | Wait 5 seconds before DNS resolution (Kubernetes) | `"true"` |

**Configuration Precedence:**
1. Environment variables (highest priority)
2. System properties (-D flags)
3. Default values (lowest priority)

### 2.2 JVM System Properties

**Aeron-Specific Properties:**

```bash
# Network configuration
-Djava.net.preferIPv4Stack=true              # Force IPv4 (recommended)
-Daeron.ipc.mtu.length=8192                  # IPC MTU size (8KB default)

# Performance tuning (production)
-Daeron.term.buffer.length=67108864          # 64MB term buffers
-Daeron.socket.so_sndbuf=2097152             # 2MB UDP send buffer
-Daeron.socket.so_rcvbuf=2097152             # 2MB UDP receive buffer
-Daeron.rcv.initial.window.length=2097152    # Initial receiver window

# Threading (for dedicated threading mode)
-Daeron.conductor.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy
-Daeron.sender.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy
-Daeron.receiver.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy

# Archive configuration
-Daeron.archive.file.sync.level=0            # Async file I/O (faster but less durable)
-Daeron.archive.catalog.file.sync.level=0
```

**Application Properties (from ClusterApp.java):**

```bash
-Dnode.id=0                                  # Node identifier
-Dcluster.addresses=localhost                # Cluster member addresses
-Dport.base=9000                             # Base port
```

### 2.3 Consensus Module Configuration

Located in `ClusterApp.java:58-62`

```java
// Current configuration
clusterConfig.consensusModuleContext()
    .ingressChannel("aeron:udp")                             // Transport protocol
    .leaderHeartbeatTimeoutNs(TimeUnit.SECONDS.toNanos(3));  // Leader heartbeat timeout
```

**Recommended Production Settings:**

```java
consensusModuleContext()
    // Timeouts (tune based on network latency)
    .leaderHeartbeatTimeoutNs(TimeUnit.SECONDS.toNanos(1))      // Faster failure detection
    .electionTimeoutNs(TimeUnit.SECONDS.toNanos(5))             // Election timeout
    .startupCanvassTimeoutNs(TimeUnit.SECONDS.toNanos(60))      // Initial startup grace
    .sessionTimeoutNs(TimeUnit.SECONDS.toNanos(10))             // Client session timeout

    // Channels (with larger term buffers)
    .logChannel("aeron:udp?term-length=64m")
    .replicationChannel("aeron:udp?term-length=64m")

    // Performance
    .errorHandler(throwable -> LOGGER.error("Consensus error", throwable))
    .deleteDirOnStart(false);                                   // Preserve state
```

### 2.4 Current Configuration in entrypoint.sh

Location: `/cluster/entrypoint.sh`

```bash
#!/bin/sh
java -Djava.net.preferIPv4Stack=true \
     -Daeron.ipc.mtu.length=8k \
     "$@" \
     -jar /home/aeron/jar/cluster-uber.jar
```

**Notes:**
- `$@` passes through additional JVM flags from container command
- Minimal configuration for development
- Production requires additional tuning (see section 9)

---

## 3. Cluster Member Build Process

### 3.1 Gradle Build Configuration

**File:** `/cluster/build.gradle.kts`

```kotlin
plugins {
    id("java-application-conventions")
}

dependencies {
    implementation(libs.agrona)              // Low-level data structures
    implementation(libs.aeron)               // Aeron client libraries
    implementation(libs.slf4j)               // Logging facade
    implementation(libs.logback)             // Logging implementation
    implementation(project(":cluster-protocol"))  // SBE message definitions
    testImplementation(libs.bundles.testing)
}

application {
    mainClass = "io.aeron.samples.ClusterApp"  // Entry point
}
```

**Build Command:**
```bash
./gradlew :cluster:build
```

**Output:** Uber JAR at `/cluster/build/libs/cluster-uber.jar`

### 3.2 Docker Build Process

**Build Script:** `/build-container-images.sh`

```bash
#!/bin/bash
echo "building cluster image..."
docker build -f docker/Dockerfile \
    --build-context gradle=./cluster \
    -t cluster . \
    --no-cache
```

**Dockerfile:** `/docker/Dockerfile`

```dockerfile
# Base image with JDK 21
ARG REPO_NAME=docker.io/
ARG IMAGE_NAME=azul/zulu-openjdk-debian
ARG IMAGE_TAG=21
FROM ${REPO_NAME}${IMAGE_NAME}:${IMAGE_TAG} AS base

ARG AERON_VERSION=1.48.0
ENV DEBIAN_FRONTEND=noninteractive

# Install utilities
RUN apt-get --quiet --assume-yes update && \
    apt-get install --quiet --assume-yes \
        bash less procps sysstat && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r aeron && \
    useradd --no-log-init -r -g aeron aeron && \
    mkdir -p /home/aeron/jar && \
    chown -R aeron:aeron /home/aeron

# Download Aeron library (for utilities/debugging)
ADD --chown=aeron:aeron --chmod=0644 \
    "https://repo1.maven.org/maven2/io/aeron/aeron-all/${AERON_VERSION}/aeron-all-${AERON_VERSION}.jar" \
    /home/aeron/jar/aeron-all-${AERON_VERSION}.jar

WORKDIR /home/aeron/jar/

# Copy application artifacts from Gradle build
FROM base AS target
COPY --from=gradle --chown=aeron:aeron --chmod=0644 \
    /build/libs/*-uber.jar /home/aeron/jar/
COPY --from=gradle --chown=aeron:aeron --chmod=0755 \
    *.sh /home/aeron/jar/

# Run as non-root user
USER aeron

ENTRYPOINT ["/home/aeron/jar/entrypoint.sh"]
```

**Key Build Features:**
- **Multi-stage build**: Separates build context from runtime
- **Non-root user**: Runs as `aeron` user (UID determined by image)
- **Build context**: Uses Gradle output via `--build-context`
- **Uber JAR**: Single JAR with all dependencies
- **Security**: Minimal attack surface, no root access

### 3.3 Build Process Summary

```
┌─────────────────┐
│  Source Code    │
│  (.java files)  │
└────────┬────────┘
         │
         │ ./gradlew :cluster:build
         ▼
┌─────────────────┐
│  Gradle Build   │
│  - Compile      │
│  - Test         │
│  - Package Uber │
└────────┬────────┘
         │
         │ docker build
         ▼
┌─────────────────┐
│  Base Image     │
│  (JDK 21)       │
└────────┬────────┘
         │
         │ COPY from gradle context
         ▼
┌─────────────────┐
│  Final Image    │
│  - Uber JAR     │
│  - entrypoint   │
│  - aeron user   │
└─────────────────┘
```

**Manual Build Steps:**

```bash
# 1. Build all modules
./gradlew clean build

# 2. Build Docker images (cluster, admin, backup)
./build-container-images.sh

# 3. Verify images
docker images | grep -E "(cluster|admin|backup)"
```

---

## 4. Transport Configuration (UDP vs IPC)

### 4.1 Overview

Aeron supports multiple transport types with different characteristics:

| Transport | Use Case | Latency | Throughput | Multi-Host | Configuration Complexity |
|-----------|----------|---------|------------|------------|-------------------------|
| **UDP**   | Distributed clusters | ~10-50µs | 10+ GB/s | ✅ Yes | Medium |
| **IPC**   | Single-machine | ~1-5µs | 40+ GB/s | ❌ No | Low |
| **MDC**   | Multi-homed hosts | ~10-50µs | 10+ GB/s | ✅ Yes | High |

### 4.2 Current Configuration (UDP)

**Location:** `ClusterApp.java:58`

```java
clusterConfig.consensusModuleContext().ingressChannel("aeron:udp");
```

**Full UDP Configuration:**

```java
// Ingress: Client → Cluster
.ingressChannel("aeron:udp")

// Egress: Cluster → Client (ephemeral port)
.egressChannel("aeron:udp?endpoint=localhost:0")

// Log replication between cluster members
.logChannel("aeron:udp?term-length=64m")

// Snapshot replication
.replicationChannel("aeron:udp?term-length=64m")
```

**When to use UDP:**
- ✅ Multi-node cluster across multiple hosts/containers
- ✅ Kubernetes deployments
- ✅ Cloud deployments (AWS, GCP, Azure)
- ✅ Docker Compose with bridge networking
- ✅ Production environments

### 4.3 IPC Configuration (Shared Memory)

**For single-machine deployments:**

```java
// Replace UDP with IPC
clusterConfig.consensusModuleContext()
    .ingressChannel("aeron:ipc")
    .egressChannel("aeron:ipc")
    .logChannel("aeron:ipc")
    .replicationChannel("aeron:ipc");

// Archive channels also need IPC
clusterConfig.archiveContext()
    .controlChannel("aeron:ipc")
    .replicationChannel("aeron:ipc");
```

**JVM Property for IPC MTU:**
```bash
-Daeron.ipc.mtu.length=8192  # 8KB messages (already in entrypoint.sh)
```

**When to use IPC:**
- ✅ Local development (single machine, multiple processes)
- ✅ Performance testing (maximum throughput)
- ✅ Single-node embedded cluster
- ❌ NOT for Kubernetes (pods can't share memory)
- ❌ NOT for Docker Compose (containers isolated)

### 4.4 Switching Between UDP and IPC

**Option 1: Environment Variable (Recommended)**

Add to `ClusterApp.java`:

```java
private static String getTransportType() {
    String transport = System.getenv("CLUSTER_TRANSPORT");
    if (null == transport || transport.isEmpty()) {
        transport = System.getProperty("cluster.transport", "udp");
    }
    return transport;
}

// In main method:
String transportType = getTransportType();
String channelPrefix = "aeron:" + transportType;

clusterConfig.consensusModuleContext()
    .ingressChannel(channelPrefix)
    .egressChannel(channelPrefix + (transportType.equals("udp") ? "?endpoint=localhost:0" : ""))
    .logChannel(channelPrefix + (transportType.equals("udp") ? "?term-length=64m" : ""))
    .replicationChannel(channelPrefix + (transportType.equals("udp") ? "?term-length=64m" : ""));
```

**Option 2: Configuration Profiles**

```java
enum TransportProfile {
    UDP_DISTRIBUTED("aeron:udp", "aeron:udp?endpoint=localhost:0"),
    IPC_LOCAL("aeron:ipc", "aeron:ipc"),
    UDP_MULTICAST("aeron:udp?endpoint=224.0.1.1:40456", "aeron:udp");

    private final String ingressChannel;
    private final String egressChannel;

    TransportProfile(String ingress, String egress) {
        this.ingressChannel = ingress;
        this.egressChannel = egress;
    }
}
```

### 4.5 Advanced: Multi-Destination-Cast (MDC)

**For multi-homed hosts (multiple network interfaces):**

```java
// Manual control mode with multiple destinations
.ingressChannel("aeron:udp?control-mode=manual|control=localhost:9050")
.logChannel("aeron:udp?control-mode=manual|control=localhost:9051")

// Requires explicit destination management
// More complex, only needed for advanced network topologies
```

### 4.6 Channel URI Parameters Reference

**Common UDP Parameters:**

```
aeron:udp?endpoint=host:port          # Explicit endpoint
aeron:udp?interface=192.168.1.100     # Bind to specific interface
aeron:udp?term-length=64m             # Term buffer size (16m, 32m, 64m, 128m)
aeron:udp?mtu=1408                    # Maximum transmission unit
aeron:udp?control=host:port           # Control endpoint (MDC)
aeron:udp?ttl=16                      # Time-to-live for multicast
```

**IPC Parameters:**

```
aeron:ipc                              # Basic IPC (no parameters needed)
aeron:ipc?term-length=64m             # Larger term buffers
```

---

## 5. Port Allocation Strategy

### 5.1 Port Calculation Formula

**Base Formula:**
```
Port = BASE_PORT + (NODE_ID × 100) + OFFSET
```

### 5.2 Port Offset Constants

Based on `ClusterConfig` usage in backup/standby applications:

```java
public static final int CLIENT_FACING_PORT_OFFSET = 0;
public static final int MEMBER_FACING_PORT_OFFSET = 10;
public static final int ARCHIVE_CONTROL_PORT_OFFSET = 20;
public static final int ARCHIVE_REPLICATION_PORT_OFFSET = 30;
public static final int LOG_PORT_OFFSET = 40;
public static final int TRANSFER_PORT_OFFSET = 50;
```

### 5.3 Port Allocation Table

**With BASE_PORT=9000:**

| Node | Purpose | Offset | Calculation | Port | Channel |
|------|---------|--------|-------------|------|---------|
| **Node 0** | | | | | |
| | Client Ingress | 0 | 9000 + (0×100) + 0 | **9000** | Client → Cluster |
| | Member Facing | 10 | 9000 + (0×100) + 10 | **9010** | Cluster consensus |
| | Archive Control | 20 | 9000 + (0×100) + 20 | **9020** | Archive requests |
| | Archive Replication | 30 | 9000 + (0×100) + 30 | **9030** | Snapshot sync |
| | Log Channel | 40 | 9000 + (0×100) + 40 | **9040** | Log replication |
| | Transfer | 50 | 9000 + (0×100) + 50 | **9050** | Data transfer |
| **Node 1** | | | | | |
| | Client Ingress | 0 | 9000 + (1×100) + 0 | **9100** | Client → Cluster |
| | Member Facing | 10 | 9000 + (1×100) + 10 | **9110** | Cluster consensus |
| | Archive Control | 20 | 9000 + (1×100) + 20 | **9120** | Archive requests |
| | Archive Replication | 30 | 9000 + (1×100) + 30 | **9130** | Snapshot sync |
| | Log Channel | 40 | 9000 + (1×100) + 40 | **9140** | Log replication |
| | Transfer | 50 | 9000 + (1×100) + 50 | **9150** | Data transfer |
| **Node 2** | | | | | |
| | Client Ingress | 0 | 9000 + (2×100) + 0 | **9200** | Client → Cluster |
| | Member Facing | 10 | 9000 + (2×100) + 10 | **9210** | Cluster consensus |
| | Archive Control | 20 | 9000 + (2×100) + 20 | **9220** | Archive requests |
| | Archive Replication | 30 | 9000 + (2×100) + 30 | **9230** | Snapshot sync |
| | Log Channel | 40 | 9000 + (2×100) + 40 | **9240** | Log replication |
| | Transfer | 50 | 9000 + (2×100) + 50 | **9250** | Data transfer |

### 5.4 Port Range Planning

**For 3-node cluster (BASE_PORT=9000):**
- **Used range:** 9000-9250 (251 ports)
- **Reserved per node:** 100 ports
- **Actual used per node:** ~6 ports

**For 5-node cluster:**
- **Used range:** 9000-9450 (451 ports)

**Firewall Rules (UDP):**
```bash
# Allow cluster member traffic
iptables -A INPUT -p udp --dport 9000:9450 -s <cluster-subnet> -j ACCEPT

# Allow client ingress traffic
iptables -A INPUT -p udp --dport 9000 -j ACCEPT  # Node 0
iptables -A INPUT -p udp --dport 9100 -j ACCEPT  # Node 1
iptables -A INPUT -p udp --dport 9200 -j ACCEPT  # Node 2
```

### 5.5 Customizing Port Base

**Development (avoid conflicts):**
```bash
export CLUSTER_PORT_BASE=10000
# Node 0 will use 10000, 10010, 10020, ...
```

**Production (separate environments):**
```bash
# Environment A
export CLUSTER_PORT_BASE=9000

# Environment B (same host)
export CLUSTER_PORT_BASE=12000
```

---

## 6. Deployment Scenarios

### 6.1 Local Development (IPC)

**Use Case:** Testing on single laptop/workstation

**Configuration:**
```bash
# Terminal 1 - Node 0
export CLUSTER_NODE=0
export CLUSTER_ADDRESSES="localhost,localhost,localhost"
export CLUSTER_PORT_BASE=9000
export BASE_DIR="./node0"
java -Dcluster.transport=ipc -jar cluster-uber.jar

# Terminal 2 - Node 1
export CLUSTER_NODE=1
export CLUSTER_ADDRESSES="localhost,localhost,localhost"
export CLUSTER_PORT_BASE=9000
export BASE_DIR="./node1"
java -Dcluster.transport=ipc -jar cluster-uber.jar

# Terminal 3 - Node 2
export CLUSTER_NODE=2
export CLUSTER_ADDRESSES="localhost,localhost,localhost"
export CLUSTER_PORT_BASE=9000
export BASE_DIR="./node2"
java -Dcluster.transport=ipc -jar cluster-uber.jar
```

**Advantages:** Lowest latency, fastest development cycle
**Disadvantages:** Not representative of production networking

### 6.2 Docker Compose (UDP)

**File:** `/docker/docker-compose.yml`

**Key Configuration:**
```yaml
services:
  node0:
    image: cluster:latest
    hostname: cluster0
    shm_size: "1gb"  # Critical for Aeron IPC
    networks:
      internal_bus:
        ipv4_address: 172.16.202.2  # Static IP
    environment:
      CLUSTER_NODE: "0"
      CLUSTER_ADDRESSES: "172.16.202.2,172.16.202.3,172.16.202.4"
      CLUSTER_PORT_BASE: "9000"
      BASE_DIR: "/home/aeron/jar/aeron-cluster"

networks:
  internal_bus:
    driver: bridge
    driver_opts:
      com.docker.network.bridge.enable_icc: "true"  # Inter-container communication
      com.docker.network.driver.mtu: 9000           # Jumbo frames
```

**Start cluster:**
```bash
cd docker
docker compose up -d
```

**Advantages:** Full network isolation, representative of production
**Disadvantages:** Slower than IPC, requires Docker

### 6.3 Kubernetes (Production)

See section 7 for complete Kubernetes configuration.

---

## 7. Kubernetes Configuration

### 7.1 Architecture

**Components:**
- **StatefulSet**: Manages 3 cluster node pods
- **Headless Service**: Provides stable DNS names
- **PersistentVolumeClaims**: Stores cluster data (1GB per node)
- **Namespace**: Isolates cluster resources

### 7.2 StatefulSet Configuration

**File:** `/kubernetes/cluster/03-controller.yaml`

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: aeron-io-sample-cluster
  namespace: aeron-io-sample-cluster
spec:
  replicas: 3                        # 3-node cluster for quorum
  podManagementPolicy: Parallel      # All pods start simultaneously
  serviceName: aeron-io-sample-cluster

  volumeClaimTemplates:
  - metadata:
      name: data-volume-claim
    spec:
      accessModes: [ReadWriteOnce]
      storageClassName: standard     # Change for production (fast-ssd)
      resources:
        requests:
          storage: 1Gi               # Increase for production workloads

  template:
    spec:
      serviceAccountName: aeron-io-sample-cluster

      volumes:
        - name: shm
          emptyDir:
            medium: Memory           # RAM-backed shared memory
            sizeLimit: 1Gi           # Critical for Aeron IPC

      containers:
        - name: aeron-io-sample-cluster
          image: "cluster:latest"
          imagePullPolicy: IfNotPresent

          command: ["/bin/bash"]
          args:
            - -c
            # Extract node ID from pod name (cluster-0 → 0)
            - /home/aeron/jar/entrypoint.sh -Dnode.id=${POD_NAME##*-}

          env:
            - name: DNS_DELAY
              value: "true"          # Wait for DNS registration

            - name: BASE_DIR
              value: "/home/aeron/jar/aeron-cluster"

            - name: CLUSTER_ADDRESSES
              # Fully-qualified DNS names for all pods
              value: >-
                aeron-io-sample-cluster-0.aeron-io-sample-cluster.aeron-io-sample-cluster.svc.cluster.local.,
                aeron-io-sample-cluster-1.aeron-io-sample-cluster.aeron-io-sample-cluster.svc.cluster.local.,
                aeron-io-sample-cluster-2.aeron-io-sample-cluster.aeron-io-sample-cluster.svc.cluster.local.

            - name: POD_NAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name  # Inject pod name dynamically

          volumeMounts:
            - mountPath: /dev/shm
              name: shm                      # Shared memory for IPC

            - mountPath: /home/aeron/jar/aeron-cluster
              name: data-volume-claim        # Persistent storage
```

### 7.3 Headless Service

**File:** `/kubernetes/cluster/04-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: aeron-io-sample-cluster
  namespace: aeron-io-sample-cluster
spec:
  type: ClusterIP
  clusterIP: None          # Headless service (no load balancing)
  selector:
    app.kubernetes.io/name: aeron-io-sample-cluster
```

**Why Headless Service:**
- Returns **all** pod IPs via DNS (not a single virtual IP)
- Enables **direct pod-to-pod** communication
- Required for **Raft consensus** (nodes must address specific peers)
- Provides **stable DNS names** that survive pod restarts

**DNS Resolution:**
```
<pod-name>.<service-name>.<namespace>.svc.cluster.local

Examples:
aeron-io-sample-cluster-0.aeron-io-sample-cluster.aeron-io-sample-cluster.svc.cluster.local
aeron-io-sample-cluster-1.aeron-io-sample-cluster.aeron-io-sample-cluster.svc.cluster.local
aeron-io-sample-cluster-2.aeron-io-sample-cluster.aeron-io-sample-cluster.svc.cluster.local
```

### 7.4 Namespace and RBAC

**Files:**
- `/kubernetes/cluster/01-namespace.yaml`
- `/kubernetes/cluster/02-serviceaccount.yaml`

```yaml
---
apiVersion: v1
kind: Namespace
metadata:
  name: aeron-io-sample-cluster
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: aeron-io-sample-cluster
  namespace: aeron-io-sample-cluster
```

### 7.5 Deployment Steps

```bash
# 1. Build Docker image
./build-container-images.sh

# 2. Tag for your registry (if using remote cluster)
docker tag cluster:latest <your-registry>/cluster:latest
docker push <your-registry>/cluster:latest

# 3. Update image in 03-controller.yaml
sed -i 's|image: "cluster:latest"|image: "<your-registry>/cluster:latest"|' \
    kubernetes/cluster/03-controller.yaml

# 4. Deploy to Kubernetes
kubectl apply -f kubernetes/cluster/01-namespace.yaml
kubectl apply -f kubernetes/cluster/02-serviceaccount.yaml
kubectl apply -f kubernetes/cluster/03-controller.yaml
kubectl apply -f kubernetes/cluster/04-service.yaml

# 5. Verify deployment
kubectl get pods -n aeron-io-sample-cluster
kubectl logs -n aeron-io-sample-cluster aeron-io-sample-cluster-0

# 6. Check cluster health
kubectl exec -n aeron-io-sample-cluster aeron-io-sample-cluster-0 -- \
    java -cp /home/aeron/jar/aeron-all-*.jar \
    io.aeron.cluster.ClusterTool describe \
    --cluster-dir=/home/aeron/jar/aeron-cluster
```

### 7.6 Kubernetes Best Practices

**1. Resource Limits (Add to container spec):**

```yaml
resources:
  requests:
    cpu: "2"
    memory: "4Gi"
  limits:
    cpu: "4"
    memory: "8Gi"
```

**2. Pod Anti-Affinity (Spread across nodes):**

```yaml
affinity:
  podAntiAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
    - topologyKey: kubernetes.io/hostname
      labelSelector:
        matchLabels:
          app.kubernetes.io/name: aeron-io-sample-cluster
```

**3. Pod Disruption Budget:**

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: aeron-cluster-pdb
  namespace: aeron-io-sample-cluster
spec:
  minAvailable: 2  # Always keep quorum (2 out of 3)
  selector:
    matchLabels:
      app.kubernetes.io/name: aeron-io-sample-cluster
```

**4. Liveness and Readiness Probes:**

```yaml
livenessProbe:
  exec:
    command:
    - /bin/sh
    - -c
    - "test -f /home/aeron/jar/aeron-cluster/cluster/cluster-mark-file.dat"
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  exec:
    command:
    - /bin/sh
    - -c
    - "test -f /home/aeron/jar/aeron-cluster/cluster/cluster-mark-file.dat"
  initialDelaySeconds: 20
  periodSeconds: 5
```

---

## 8. DNS Resolution Strategy

### 8.1 Implementation

**Location:** `ClusterApp.java:144-175`

```java
private static void awaitDnsResolution(final String host) {
    // Step 1: Optional initial delay for Kubernetes DNS propagation
    if (applyDnsDelay()) {
        LOGGER.info("Waiting 5 seconds for DNS to be registered...");
        quietSleep(5000);
    }

    // Step 2: Disable DNS caching (critical for dynamic environments)
    java.security.Security.setProperty("networkaddress.cache.ttl", "0");

    // Step 3: Retry loop with 60-second timeout
    final long endTime = SystemEpochClock.INSTANCE.time() + 60000;
    boolean resolved = false;

    while (!resolved) {
        if (SystemEpochClock.INSTANCE.time() > endTime) {
            LOGGER.error("cannot resolve name {}, exiting", host);
            System.exit(-1);  // Hard failure after timeout
        }

        try {
            InetAddress.getByName(host);
            resolved = true;
            LOGGER.info("Successfully resolved: {}", host);
        } catch (final UnknownHostException e) {
            LOGGER.warn("cannot yet resolve name {}, retrying in 3 seconds", host);
            quietSleep(3000);
        }
    }
}
```

### 8.2 Why DNS Resolution is Critical

**Kubernetes StatefulSet Timing:**
1. Pod created → Pod gets IP → DNS registered (3-10 seconds delay)
2. Parallel pod creation means DNS may not be ready when first pod starts
3. Without delay, pods fail to find each other and crash

**Network Partition Recovery:**
- Temporary DNS failures during network splits
- Retry mechanism allows recovery without restart

**Cloud Environments:**
- AWS Route53: eventual consistency (1-2 seconds typical)
- GCP Cloud DNS: global propagation (5-10 seconds)
- Azure DNS: zone updates (5-10 seconds)

### 8.3 Configuration

**Enable DNS delay (Kubernetes):**
```yaml
env:
  - name: DNS_DELAY
    value: "true"  # Adds 5-second initial delay
```

**Disable DNS delay (Docker Compose/Local):**
```yaml
env:
  - name: DNS_DELAY
    value: "false"  # No initial delay (DNS ready immediately)
```

### 8.4 DNS Resolution Flow

```
┌─────────────────────────┐
│   awaitDnsResolution    │
│   called for each host  │
└───────────┬─────────────┘
            │
            ▼
    ┌───────────────┐
    │  DNS_DELAY?   │
    └───┬───────┬───┘
        │       │
       YES     NO
        │       │
        ▼       │
   ┌─────────┐ │
   │ Sleep 5s│ │
   └────┬────┘ │
        │      │
        └──────┴──────┐
                      ▼
             ┌──────────────────┐
             │ Disable DNS cache│
             └────────┬─────────┘
                      ▼
             ┌──────────────────┐
             │ Start 60s timer  │
             └────────┬─────────┘
                      ▼
             ┌──────────────────┐
        ┌────│  Try resolve DNS │────┐
        │    └──────────────────┘    │
        │                            │
     SUCCESS                       FAIL
        │                            │
        ▼                            ▼
   ┌─────────┐            ┌──────────────────┐
   │  Done   │            │  Timeout (60s)?  │
   └─────────┘            └────┬─────────┬───┘
                               │         │
                              YES       NO
                               │         │
                               ▼         ▼
                          ┌────────┐ ┌────────┐
                          │  EXIT  │ │Sleep 3s│
                          │  (-1)  │ └───┬────┘
                          └────────┘     │
                                         │
                                    ┌────┴────┐
                                    │  RETRY  │
                                    └─────────┘
```

---

## 9. Performance Tuning

### 9.1 Threading Modes

**Current (Default - SHARED):**
```java
// All operations share one thread (simple, lower throughput)
mediaDriverContext.threadingMode(ThreadingMode.SHARED);
```

**Production (DEDICATED):**
```java
// Separate threads for conductor, sender, receiver (maximum performance)
mediaDriverContext
    .threadingMode(ThreadingMode.DEDICATED)
    .conductorIdleStrategy(new BusySpinIdleStrategy())
    .senderIdleStrategy(new BusySpinIdleStrategy())
    .receiverIdleStrategy(new BusySpinIdleStrategy());
```

**Impact:**
- SHARED: 1 thread, ~100K msgs/sec, lower CPU
- DEDICATED: 3 threads, 1M+ msgs/sec, 300% CPU

### 9.2 Term Buffer Sizing

**Current:** Default (16MB)

**Production:**
```java
// Larger buffers reduce wrapping, improve throughput
.logChannel("aeron:udp?term-length=64m")      // 64MB per term
.replicationChannel("aeron:udp?term-length=64m")
```

**JVM Property (all channels):**
```bash
-Daeron.term.buffer.length=67108864  # 64MB in bytes
```

**Trade-offs:**
- Larger = more memory, less wrapping, better throughput
- Smaller = less memory, more wrapping, lower throughput

### 9.3 Socket Buffer Tuning

**OS Limits (Linux):**
```bash
# Increase OS limits
sudo sysctl -w net.core.rmem_max=8388608      # 8MB receive
sudo sysctl -w net.core.wmem_max=8388608      # 8MB send
sudo sysctl -w net.core.rmem_default=2097152  # 2MB default receive
sudo sysctl -w net.core.wmem_default=2097152  # 2MB default send

# Make persistent
echo "net.core.rmem_max = 8388608" | sudo tee -a /etc/sysctl.conf
echo "net.core.wmem_max = 8388608" | sudo tee -a /etc/sysctl.conf
sudo sysctl -p
```

**JVM Properties:**
```bash
-Daeron.socket.so_sndbuf=2097152  # 2MB send buffer
-Daeron.socket.so_rcvbuf=2097152  # 2MB receive buffer
-Daeron.rcv.initial.window.length=2097152
```

### 9.4 CPU Affinity (Linux)

**Pin Aeron threads to specific cores:**

```bash
# Install taskset
sudo apt-get install util-linux

# Run with CPU affinity (cores 0-3)
taskset -c 0-3 java -jar cluster-uber.jar
```

**Docker:**
```yaml
services:
  node0:
    cpuset: "0-3"  # Limit to cores 0-3
    cpu_count: 4
```

**Kubernetes:**
```yaml
resources:
  requests:
    cpu: "4"       # Guaranteed 4 cores
  limits:
    cpu: "4"       # No overcommit
```

### 9.5 Heap Sizing

**Recommendations:**
```bash
# Development
-Xms1g -Xmx2g

# Production (cluster node)
-Xms4g -Xmx8g

# High-throughput
-Xms8g -Xmx16g
```

**GC Tuning (G1GC - default in JDK 21):**
```bash
# Minimize GC pauses
-XX:+UseG1GC
-XX:MaxGCPauseMillis=10          # Target 10ms pauses
-XX:G1ReservePercent=20          # Reserve for spikes
-XX:InitiatingHeapOccupancyPercent=45
```

**Low-latency (ZGC):**
```bash
# Sub-millisecond GC pauses (JDK 15+)
-XX:+UseZGC
-XX:ZCollectionInterval=5        # 5-second intervals
-Xms8g -Xmx8g                    # Fixed heap size
```

### 9.6 Complete Production JVM Flags

**Production entrypoint.sh:**

```bash
#!/bin/sh
exec java \
  -Xms4g -Xmx8g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=10 \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.ipc.mtu.length=8192 \
  -Daeron.term.buffer.length=67108864 \
  -Daeron.socket.so_sndbuf=2097152 \
  -Daeron.socket.so_rcvbuf=2097152 \
  -Daeron.rcv.initial.window.length=2097152 \
  -Daeron.conductor.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy \
  -Daeron.sender.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy \
  -Daeron.receiver.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy \
  "$@" \
  -jar /home/aeron/jar/cluster-uber.jar
```

### 9.7 Network Performance (Linux)

**MTU (Maximum Transmission Unit):**
```bash
# Check current MTU
ip link show eth0

# Increase MTU (if network supports jumbo frames)
sudo ip link set eth0 mtu 9000

# Docker network with jumbo frames (docker-compose.yml)
networks:
  internal_bus:
    driver_opts:
      com.docker.network.driver.mtu: 9000
```

**Disable TCP timestamps (UDP clusters):**
```bash
sudo sysctl -w net.ipv4.tcp_timestamps=0
```

---

## 10. Best Practices

### 10.1 Configuration Management

**✅ DO:**
- Use environment variables for container deployments
- Use system properties for local development
- Version control your configuration (Infrastructure as Code)
- Validate configuration before cluster startup
- Document all non-default settings

**❌ DON'T:**
- Hard-code hostnames or ports
- Mix environment variables and system properties inconsistently
- Deploy without testing DNS resolution first
- Use default timeouts in production

### 10.2 Cluster Sizing

**Minimum: 3 nodes (quorum = 2)**
- Survives 1 node failure
- Recommended for most use cases

**5 nodes (quorum = 3)**
- Survives 2 node failures
- Higher write latency (more consensus rounds)
- Use for critical systems

**7+ nodes:**
- Rarely needed (diminishing returns)
- Much higher latency
- Consider sharding instead

### 10.3 Monitoring

**Essential Metrics:**
```java
// Consensus health
- cluster.role (LEADER, FOLLOWER, CANDIDATE, CLOSED)
- cluster.commitPosition
- cluster.leadershipTermId
- consensus.electionCount

// Performance
- cluster.clientSessionCount
- cluster.ingressMessages (msgs/sec)
- cluster.egressMessages (msgs/sec)
- cluster.messageLatency (p50, p99, p999)

// Storage
- archive.recordingCount
- archive.storageSize
- snapshot.frequency
- snapshot.duration
```

**Recommended Tools:**
- Prometheus + Grafana
- Datadog APM
- New Relic
- Custom Aeron metrics exporter

### 10.4 Backup Strategy

**Snapshot Frequency:**
```java
// Take snapshots every N messages or T seconds
consensusModuleContext
    .snapshotIntervalNs(TimeUnit.MINUTES.toNanos(5))  // Every 5 minutes
    .snapshotThreshold(100_000);                       // Or every 100K msgs
```

**Backup Archives:**
- Use external backup pod (see `/backup` module)
- Continuous replication to S3/GCS/Azure Blob
- Test restore procedures monthly

### 10.5 Security

**Network Isolation:**
```yaml
# Kubernetes NetworkPolicy
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: cluster-network-policy
spec:
  podSelector:
    matchLabels:
      app: aeron-cluster
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: aeron-cluster
    ports:
    - protocol: UDP
      port: 9000
      endPort: 9300
```

**Authentication (Future):**
- Aeron doesn't provide built-in authentication
- Implement at application level in `AppClusteredService`
- Use TLS termination proxy for external clients

### 10.6 Disaster Recovery

**Planned Failover:**
1. Gracefully shut down follower
2. Wait for snapshot completion
3. Backup snapshot files
4. Restart with new configuration

**Unplanned Failure:**
1. If quorum maintained (2/3 up), cluster continues
2. Failed node can rejoin and catch up automatically
3. If quorum lost, manual intervention required (see ClusterTool)

**Total Cluster Loss:**
```bash
# Restore from backup archive
java -cp aeron-all.jar io.aeron.archive.ArchiveTool \
    migrate \
    --source /backup/archive \
    --destination /cluster/node0/archive

# Start cluster with existing data
BASE_DIR=/cluster/node0 java -jar cluster-uber.jar
```

---

## 11. Troubleshooting

### 11.1 Common Issues

**Issue: "ClusterConfig class not found"**
```
Error: cannot find symbol: class ClusterConfig
```

**Solution:** The ClusterConfig utility class is missing from the codebase. See `AERON_CLUSTER_CONFIG_ANALYSIS.md` for implementation template.

---

**Issue: DNS resolution timeout in Kubernetes**
```
ERROR cannot resolve name aeron-io-sample-cluster-0..., exiting
```

**Solution:**
```yaml
env:
  - name: DNS_DELAY
    value: "true"  # Add 5-second initial delay
```

---

**Issue: Port already in use**
```
ERROR: Address already in use (Bind failed)
```

**Solution:**
```bash
# Check port usage
lsof -i :9000

# Change port base
export CLUSTER_PORT_BASE=10000
```

---

**Issue: Slow cluster startup (60+ seconds)**
```
INFO: Waiting for cluster members...
```

**Solution:**
- Check `startupCanvassTimeoutNs` (may be too long)
- Verify network connectivity between nodes
- Check firewall rules for UDP ports

---

**Issue: Leader elections happening frequently**
```
INFO: Election count: 47 (should be ~1-2)
```

**Solution:**
- Increase `leaderHeartbeatTimeoutNs` (network latency too high)
- Check network stability (packet loss?)
- Verify CPU isn't oversubscribed

---

**Issue: Out of memory / GC thrashing**
```
OutOfMemoryError: Java heap space
```

**Solution:**
```bash
# Increase heap size
-Xms8g -Xmx16g

# Check term buffer sizing (may be too large)
-Daeron.term.buffer.length=67108864  # 64MB (was 128MB?)
```

---

**Issue: Shared memory mount missing in Kubernetes**
```
ERROR: Cannot create IPC directory
```

**Solution:**
```yaml
volumes:
  - name: shm
    emptyDir:
      medium: Memory
      sizeLimit: 1Gi

volumeMounts:
  - mountPath: /dev/shm
    name: shm
```

---

### 11.2 Diagnostic Commands

**Check cluster health:**
```bash
# Kubernetes
kubectl exec -n aeron-io-sample-cluster aeron-io-sample-cluster-0 -- \
    java -cp /home/aeron/jar/aeron-all-*.jar \
    io.aeron.cluster.ClusterTool describe \
    --cluster-dir=/home/aeron/jar/aeron-cluster/cluster

# Docker Compose
docker exec node0 \
    java -cp /home/aeron/jar/aeron-all-*.jar \
    io.aeron.cluster.ClusterTool describe \
    --cluster-dir=/home/aeron/jar/aeron-cluster/cluster
```

**Check leader:**
```bash
# Kubernetes
./k8s_find_leader.sh

# Docker
./docker/docker_find_leader.sh
```

**View logs:**
```bash
# Kubernetes
kubectl logs -n aeron-io-sample-cluster aeron-io-sample-cluster-0 -f

# Docker Compose
docker compose -f docker/docker-compose.yml logs -f node0
```

**Force snapshot:**
```bash
java -cp aeron-all.jar io.aeron.cluster.ClusterTool snapshot \
    --cluster-dir=/path/to/cluster
```

---

## Summary

This guide provides comprehensive configuration documentation for the Aeron Cluster application. Key takeaways:

1. **Configuration:** Environment variables (containers) or system properties (local)
2. **Transport:** UDP for distributed, IPC for single-machine (dev only)
3. **Ports:** BASE_PORT + (NODE_ID × 100) + OFFSET
4. **Build:** Gradle → Uber JAR → Docker image
5. **Deploy:** Docker Compose (dev), Kubernetes (production)
6. **DNS:** Critical for Kubernetes (use DNS_DELAY=true)
7. **Performance:** Dedicated threading, busy-spin, large term buffers
8. **Monitoring:** Track consensus health, performance, storage
9. **Backup:** Snapshots + continuous archive replication
10. **Troubleshooting:** Check DNS, ports, network, CPU, memory

**For new projects:**
- Copy this configuration structure
- Customize port ranges and timeouts
- Implement proper monitoring
- Test disaster recovery procedures
- Document any deviations from these practices

---

**Last Updated:** 2025-10-08
**Aeron Version:** 1.48.0
**JDK Version:** 21 (Azul Zulu)
**Kubernetes Tested:** 1.28+