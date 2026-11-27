#!/bin/bash

mkdir -p "$HOME/.ssh"
chmod 700 "$HOME/.ssh"

if [ ! -f "$HOME/.ssh/id_ed25519" ]; then
  ssh-keygen -q -t ed25519 -N "" -f "$HOME/.ssh/id_ed25519"
fi

ssh -p 443 \
  -o StrictHostKeyChecking=accept-new \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 \
  -R0:127.0.0.1:25565 \
  tcp@a.pinggy.io