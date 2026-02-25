FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd -r appuser && useradd -r -g appuser appuser
COPY --from=build /app/build/libs/*-all.jar app.jar
COPY static/ static/
RUN chown -R appuser:appuser /app
USER appuser
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
