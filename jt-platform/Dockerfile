# JT/T 808 与 JT/T 1078 协议网关
#
# 多阶段构建：依赖解析与编译在构建阶段完成，运行镜像只保留 JRE 与可执行 JAR。
# 先单独复制 pom 再复制源码，可以让 Maven 依赖层在源码变动时仍然命中缓存。

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

COPY pom.xml ./
COPY jt-protocol/pom.xml   jt-protocol/
COPY jt-common/pom.xml     jt-common/
COPY jt-delivery/pom.xml   jt-delivery/
COPY jt-signal/pom.xml     jt-signal/
COPY jt-media/pom.xml      jt-media/
COPY jt-api/pom.xml        jt-api/
COPY jt-boot-all/pom.xml   jt-boot-all/
# 只解析这七个模块的依赖：jt-terminal-simulator 是桌面工具，不参与服务端构建
RUN mvn -B -ntp -pl jt-protocol,jt-common,jt-delivery,jt-signal,jt-media,jt-api,jt-boot-all \
        dependency:go-offline || true

COPY jt-protocol/src   jt-protocol/src
COPY jt-common/src     jt-common/src
COPY jt-delivery/src   jt-delivery/src
COPY jt-signal/src     jt-signal/src
COPY jt-media/src      jt-media/src
COPY jt-api/src        jt-api/src
COPY jt-boot-all/src   jt-boot-all/src
RUN mvn -B -ntp -pl jt-protocol,jt-common,jt-delivery,jt-signal,jt-media,jt-api,jt-boot-all \
        -am clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /var/lib/jt-platform

RUN groupadd --system jt-platform \
 && useradd --system --gid jt-platform --home-dir /var/lib/jt-platform jt-platform \
 && mkdir -p /var/lib/jt-platform/logs \
             /var/lib/jt-platform/recordings \
             /var/lib/jt-platform/data/delivery-overflow/api \
 && chown -R jt-platform:jt-platform /var/lib/jt-platform

COPY --from=build /build/jt-boot-all/target/jt-boot-all-*.jar /opt/jt-platform/app.jar

USER jt-platform

# 7100/7101 终端信令，7810-7815 媒体，8100 REST，8109 Actuator
EXPOSE 7100 7101/udp 7810 7811 7812 7813 7814 7815 8100 8109

ENV JAVA_OPTS="-Xms256m -Xmx1g -XX:MaxDirectMemorySize=512m -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.net.preferIPv4Stack=true"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/jt-platform/app.jar"]
