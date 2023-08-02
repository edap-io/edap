package io.edap.json;

import io.edap.json.enums.DataType;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public class AbstractDecoder {

    public <T> List<T> readList(JsonReader reader, Class<T> pojo)
            throws InvocationTargetException, InstantiationException, IllegalAccessException {
        JsonDecoder<T> decoder = JsonCodecRegister.instance().getDecoder(pojo, DataType.STRING);
        char c = reader.firstNotSpaceChar();
        if (c != '[') {
            throw new JsonParseException("不是数组类型数据");
        }
        reader.nextPos(1);
        c = reader.firstNotSpaceChar();
        List<T> list = new ArrayList<>();
        while (c != ']') {
            if (c == ',') {
                reader.nextPos(1);
            }
            list.add(decoder.decode(reader));
            c = reader.firstNotSpaceChar();
        }
        reader.nextPos(1);
        return list;
    }
}
