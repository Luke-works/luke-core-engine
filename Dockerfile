# syntax=docker/dockerfile:1.7

# ─── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Resolve dependencies in a separate layer so they cache across source changes
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw mvnw
RUN ./mvnw -B dependency:go-offline

# Build the application jar
COPY src ./src
RUN ./mvnw -B clean package -DskipTests

# ─── Runtime stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as non-root, with a real writable home (-m) as defensive hygiene for any
# library that writes under $HOME. (Historically REQUIRED by GraalVM JS/Truffle,
# which unpacked runtime resources into $HOME and crashed a homeless user with
# `AccessDeniedException: /home/luke`; the scripting engines were removed in #22,
# but the writable home is cheap and kept.)
RUN groupadd -r luke && useradd -r -g luke -m -d /home/luke luke
USER luke
ENV HOME=/home/luke

# Copy the built jar (version pattern matches pom artifactId-version)
COPY --from=build --chown=luke:luke /app/target/luke-core-engine-*.jar app.jar

# Render injects $PORT at runtime; 8080 is the local default
EXPOSE 8080

# Container-aware JVM sizing (#22). Heap is capped at 55% (down from the original
# 75%) because Render OOM-kills on total RSS, not just heap — the remaining ~45%
# holds Metaspace, thread stacks, code cache, direct buffers and GC structures.
# With the scripting engines removed (#22) the off-heap footprint dropped sharply,
# so 55% now leaves comfortable headroom on the 512 MB starter plan; it can be
# raised toward 65% if the app ever needs more heap. Upgrade the instance for a
# larger absolute heap.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=55.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
