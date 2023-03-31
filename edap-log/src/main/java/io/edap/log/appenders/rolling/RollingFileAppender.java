package io.edap.log.appenders.rolling;

import io.edap.log.appenders.FileAppender;

import java.text.ParseException;

import static io.edap.log.helpers.Util.printError;

public class RollingFileAppender extends FileAppender {

    private RollingPolicy rollingPolicy;

    public void setRollingPolicy(RollingPolicy rollingPolicy) {
        this.rollingPolicy = rollingPolicy;
    }

    /**
     * 初始化文件并打开OutputStream，允许写入日志数据，如果没有配置默认日志文件名称则使用当天切换日志的文件名作为
     * 正在使用的文件名进行OutputStream进行打开。
     */
    @Override
    public void start() {
        String file = getFile();
        String activeFileName = null;
        if (rollingPolicy != null) {
            try {
                activeFileName = rollingPolicy.getActiveFileName();
            } catch (Throwable e) {
                printError("rollingPolicy.getActiveFileName error", e);
            }
        }

    }
}
