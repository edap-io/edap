package io.edap.log;

public interface RolloverLogWriter extends LogWriter {

    /**
     * 切换日志为文件，如果日志文件不用日志框架切换则不处理即可
     * @param event
     */
    void rollover(LogEvent event);
}
