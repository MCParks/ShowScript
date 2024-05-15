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

# Install OpenJDK 8
RUN apt-get update && \
    apt-get install -y openjdk-8-jdk && \
    apt-get clean;

# Set JAVA_HOME environment variable
ENV JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

# Expose the Minecraft server port
EXPOSE 25565



ENTRYPOINT [ "" ]
