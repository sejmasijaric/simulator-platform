# Build: Maven builds the backend and, with a project-local Node, the web UI.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline -Dweb.skip=true
COPY web/package.json web/package-lock.json web/
COPY web web
COPY src src
RUN mvn -B -q -DskipTests package

# Run
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/simulation-platform-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
