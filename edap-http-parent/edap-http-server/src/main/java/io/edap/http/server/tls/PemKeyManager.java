/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.http.server.tls;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * 从 <b>PEM 文件</b>装配服务端 {@link KeyManager}（证书链 + 私钥）。
 *
 * <p><b>为什么需要它</b>：CA 签发的证书交付物通常是两个 PEM 文件 ——
 * 证书链（Nginx 叫 {@code fullchain.cer}）与私钥（{@code www.example.com.key}）。
 * 运维手里只有这两个文件时，keystore 路线就多出一步「用 openssl 转换」的工序。
 * 这里直接读 PEM，把转换这步从部署流程里去掉。</p>
 *
 * <p><b>证书链格式</b>：一个文件里按顺序拼接多个 {@code -----BEGIN CERTIFICATE-----} 块
 * （leaf 在前、CA 在后，与 {@code fullchain.cer} 一致）。JDK 的
 * {@link CertificateFactory#generateCertificates} 直接吃这个格式，
 * 同时也吃二进制 DER，所以 {@code .cer} 扩展名是 DER 还是 PEM 都能用。</p>
 *
 * <p><b>私钥格式</b>：支持两种 ——</p>
 * <ul>
 *   <li>{@code -----BEGIN PRIVATE KEY-----}（PKCS#8，OpenSSL 3.x 的默认输出，<b>推荐</b>）；</li>
 *   <li>{@code -----BEGIN RSA PRIVATE KEY-----}（PKCS#1，OpenSSL 1.x 的默认输出）——
 *       内部先包成 PKCS#8 再交给 {@link KeyFactory}。</li>
 * </ul>
 *
 * <p><b>不支持</b>（遇到就报错，不静默降级）：</p>
 * <ul>
 *   <li>{@code -----BEGIN EC PRIVATE KEY-----}（SEC1）—— 请先
 *       {@code openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-p8.pem}；</li>
 *   <li>{@code -----BEGIN ENCRYPTED PRIVATE KEY-----}（加密的 PKCS#8）—— JDK 内置
 *       SunJSSE 不支持解密，请先 {@code openssl pkcs8 -topk8 -nocrypt} 解密。</li>
 * </ul>
 *
 * <p>不引 BouncyCastle：整个 PEM 解析只需要 JDK 自带的
 * {@link CertificateFactory} + {@link KeyFactory} + 一点点 DER 读字节。</p>
 */
final class PemKeyManager {

    /** 放进临时 KeyStore 里的私钥别名（只在本进程内用，名字不重要）。 */
    private static final String ALIAS = "edap-tls";

    private PemKeyManager() {
    }

    /**
     * 读 PEM 证书链与私钥，装配出服务端 KeyManager。
     *
     * @param certPath 证书链文件（{@code fullchain.cer}）
     * @param keyPath  私钥文件（{@code www.example.com.key}）
     * @return 可直接交给 {@code SSLContext.init} 的 KeyManager 数组
     * @throws IllegalStateException 文件不存在 / 格式不识别 / 私钥与证书不匹配
     */
    static KeyManager[] create(String certPath, String keyPath) {
        if (certPath == null || certPath.trim().isEmpty()) {
            throw new IllegalStateException("TLS 缺少证书文件配置（certificate）");
        }
        if (keyPath == null || keyPath.trim().isEmpty()) {
            throw new IllegalStateException("TLS 缺少私钥文件配置（privateKey）");
        }
        try {
            Certificate[] chain = readCertificateChain(certPath.trim());
            PrivateKey key = readPrivateKey(keyPath.trim());
            return toKeyManagers(key, chain);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取 PEM 证书/私钥失败（" + certPath + " / " + keyPath + "): "
                    + e.getMessage(), e);
        }
    }

    /**
     * 读证书链 —— PEM（多块拼接）或 DER（单张证书）都吃。
     */
    private static Certificate[] readCertificateChain(String certPath) throws Exception {
        List<Certificate> list = new ArrayList<>();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(Paths.get(certPath))) {
            Collection<? extends Certificate> certs = cf.generateCertificates(in);
            list.addAll(certs);
        }
        if (list.isEmpty()) {
            throw new IllegalStateException("证书文件里没有解析出任何证书: " + certPath);
        }
        return list.toArray(new Certificate[0]);
    }

    /**
     * 读私钥 —— 支持 PKCS#8 与 PKCS#1(RSA)。
     */
    private static PrivateKey readPrivateKey(String keyPath) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(keyPath)), StandardCharsets.US_ASCII);
        PemBlock block = PemBlock.parse(text);
        if (block == null) {
            throw new IllegalStateException("私钥文件里找不到 PEM 块（-----BEGIN ... -----）: " + keyPath);
        }
        switch (block.type) {
            case "PRIVATE KEY":
                return parsePkcs8(block.der);
            case "RSA PRIVATE KEY":
                return parsePkcs8(pkcs1ToPkcs8(block.der));
            case "EC PRIVATE KEY":
                throw new IllegalStateException("不支持 SEC1 格式的 EC 私钥（-----BEGIN EC PRIVATE KEY-----），"
                        + "请先转换：openssl pkcs8 -topk8 -nocrypt -in " + keyPath + " -out key-pkcs8.pem");
            case "ENCRYPTED PRIVATE KEY":
                throw new IllegalStateException("不支持加密的 PKCS#8 私钥（-----BEGIN ENCRYPTED PRIVATE KEY-----），"
                        + "请先解密：openssl pkcs8 -topk8 -nocrypt -in " + keyPath + " -out key-plain.pem");
            default:
                throw new IllegalStateException("不认识的私钥 PEM 类型: " + block.type
                        + "（支持 PRIVATE KEY / RSA PRIVATE KEY）");
        }
    }

    /**
     * PKCS#8 → {@link PrivateKey}。算法从 PKCS#8 里的 AlgorithmIdentifier OID 读出来，
     * 不靠猜（RSA / EC / DSA / Ed25519 / Ed448）。
     */
    private static PrivateKey parsePkcs8(byte[] der) throws Exception {
        String oid = readPkcs8AlgorithmOid(der);
        String algorithm = algorithmOf(oid);
        KeyFactory kf = KeyFactory.getInstance(algorithm);
        return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /**
     * OID → JCA 算法名。
     */
    private static String algorithmOf(String oid) {
        switch (oid) {
            case "1.2.840.113549.1.1.1": return "RSA";
            case "1.2.840.10045.2.1":    return "EC";
            case "1.2.840.10040.4.1":    return "DSA";
            case "1.3.101.112":          return "Ed25519";
            case "1.3.101.113":          return "Ed448";
            default:
                throw new IllegalStateException("不认识的私钥算法 OID: " + oid
                        + "（支持 RSA / EC / DSA / Ed25519 / Ed448）");
        }
    }

    /**
     * 读 PKCS#8 里的 AlgorithmIdentifier OID。
     *
     * <p>结构：{@code SEQUENCE { INTEGER version, SEQUENCE { OID algorithm, params OPTIONAL },
     * OCTET STRING privateKey }}。这里只解析到 OID 为止。</p>
     */
    private static String readPkcs8AlgorithmOid(byte[] der) {
        int p = 0;
        int[] outer = header(der, p);
        p = outer[0];                                   // 进入 PKCS#8 SEQUENCE
        int[] version = header(der, p);
        p = version[0] + version[1];                    // 跳过 version
        int[] algId = header(der, p);                   // AlgorithmIdentifier SEQUENCE
        p = algId[0];
        int[] oid = header(der, p);
        return decodeOid(der, oid[0], oid[1]);
    }

    /**
     * DER 头解析：返回 {内容起始下标, 内容长度}。
     */
    private static int[] header(byte[] d, int p) {
        if (p >= d.length) {
            throw new IllegalStateException("私钥 DER 结构意外结束");
        }
        int q = p + 1;
        int b = d[q] & 0xff;
        int len;
        if (b < 0x80) {
            len = b;
            q += 1;
        } else {
            int n = b & 0x7f;
            if (n == 0 || n > 4 || q + n >= d.length) {
                throw new IllegalStateException("私钥 DER 长度字段非法");
            }
            len = 0;
            for (int i = 0; i < n; i++) {
                len = (len << 8) | (d[q + 1 + i] & 0xff);
            }
            q += 1 + n;
        }
        return new int[]{q, len};
    }

    /**
     * OID 内容的点分十进制还原。
     */
    private static String decodeOid(byte[] d, int start, int len) {
        if (len <= 0 || start + len > d.length) {
            throw new IllegalStateException("私钥 DER 里的 OID 越界");
        }
        StringBuilder sb = new StringBuilder();
        int first = d[start] & 0xff;
        sb.append(first / 40).append('.').append(first % 40);
        long val = 0;
        for (int i = 1; i < len; i++) {
            int b = d[start + i] & 0xff;
            val = (val << 7) | (b & 0x7f);
            if ((b & 0x80) == 0) {
                sb.append('.').append(val);
                val = 0;
            }
        }
        return sb.toString();
    }

    /**
     * PKCS#1(RSA) → PKCS#8：给私钥套一层 {@code SEQUENCE { INTEGER 0, AlgorithmIdentifier, OCTET STRING }}。
     */
    private static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        // AlgorithmIdentifier: SEQUENCE { OID rsaEncryption, NULL }
        byte[] algId = {
                0x30, 0x0d,
                0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
        };
        byte[] version = {0x02, 0x01, 0x00};                        // version = 0
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(version);
        out.writeBytes(algId);
        out.writeBytes(derWrap(0x04, pkcs1));                       // privateKey OCTET STRING
        return derWrap(0x30, out.toByteArray());
    }

    /**
     * 给内容套上 DER 的 tag + length。
     */
    private static byte[] derWrap(int tag, byte[] content) {
        byte[] len = derLength(content.length);
        byte[] r = new byte[1 + len.length + content.length];
        r[0] = (byte) tag;
        System.arraycopy(len, 0, r, 1, len.length);
        System.arraycopy(content, 0, r, 1 + len.length, content.length);
        return r;
    }

    /**
     * DER 长度字段（短形式 / 长形式）。
     */
    private static byte[] derLength(int len) {
        if (len < 0x80) {
            return new byte[]{(byte) len};
        }
        int bytes = 1;
        int t = len;
        while ((t >>>= 8) != 0) {
            bytes++;
        }
        byte[] r = new byte[bytes + 1];
        r[0] = (byte) (0x80 | bytes);
        for (int i = 0; i < bytes; i++) {
            r[bytes - i] = (byte) ((len >>> (8 * i)) & 0xff);
        }
        return r;
    }

    /**
     * 私钥 + 证书链 → KeyManager。
     *
     * <p>走「内存 KeyStore + {@link KeyManagerFactory}」这条路，而不是自己实现
     * {@code X509KeyManager}：算法选择、别名匹配这些逻辑 JDK 已经写好了。</p>
     */
    private static KeyManager[] toKeyManagers(PrivateKey key, Certificate[] chain) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        char[] pwd = new char[0];
        keyStore.setKeyEntry(ALIAS, key, pwd, chain);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, pwd);
        return kmf.getKeyManagers();
    }

    /**
     * 一个 PEM 块（类型 + base64 解码后的 DER）。
     */
    private static final class PemBlock {

        final String type;
        final byte[] der;

        PemBlock(String type, byte[] der) {
            this.type = type;
            this.der = der;
        }

        /**
         * 从 PEM 文本里取出第一个私钥块。
         *
         * <p>★ 为什么要手工剔掉 PEM 头（{@code Proc-Type:} / {@code DEK-Info:}）：
         * base64 的 MIME 解码器会忽略非法字符，但 {@code Proc-Type:4,ENCRYPTED}
         * 里的字母全是合法 base64 字符 —— 不剔掉就会拼出一段垃圾数据。</p>
         */
        static PemBlock parse(String text) {
            int begin = text.indexOf("-----BEGIN ");
            if (begin < 0) {
                return null;
            }
            int typeEnd = text.indexOf("-----", begin + "-----BEGIN ".length());
            if (typeEnd < 0) {
                return null;
            }
            String type = text.substring(begin + "-----BEGIN ".length(), typeEnd).trim();
            String endMarker = "-----END " + type + "-----";
            int end = text.indexOf(endMarker, typeEnd);
            if (end < 0) {
                throw new IllegalStateException("PEM 块缺少结束标记: " + endMarker);
            }
            StringBuilder body = new StringBuilder();
            for (String line : text.substring(typeEnd + "-----".length(), end).split("\\R")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("Proc-Type:") || t.startsWith("DEK-Info:")) {
                    continue;
                }
                body.append(t);
            }
            byte[] der = Base64.getMimeDecoder().decode(body.toString());
            if (der.length == 0) {
                throw new IllegalStateException("PEM 块内容为空: " + type);
            }
            return new PemBlock(type, der);
        }
    }

}
