#!/bin/bash
ln -s /data /workspaces/ShowScript/sandbox

# Run script to build ShowScript
bash /workspaces/ShowScript/sandbox/scripts/build-plugin.sh

# Run script to fetch other plugins if they don't exist
bash /workspaces/ShowScript/sandbox/scripts/download-plugins.sh



# Start the Minecraft server
cd /workspaces/ShowScript/sandbox



# Attach the VSCode Console to the Minecraft server