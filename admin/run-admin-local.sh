#!/bin/bash

# Script to run admin connecting to 3 local cluster nodes
# The cluster nodes should be running at:
# - Node 0: localhost:9000
# - Node 1: localhost:9100
# - Node 2: localhost:9200

# Set environment variables for cluster connection
export CLUSTER_ADDRESSES="localhost,localhost,localhost"
export RESPONSE_PORT="0"  # Use ephemeral port
export AUTO_CONNECT="false"  # Set to "true" to auto-connect on startup
export DUMB_TERMINAL="false"  # Set to "true" if terminal issues

# Optional: Set participant ID for this admin session
# export PARTICIPANT_ID="0"

echo "=========================================="
echo "Starting Admin CLI"
echo "=========================================="
echo "Cluster Addresses: $CLUSTER_ADDRESSES"
echo "Response Port: $RESPONSE_PORT"
echo "Auto Connect: $AUTO_CONNECT"
echo ""
echo "To connect to cluster, type: connect"
echo "For help, type: help"
echo "=========================================="
echo ""

# Run the admin uber jar
cd "$(dirname "$0")/build/libs"
java -jar admin-uber.jar