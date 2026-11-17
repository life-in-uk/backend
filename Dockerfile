# Production image of the Life in UK backend (deploy/RUNBOOK.md).
# Built by deploy/scripts/build-image.sh from a jar that passed `mvn verify`;
# the image only adds the Java 21 runtime and a non-root user.
FROM eclipse-temurin:21-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515

ARG REVISION=unknown
LABEL org.opencontainers.image.title="life-in-uk-backend" \
      org.opencontainers.image.revision="${REVISION}"

RUN groupadd --system --gid 10001 lifeinuk \
    && useradd --system --uid 10001 --gid lifeinuk --home-dir /app --shell /usr/sbin/nologin lifeinuk

WORKDIR /app
COPY target/backend-0.0.1-SNAPSHOT.jar /app/app.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
