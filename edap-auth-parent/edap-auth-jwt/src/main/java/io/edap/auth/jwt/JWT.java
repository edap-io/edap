package io.edap.auth.jwt;

import io.edap.auth.jwt.algorithm.HmacSha256;
import io.edap.auth.jwt.utils.Base64URL;
import io.edap.json.Eson;
import io.edap.json.JsonObject;
import io.edap.util.StringUtil;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class JWT {

    private static String DEFAULT_HEADER_STR = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";

    private static final Header DEFAULT_HEADER;

    public static final Map<String, Algorithm> ALGORITHM_CACHE = new HashMap<>();

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
        return new JwtBuilder();
    }

    public static VerifyResult verify(String token, String signKey) {
        VerifyResult result = new VerifyResult();
        int index = token.indexOf(".");
        if (index == -1) {
            result.setCode(1);
            result.setMessage("token格式错误");

            return result;
        }
        String header = token.substring(0, index);
        if (DEFAULT_HEADER_STR.equals(header)) {
            result.setHeader(DEFAULT_HEADER);
        } else {
            String headerStr = Base64URL.decode(header);
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

        String sign = token.substring(index2 + 1);
        if (StringUtil.isEmpty(sign)) {
            result.setCode(1);
            result.setMessage("token格式错误");

            return result;
        }

        String algorithm = DEFAULT_HEADER.getAlgorithm();
        if ("HS256".equals(algorithm)) {
            String key = algorithm + ":" + signKey;
            Algorithm mac = ALGORITHM_CACHE.get(key);
            if (mac == null) {
                mac = new HmacSha256(signKey);
                ALGORITHM_CACHE.put(key, mac);
            }
            byte[] signBytes = mac.sign(token.substring(0, index2).getBytes(StandardCharsets.UTF_8), 0, index2);
            String calSign = Base64URL.encode(signBytes);
            if (calSign.equals(sign)) {
                result.setCode(0);
                result.setMessage("success");
                JsonObject jsonObject = Eson.parseJsonObject(Base64URL.decode(payload));
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
                            pl.setExpiresAt((long)entry.getValue());
                            break;
                        case "nbf":
                            pl.setNotBefore((long)entry.getValue());
                            break;
                        case "iat":
                            pl.setIssuedAt((long)entry.getValue());
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
            } else {
                result.setCode(2);
                result.setMessage("签名错误");
            }
        }
        return result;

    }
}
