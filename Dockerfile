FROM eclipse-temurin:25-jdk

WORKDIR /app

COPY build/libs/KtorService-all.jar build/libs/KtorService-all.jar
COPY start.sh start.sh

RUN chmod +x start.sh

EXPOSE 10000

ENTRYPOINT ["./start.sh"]