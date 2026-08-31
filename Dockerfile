# Generic runtime image for any platform service. The Deploy workflow
# builds the jars once with Maven, then stamps one image per service:
#
#   docker build --build-arg MODULE=ingestion-service -t <ecr>/ingestion-service .
#
ARG MODULE=api-gateway-service

FROM eclipse-temurin:17-jre
ARG MODULE
WORKDIR /app
COPY services/${MODULE}/target/${MODULE}-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
