package io.edap.mqtt;

import static io.edap.mqtt.MqttConstant.TWO_BYTE_INT_MAX_VALUE;
import static io.edap.util.StringUtil.*;

public class MqttWriter {

    private int    start;
    private int    pos;
    private byte[] data;
    private int    cap;

    public MqttWriter() {
        this.data  = new byte[4096];
        this.cap   = data.length;
        this.start = 0;
        this.pos   = 0;
    }

    public MqttWriter(int cap) {
        this.data = new byte[cap];
        this.cap  = cap;
        this.start = 0;
        this.pos   = 0;
    }

    public int getLength() {
        return pos - start;
    }

    public byte[] getData() {
        return data;
    }

    public int getPos() {
        return pos;
    }

    public void setPos(int pos) {
        if (pos > data.length) {
            int len = data.length << 1;
            if (len < pos) {
                len = pos;
            }
            byte[] tmp = new byte[len];
            System.arraycopy(data, start, tmp, start, this.pos - start);
            this.pos = pos;
            this.data = tmp;
        } else {
            this.pos = pos;
        }
    }

    public void write(byte[] bs) {
        int len = bs.length;
        expand(len);
        System.arraycopy(bs, 0, data, pos, len);
        pos += len;
    }

    public void setData(byte[] data) {
        this.data = data;
        this.cap  = data.length;
    }

    public void writeByte(byte b) {
        expand(1);
        data[pos++] = b;
    }

    public void writeByte(int pos, byte b) {
        data[pos] = b;
    }

    public void writeBytes(int pos, byte b1, byte b2) {
        if (pos + 2 < cap) {
            data[pos++] = b1;
            data[pos]   = b2;
        } else {
            expand(2, pos);
            data[pos++] = b1;
            data[pos]   = b2;
        }
    }

    public void writeString(String value) {
        String _val = value;
        if (IS_BYTE_ARRAY && isLatin1(_val)) {
            byte[] bs = getValue(_val);
            int len = bs.length;
            if (len > TWO_BYTE_INT_MAX_VALUE) {
                throw new StringToLongException("String to long!");
            }
            writeBytes((byte)(len >> 8), (byte)(len & 0xFF));
            write(bs);
            return;
        }
        int len  = _val.length();
        int oldPos = pos;
        int _pos = pos + 2;
        pos = _pos;
        expand(_val.length() * 3);
        byte[] _data = data;
        for (int i=0;i < len; i++) {
            char c = _val.charAt(i);
            if (c < 128) {
                _data[_pos++] = (byte) c;
            } else if (c < 0x800) {
                _data[_pos++] = (byte) ((0xF << 6) | (c >>> 6));
                _data[_pos++] = (byte) (0x80       | (0x3F & c));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int codePoint = Character.toCodePoint((char) c, (char) _val.charAt(i + 1));
                _data[_pos++] = (byte) (0xF0 | ((codePoint >> 18) & 0x07));
                _data[_pos++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                _data[_pos++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
                _data[_pos++] = (byte) (0x80 | (codePoint & 0x3F));
                i++;
            } else {
                _data[_pos++] = (byte) ((0xF << 5) | (c >>> 12));
                _data[_pos++] = (byte) (0x80 | (0x3F & (c >>> 6)));
                _data[_pos++] = (byte) (0x80 | (0x3F & c));
            }
        }
        pos = _pos;
        len = _pos - oldPos - 2;
        if (len > TWO_BYTE_INT_MAX_VALUE) {
            throw new StringToLongException("String to long!");
        }
        writeBytes(oldPos, (byte)(len << 8), (byte)(len & 0xFF));
    }

    public int remain() {
        return cap - pos;
    }

    public void writeBytes(byte b1, byte b2) {
        expand(2);
        data[pos++] = b1;
        data[pos++] = b2;
    }

    public void writeVarInt(int i) {
        int val = i;
        if ((val & ~0x7F) == 0) {
            writeByte(pos++, (byte)(val & 0x7F));
        } else {
            byte b1 = (byte) ((val & 0x7F) | 0x80);
            val >>>= 7;
            if ((val & ~0x7F) == 0) {
                writeBytes((byte) val, b1);
            } else {
                byte b2 = (byte) ((val & 0x7F) | 0x80);
                val >>>= 7;
                if ((val & ~0x7F) == 0) {
                    writeBytes((byte) val, b2, b1);
                } else {
                    byte b3 = (byte) ((val & 0x7F) | 0x80);
                    val >>>= 7;
                    if ((val & ~0x7F) == 0) {
                        writeBytes((byte) val, b3, b2, b1);
                    } else {
                        throw new IntegerToLongException("Integer " + i + " too big");
                    }
                }
            }
        }
    }

    public void writeLength(int len) {
        int val = len;
        if ((val & ~0x7F) == 0) {
            writeByte(start--, (byte)(val & 0x7F));
        } else {
            writeByte(start--, (byte) ((val & 0x7F) | 0x80));
            val >>>= 7;
            if ((val & ~0x7F) == 0) {
                writeByte(start--, (byte) val);
            } else {
                writeByte(start--, (byte) ((val & 0x7F) | 0x80));
                val >>>= 7;
                if ((val & ~0x7F) == 0) {
                    writeByte(start--, (byte) val);
                } else {
                    writeByte(start--, (byte) ((val & 0x7F) | 0x80));
                    val >>>= 7;
                    if ((val & ~0x7F) == 0) {
                        writeByte(start--, (byte) val);
                    } else {
                        throw new IntegerToLongException("Integer " + len + " too big");
                    }
                }
            }
        }
    }

    public void writeBytes(byte b1, byte b2, byte b3) {
        expand(3);
        int _pos = pos;
        byte[] _data = data;
        _data[_pos++] = b1;
        _data[_pos++] = b2;
        _data[_pos++] = b3;
        pos = _pos;
    }

    public void writeBytes(byte b1, byte b2, byte b3, byte b4) {
        expand(4);
        int _pos = pos;
        byte[] _data = data;
        _data[_pos++] = b1;
        _data[_pos++] = b2;
        _data[_pos++] = b3;
        _data[_pos++] = b4;
        pos = _pos;
    }

    public void writeBytes(byte b1, byte b2, byte b3, byte b4, byte b5) {
        expand(5);
        int _pos = pos;
        byte[] _data = data;
        _data[_pos++] = b1;
        _data[_pos++] = b2;
        _data[_pos++] = b3;
        _data[_pos++] = b4;
        _data[_pos++] = b5;
        pos = _pos;
    }


    public void expand(int minLength) {
        expand(minLength, pos);
    }

    public void expand(int minLength, int pos) {
        if (cap - pos >= minLength) {
            return;
        }
        int len = cap * 2;
        if (len < minLength + pos) {
            len = minLength + pos;
        }
        byte[] newData = new byte[len];
        System.arraycopy(data, start, newData, start, pos - start);
        setData(newData);
    }

    public void reset() {
        byte[] _data = data;
        for (int i=0;i<pos;i++) {
            _data[i] = 0;
        }
        this.pos = 0;
        this.start  = 0;
    }

    public int getStart() {
        return start;
    }

    public void setStart(int start) {
        if (this.start == start) {
            return;
        }
        if (start < 0) {
            return;
        }
        if (start > this.start) {
            if (start > pos) {
                this.start = start;
                this.pos   = start;
            } else {
                this.start = start;
            }
        } else {
            this.start = start;
        }
    }
}
