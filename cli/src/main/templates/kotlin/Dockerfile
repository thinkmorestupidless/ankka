# The service as `./gradlew installDist` lays it out: build that first, then `docker build`. The
# image only copies files, so one build holds both architectures without emulating either.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/install/{{name}}/ ./
EXPOSE 9000
ENTRYPOINT ["/app/bin/{{name}}"]
