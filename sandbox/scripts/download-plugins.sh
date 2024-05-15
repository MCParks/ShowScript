#!/bin/bash

# Navigate to the plugins directory
cd /workspaces/ShowScript/sandbox/data/plugins

# Read each line from the CSV file
while IFS=, read -r pluginName directDownloadUrl
do
  # Check if the plugin jar already exists
  if [ ! -f "$pluginName.jar" ]; then
    echo "Downloading $pluginName..."
    # Download the plugin jar from the direct download URL
    wget -O "$pluginName.jar" "$directDownloadUrl"
  else
    echo "$pluginName already exists, skipping download."
  fi
done < /workspaces/ShowScript/sandbox/plugins.csv
