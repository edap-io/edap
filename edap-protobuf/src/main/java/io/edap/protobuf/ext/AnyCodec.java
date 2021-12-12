/*
 * Copyright 2020 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.ext;

import io.edap.protobuf.*;
import io.edap.protobuf.ext.codec.*;

import java.util.HashMap;

/**
 * Any类型的编解码器
 * 当protobuf协议的wireType为6即为Any类型时，由该编解码器进行编解码。Any的组成为
 * [varint] + [className] + byte[]
 */
public class AnyCodec {

    static ProtoBufDecoder[] DECODERS = new ProtoBufDecoder[256];
    static HashMap<String, ProtoBufDecoder> MSG_DECODERS = new HashMap<>();
    static HashMap<String, ProtoBufEncoder> MSG_ENCODERS = new HashMap<>();
    static NullCodec       NULL_CODEC = new NullCodec();
    static ProtoBufEncoder MSG_ENCODER;
    static ProtoBufDecoder MSG_DECODER;

    /**
     * 整数编码开始的值
     */
    public static final int RANGE_INT_START       = 0;
    public static final int RANGE_INT_END         = 15;

    public static final int RANGE_STRING_START    = 16;
    public static final int RANGE_STRING_END      = 47;

    public static final int RANGE_HASHMAP_START   = 48;
    public static final int RANGE_HASHMAP_END     = 63;

    public static final int RANGE_LONG            = 64;
    public static final int RANGE_BOOL_FALSE      = 65;
    public static final int RANGE_BOOL_TRUE       = 66;
    public static final int RANGE_DOUBLE          = 67;
    public static final int RANGE_FLOAT           = 68;
    public static final int RANGE_DATE            = 69;
    public static final int RANGE_LOCALDATE       = 70;
    public static final int RANGE_LOCALTIME       = 71;
    public static final int RANGE_LOCALDATETIME   = 72;
    public static final int RANGE_CALENDAR        = 73;
    public static final int RANGE_BIGINTEGER      = 74;
    public static final int RANGE_BIGDDECIMAL     = 75;
    public static final int RANGE_CLASS           = 76;

    public static final int RANGE_ARRAYLIST_START = 77;
    public static final int RANGE_ARRAYLIST_END   = 93;

    public static final int RANGE_MESSAGE         = 94;

    public static final int RANGE_ARRAY_BYTE       = 95;
    public static final int RANGE_ARRAY_CHAR       = 96;
    public static final int RANGE_ARRAY_INT        = 97;
    public static final int RANGE_ARRAY_INTEGER    = 98;
    public static final int RANGE_ARRAY_LONG       = 99;
    public static final int RANGE_ARRAY_LONG_OBJ   = 100;
    public static final int RANGE_ARRAY_FLOAT      = 101;
    public static final int RANGE_ARRAY_FLOAT_OBJ  = 102;
    public static final int RANGE_ARRAY_DOUBLE     = 103;
    public static final int RANGE_ARRAY_DOUBLE_OBJ = 104;
    public static final int RANGE_ARRAY_STRING     = 105;
    public static final int RANGE_ARRAY_BOOL       = 106;
    public static final int RANGE_ARRAY_BOOL_OBJ   = 107;
    public static final int RANGE_ARRAY_OBJECT     = 108;
    public static final int RANGE_NULL             = 109;


    static {
        MessageCodec msgCodec = new MessageCodec();
        MSG_ENCODER = msgCodec;
        MSG_DECODER = msgCodec;

        StringCodec         stringCodec         = new StringCodec();
        IntegerCodec        integerCodec        = new IntegerCodec();
        LongCodec           longCodec           = new LongCodec();
        BoolCodec           boolCodec           = new BoolCodec();
        DoubleCodec         doubleCodec         = new DoubleCodec();
        FloatCodec          floatCodec          = new FloatCodec();
        DateCodec           dateCodec           = new DateCodec();
        LocalDateCodec      localDateCodec      = new LocalDateCodec();
        LocalTimeCodec      localTimeCodec      = new LocalTimeCodec();
        LocalDateTimeCodec  localDateTimeCodec  = new LocalDateTimeCodec();
        CalendarCodec       calendarCodec       = new CalendarCodec();
        BigIntegerCodec     bigIntegerCodec     = new BigIntegerCodec();
        BigDecimalCodec     bigDecimalCodec     = new BigDecimalCodec();
        ClassCodec          classCodec          = new ClassCodec();

        ArrayByteCodec      arrayByteCodec      = new ArrayByteCodec();
        ArrayCharCodec      arrayCharCodec      = new ArrayCharCodec();
        ArrayIntCodec       arrayIntCodec       = new ArrayIntCodec();
        ArrayIntegerCodec   arrayIntegerCodec   = new ArrayIntegerCodec();
        ArrayLongCodec      arrayLongCodec      = new ArrayLongCodec();
        ArrayLongObjCodec   arrayLongObjCodec   = new ArrayLongObjCodec();
        ArrayFloatCodec     arrayFloatCodec     = new ArrayFloatCodec();
        ArrayFloatObjCodec  arrayFloatObjCodec  = new ArrayFloatObjCodec();
        ArrayDoubleCodec    arrayDoubleCodec    = new ArrayDoubleCodec();
        ArrayDoubleObjCodec arrayDoubleObjCodec = new ArrayDoubleObjCodec();
        ArrayStringCodec    arrayStringCodec    = new ArrayStringCodec();
        ArrayBoolCodec      arrayBoolCodec      = new ArrayBoolCodec();
        ArrayBoolObjCodec   arrayBoolObjCodec   = new ArrayBoolObjCodec();
        HashMapCodec        hashMapCodec        = new HashMapCodec();
        ArrayListCodec      arrayListCodec      = new ArrayListCodec();
        ArrayObjectCodec    arrayObjectCodec    = new ArrayObjectCodec();

        MSG_ENCODERS.put("java.lang.String",            stringCodec);
        MSG_ENCODERS.put("java.lang.Integer",           integerCodec);
        MSG_ENCODERS.put("java.lang.Long",              longCodec);
        MSG_ENCODERS.put("java.lang.Boolean",           boolCodec);
        MSG_ENCODERS.put("java.lang.Double",            doubleCodec);
        MSG_ENCODERS.put("java.lang.Float",             floatCodec);
        MSG_ENCODERS.put("java.util.Date",              dateCodec);
        MSG_ENCODERS.put("java.time.LocalDate",         localDateCodec);
        MSG_ENCODERS.put("java.time.LocalTime",         localTimeCodec);
        MSG_ENCODERS.put("java.time.LocalDateTime",     localDateTimeCodec);
        MSG_ENCODERS.put("java.util.GregorianCalendar", calendarCodec);
        MSG_ENCODERS.put("java.math.BigInteger",        bigIntegerCodec);
        MSG_ENCODERS.put("java.math.BigDecimal",        bigDecimalCodec);
        MSG_ENCODERS.put("java.lang.Class",             classCodec);
        MSG_ENCODERS.put("java.util.HashMap",           hashMapCodec);
        MSG_ENCODERS.put("java.util.ArrayList",         arrayListCodec);

        MSG_ENCODERS.put("[B",                          arrayByteCodec);
        MSG_ENCODERS.put("[C",                          arrayCharCodec);
        MSG_ENCODERS.put("[I",                          arrayIntCodec);
        MSG_ENCODERS.put("[Ljava.lang.Integer;",        arrayIntegerCodec);
        MSG_ENCODERS.put("[J",                          arrayLongCodec);
        MSG_ENCODERS.put("[Ljava.lang.Long;",           arrayLongObjCodec);
        MSG_ENCODERS.put("[F",                          arrayFloatCodec);
        MSG_ENCODERS.put("[Ljava.lang.Float;",          arrayFloatObjCodec);
        MSG_ENCODERS.put("[D",                          arrayDoubleCodec);
        MSG_ENCODERS.put("[Ljava.lang.Double;",         arrayDoubleObjCodec);
        MSG_ENCODERS.put("[Ljava.lang.String;",         arrayStringCodec);
        MSG_ENCODERS.put("[Z",                          arrayBoolCodec);
        MSG_ENCODERS.put("[Ljava.lang.Boolean;",        arrayBoolObjCodec);
        MSG_ENCODERS.put("[Ljava.lang.Object;",         arrayObjectCodec);

        // int的编码范围
        for (int i=RANGE_INT_START;i<RANGE_INT_END;i++) {
            DECODERS[i]  = new IntegerCodec(i);
        }
        DECODERS[RANGE_INT_END]  = new IntegerCodec();

        // String的编码范围
        for (int i=RANGE_STRING_START;i<RANGE_STRING_END;i++) {
            DECODERS[i]  = new StringCodec(i-RANGE_STRING_START);
        }
        DECODERS[RANGE_STRING_END]  = new StringCodec();

        // HashMap的编码范围
        for (int i=RANGE_HASHMAP_START;i<RANGE_HASHMAP_END;i++) {
            DECODERS[i]  = new HashMapCodec(i-RANGE_HASHMAP_START);
        }
        DECODERS[RANGE_HASHMAP_END] = new HashMapCodec();

        DECODERS[RANGE_LONG]          = longCodec;
        DECODERS[RANGE_BOOL_FALSE]    = new BoolCodec(false);
        DECODERS[RANGE_BOOL_TRUE]     = new BoolCodec(true);
        DECODERS[RANGE_DOUBLE]        = doubleCodec;
        DECODERS[RANGE_FLOAT]         = floatCodec;
        DECODERS[RANGE_DATE]          = dateCodec;
        DECODERS[RANGE_LOCALDATE]     = localDateCodec;
        DECODERS[RANGE_LOCALTIME]     = localTimeCodec;
        DECODERS[RANGE_LOCALDATETIME] = localDateTimeCodec;
        DECODERS[RANGE_CALENDAR]      = calendarCodec;
        DECODERS[RANGE_BIGINTEGER]    = bigIntegerCodec;
        DECODERS[RANGE_BIGDDECIMAL]   = bigDecimalCodec;
        DECODERS[RANGE_CLASS]         = classCodec;
        // ArrayList的编码范围
        for (int i=RANGE_ARRAYLIST_START;i<RANGE_ARRAYLIST_END;i++) {
            DECODERS[i] = new ArrayListCodec(i-RANGE_ARRAYLIST_START);
        }
        DECODERS[RANGE_ARRAYLIST_END] = new ArrayListCodec();
        DECODERS[RANGE_MESSAGE]       = MSG_DECODER;
//
        DECODERS[RANGE_ARRAY_BYTE]       = arrayByteCodec;
        DECODERS[RANGE_ARRAY_CHAR]       = arrayCharCodec;
        DECODERS[RANGE_ARRAY_INT]        = arrayIntCodec;
        DECODERS[RANGE_ARRAY_INTEGER]    = arrayIntegerCodec;
        DECODERS[RANGE_ARRAY_LONG]       = arrayLongCodec;
        DECODERS[RANGE_ARRAY_LONG_OBJ]   = arrayLongObjCodec;
        DECODERS[RANGE_ARRAY_FLOAT]      = arrayFloatCodec;
        DECODERS[RANGE_ARRAY_FLOAT_OBJ]  = arrayFloatObjCodec;
        DECODERS[RANGE_ARRAY_DOUBLE]     = arrayDoubleCodec;
        DECODERS[RANGE_ARRAY_DOUBLE_OBJ] = arrayDoubleObjCodec;
        DECODERS[RANGE_ARRAY_STRING]     = arrayStringCodec;
        DECODERS[RANGE_ARRAY_BOOL]       = arrayBoolCodec;
        DECODERS[RANGE_ARRAY_BOOL_OBJ]   = arrayBoolObjCodec;
        DECODERS[RANGE_ARRAY_OBJECT]     = arrayObjectCodec;
        DECODERS[RANGE_NULL]             = NULL_CODEC;

    }

    public static void encode(ProtoBufWriter writer, Object v) throws EncodeException {
        if (null == v) {
            NULL_CODEC.encode(writer, v);
            return;
        }
        String encoderKey;
        if (v instanceof HashMap) {
            encoderKey = "java.util.HashMap";
        } else {
            encoderKey = v.getClass().getName();
        }
        ProtoBufEncoder encoder = MSG_ENCODERS.get(encoderKey);
        if (null == encoder) {
            encoder = MSG_ENCODER;
        }
        encoder.encode(writer, v);
    }

    public static Object decode(ProtoBufReader reader) throws ProtoBufException {
        int type = reader.getByte() & 0xff;
        try {
            ProtoBufDecoder decoder = DECODERS[type];
            if (decoder != null) {
                return decoder.decode(reader);
            } else {
                throw new ProtoBufException("type[" + type + "] hasn't ProtoBufDecoder");
            }
        } catch (Exception e) {
            throw new ProtoBufException(e);
        }
    }
}
