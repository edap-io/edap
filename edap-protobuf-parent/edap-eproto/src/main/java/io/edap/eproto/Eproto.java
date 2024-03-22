package io.edap.eproto;

import io.edap.eproto.write.ByteArrayWriter;
import io.edap.io.BufOut;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.internal.ProtoBufOut;

public class Eproto {

    /**
     * 本地线程的ProtoBuf的Writer减少内存分配次数
     */
    public static final ThreadLocal<EprotoWriter> THREAD_WRITER;

    static {
        THREAD_WRITER = ThreadLocal.withInitial(() -> {
            BufOut out    = new ProtoBufOut();
            EprotoWriter writer = new ByteArrayWriter(out);
            return writer;
        });

    }


    /**
     * 将java对象序列化为Protobuf的字节数组
     * @param obj 需要序列化的java对象
     * @return 返回序列化后的字节数组，如果对象为null则返回null
     */
    public static byte [] toByteArray(Object obj) {
        if (obj == null) {
            return null;
        }
        EprotoEncoder codec = EprotoCodecRegister.instance().getEncoder(obj.getClass());
        EprotoWriter writer = THREAD_WRITER.get();
        writer.reset();
        BufOut out = writer.getBufOut();
        out.reset();
        byte[] bs;
        try {
            codec.encode(writer, obj);
            int len = writer.size();
            bs = new byte[len];
            System.arraycopy(out.getWriteBuf().bs, 0, bs, 0, len);
            return bs;
        } catch (EncodeException e) {
            //throw e;
        } finally {
            //THREAD_WRITER.set(writer);
        }
        return null;
    }
}
