# Running Admin Locally with 3 Cluster Nodes

This guide shows how to run the admin CLI to connect to three local Aeron cluster nodes.

## Prerequisites

1. Java 21+ installed
2. Project built: `./gradlew build`

## Architecture

When running locally, the cluster nodes listen on these ports:

| Node | Ingress Port | Consensus Port | Log Port | Archive Port |
|------|--------------|----------------|----------|--------------|
| 0    | 9000         | 9010           | 9040     | 9030         |
| 1    | 9100         | 9110           | 9140     | 9130         |
| 2    | 9200         | 9210           | 9240     | 9230         |

The admin connects to the **ingress ports** (9000, 9100, 9200) to send commands.

## Step 1: Start Cluster Nodes

### Option A: Start Single Node Cluster (for testing)
```bash
./gradlew :cluster:runSingleNodeCluster
```

This starts a single node cluster (node 0) listening on port 9000.

### Option B: Start 3-Node Cluster Manually

In separate terminals, run each node:

**Terminal 1 - Node 0:**
```bash
export CLUSTER_NODE=0
export CLUSTER_ADDRESSES="localhost,localhost,localhost"
export CLUSTER_PORT_BASE=9000
./gradlew :cluster:run
```

**Terminal 2 - Node 1:**
```bash
export CLUSTER_NODE=1
export CLUSTER_ADDRESSES="localhost,localhost,localhost"
export CLUSTER_PORT_BASE=9000
./gradlew :cluster:run
```

**Terminal 3 - Node 2:**
```bash
export CLUSTER_NODE=2
export CLUSTER_ADDRESSES="localhost,localhost,localhost"
export CLUSTER_PORT_BASE=9000
./gradlew :cluster:run
```

## Step 2: Run Admin CLI

In a new terminal:

```bash
cd admin
./run-admin-local.sh
```

### Configuration

The script sets these environment variables:
- `CLUSTER_ADDRESSES="localhost"` - Connect to all three nodes at localhost
- `RESPONSE_PORT="0"` - Use ephemeral port for responses
- `AUTO_CONNECT="false"` - Manual connection (change to "true" for auto-connect)
- `DUMB_TERMINAL="false"` - Full terminal support

### Customizing Connection

Edit `admin/run-admin-local.sh` to customize:

```bash
# Auto-connect on startup
export AUTO_CONNECT="true"

# Use specific response port (useful for debugging)
export RESPONSE_PORT="49152"

# Set participant ID for this session
export PARTICIPANT_ID="500"

# Use dumb terminal mode (if terminal issues occur)
export DUMB_TERMINAL="true"
```

## Step 3: Connect to Cluster

Once admin starts, you'll see:
```
==========================================
Starting Admin CLI
==========================================
Cluster Addresses: localhost
Response Port: 0
Auto Connect: false

To connect to cluster, type: connect
For help, type: help
==========================================

-------------------------------------------------
 Welcome to the Aeron Cluster QuickStart Console
-------------------------------------------------

Not auto-connecting to cluster
admin >
```

Type `connect` to connect to the cluster:
```
admin > connect
Connected to cluster leader, node 0
```

## Available Commands

Once connected, you can use these commands:

```bash
# Add a participant
add-participant id=500 name=Alice

# List all participants
list-participants

# Add an auction
add-auction created-by=500 name=Tulips

# Add a bid to an auction
add-bid auction-id=1 created-by=500 price=1000

# List all auctions
list-auctions

# Disconnect from cluster
disconnect

# Exit the application
exit
```

## Sample Session

```
admin > connect
Connected to cluster leader, node 0

admin > add-participant id=500 name=Alice
Participant added: 500 Alice

admin > add-participant id=501 name=Bob
Participant added: 501 Bob

admin > add-auction created-by=500 name=Tulips
Auction created: 1 Tulips

admin > add-bid auction-id=1 created-by=501 price=1000
Bid added: auction=1 participant=501 price=1000

admin > list-auctions
Auction 1: Tulips (status: OPEN) by participant 500

admin > disconnect
Disconnecting from cluster
Cluster disconnected

admin > exit
```

## Troubleshooting

### Terminal Issues

If you get terminal-related errors, try running with dumb terminal mode:
```bash
export DUMB_TERMINAL="true"
cd admin/build/libs
java -jar admin-uber.jar
```

### Connection Issues

1. **Cannot connect to cluster:**
   - Verify cluster nodes are running
   - Check ports 9000, 9100, 9200 are not blocked
   - Verify `CLUSTER_ADDRESSES` is set correctly

2. **Commands timeout:**
   - Check cluster is fully started (leader elected)
   - Verify firewall allows UDP traffic on ports 9000-9250

3. **"Not connected to cluster" error:**
   - Type `connect` first before sending commands

### Port Already in Use

If ports are already in use, you can change the base port:
```bash
export CLUSTER_PORT_BASE=10000
```

This will use ports 10000, 10100, 10200 instead.

## Advanced: Docker Compose Setup

For a complete local setup with Docker Compose, see `docker/docker-compose.yml`.

## Next Steps

- Review cluster logs: `cluster/build/node0/`, `cluster/build/node1/`, `cluster/build/node2/`
- Monitor cluster health using bundled scripts in `cluster/` directory
- Deploy to Kubernetes following `kubernetes/` manifests