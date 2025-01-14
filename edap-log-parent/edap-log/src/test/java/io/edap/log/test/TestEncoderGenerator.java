package io.edap.log.test;

import io.edap.log.Encoder;
import io.edap.log.LogEvent;
import io.edap.log.helps.EncoderGenerator;
import io.edap.log.helps.LogEncoderRegister;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestEncoderGenerator {

    @Test
    public void testStringToInternal() {
        Encoder encoder = LogEncoderRegister.instance().getEncoder(
                "%date{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%logger{36}] " +
                        "[%X{traceId}] [%X{spanId}] [%X{pSpanId}] [%X{rpcOccurrence}] [%X{code}] " +
                        "[%X{req.requestURL}] [%X{req.queryString}] [${spring.domain.name}," +
                        "${spring.application.name},%X{sysId},%X{tenantId},%X{userId},%X{profile}," +
                        "%X{agentId}] - %msg %ex%n");
        System.out.println(encoder);

        encoder = LogEncoderRegister.instance().getEncoder(
                "### %date{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%logger{36}] " +
                        "用户id: [%X{traceId}] [%X{spanId}] [%X{pSpanId}] [%X{rpcOccurrence}] [%X{code}] " +
                        "[%X{req.requestURL}] [%X{req.queryString}] [${spring.domain.name}," +
                        "${spring.application.name},%X{sysId},%X{tenantId},%X{userId},%X{profile}," +
                        "%X{agentId}] - %msg %ex%n");
        System.out.println(encoder);
    }
}
