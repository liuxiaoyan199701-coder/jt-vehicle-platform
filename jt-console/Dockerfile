# 业务后端：承接网关投递的协议消息，提供车辆、轨迹、告警、围栏与运营接口

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline || true

COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /var/lib/jt-console

RUN groupadd --system jt-console \
 && useradd --system --gid jt-console --home-dir /var/lib/jt-console jt-console \
 && mkdir -p /var/lib/jt-console/data /var/lib/jt-console/logs \
 && chown -R jt-console:jt-console /var/lib/jt-console

COPY --from=build /build/target/jt-console.jar /opt/jt-console/app.jar

USER jt-console

EXPOSE 8300

# SQLite 写入频繁但数据量小，串行 GC 比 G1 更省内存
ENV JAVA_OPTS="-Xms128m -Xmx512m -XX:MaxDirectMemorySize=128m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Djava.net.preferIPv4Stack=true" \
    JT_CONSOLE_DB=/var/lib/jt-console/data/jt-console.db

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/jt-console/app.jar"]
