FROM eclipse-temurin:21-jdk AS builder
WORKDIR /project
COPY gradlew ./gradlew
COPY gradle ./gradle
COPY src ./src
COPY build.gradle.kts ./build.gradle.kts
COPY settings.gradle.kts ./settings.gradle.kts
RUN ./gradlew --no-daemon clean test installDist

FROM eclipse-temurin:21-jre AS backend
WORKDIR /root
COPY --from=builder /project/build/install/glock/ ./app/
WORKDIR /root/app/
ENTRYPOINT ["bin/glock"]
