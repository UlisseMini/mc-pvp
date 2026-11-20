#!/bin/sh
set -eu
if ! command -v Xvfb >/dev/null || ! test -e /usr/share/glvnd/egl_vendor.d/50_mesa.json || ! test -x /usr/lib/jvm/java-25-openjdk-arm64/bin/java; then
    sudo env DEBIAN_FRONTEND=noninteractive apt-get -qq update
    sudo env DEBIAN_FRONTEND=noninteractive apt-get -qq install -y --no-install-recommends \
      openjdk-25-jdk-headless xvfb xauth libgl1-mesa-dri libegl-mesa0 libglx-mesa0 libgl1 libegl1 libasound2t64 \
      libpulse0 libxrandr2 libxinerama1 libxcursor1 libxi6 libxkbcommon0 libxkbcommon-x11-0 \
      libxss1 libxtst6 libvulkan1 mesa-vulkan-drivers fonts-dejavu-core curl ca-certificates
fi

if ! command -v ffmpeg >/dev/null || ! command -v xwininfo >/dev/null; then
    sudo env DEBIAN_FRONTEND=noninteractive apt-get -qq update
    sudo env DEBIAN_FRONTEND=noninteractive apt-get -qq install -y --no-install-recommends ffmpeg x11-utils
fi
