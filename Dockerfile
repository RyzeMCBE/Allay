# Build with Git submodules checked out: git clone --recurse-submodules ...
FROM eclipse-temurin:21-jdk-jammy AS build
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
RUN test -f protocol-local/bedrock-codec/build.gradle.kts \
    && test -f stateupdater-local/settings.gradle.kts
RUN chmod +x gradlew && ./gradlew :server:shadowJar --no-daemon

FROM eclipse-temurin:21-jre-jammy
LABEL org.opencontainers.image.title="Engine" \
      org.opencontainers.image.source="https://github.com/RyzeMCBE/Engine"
RUN useradd --system --create-home --home-dir /home/engine --shell /usr/sbin/nologin engine \
    && mkdir -p /opt/engine /data && chown engine:engine /data
COPY --from=build /src/server/build/libs/Engine-*-shaded.jar /opt/engine/Engine.jar
USER engine
WORKDIR /data
EXPOSE 19132/tcp
EXPOSE 19132/udp
VOLUME ["/data"]
ENTRYPOINT ["java", "-jar", "/opt/engine/Engine.jar"]
