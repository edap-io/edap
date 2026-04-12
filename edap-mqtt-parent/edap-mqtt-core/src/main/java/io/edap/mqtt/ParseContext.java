package io.edap.mqtt;

import io.edap.nio.ParseResult;

public class ParseContext {
    private byte[]                     parseData;
    private ParseResult<ControlPacket> result;
    private MqttNioSession             session;
    private long                       rpos;

    public byte[] getParseData() {
        return parseData;
    }

    public void setParseData(byte[] parseData) {
        this.parseData = parseData;
    }

    public ParseResult<ControlPacket> getResult() {
        return result;
    }

    public void setResult(ParseResult<ControlPacket> result) {
        this.result = result;
    }

    public MqttNioSession getSession() {
        return session;
    }

    public void setSession(MqttNioSession session) {
        this.session = session;
    }

    public long getRpos() {
        return rpos;
    }

    public void setRpos(long rpos) {
        this.rpos = rpos;
    }
}
