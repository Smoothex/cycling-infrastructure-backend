FROM ubuntu:24.04 AS tippecanoe-builder

RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential git ca-certificates libsqlite3-dev zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch 2.78.0 https://github.com/felt/tippecanoe.git /tippecanoe \
    && make -C /tippecanoe -j"$(nproc)" \
    && make -C /tippecanoe install

FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon

COPY src ./src
RUN ./gradlew bootJar --no-daemon
RUN find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /tmp/app.jar \;

FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends libsqlite3-0 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=tippecanoe-builder /usr/local/bin/tippecanoe /usr/local/bin/tippecanoe
COPY --from=tippecanoe-builder /usr/local/bin/tile-join /usr/local/bin/tile-join

WORKDIR /app
COPY --from=builder /tmp/app.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Xms2g", "-Xmx10g", "-jar", "/app/app.jar"]
