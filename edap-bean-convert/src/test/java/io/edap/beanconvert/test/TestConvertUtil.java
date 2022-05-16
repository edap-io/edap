package io.edap.beanconvert.test;

import io.edap.beanconvert.AbstractConvertor;
import io.edap.beanconvert.util.ConvertUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestConvertUtil {

    public static class DemoChain {
        private String name;
        private boolean isType;

        public String name() {
            return this.name;
        }

        public DemoChain name(String name) {
            this.name = name;
            return this;
        }

        public void setType(boolean isType) {
            this.isType = isType;
        }

        public boolean isType() {
            return isType;
        }
    }

    @Test
    public void testGetConvertFields() {
        Map<String, AbstractConvertor.ConvertFieldInfo> fieldInfos = ConvertUtil.getConvertFields(DemoChain.class);
        assertNotNull(fieldInfos);
        AbstractConvertor.ConvertFieldInfo info = fieldInfos.get("name");
        assertNotNull(info);
        assertEquals(info.getMethod.getName(), "name");
        assertEquals(info.setMethod.getName(), "name");

        AbstractConvertor.ConvertFieldInfo typeInfo = fieldInfos.get("isType");
        assertNotNull(typeInfo);
        assertEquals(typeInfo.getMethod.getName(), "isType");
        assertEquals(typeInfo.setMethod.getName(), "setType");
    }
}
