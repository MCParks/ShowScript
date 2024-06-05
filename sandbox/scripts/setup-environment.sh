#!/bin/bash
ln -s /data /workspaces/ShowScript/sandbox

# Ensure the plugins directory exists
mkdir -p /workspaces/ShowScript/sandbox/data/plugins

bash /workspaces/ShowScript/sandbox/scripts/configure-terminals-manager.sh

chmod 777 /workspaces/ShowScript/sandbox/data/plugins

# Run script to build ShowScript
bash /workspaces/ShowScript/sandbox/scripts/build-plugin.sh

# Run script to fetch other plugins if they don't exist
bash /workspaces/ShowScript/sandbox/scripts/download-plugins.sh



# Start the Minecraft server
cd /workspaces/ShowScript/sandbox


