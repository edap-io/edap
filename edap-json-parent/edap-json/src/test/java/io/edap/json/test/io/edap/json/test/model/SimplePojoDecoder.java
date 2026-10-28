package io.edap.json.test.io.edap.json.test.model;

import io.edap.json.JsonDecoder;
import io.edap.json.JsonReader;
import io.edap.json.test.model.DemoPojo;
import io.edap.json.test.model.SimplePojo;

import java.lang.reflect.InvocationTargetException;
import java.util.Base64;

public class SimplePojoDecoder implements JsonDecoder<SimplePojo> {

    @Override
    public SimplePojo decode(JsonReader jsonReader) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        SimplePojo pojo = new SimplePojo();
        pojo.setData(Base64.getDecoder().decode(jsonReader.readString()));
        return pojo;
    }
}
