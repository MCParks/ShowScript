#!/bin/bash

# Run script to build ShowScript
bash /workspaces/showscriptsandbox/scripts/build-plugin.sh

# Run script to fetch other plugins if they don't exist
bash /workspaces/showscriptsandbox/scripts/download-plugins.sh

# Start the Minecraft server
cd /workspaces/showscriptsandbox
java -Xms1G -Xmx1G -jar paper-1.12.2.jar nogui

# Attach the VSCode Console to the Minecraft server
# Note: Implementation depends on the specific method used to start the server and VSCode integration
