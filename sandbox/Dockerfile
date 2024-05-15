# Use Paper 1.12.2 as the base image
FROM itzg/minecraft-server:latest

# Set environment variables for Minecraft server
ENV TYPE=PAPER
ENV VERSION=1.12.2
ENV EULA=TRUE
ENV ENABLE_AUTOPAUSE=FALSE
ENV MEMORY=2G
ENV MAX_WORLD_SIZE=1000
ENV MODE=creative
ENV LEVEL_TYPE=FLAT
ENV MOTD=ShowScriptSandbox
ENV ALLOW_NETHER=FALSE

# Expose the Minecraft server port
EXPOSE 25565

# Copy the setup script and make it executable
COPY setup-environment.sh /setup-environment.sh
RUN chmod +x /setup-environment.sh

ENTRYPOINT [ "" ]
