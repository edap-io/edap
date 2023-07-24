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
import io.edap.protobuf.model.ProtoBufOption;

import java.util.HashMap;

/**
 * Any类型的编解码器
 * 当protobuf协议的wireType为6即为Any类型时，由该编解码器进行编解码。Any的组成为
 * [varint] + [className] + byte[]
 */
public class AnyCodec {

    static ProtoBufDecoder[] DECODERS = new ProtoBufDecoder[256];

    static ProtoBufDecoder[] FAST_DECODERS = new ProtoBufDecoder[256];

    static HashMap<String, ProtoBufDecoder> MSG_DECODERS = new HashMap<>();
    static HashMap<String, ProtoBufEncoder> MSG_ENCODERS = new HashMap<>();

    static HashMap<String, ProtoBufEncoder> MSG_FAST_ENCODERS = new HashMap<>();

    static NullCodec       NULL_CODEC = new NullCodec();
    static ProtoBufEncoder MSG_ENCODER;

    static ProtoBufEncoder MSG_FAST_ENCODER;
    static ProtoBufDecoder MSG_DECODER;

    static ProtoBufDecoder MSG_FAST_DECODER;

    /**
     * 整数编码开始的值
     */
    public static final int RANGE_INT_START       = 0;
    public static final int RANGE_INT_END         = RANGE_INT_START + 15;

    public static final int RANGE_STRING_START    = RANGE_INT_END + 1; //16;
    public static final int RANGE_STRING_END      = RANGE_STRING_START + 64; //47;

    public static final int RANGE_HASHMAP_START   = RANGE_STRING_END + 1; //48;
    public static final int RANGE_HASHMAP_END     = RANGE_HASHMAP_START + 15;//63;

    public static final int RANGE_LONG            = RANGE_HASHMAP_END + 1; //64;
    public static final int RANGE_BOOL_FALSE      = RANGE_LONG + 1; //65;
    public static final int RANGE_BOOL_TRUE       = RANGE_BOOL_FALSE + 1; //66;
    public static final int RANGE_DOUBLE          = RANGE_BOOL_TRUE + 1; //67;
    public static final int RANGE_FLOAT           = RANGE_DOUBLE + 1; //68;
    public static final int RANGE_DATE            = RANGE_FLOAT + 1; //69;
    public static final int RANGE_LOCALDATE       = RANGE_DATE + 1; //70;
    public static final int RANGE_LOCALTIME       = RANGE_LOCALDATE + 1; //71;
    public static final int RANGE_LOCALDATETIME   = RANGE_LOCALTIME + 1; //72;
    public static final int RANGE_CALENDAR        = RANGE_LOCALDATETIME + 1; //73;
    public static final int RANGE_BIGINTEGER      = RANGE_CALENDAR + 1; //74;
    public static final int RANGE_BIGDDECIMAL     = RANGE_BIGINTEGER + 1; //75;
    public static final int RANGE_CLASS           = RANGE_BIGDDECIMAL + 1; //76;

    public static final int RANGE_ARRAYLIST_START = RANGE_CLASS + 1; //77;
    public static final int RANGE_ARRAYLIST_END   = RANGE_ARRAYLIST_START + 16; //93;

    public static final int RANGE_MESSAGE         = RANGE_ARRAYLIST_END + 1; //94;

    public static final int RANGE_ARRAY_BYTE       = RANGE_MESSAGE + 1; //95;
    public static final int RANGE_ARRAY_CHAR       = RANGE_ARRAY_BYTE + 1; //96;
    public static final int RANGE_ARRAY_INT        = RANGE_ARRAY_CHAR + 1; //97;
    public static final int RANGE_ARRAY_INTEGER    = RANGE_ARRAY_INT + 1; //98;
    public static final int RANGE_ARRAY_LONG       = RANGE_ARRAY_INTEGER + 1; //99;
    public static final int RANGE_ARRAY_LONG_OBJ   = RANGE_ARRAY_LONG + 1; //100;
    public static final int RANGE_ARRAY_FLOAT      = RANGE_ARRAY_LONG_OBJ + 1; //101;
    public static final int RANGE_ARRAY_FLOAT_OBJ  = RANGE_ARRAY_FLOAT + 1; //102;
    public static final int RANGE_ARRAY_DOUBLE     = RANGE_ARRAY_FLOAT_OBJ + 1; //103;
    public static final int RANGE_ARRAY_DOUBLE_OBJ = RANGE_ARRAY_DOUBLE + 1; //104;
    public static final int RANGE_ARRAY_STRING     = RANGE_ARRAY_DOUBLE_OBJ + 1; //105;
    public static final int RANGE_ARRAY_BOOL       = RANGE_ARRAY_STRING + 1; //106;
    public static final int RANGE_ARRAY_BOOL_OBJ   = RANGE_ARRAY_BOOL + 1; //107;
    public static final int RANGE_ARRAY_OBJECT     = RANGE_ARRAY_BOOL_OBJ + 1; //108;
    public static final int RANGE_NULL             = RANGE_ARRAY_OBJECT + 1; //109;

    public static final int RANGE_LINKED_HASHMAP   = RANGE_NULL + 1; //110;

    public static final int RANGE_MAX              = RANGE_LINKED_HASHMAP + 1;

    static {
        MessageCodec msgCodec = new MessageCodec();
        MessageFastCodec msgFastCodec = new MessageFastCodec();
        MSG_ENCODER = msgCodec;
        MSG_DECODER = msgCodec;
        MSG_FAST_ENCODER = msgFastCodec;
        MSG_FAST_DECODER = msgFastCodec;

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
        LinkedHashMapCodec  linkedHashMapCodec  = new LinkedHashMapCodec();

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
        MSG_ENCODERS.put("java.util.LinkedHashMap",     linkedHashMapCodec);

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

        MSG_FAST_ENCODERS.put("java.lang.String",            stringCodec);
        MSG_FAST_ENCODERS.put("java.lang.Integer",           integerCodec);
        MSG_FAST_ENCODERS.put("java.lang.Long",              longCodec);
        MSG_FAST_ENCODERS.put("java.lang.Boolean",           boolCodec);
        MSG_FAST_ENCODERS.put("java.lang.Double",            doubleCodec);
        MSG_FAST_ENCODERS.put("java.lang.Float",             floatCodec);
        MSG_FAST_ENCODERS.put("java.util.Date",              dateCodec);
        MSG_FAST_ENCODERS.put("java.time.LocalDate",         localDateCodec);
        MSG_FAST_ENCODERS.put("java.time.LocalTime",         localTimeCodec);
        MSG_FAST_ENCODERS.put("java.time.LocalDateTime",     localDateTimeCodec);
        MSG_FAST_ENCODERS.put("java.util.GregorianCalendar", calendarCodec);
        MSG_FAST_ENCODERS.put("java.math.BigInteger",        bigIntegerCodec);
        MSG_FAST_ENCODERS.put("java.math.BigDecimal",        bigDecimalCodec);
        MSG_FAST_ENCODERS.put("java.lang.Class",             classCodec);
        MSG_FAST_ENCODERS.put("java.util.HashMap",           hashMapCodec);
        MSG_FAST_ENCODERS.put("java.util.ArrayList",         arrayListCodec);
        MSG_FAST_ENCODERS.put("java.util.LinkedHashMap",     linkedHashMapCodec);

        MSG_FAST_ENCODERS.put("[B",                          arrayByteCodec);
        MSG_FAST_ENCODERS.put("[C",                          arrayCharCodec);
        MSG_FAST_ENCODERS.put("[I",                          arrayIntCodec);
        MSG_FAST_ENCODERS.put("[Ljava.lang.Integer;",        arrayIntegerCodec);
        MSG_FAST_ENCODERS.put("[J",                          arrayLongCodec);
        MSG_FAST_ENCODERS.put("[Ljava.lang.Long;",           arrayLongObjCodec);
        MSG_FAST_ENCODERS.put("[F",                          arrayFloatCodec);
        MSG_FAST_ENCODERS.put("[Ljava.lang.Float;",          arrayFloatObjCodec);
        MSG_FAST_ENCODERS.put("[D",                          arrayDoubleCodec);
        MSG_FAST_ENCODERS.put("[Ljava.lang.Double;",         arrayDoubleObjCodec);
        MSG_FAST_ENCODERS.put("[Ljava.lang.String;",         arrayStringCodec);
        MSG_FAST_ENCODERS.put("[Z",                          arrayBoolCodec);
        MSG_FAST_ENCODERS.put("[Ljava.lang.Boolean;",        arrayBoolObjCodec);
        MSG_FAST_ENCODERS.put("[Ljava.lang.Object;",         arrayObjectCodec);

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
        DECODERS[RANGE_LINKED_HASHMAP]   = linkedHashMapCodec;

        // int的编码范围
        for (int i=RANGE_INT_START;i<RANGE_INT_END;i++) {
            FAST_DECODERS[i]  = new IntegerCodec(i);
        }
        FAST_DECODERS[RANGE_INT_END]  = new IntegerCodec();

        // String的编码范围
        for (int i=RANGE_STRING_START;i<RANGE_STRING_END;i++) {
            FAST_DECODERS[i]  = new StringCodec(i-RANGE_STRING_START);
        }
        FAST_DECODERS[RANGE_STRING_END]  = new StringCodec();

        // HashMap的编码范围
        for (int i=RANGE_HASHMAP_START;i<RANGE_HASHMAP_END;i++) {
            FAST_DECODERS[i]  = new HashMapCodec(i-RANGE_HASHMAP_START);
        }
        FAST_DECODERS[RANGE_HASHMAP_END] = new HashMapCodec();

        FAST_DECODERS[RANGE_LONG]          = longCodec;
        FAST_DECODERS[RANGE_BOOL_FALSE]    = new BoolCodec(false);
        FAST_DECODERS[RANGE_BOOL_TRUE]     = new BoolCodec(true);
        FAST_DECODERS[RANGE_DOUBLE]        = doubleCodec;
        FAST_DECODERS[RANGE_FLOAT]         = floatCodec;
        FAST_DECODERS[RANGE_DATE]          = dateCodec;
        FAST_DECODERS[RANGE_LOCALDATE]     = localDateCodec;
        FAST_DECODERS[RANGE_LOCALTIME]     = localTimeCodec;
        FAST_DECODERS[RANGE_LOCALDATETIME] = localDateTimeCodec;
        FAST_DECODERS[RANGE_CALENDAR]      = calendarCodec;
        FAST_DECODERS[RANGE_BIGINTEGER]    = bigIntegerCodec;
        FAST_DECODERS[RANGE_BIGDDECIMAL]   = bigDecimalCodec;
        FAST_DECODERS[RANGE_CLASS]         = classCodec;
        // ArrayList的编码范围
        for (int i=RANGE_ARRAYLIST_START;i<RANGE_ARRAYLIST_END;i++) {
            FAST_DECODERS[i] = new ArrayListCodec(i-RANGE_ARRAYLIST_START);
        }
        FAST_DECODERS[RANGE_ARRAYLIST_END] = new ArrayListCodec();
        FAST_DECODERS[RANGE_MESSAGE]       = MSG_FAST_DECODER;
//
        FAST_DECODERS[RANGE_ARRAY_BYTE]       = arrayByteCodec;
        FAST_DECODERS[RANGE_ARRAY_CHAR]       = arrayCharCodec;
        FAST_DECODERS[RANGE_ARRAY_INT]        = arrayIntCodec;
        FAST_DECODERS[RANGE_ARRAY_INTEGER]    = arrayIntegerCodec;
        FAST_DECODERS[RANGE_ARRAY_LONG]       = arrayLongCodec;
        FAST_DECODERS[RANGE_ARRAY_LONG_OBJ]   = arrayLongObjCodec;
        FAST_DECODERS[RANGE_ARRAY_FLOAT]      = arrayFloatCodec;
        FAST_DECODERS[RANGE_ARRAY_FLOAT_OBJ]  = arrayFloatObjCodec;
        FAST_DECODERS[RANGE_ARRAY_DOUBLE]     = arrayDoubleCodec;
        FAST_DECODERS[RANGE_ARRAY_DOUBLE_OBJ] = arrayDoubleObjCodec;
        FAST_DECODERS[RANGE_ARRAY_STRING]     = arrayStringCodec;
        FAST_DECODERS[RANGE_ARRAY_BOOL]       = arrayBoolCodec;
        FAST_DECODERS[RANGE_ARRAY_BOOL_OBJ]   = arrayBoolObjCodec;
        FAST_DECODERS[RANGE_ARRAY_OBJECT]     = arrayObjectCodec;
        FAST_DECODERS[RANGE_NULL]             = NULL_CODEC;
        FAST_DECODERS[RANGE_LINKED_HASHMAP]   = linkedHashMapCodec;

    }

    public static void encode(ProtoBufWriter writer, Object v) throws EncodeException {
        if (null == v) {
            NULL_CODEC.encode(writer, v);
            return;
        }
        ProtoBufEncoder encoder = MSG_ENCODERS.get(v.getClass().getName());
        if (null == encoder) {
            encoder = MSG_ENCODER;
        }
        encoder.encode(writer, v);
    }

    public static void encode(ProtoBufWriter writer, Object v, ProtoBufOption option) throws EncodeException {
        if (option == null || ProtoBuf.CodecType.FAST != option.getCodecType()) {
            encode(writer, v);
            return;
        }
        if (null == v) {
            NULL_CODEC.encode(writer, v);
            return;
        }
        ProtoBufEncoder encoder = MSG_FAST_ENCODERS.get(v.getClass().getName());
        if (null == encoder) {
            encoder = MSG_FAST_ENCODER;
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

    public static Object decode(ProtoBufReader reader, ProtoBufOption option) throws ProtoBufException {
        if (option == null || ProtoBuf.CodecType.FAST != option.getCodecType()) {
            return decode(reader);
        }
        int type = reader.getByte() & 0xff;
        try {
            ProtoBufDecoder decoder = FAST_DECODERS[type];
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
