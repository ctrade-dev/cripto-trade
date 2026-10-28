FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY gradlew gradlew
COPY gradle/ gradle/
RUN chmod +x gradlew

COPY settings.gradle build.gradle ./
COPY core/build.gradle core/build.gradle
COPY adapter-binance/build.gradle adapter-binance/build.gradle
COPY adapter-coinbase/build.gradle adapter-coinbase/build.gradle
COPY adapter-mock/build.gradle adapter-mock/build.gradle
COPY adapter-strategy-remote/build.gradle adapter-strategy-remote/build.gradle
COPY strategy-service-reference/build.gradle strategy-service-reference/build.gradle
COPY strategy/build.gradle strategy/build.gradle
COPY spring-application/build.gradle spring-application/build.gradle
RUN ./gradlew :spring-application:dependencies --no-daemon -q

COPY . .
RUN ./gradlew :spring-application:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/spring-application/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
