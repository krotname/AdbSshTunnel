FROM ghcr.io/cirruslabs/android-sdk@sha256:f9b3ea9ed2b5fc9522adae82c7b4622ab7aa54207ef532c8e615a347dca08f31
USER root
RUN yes | sdkmanager --licenses >/dev/null && sdkmanager 'ndk;27.2.12479018' 'cmake;3.22.1' 'platforms;android-36' 'build-tools;36.0.0'
RUN curl -fsSL https://services.gradle.org/distributions/gradle-8.11.1-bin.zip -o /tmp/gradle.zip && curl -fsSL https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256 -o /tmp/gradle.sha256 && cd /tmp && echo "$(cat gradle.sha256)  gradle.zip" | sha256sum -c - && unzip -q gradle.zip -d /opt && rm gradle.zip gradle.sha256
ENV PATH="/opt/gradle-8.11.1/bin:${PATH}"
USER 1000:1000
