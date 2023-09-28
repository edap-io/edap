package io.edap.log.appenders.rolling;

import io.edap.log.LogEvent;
import io.edap.log.appenders.FileAppender;
import io.edap.log.helps.ByteArrayBuilder;

import java.io.File;
import java.io.IOException;

import static io.edap.log.helpers.Util.printError;

public class RollingFileAppender extends FileAppender {

    File currentlyActiveFile;

    private RollingPolicy rollingPolicy;

    public static final int UNBOUNDED_HISTORY = 0;

    private TriggeringPolicy triggeringPolicy;

    public void setRollingPolicy(RollingPolicy rollingPolicy) {
        this.rollingPolicy = rollingPolicy;
        if (rollingPolicy instanceof TriggeringPolicy) {
            this.triggeringPolicy = (TriggeringPolicy) rollingPolicy;
        }
    }

    public void setTriggeringPolicy(TriggeringPolicy triggeringPolicy) {
        this.triggeringPolicy = triggeringPolicy;
        if (triggeringPolicy instanceof RollingPolicy) {
            this.rollingPolicy = (RollingPolicy)triggeringPolicy;
        }
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
                currentlyActiveFile = new File(activeFileName);
            } catch (Throwable e) {
                printError("rollingPolicy.getActiveFileName error", e);
            }
            if (file == null && activeFileName != null) {
                setFile(activeFileName);
            }
        }
        super.start();
    }

    @Override
    public void append(LogEvent logEvent) throws IOException {
        ByteArrayBuilder builder = encoder.encode(logEvent);
        // 如果判断日志文件需要滚动，则先加锁后再次判断是否需要滚动，判断是否需要滚动和日志文件滚动为非原子操作
        // 为了降低加锁频率所以先判断后如果需要滚动再加锁然后再次判断，因为当前线程判断时需要加锁，但是加锁后
        // 可能其他线程已经进行了日志滚动，所以需要再次判断是否需要滚动，因为需要滚次频次比非滚动频次低的多，所以
        // 这个操作会比每次都加锁效率高。
        if (triggeringPolicy.isTriggeringEvent(currentlyActiveFile, logEvent, builder)) {
            try {
                lock.lock();
                if (triggeringPolicy.isTriggeringEvent(currentlyActiveFile, logEvent, builder)) {
                    rollingPolicy.rollover();
                }
            } finally {
                lock.unlock();
            }
        }
        super.writeData(builder);
    }
}
