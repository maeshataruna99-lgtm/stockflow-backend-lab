# Multi-stage build for discovery-server (Phase 4, ADR 0001 pins).
# Builder image pins Maven 3.9.16 - the same version the mvnw wrapper
# uses on the host - on Temurin JDK 21. Runtime is the Temurin 21.0.12.1
# JRE (the same JDK build the host builds with), Alpine variant.
# Runtime runs as a non-root user; the service port is >1024 so no
# privileged binding is needed.

FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build
# Dependency layer first: pom.xml alone rarely changes, so the .m2
# cache layer survives source-only edits.
COPY pom.xml .
RUN mvn --batch-mode dependency:go-offline
COPY src ./src
RUN mvn --batch-mode package -DskipTests

FROM eclipse-temurin:21.0.12.1_1-jre-alpine-3.24
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER app
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
