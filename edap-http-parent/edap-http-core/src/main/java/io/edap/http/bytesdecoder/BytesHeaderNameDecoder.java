package io.edap.http.bytesdecoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HeaderName;
import io.edap.http.HttpRequest;
import io.edap.http.cache.HeaderNameCache;
import io.edap.util.ByteArrayBuilder;
import io.edap.util.StringUtil;

import static io.edap.http.AbstractHttpDecoder.FINISH_HEADERNAME;

public class BytesHeaderNameDecoder implements BytesTokenDecoder<HeaderName> {

	static HeaderNameCache CACHE = HeaderNameCache.instance();

	@Override
	public HeaderName decode(FastBuf buf, ByteArrayBuilder sb, HttpRequest request) {
		FastBuf _buf   = buf;
		int     remain = _buf.remain();
		if (remain <= 0) {
			return null;
		}
		long rpos = _buf.rpos();
		byte b    = _buf.get(rpos);
		if (b == '\r') {
			if (remain > 1 && _buf.get(rpos + 1) == '\n') {
				_buf.rpos(rpos+2);
				return FINISH_HEADERNAME;
			} else {
				return null;
			}
		}

		sb.setLength(0);
		for (int i=1;i<remain;i++) {
			b = _buf.get(rpos+i);
			if (b == ':') {
				byte[] data = new byte[i];
				_buf.get(rpos, data);
				_buf.rpos(rpos + i + 1);
				return CACHE.get(new String(data));
			} else if (b == ' ') {
				for (int j=i+1;j<remain;j++) {
					b = _buf.get(rpos+j);
					switch (b) {
						case ' ':
							break;
						case ':':
							_buf.rpos(rpos+j+1);
							byte[] data = new byte[i];
							_buf.get(rpos, data);
							return CACHE.get(StringUtil.fastInstance(data, (byte)0));
						default:
							int l = (int)(_buf.limit() - 0);
							byte[] bs = new byte[j];
							_buf.get(rpos, bs);
							throw new IllegalArgumentException("HeaderName: Illegal name can't have space!");
					}
				}
			}
		}
		return null;
	}
}
