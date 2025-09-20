# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -f orchestrator-db-core/pom.xml -DskipTests install  && mvn -q -f orchestrator-starter/pom.xml -DskipTests install  && mvn -q -f orders-orchestrator-app/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/orders-orchestrator-app/target/orders-orchestrator-app-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
