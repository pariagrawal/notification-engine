# Build the jar inside the image so a clone needs nothing but Docker.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy the POM first: this layer is cached until a dependency actually changes,
# so ordinary source edits do not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Run unprivileged: a container that never needs to write to its own filesystem
# should not be able to.
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /build/target/*.jar app.jar
USER app

EXPOSE 8080

# The add-opens flags are required by Ignite 2.x, which reflects into JDK internals that
# have been closed by default since Java 16. Without them the thin client cannot start.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "--add-opens=java.base/java.nio=ALL-UNNAMED", \
  "--add-opens=java.base/java.util=ALL-UNNAMED", \
  "--add-opens=java.base/java.lang=ALL-UNNAMED", \
  "-jar", "app.jar"]
