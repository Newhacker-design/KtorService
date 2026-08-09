FROM eclipse-temurin:25-jdk

WORKDIR /app

COPY . .

RUN chmod +x gradlew
RUN ./gradlew clean build --no-daemon

ENV PORT=10000

EXPOSE 10000

CMD ["./gradlew", "run", "--no-daemon"]
