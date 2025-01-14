package io.edap.eproto.test;

import io.edap.eproto.EprotoWriter;
import io.edap.protobuf.EncodeException;

import java.util.List;

import static io.edap.eproto.writer.AbstractWriter.ZIGZAG32_NEGATIVE_ONE;
import static io.edap.eproto.writer.AbstractWriter.ZIGZAG32_ZERO;

public class T {
    public static void main(String[] args) {
        byte b = '\0';
        System.out.println(Integer.toBinaryString(b));

        b = '0';
        System.out.println(Integer.toBinaryString(b));

        b = (byte)0;
        System.out.println(Integer.toBinaryString(b));
    }

    private void writeList_0(EprotoWriter var1, List<String> var2) throws EncodeException {
        if (var2 == null) {
            var1.writeByte(ZIGZAG32_NEGATIVE_ONE);
        } else if (var2.isEmpty()) {
            var1.writeByte(ZIGZAG32_ZERO);
        } else {
            int var3 = var2.size();
            var1.writeSInt32(var3);
            for(int var4 = 0; var4 < var3; var4++) {
                var1.writeString(var2.get(var4));
            }
        }
    }
}
