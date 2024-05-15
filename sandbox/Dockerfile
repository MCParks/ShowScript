# Use Paper 1.12.2 as the base image
FROM itzg/minecraft-server:latest

# Set environment variables for Minecraft server
ENV TYPE=PAPER
ENV VERSION=1.12.2
ENV EULA=TRUE
ENV ENABLE_AUTOPAUSE=FALSE
ENV MEMORY=2G

# Expose the Minecraft server port
EXPOSE 25565

# Copy the setup script and make it executable
COPY setup-environment.sh /setup-environment.sh
RUN chmod +x /setup-environment.sh

# Run the setup script before starting the server
ENTRYPOINT ["/bin/bash", "/setup-environment.sh"]
