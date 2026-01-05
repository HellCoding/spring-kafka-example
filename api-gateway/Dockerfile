FROM gradle:8.7-jdk17 AS build
WORKDIR /workspace
COPY src src
RUN printf 'rootProject.name = "api-gateway"\n' > settings.gradle && \
    printf 'plugins {\n\
    id "java"\n\
    id "org.springframework.boot" version "3.2.5"\n\
    id "io.spring.dependency-management" version "1.1.5"\n\
}\n\
group = "com.example"\n\
version = "1.0.0"\n\
java { sourceCompatibility = "17" }\n\
repositories { mavenCentral() }\n\
dependencyManagement {\n\
    imports { mavenBom "org.springframework.cloud:spring-cloud-dependencies:2023.0.1" }\n\
}\n\
dependencies {\n\
    implementation "org.springframework.cloud:spring-cloud-starter-gateway"\n\
    implementation "org.springframework.cloud:spring-cloud-starter-netflix-eureka-client"\n\
    implementation "org.springframework.boot:spring-boot-starter-actuator"\n\
}\n' > build.gradle && \
    gradle bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
