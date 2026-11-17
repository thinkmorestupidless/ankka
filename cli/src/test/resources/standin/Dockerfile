# The stand-in LocalUpSuite brings up in place of a real service (feature 066, research R16): one
# source file on the JDK, which answers what the command line asks of a runtime (the local console's
# service document at ANKKA_OBSERVABILITY_ADDRESS, HTTP at ANKKA_HTTP_PORT) and keeps what it is
# asked to record in the database the ANKKA_DB_* variables name, through psql. It proves the
# command's orchestration, not a runtime's behaviour: ProjectTemplateSuite proves that.
FROM eclipse-temurin:21-jdk AS build
COPY Standin.java /src/Standin.java
RUN javac -d /out /src/Standin.java

FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /out /standin
ENTRYPOINT ["java", "-cp", "/standin", "Standin"]
