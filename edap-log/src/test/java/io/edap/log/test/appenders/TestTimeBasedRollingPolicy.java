package io.edap.log.test.appenders;

import io.edap.log.appenders.rolling.TimeBasedRollingPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class TestTimeBasedRollingPolicy {

    @ParameterizedTest
    @ValueSource(strings = {
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd HH",
            "yyyy-MM-dd",
            "yyyy-MM",
            "yyyy",
    })
    public void testStart(String dateFormat) throws NoSuchFieldException, IllegalAccessException {
        TimeBasedRollingPolicy policy = new TimeBasedRollingPolicy();
        policy.setFileNamePattern("${logging.path}/javascript-${spring.application.name}.%d{" + dateFormat + "}.log.bak");
        policy.start();
        Field currentMaxTimeField = policy.getClass().getDeclaredField("currentMaxTime");
        currentMaxTimeField.setAccessible(true);
        long currentMaxTime = (long)currentMaxTimeField.get(policy);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(currentMaxTime);

        String currentDateStr = new SimpleDateFormat(dateFormat).format(cal.getTime());
        System.out.println(currentDateStr);

        currentMaxTime++;
        cal.setTimeInMillis(currentMaxTime);
        String nextMillsDateStr = new SimpleDateFormat(dateFormat).format(cal.getTime());
        System.out.println(nextMillsDateStr);

        assertNotEquals(currentDateStr, nextMillsDateStr);
    }

    @Test
    public void testAvalidDateFormat() throws NoSuchFieldException, IllegalAccessException {
        TimeBasedRollingPolicy policy = new TimeBasedRollingPolicy();
        policy.setFileNamePattern("${logging.path}/javascript-${spring.application.name}.%d{}.log.bak");
        policy.start();
        Field currentMaxTimeField = policy.getClass().getDeclaredField("currentMaxTime");
        currentMaxTimeField.setAccessible(true);
        long currentMaxTime = (long)currentMaxTimeField.get(policy);

        assertEquals(currentMaxTime, Long.MAX_VALUE);

        policy = new TimeBasedRollingPolicy();
        policy.setFileNamePattern("${logging.path}/javascript-${spring.application.name}.%p{}.log.bak");
        policy.start();
        currentMaxTimeField = policy.getClass().getDeclaredField("currentMaxTime");
        currentMaxTimeField.setAccessible(true);
        currentMaxTime = (long)currentMaxTimeField.get(policy);

        assertEquals(currentMaxTime, Long.MAX_VALUE);

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(currentMaxTime);
        String currentDateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(cal.getTime());
        System.out.println(currentDateStr);

        policy = new TimeBasedRollingPolicy();
        policy.setFileNamePattern("");
        policy.start();
        currentMaxTimeField = policy.getClass().getDeclaredField("currentMaxTime");
        currentMaxTimeField.setAccessible(true);
        currentMaxTime = (long)currentMaxTimeField.get(policy);
    }
}
