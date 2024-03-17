package io.edap.protobuf;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.edap.protobuf.annotation.ProtoField;

public class ProtoFieldInfo {
        public Field field;
        /**
         * 是否有Get方法或者Field是public，可以直接获取Field的值
         */
        public boolean hasGetAccessed;
        /**
         * 是否有Set方法或者Field是public，可以直接给Field赋值
         */
        public boolean hasSetAccessed;
        public Method getMethod;
        public Method setMethod;
        public ProtoField protoField;
    }