package io.edap.log.appenders.rolling;

import io.edap.log.LifeCycle;
import io.edap.log.LogCompression;
import io.edap.log.appenders.FileAppender;

import java.text.ParseException;

public interface RollingPolicy extends LifeCycle {
    /**
     * 日志文件切换,判断当前的日志是否需要进行日志文件的切换如果需要切换则切换日志文件后在记录日志到切换后的日志文件，并启动清理和归档相当的程序
     * 如果不需要切换则直接返回即可。
     */
    void rollover();

    /**
     * 当前使用的日志文件的文件名
     * @return
     */
    String getActiveFileName() throws ParseException;

    /**
     * 历史日志文件压缩模式
     * @return
     */
    LogCompression getCompression();

    /**
     * 设置改日志切换器的FileAppender实例
     * @param appender
     */
    void setParent(FileAppender appender);
}
