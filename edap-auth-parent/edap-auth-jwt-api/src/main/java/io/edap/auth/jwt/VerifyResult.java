package io.edap.auth.jwt;

/**
 * JWT 验签结果 DTO。
 *
 * <p><b>失败码语义</b>：
 * <ul>
 *   <li>0 — 签名验证通过，payload 已填充</li>
 *   <li>1 — format error（token 非 {@code header.payload.signature} 三段 / base64url 解码失败 / JSON 解析失败）</li>
 *   <li>2 — signature error（签名不匹配 / 算法不支持 / key 错）</li>
 * </ul>
 */
public class VerifyResult {

    private int code;
    private String message;
    private JwtPayload payload;
    private Header header;

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public JwtPayload getPayload() {
        return payload;
    }

    public void setPayload(JwtPayload payload) {
        this.payload = payload;
    }

    public Header getHeader() {
        return header;
    }

    public void setHeader(Header header) {
        this.header = header;
    }
}
