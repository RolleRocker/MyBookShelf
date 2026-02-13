FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar
COPY static/ static/
RUN mkdir -p /app/covers
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
