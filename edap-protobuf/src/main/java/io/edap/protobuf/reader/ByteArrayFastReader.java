package io.edap.protobuf.reader;

import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoBufException;

public class ByteArrayFastReader extends ByteArrayReader {
    public ByteArrayFastReader(byte[] buf) {
        super(buf);
    }

    public ByteArrayFastReader(byte [] buf, int offset, int len) {
        super(buf, offset, len);
    }

    public <T extends Object> T readMessage(ProtoBufDecoder<T> decoder, int endTag)
            throws ProtoBufException {
        return decoder.decode(this, endTag);
    }
}
