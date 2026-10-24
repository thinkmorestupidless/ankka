# For whoever prefers a Dockerfile to Jib (`./mvnw package jib:dockerBuild`): the same image, built
# in two stages with the project's own wrapper, so the build machine needs Docker and nothing else.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/dependency/ lib/
COPY --from=build /src/target/*.jar lib/
EXPOSE 9000
ENTRYPOINT ["java", "-cp", "/app/lib/*", "{{package}}.Main"]
