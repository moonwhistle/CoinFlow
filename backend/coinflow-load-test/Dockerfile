FROM amazoncorretto:17-alpine
WORKDIR /app

# Gradle에서 빌드된 fat jar 복사 (개별 모듈 빌드 폴더 기준)
COPY build/libs/coinflow-load-test-*.jar app.jar

# 환경 변수 기본값
ENV REDIS_HOST=localhost \
    REDIS_PORT=6379 \
    TARGET_TPS=10000 \
    SYMBOLS=btcusdt

# 실행 (CLI 인자 없이 환경 변수 모드로 실행)
ENTRYPOINT ["java", "-jar", "app.jar"]
