ARG BASE_IMAGE=debian:trixie-slim
FROM ${BASE_IMAGE}
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 python3-venv python3-pip python3-gi python3-asyncssh \
    gir1.2-gtk-4.0 gir1.2-adw-1 adb openssh-client network-manager iproute2 \
    xvfb xauth dbus-x11 desktop-file-utils x11-utils x11-apps imagemagick \
    && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv --system-site-packages /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir asyncssh==2.24.0
ENV PATH=/opt/venv/bin:$PATH PYTHONDONTWRITEBYTECODE=1
USER 1000:1000
WORKDIR /work
