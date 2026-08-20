package io.edap.auth.jwt;

import io.edap.auth.jwt.utils.Base64URL;
import io.edap.json.Eson;
import io.edap.json.JsonObject;
import io.edap.json.JsonParseException;
import io.edap.util.StringUtil;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class JWT {

    private static String DEFAULT_HEADER_STR = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";

    private static final Header DEFAULT_HEADER;

    static final KeyCache ALGORITHM_CACHE = new KeyCache();

    static {
        Header header = new Header() {
            @Override
            public String getAlgorithm() {
                return "HS256";
            }

            @Override
            public String getType() {
                return "JWT";
            }

            @Override
            public String getKeyId() {
                return "";
            }
        };
        DEFAULT_HEADER = header;
    }

    public static JwtBuilder create() {
        return new DefaultJwtBuilder();
    }

    public static VerifyResult verify(String token, String signKey) {
        VerifyResult result = new VerifyResult();

        // 1. token 结构：必须 header.payload.signature 三段
        int index = token.indexOf(".");
        if (index == -1) {
            result.setCode(1);
            result.setMessage("token格式错误");
            return result;
        }
        int index2 = token.indexOf(".", index + 1);
        if (index2 == -1) {
            result.setCode(1);
            result.setMessage("token格式错误");
            return result;
        }
        String payload = token.substring(index + 1, index2);
        if (StringUtil.isEmpty(payload)) {
            result.setCode(1);
            result.setMessage("token格式错误");
            return result;
        }

        // 2. 解析 header → 拿到 alg（先于签名检查：必须先识别 alg=none 才能拒绝）
        Header header = parseHeader(token.substring(0, index), result);
        if (header == null) {
            // parseHeader 已填 code=1 + message
            return result;
        }
        result.setHeader(header);

        String algorithm = header.getAlgorithm();
        if (StringUtil.isEmpty(algorithm)) {
            result.setCode(1);
            result.setMessage("token格式错误：缺少 alg");
            return result;
        }

        // 3. AlgorithmRegistry 派发（修复原 line 84 硬编码 HS256；拒绝 none；未知算法返回 null）
        Function<String, Algorithm> factory;
        try {
            factory = AlgorithmRegistry.getFactory(algorithm);
        } catch (SecurityException e) {
            result.setCode(2);
            result.setMessage("不支持的算法: " + algorithm);
            return result;
        }
        if (factory == null) {
            result.setCode(2);
            result.setMessage("不支持的算法: " + algorithm);
            return result;
        }

        // 4. 签名段非空检查（移到 alg 检查之后：alg=none 时签名段允许为空）
        String sign = token.substring(index2 + 1);
        if (StringUtil.isEmpty(sign)) {
            result.setCode(1);
            result.setMessage("token格式错误：缺少 signature");
            return result;
        }

        // 5. KeyCache 缓存 Algorithm 实例（按 alg+key 复合维度）
        Algorithm alg = ALGORITHM_CACHE.getOrCreate(algorithm, signKey, factory);

        // 6. 验签
        byte[] signBytes = alg.sign(token.substring(0, index2).getBytes(StandardCharsets.UTF_8), 0, index2);
        String calSign = Base64URL.encodeToString(signBytes);
        if (!calSign.equals(sign)) {
            result.setCode(2);
            result.setMessage("签名错误");
            return result;
        }

        // 7. 签名通过后再解析 payload（避免无效 token 浪费 JSON 解析）
        parsePayload(payload, result);
        result.setCode(0);
        result.setMessage("success");
        return result;
    }

    /**
     * 解析 JWT header 段：默认 header 直接复用 DEFAULT_HEADER 常量；
     * 其他 header 经 base64url → JSON → JwtHeader。
     *
     * <p>失败时填 code=1 + message 并返回 null（调用方应直接 return result）。</p>
     */
    private static Header parseHeader(String headerB64, VerifyResult result) {
        if (DEFAULT_HEADER_STR.equals(headerB64)) {
            return DEFAULT_HEADER;
        }
        // 先校验 base64url 字符集（Base64URL.decode 对非法字符会静默返回乱码，
        // 必须前置拦截，否则会落到 JSON 解析失败分支，错误信息不准）
        if (!isValidBase64Url(headerB64)) {
            result.setCode(1);
            result.setMessage("header base64url 解码失败");
            return null;
        }
        byte[] headerJson;
        try {
            headerJson = Base64URL.decode(headerB64.getBytes());
        } catch (RuntimeException e) {
            result.setCode(1);
            result.setMessage("header base64url 解码失败");
            return null;
        }
        JsonObject obj;
        try {
            obj = Eson.parseJsonObject(headerJson);
        } catch (JsonParseException e) {
            result.setCode(1);
            result.setMessage("header JSON 解析失败");
            return null;
        }
        JwtHeader hdr = new JwtHeader();
        hdr.setAlgorithm(obj.getString("alg"));
        hdr.setType(obj.getString("typ"));
        hdr.setKeyId(obj.getString("kid"));
        return hdr;
    }

    /** 检查字符串是否仅含合法 base64url 字符（A-Z / a-z / 0-9 / - / _） */
    private static boolean isValidBase64Url(String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                      || (c >= 'a' && c <= 'z')
                      || (c >= '0' && c <= '9')
                      || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * 解析 JWT payload 段：base64url → JSON → JwtPayload。
     *
     * <p>调用前提：签名已验证通过；这里不再做格式校验（payload 内容由 issuer 负责）。</p>
     */
    private static void parsePayload(String payloadB64, VerifyResult result) {
        JsonObject jsonObject = Eson.parseJsonObject(Base64URL.decode(payloadB64.getBytes()));
        JwtPayload pl = new JwtPayload();
        Map<String, Object> customerClaims = new HashMap<>();
        for (Map.Entry<String, Object> entry : jsonObject.entrySet()) {
            switch (entry.getKey()) {
                case "iss":
                    pl.setIssuer(entry.getValue().toString());
                    break;
                case "sub":
                    pl.setSubject(entry.getValue().toString());
                    break;
                case "aud":
                    pl.setAudience(entry.getValue().toString());
                    break;
                case "exp":
                    pl.setExpiresAt((long) entry.getValue());
                    break;
                case "nbf":
                    pl.setNotBefore((long) entry.getValue());
                    break;
                case "iat":
                    pl.setIssuedAt((long) entry.getValue());
                    break;
                case "jti":
                    pl.setJwtId(entry.getValue().toString());
                    break;
                default:
                    customerClaims.put(entry.getKey(), entry.getValue());
                    break;
            }
        }
        pl.setCustomerClaims(customerClaims);
        result.setPayload(pl);
    }
}