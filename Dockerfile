# Estágio 1: Build (Compila o código usando Maven)
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Estágio 2: Runtime (A imagem final enxuta)
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app

# Copia o JAR compilado do estágio anterior
COPY --from=build /build/target/scadufax-thoth.jar /app/api.jar

# Tuning extremo para a JVM em contêineres minúsculos:
# -Xms / -Xmx: Limita a Heap para sobrar memória para o Mmap do SO.
# -XX:+UseSerialGC: O Garbage Collector mais leve e que gasta menos CPU para aplicações Single-Core/Low-Memory.
ENV JAVA_OPTS="-Xms50m -Xmx80m -XX:+UseSerialGC -XX:MaxDirectMemorySize=10m --enable-native-access=ALL-UNNAMED"

EXPOSE 8080

CMD ["sh", "-c", "java $JAVA_OPTS -jar /app/api.jar"]