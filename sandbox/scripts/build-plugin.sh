#!/bin/bash

# Navigate to the project root directory
cd "$(dirname "$0")/../.."

# Build the ShowScript plugin
./gradlew build

# Ensure the plugins directory exists
mkdir -p /workspaces/showscriptsandbox/plugins

# Move the built plugin jar to the plugins directory
mv build/libs/showscript.jar /workspaces/showscriptsandbox/plugins/
