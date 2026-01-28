FROM eclipse-temurin:17 AS build
WORKDIR /app
COPY build/libs/*.jar .
CMD java -jar *.jar

