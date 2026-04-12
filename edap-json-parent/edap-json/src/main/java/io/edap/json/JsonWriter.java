package io.edap.json;

import io.edap.io.BufWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.Map;

/**
 * @author : luysh@yonyou.com
 * @date : 2020/11/5
 */
public interface JsonWriter extends BufWriter {

    void write(boolean bool);
    void write(Boolean bool);

    void write(byte b);
    void write(byte b1, byte b2);
    void write(byte b1, byte b2, byte b3);
    void write(int i);
    void write(Integer i);
    void write(long l);
    void write(Long l);
    void write(float f);
    void write(Float f);
    void write(double d);
    void write(Double d);
    void write(byte b, int i);
    void write(byte b, Integer i);
    void write(byte b, long l);
    void write(byte b, Long l);
    void writeNull();

    void write(String s);
    void write(byte[] bs, int offset, int length);

    void write(BigDecimal bigDecimal);

    void writeObject(Object obj);

    void write(Object obj, JsonEncoder encoder);

    <K, V> void write(Map<K, V> map, MapEncoder<K, V> mapEncoder);

    void writeField(byte[] bs, int offset, int end);

    void toStream(OutputStream stream) throws IOException;
    void reset();
}
