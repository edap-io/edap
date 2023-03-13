package io.edap.json;

/**
 * JSON序列化的接口定义
 * @param <T>
 */
public interface JsonEncoder<T> {

    void encode(JsonWriter writer, T obj);
}
