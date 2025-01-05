package io.edap.log.consts;

import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;

public class LogConsts {

    private LogConsts() {}

    public static final String CONFIG_FILE_PROPERTY = "edaplog.configurationFile";

    public static final String DEFAULT_CONSOLE_APPENDER_NAME = "CONSOLE";

    public static final String DEFAULT_FILE_APPENDER_NAME = "FILE";

    public static final int DEFAULT_QUEUE_SIZE = 1024;
    public static final WaitStrategy DEFAULT_WAIT_STRATEGY = new SleepingWaitStrategy();

    public static final String DEFAULT_EVENT_QUEUE_NAME = "DEFAULT_LOGEVENT_QUEUE";
}
