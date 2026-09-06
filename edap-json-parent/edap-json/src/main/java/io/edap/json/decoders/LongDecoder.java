package io.edap.json.decoders;

import io.edap.json.JsonDecoder;
import io.edap.json.JsonReader;

import java.lang.reflect.InvocationTargetException;

public class LongDecoder implements JsonDecoder<Long> {
    @Override
    public Long decode(JsonReader jsonReader) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        return jsonReader.readLong();
    }
}
