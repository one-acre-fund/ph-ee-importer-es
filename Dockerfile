FROM eclipse-temurin:17 AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
COPY --from=build /app/config/elastic/elastic-apm-agent-1.54.0.jar /config/elastic/elastic-apm-agent.jar

CMD ["java", "-jar", "app.jar"]

