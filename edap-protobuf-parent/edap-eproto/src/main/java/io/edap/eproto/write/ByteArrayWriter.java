package io.edap.eproto.write;

import io.edap.buffer.FastBuf;
import io.edap.io.BufOut;

import java.io.IOException;
import java.io.OutputStream;

public class ByteArrayWriter extends AbstractWriter {
    public ByteArrayWriter(BufOut out) {
        super(out);
    }


}
