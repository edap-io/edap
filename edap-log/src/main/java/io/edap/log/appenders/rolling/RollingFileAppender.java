package io.edap.log.appenders.rolling;

import io.edap.log.appenders.FileAppender;

public class RollingFileAppender extends FileAppender {

    private RollingPolicy rollingPolicy;

    public void setRollingPolicy(RollingPolicy rollingPolicy) {
        this.rollingPolicy = rollingPolicy;
    }

}
