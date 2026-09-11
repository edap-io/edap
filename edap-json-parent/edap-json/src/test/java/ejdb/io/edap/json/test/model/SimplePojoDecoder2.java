//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package ejdb.io.edap.json.test.model;

import io.edap.json.AbstractDecoder;
import io.edap.json.JsonDecoder;
import io.edap.json.JsonParseException;
import io.edap.json.JsonReader;
import io.edap.json.test.model.SimplePojo;
import java.lang.reflect.InvocationTargetException;

public class SimplePojoDecoder2 extends AbstractDecoder implements JsonDecoder<SimplePojo> {
    public SimplePojoDecoder2() {
    }

    public SimplePojo decode(JsonReader var1) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        char var2 = var1.firstNotSpaceChar();
        if (var2 != '{') {
            return null;
        } else {
            var1.nextPos(1);
            var2 = var1.firstNotSpaceChar();
            SimplePojo var3 = new SimplePojo();
            if (var2 == '}') {
                return var3;
            } else {
                int var4 = var1.keyHash();
                switch (var4) {
                    case -1925595674:
                        var3.setName(var1.readString());
                        break;
                    case 926444256:
                        var3.setId(var1.readLong());
                        break;
                    default:
                        var1.skipValue();
                }

                for(var2 = var1.firstNotSpaceChar(); var2 == ','; var2 = var1.firstNotSpaceChar()) {
                    var1.nextPos(1);
                    var4 = var1.keyHash();
                    switch (var4) {
                        case -1925595674:
                            var3.setName(var1.readString());
                            break;
                        case 926444256:
                            var3.setId(var1.readLong());
                            break;
                        default:
                            var1.skipValue();
                    }
                }

                if (var2 != '}') {
                    throw new JsonParseException("key and value 后为不符合json字符[" + var2 + "]");
                } else {
                    var1.nextPos(1);
                    return var3;
                }
            }
        }
    }
}
