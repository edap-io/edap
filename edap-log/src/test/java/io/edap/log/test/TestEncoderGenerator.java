package io.edap.log.test;

import io.edap.log.helps.EncoderGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestEncoderGenerator {

    @Test
    public void testStringToInternal() {
        String s = "中文";
        String internal = new EncoderGenerator("").stringToInternal(s);
        assertEquals(internal, "\\u4e2d\\u6587");
    }
}
