# EP3 - Imagen generica para los microservicios Spring Boot de Pedidos360.
# Se construye con el contexto de cada microservicio (ver docker-compose.yml).
# Etapa 1: compila con Maven. Etapa 2: imagen liviana solo con el JRE y el jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
