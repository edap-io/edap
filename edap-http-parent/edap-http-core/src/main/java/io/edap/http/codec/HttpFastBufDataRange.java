package io.edap.http.codec;

import io.edap.buffer.FastBuf;
import io.edap.nio.codec.FastBufDataRange;
import io.edap.util.ByteArrayBuilder;
import io.edap.util.StringUtil;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static io.edap.http.HttpConsts.BYTE_VALUES;
import static io.edap.util.Constants.*;

public class HttpFastBufDataRange extends FastBufDataRange {

    private boolean urlEncoded;

    private int urlEncoderLen;

    private String value;

    public HttpFastBufDataRange() {

    }

    public HttpFastBufDataRange urlEncoded(boolean urlEncoded) {
        this.urlEncoded = urlEncoded;

        return this;
    }

    public static HttpFastBufDataRange from(String v) {
        if (StringUtil.isEmpty(v)) {
            return null;
        }
        return fromBytes(v.getBytes(DEFAULT_CHARSET));
    }

    /**
     * 用<b>原始字节</b>构造 —— 与 {@link #from(String)} 的区别在于不做字符集往返。
     *
     * <p>路径解码必须走这个入口：请求侧的 hash 是对<b>解码后</b>的字节算的，
     * 如果先解码成 String 再用 {@link #from(String)} 编码回字节，遇到非法 UTF-8
     * 序列（{@code %FF}）字节会变，hash 就对不上了。</p>
     *
     * <p>（不叫 {@code from(byte[])} 是为了不与 {@code from(String)} 在
     * {@code from(null)} 这种调用上产生歧义。）</p>
     */
    public static HttpFastBufDataRange fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        HttpFastBufDataRange dr = new HttpFastBufDataRange();
        long hashCode = FNV_1a_INIT_VAL;
        FastBuf buf = new FastBuf(bytes.length);
        // 必须走 FastBuf.write：它会推进 writePos。直接用 byteBuffer().put 绕过了 FastBuf 的
        // 写位置记账，get(pos,bs) 里的 "pos + len > writePos" 钳位会把读取长度截成 0，
        // 导致 getString() 返回空串。
        buf.write(bytes, 0, bytes.length);
        dr.start(buf.address());
        dr.first(bytes[0]);
        dr.last(bytes[bytes.length-1]);
        dr.buffer(buf);
        for (byte b : bytes) {
            hashCode ^= b;
            hashCode *= FNV_1a_FACTOR_VAL;
        }
        dr.length(bytes.length);
        dr.hash(hashCode);
        return dr;
    }

    /**
     * 按 {@code io.edap.http.server.rangedecoder.PathDecoder} 的规则做<b>字节级</b>路径解码：
     * {@code '+'} → 空格，{@code %XX} → 单个字节。
     *
     * <p><b>为什么必须有它</b>：精确路径 cache 的 key 是用 hash 索引的，
     * 请求侧（{@code PathDecoder.decode}）对<b>解码后</b>的字节算 FNV hash，
     * 而注册侧若直接用原始路径字节算 hash，两者永远对不上 ——
     * 注册 {@code /a%20b} 时 hash 的是 {@code /a%20b}，请求 {@code /a%20b} 时 hash 的是
     * {@code /a b} ⇒ 精确 cache 永远 miss，只能退回通配符路由（性能差且语义不一致）。
     * 注册时额外注册一份「解码后」的 key 即可命中。</p>
     *
     * <p><b>与 PathDecoder 严格一致</b>：十六进制只认<b>大写</b>（{@code BYTE_VALUES} 只填了
     * {@code 0-9A-F}），小写 hex 与其它字符一样视为非法 escape。返回 null 表示
     * 「无法解码」（escape 不完整或非法）—— 调用方应跳过解码后的别名注册，
     * 让该路径保持与改造前完全一致的行为（请求时由 PathDecoder 抛异常）。</p>
     *
     * @return 解码后的字节；入参为空或 escape 非法时返回 null
     */
    public static byte[] decodePathBytes(String path) {
        if (StringUtil.isEmpty(path)) {
            return null;
        }
        byte[] raw = path.getBytes(DEFAULT_CHARSET);
        boolean encoded = false;
        for (byte b : raw) {
            if (b == '+' || b == '%') {
                encoded = true;
                break;
            }
        }
        if (!encoded) {
            return raw;
        }
        byte[] out = new byte[raw.length];
        int c = 0;
        for (int i = 0; i < raw.length; i++) {
            byte b = raw[i];
            if (b == '+') {
                out[c++] = (byte) ' ';
            } else if (b == '%') {
                if (i + 2 >= raw.length) {
                    // escape 不完整 —— 与 PathDecoder 的 "i < remain - 2" 同义
                    return null;
                }
                int hi = raw[i + 1] & 0xFF;
                int lo = raw[i + 2] & 0xFF;
                // BYTE_VALUES 只覆盖 '0'-'9' 与 'A'-'F'，越界即非法 hex
                if (hi >= BYTE_VALUES.length || lo >= BYTE_VALUES.length) {
                    return null;
                }
                int v = (BYTE_VALUES[hi] << 4) + BYTE_VALUES[lo];
                if (v < 0) {
                    return null;
                }
                out[c++] = (byte) v;
                i += 2;
            } else {
                out[c++] = b;
            }
        }
        return c == out.length ? out : java.util.Arrays.copyOf(out, c);
    }

    public boolean urlEncoded() {
        return urlEncoded;
    }

    @Override
    public String getString(Charset charset) {
        if (value != null) {
            return value;
        }
        boolean encode = urlEncoded;
        if (encode) {
            int l = urlEncoderLen;
            FastBuf _buf = buf;
            byte[] data = new byte[l];
            int c = 0;
            long _pos = start;
            byte b;
            for (int i = 0; i < l; i++) {
                b = _buf.get(_pos + i);
                if (b == (byte) '+') {
                    data[c++] = ' ';
                } else if (b == (byte) '%') {
                    int v = (BYTE_VALUES[_buf.get(_pos + i + 1)] << 4) + BYTE_VALUES[_buf.get(_pos + i + 2)];
                    data[c++] = (byte) v;
                    i += 2;
                } else {
                    data[c++] = b;
                }
            }
            value = new String(data, 0, c, charset);
            return value;
        } else {
            value = super.getString(charset);
            return value;
            //return "";
        }
    }

    @Override
    public void reset() {
        //boolean _urlEncoded = false;
        urlEncoded = false;
        value = null;
        super.reset();
    }

    public int getUrlEncoderLen() {
        return urlEncoderLen;
    }

    public void setUrlEncoderLen(int urlEncoderLen) {
        this.urlEncoderLen = urlEncoderLen;
    }
}
