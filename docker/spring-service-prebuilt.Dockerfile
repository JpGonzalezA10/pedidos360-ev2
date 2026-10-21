# EP3 - Imagen para instancias pequenas (capa gratuita, t3.micro).
# Usa el jar ya compilado en target/ (./mvnw -DskipTests package) para evitar compilar dentro de Docker.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
