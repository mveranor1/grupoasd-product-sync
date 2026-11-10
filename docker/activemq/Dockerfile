FROM eclipse-temurin:25-jre-alpine

ARG ACTIVEMQ_VERSION=6.3.2
ARG ACTIVEMQ_SHA512=30df66a98916fd448db6f29d591b24dd75584f58bafbe059377df923a65190a9dabfa036503bd35bc1469546360704e6942c5f922322e2b89e261bd45cad128a

ENV ACTIVEMQ_HOME=/opt/activemq \
    ACTIVEMQ_BASE=/opt/activemq \
    ACTIVEMQ_CONF=/opt/activemq/conf \
    ACTIVEMQ_DATA=/opt/activemq/data \
    ACTIVEMQ_OPTS="-Xms64M -Xmx1G -Djava.util.logging.config.file=logging.properties -Djava.security.auth.login.config=/opt/activemq/conf/login.config -Djetty.host=0.0.0.0"

RUN apk add --no-cache curl \
    && addgroup -S activemq \
    && adduser -S -G activemq activemq \
    && mkdir -p "${ACTIVEMQ_HOME}" "${ACTIVEMQ_DATA}" "${ACTIVEMQ_HOME}/tmp" \
    && curl -fsSL "https://archive.apache.org/dist/activemq/${ACTIVEMQ_VERSION}/apache-activemq-${ACTIVEMQ_VERSION}-bin.tar.gz" -o /tmp/activemq.tar.gz \
    && echo "${ACTIVEMQ_SHA512}  /tmp/activemq.tar.gz" | sha512sum -c - \
    && tar -xzf /tmp/activemq.tar.gz --strip-components=1 -C "${ACTIVEMQ_HOME}" \
    && awk 'index($0, "<Item>::1</Item>") { print; print "              <Item>172.16.0.0/12</Item>"; next } { print }' "${ACTIVEMQ_CONF}/jetty/jetty-security.xml" > /tmp/jetty-security.xml \
    && mv /tmp/jetty-security.xml "${ACTIVEMQ_CONF}/jetty/jetty-security.xml" \
    && rm /tmp/activemq.tar.gz \
    && chown -R activemq:activemq "${ACTIVEMQ_HOME}"

USER activemq
WORKDIR /opt/activemq

EXPOSE 61616 8161

HEALTHCHECK --interval=10s --timeout=5s --retries=10 \
    CMD code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8161/); test "$code" = 200 -o "$code" = 401

ENTRYPOINT ["/opt/activemq/bin/activemq"]
CMD ["console"]
