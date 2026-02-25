FROM amazoncorretto:17-alpine-jdk

# 타임존 설정 (KST)
ENV TZ=Asia/Seoul

# 작업 디렉토리
WORKDIR /app

# 로컬에서 빌드된 jar 파일을 이미지로 복사
COPY build/libs/*-SNAPSHOT.jar app.jar

# 패키징된 스프링 실행 시 기본 프로필 설정 (docker-compose에서 덮어쓰기 가능)
ENV PROFILE=prod

# 포트 개방
EXPOSE 8080

# 메모리 최적화 옵션 및 실행
ENTRYPOINT ["sh", "-c", "java -Dspring.profiles.active=${PROFILE} -Xms256m -Xmx512m -jar app.jar"]
