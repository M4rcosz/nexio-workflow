FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q
COPY checkstyle.xml .
COPY src ./src
RUN ./mvnw clean package -DskipTests -q

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /app/target/nexio-workflow-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
