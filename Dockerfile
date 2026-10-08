FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src/main/java src/main/java
RUN mkdir classes && javac --release 21 -d classes src/main/java/portfolio/gateway/*.java
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/classes /app/classes
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-cp", "/app/classes", "portfolio.gateway.Main"]
