#!/bin/bash

# Run script to build ShowScript
bash /workspaces/ShowScript/sandbox/scripts/build-plugin.sh

# Run script to fetch other plugins if they don't exist
bash /workspaces/ShowScript/sandbox/scripts/download-plugins.sh



# Start the Minecraft server
cd /workspaces/ShowScript/sandbox

ln -s /workspaces/ShowScript/sandbox/server /data

# Attach the VSCode Console to the Minecraft server
# Note: Implementation depends on the specific method used to start the server and VSCode integration
code --command workbench.action.tasks.runTask 'Minecraft Server'