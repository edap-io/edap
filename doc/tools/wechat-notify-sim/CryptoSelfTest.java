import com.estylr.config.EstylrConfig;
import com.estylr.pay.WechatPayNotifyCrypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 用**部署版 EAR 里的真实实现**（com.estylr.pay.WechatPayNotifyCrypto）校验
 * wx_notify.py 生成的签名/密文格式是否符合服务端期望。
 *
 * 用法:
 *   java --add-opens java.base/java.lang=ALL-UNNAMED \
 *        -cp <cp> CryptoSelfTest [pieces.txt]
 *
 * <cp> 需要含:
 *   - EAR 解压后的 all/ 目录（com.estylr.*）
 *   - edap-common jar（io.edap.util.StringUtil）
 */
public class CryptoSelfTest {

    static Map<String, String> loadPieces(String path) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            int i = line.indexOf('=');
            if (i > 0) m.put(line.substring(0, i), line.substring(i + 1));
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> p = loadPieces(args.length > 0 ? args[0] : "/tmp/wxsim/pieces.txt");
        String base = System.getProperty("user.dir");
        String certPath = base + "/certs/wxpay_platform_cert.pem";
        String pemPath = base + "/certs/wxpay_public_key.pem";
        String apiV3Key = "0123456789abcdef0123456789abcdef";

        String timestamp = p.get("TIMESTAMP");
        String nonce = p.get("NONCE");
        String body = p.get("BODY");
        String signature = p.get("SIGNATURE");
        String ciphertext = p.get("CIPHERTEXT");
        String resourceNonce = p.get("RESOURCE_NONCE");

        System.out.println("签名串 = " + (timestamp + "\n" + nonce + "\n" + body + "\n").replace("\n", "\\n"));

        EstylrConfig cfg = new EstylrConfig();
        cfg.setWxPayPlatformCertPath(certPath);
        cfg.setWxPayPublicKeyPath(pemPath);
        cfg.setWxPayApiV3Key(apiV3Key);
        WechatPayNotifyCrypto crypto = new WechatPayNotifyCrypto(cfg);

        // 1. serial 带 PUB_KEY_ID_ 前缀 => loadPublicKey 走 PEM 分支
        System.out.println("[1] verify(PEM 分支)   = " + crypto.verify("PUB_KEY_ID_MOCK", timestamp, nonce, body, signature));

        // 2. serial 不带前缀 => 走 X.509 证书分支
        System.out.println("[2] verify(证书分支)   = " + crypto.verify("MOCK_SERIAL", timestamp, nonce, body, signature));

        // 3. 篡改 body => 必须失败
        System.out.println("[3] verify(篡改 body)  = " + crypto.verify("PUB_KEY_ID_MOCK", timestamp, nonce, body + "x", signature)
                + "   (期望 false)");

        // 4. 解密 python 生成的 AES-GCM 密文
        String plain = crypto.decryptResource("AEAD_AES_256_GCM", ciphertext, "transaction", resourceNonce);
        System.out.println("[4] decryptResource(python 产物) = " + plain);

        // 5. 独立用 Java 加密一遍，验证 Java 侧 GCM 布局与 python 一致
        //    注意 nonce 要按部署版的规则先 Base64 解码（decryptResource 里就是这么做的）
        String aad = "transaction";
        byte[] nonceBytes = Base64.getDecoder().decode(resourceNonce);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, nonceBytes));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        String javaCt = Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        System.out.println("[5] java 重新加密 == python 密文 = " + javaCt.equals(ciphertext));
    }
}
