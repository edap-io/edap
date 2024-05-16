package io.edap.http.client.test;

import io.edap.http.client.AsyncGroupConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestAsyncGroupConfig {

    @ParameterizedTest
    @ValueSource(strings = {
            "www.easyea.com",
            "www.easyea.com/",
            "http://www.easyea.com",
            "http://www.easyea.com/",
            "http://www.easyea.com/user/signIn.html",
            "https://www.easyea.com/user/signIn.html",
            "ws://www.easyea.com/user/signIn.html",
            "wss://www.easyea.com/user/signIn.html"
    })
    public void testSetHostQpsLimit(String url) throws NoSuchFieldException, IllegalAccessException {
        int count = new Random().nextInt();
        AsyncGroupConfig config = new AsyncGroupConfig();
        config.setHostQpsLimit(url, count);
        Field hostQpsLimitsMethod = AsyncGroupConfig.class.getDeclaredField("hostQpsLimits");
        hostQpsLimitsMethod.setAccessible(true);
        Map<String, Integer> limits = (Map<String, Integer>)hostQpsLimitsMethod.get(config);
        assertNotNull(limits);
        assertEquals(limits.size(), 1);
        assertEquals(limits.containsKey("www.easyea.com"), true);
        assertEquals(limits.get("www.easyea.com"), count);
    }

    @Test
    public void testSetHostQpsLimits() throws NoSuchFieldException, IllegalAccessException {
        int count1 = new Random().nextInt();
        int count2 = new Random().nextInt();
        int count3 = new Random().nextInt();
        Map<String, Integer> limits = new HashMap<>();
        limits.put("www.easyea.com", count1);
        limits.put("http://www.edap.io/user/signIn.html", count2);
        limits.put("http://www.erpc.dev:8090/", count3);
        AsyncGroupConfig config = new AsyncGroupConfig();
        config.setHostQpsLimits(limits);
        Field hostQpsLimitsMethod = AsyncGroupConfig.class.getDeclaredField("hostQpsLimits");
        hostQpsLimitsMethod.setAccessible(true);
        Map<String, Integer> hostLimits = (Map<String, Integer>)hostQpsLimitsMethod.get(config);
        assertNotNull(hostLimits);
        assertEquals(hostLimits.size(), 3);
        assertEquals(hostLimits.get("www.easyea.com"), count1);
        assertEquals(hostLimits.get("www.edap.io"), count2);
        assertEquals(hostLimits.get("www.erpc.dev:8090"), count3);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "www.easyea.com",
            "www.easyea.com/",
            "http://www.easyea.com",
            "http://www.easyea.com/",
            "http://www.easyea.com/user/signIn.html",
            "https://www.easyea.com/user/signIn.html",
            "ws://www.easyea.com/user/signIn.html",
            "wss://www.easyea.com/user/signIn.html"
    })
    public void testSetHostConCountLimit(String url) throws NoSuchFieldException, IllegalAccessException {
        int count = new Random().nextInt();
        AsyncGroupConfig config = new AsyncGroupConfig();
        config.setHostConCountLimit(url, count);
        Field hostRateLimitsMethod = AsyncGroupConfig.class.getDeclaredField("hostConCountLimits");
        hostRateLimitsMethod.setAccessible(true);
        Map<String, Integer> limits = (Map<String, Integer>)hostRateLimitsMethod.get(config);
        assertNotNull(limits);
        assertEquals(limits.size(), 1);
        assertEquals(limits.containsKey("www.easyea.com"), true);
        assertEquals(limits.get("www.easyea.com"), count);
    }

    @Test
    public void testSetHostConCountLimits() throws NoSuchFieldException, IllegalAccessException {
        int count1 = new Random().nextInt();
        int count2 = new Random().nextInt();
        int count3 = new Random().nextInt();
        Map<String, Integer> limits = new HashMap<>();
        limits.put("www.easyea.com", count1);
        limits.put("http://www.edap.io/user/signIn.html", count2);
        limits.put("http://www.erpc.dev:8090/", count3);
        AsyncGroupConfig config = new AsyncGroupConfig();
        config.setHostConCountLimits(limits);
        Field hostConCountLimitsMethod = AsyncGroupConfig.class.getDeclaredField("hostConCountLimits");
        hostConCountLimitsMethod.setAccessible(true);
        Map<String, Integer> hostLimits = (Map<String, Integer>)hostConCountLimitsMethod.get(config);
        assertNotNull(hostLimits);
        assertEquals(hostLimits.size(), 3);
        assertEquals(hostLimits.get("www.easyea.com"), count1);
        assertEquals(hostLimits.get("www.edap.io"), count2);
        assertEquals(hostLimits.get("www.erpc.dev:8090"), count3);
    }

    @Test
    public void testTimeout() {
        AsyncGroupConfig agc = new AsyncGroupConfig();
        long timeout = agc.getTimeout();
        assertEquals(timeout, 30000);
        agc.setTimeout(60000);
        assertEquals(agc.getTimeout(), 60000);
    }

}
