//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package io.edap.protobuf.mapencoder;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.test.message.v3.Project;
import io.edap.protobuf.wire.Field.Type;

public class MapEncoder_539ff74a030cda56cde7c7e4bfabf767 {
    @ProtoField(
            tag = 1,
            type = Type.STRING
    )
    public String key;
    @ProtoField(
            tag = 2,
            type = Type.MESSAGE
    )
    public Project value;

    public MapEncoder_539ff74a030cda56cde7c7e4bfabf767() {
    }
}