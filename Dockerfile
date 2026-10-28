FROM maven:3.9.15-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system bot && useradd --system --gid bot bot
WORKDIR /app
COPY --from=build /build/target/eduping-1.0.0.jar app.jar
USER bot
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Dfile.encoding=UTF-8"
ENTRYPOINT ["java","-jar","/app/app.jar"]
