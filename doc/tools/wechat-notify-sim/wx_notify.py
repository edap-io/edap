#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
微信支付 v3 回调（支付通知）模拟器。

按部署版 PaymentCallbackServiceImpl.wechatNotify 的实际契约生成请求：
  - 4 个 Wechatpay-* 请求头（大小写必须完全一致，impl 用 headers.get("Wechatpay-Serial") 取）
  - body = WeChat Pay v3 通知 JSON（resource.ciphertext 为 AES-256-GCM 密文）
  - 签名串 = timestamp + "\\n" + nonce + "\\n" + body + "\\n"
  - 签名   = Base64(RSA-SHA256(签名串))

用法:
    python3 wx_notify.py --dry-run                    # 只打印 curl，不发
    python3 wx_notify.py --token <jwt>                # 带 token 真正调用
    python3 wx_notify.py --token <jwt> --api-v3-key 0123456789abcdef0123456789abcdef
"""

import argparse
import base64
import json
import os
import shlex
import subprocess
import sys
import time
import uuid

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HERE = os.path.dirname(os.path.abspath(__file__))
CERT_DIR = os.path.join(HERE, "certs")
PRIVATE_KEY = os.path.join(CERT_DIR, "wxpay_private_key.pem")
PLATFORM_CERT = os.path.join(CERT_DIR, "wxpay_platform_cert.pem")
PUBLIC_KEY = os.path.join(CERT_DIR, "wxpay_public_key.pem")

# 部署版 EstylrConfig 的默认值（反编译 EstylrConfig.class 得到）
DEFAULT_APPID = "wx567a3b6651a4e0fa"
DEFAULT_MCHID = "1119655340"


def ensure_certs():
    """没有证书就生成一对自签 RSA（模拟微信平台证书）。

    两份产物对应 WechatPayNotifyCrypto.loadPublicKey 的两条分支：
      - wxpay_platform_cert.pem : X.509 证书，serial 不带 PUB_KEY_ID_ 前缀时走这条
      - wxpay_public_key.pem    : SPKI 公钥（-----BEGIN PUBLIC KEY-----），
                                  serial 带 PUB_KEY_ID_ 前缀时走这条
    """
    if os.path.exists(PRIVATE_KEY) and os.path.exists(PLATFORM_CERT):
        if not os.path.exists(PUBLIC_KEY):
            export_public_key()
        return
    os.makedirs(CERT_DIR, exist_ok=True)
    subprocess.check_call([
        "openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "3650",
        "-nodes", "-keyout", PRIVATE_KEY, "-out", PLATFORM_CERT,
        "-subj", "/CN=WeChatPay Mock Platform/O=MOCK",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    export_public_key()


def export_public_key():
    """导出 SPKI 公钥 PEM —— loadPublicKeyFromPem 用 X509EncodedKeySpec 解析。"""
    subprocess.check_call([
        "openssl", "rsa", "-in", PRIVATE_KEY, "-pubout", "-out", PUBLIC_KEY,
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def encrypt_resource(plaintext: str, api_v3_key: str, associated_data: str, nonce_b: bytes) -> str:
    """AES-256-GCM 加密，对齐 WechatPayNotifyCrypto.decryptResource：
       Cipher AES/GCM/NoPadding + GCMParameterSpec(128, nonce) + updateAAD(associated_data)。"""
    key = api_v3_key.encode("utf-8")
    if len(key) != 32:
        raise SystemExit("api-v3-key 必须是 32 字节（EstylrConfig.wxPayApiV3Key 校验长度 == 32）")
    aad = associated_data.encode("utf-8")
    ct = AESGCM(key).encrypt(nonce_b, plaintext.encode("utf-8"), aad)
    # Java GCM doFinal 输出 = ciphertext || tag(16)，与 Python AESGCM.encrypt 输出一致
    return base64.b64encode(ct).decode("ascii")


def sign(payload: str) -> str:
    """timestamp\\nnonce\\nbody\\n 的 RSA-SHA256 签名，Base64 编码。"""
    with open(PRIVATE_KEY, "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)
    sig = key.sign(payload.encode("utf-8"), padding.PKCS1v15(), hashes.SHA256())
    return base64.b64encode(sig).decode("ascii")


def build(args):
    ensure_certs()

    timestamp = args.timestamp or str(int(time.time()))
    nonce = args.nonce or uuid.uuid4().hex
    serial = args.serial

    # --- resource 明文：handleTrade 需要的字段 ---
    resource_plain = {
        "mchid": args.mchid,
        "appid": args.appid,
        "out_trade_no": args.out_trade_no,
        "transaction_id": args.transaction_id,
        "trade_type": "NATIVE",
        "trade_state": "SUCCESS",
        "trade_state_desc": "支付成功",
        "bank_type": "OTHERS",
        "attach": "MOCK",
        "success_time": "2026-11-05T22:00:00+08:00",
        "payer": {"openid": "oMockOpenid0000000000000000000000"},
        "amount": {
            "total": args.amount,
            "payer_total": args.amount,
            "currency": "CNY",
            "payer_currency": "CNY",
        },
    }
    plaintext = json.dumps(resource_plain, ensure_ascii=False, separators=(",", ":"))

    associated_data = "transaction"
    # 部署版 WechatPayNotifyCrypto.decryptResource 对 resource.nonce 做 **Base64 解码** 后
    # 才当作 GCM nonce（字节码：73-76 行 Base64.decode(a4) -> astore 6 -> GCMParameterSpec(128, astore 6)）。
    #   --nonce-mode base64 : resource.nonce 放 base64(12字节) => 部署版能解开（默认）
    #   --nonce-mode raw    : resource.nonce 放原始 12 字符 => 符合微信官方 SDK 用法，
    #                          但部署版会先 base64 解码成 9 字节 => AEADBadTagException（解密失败）
    nonce_raw = uuid.uuid4().hex[:12].encode("utf-8")
    if args.nonce_mode == "base64":
        resource_nonce = base64.b64encode(nonce_raw).decode("ascii")
        gcm_nonce = nonce_raw
    else:
        resource_nonce = nonce_raw.decode("ascii")
        gcm_nonce = nonce_raw
    ciphertext = encrypt_resource(plaintext, args.api_v3_key, associated_data, gcm_nonce)

    # --- 通知 body ---
    body_obj = {
        "id": "EV-MOCK-%s" % uuid.uuid4().hex[:24],
        "create_time": time.strftime("%Y-%m-%dT%H:%M:%S+08:00"),
        "resource_type": "encrypt-resource",
        "event_type": args.event_type,
        "summary": "支付成功",
        "resource": {
            "original_type": "mchpayment",
            "algorithm": "AEAD_AES_256_GCM",
            "ciphertext": ciphertext,
            "associated_data": associated_data,
            "nonce": resource_nonce,
        },
    }
    # 必须是**逐字节**确定的 body：签名串用的就是这个 body
    body = json.dumps(body_obj, ensure_ascii=False, separators=(",", ":"))

    sign_payload = "%s\n%s\n%s\n" % (timestamp, nonce, body)
    signature = sign(sign_payload)

    headers = [
        ("Wechatpay-Serial", serial),
        ("Wechatpay-Signature", signature),
        ("Wechatpay-Timestamp", timestamp),
        ("Wechatpay-Nonce", nonce),
    ]
    if args.token:
        headers.insert(0, ("Authorization", "Bearer " + args.token))
    headers.append(("Content-Type", "application/json"))
    return body, headers, sign_payload


def to_curl(url, headers, body):
    parts = ["curl", "-sS", "--noproxy", "*", "-i", "-X", "POST", url]
    for k, v in headers:
        parts += ["-H", "%s: %s" % (k, v)]
    parts += ["--data-binary", "@-"]
    return " ".join(shlex.quote(x) for x in parts)


def send(url, headers, body):
    """body 通过 stdin 传（--data-binary @-），保证签名串里的 body 与发出的字节完全一致。"""
    argv = ["curl", "-sS", "--noproxy", "*", "-i", "-X", "POST", url]
    for k, v in headers:
        argv += ["-H", "%s: %s" % (k, v)]
    argv += ["--data-binary", "@-"]
    p = subprocess.run(argv, input=body.encode("utf-8"))
    return p.returncode


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://127.0.0.1:8080/api/v1/payment-callback/wechat-notify")
    p.add_argument("--token", default=None, help="Authorization: Bearer <jwt>；部署版接口带 @RequireAuth")
    p.add_argument("--serial", default="PUB_KEY_ID_MOCK",
                   help="PUB_KEY_ID_ 前缀 => 走 wxPayPublicKeyPath(PEM)；否则走 wxPayPlatformCertPath(X.509)")
    p.add_argument("--api-v3-key", default="0123456789abcdef0123456789abcdef")
    p.add_argument("--appid", default=DEFAULT_APPID)
    p.add_argument("--mchid", default=DEFAULT_MCHID)
    p.add_argument("--out-trade-no", default="MOCK-TRADE-202611050001")
    p.add_argument("--transaction-id", default="4200001234202611051234567890")
    p.add_argument("--amount", type=int, default=100)
    p.add_argument("--event-type", default="TRANSACTION.SUCCESS")
    p.add_argument("--nonce-mode", choices=["base64", "raw"], default="base64",
                   help="resource.nonce 的编码方式：base64=对齐部署版代码；raw=对齐微信官方 SDK")
    p.add_argument("--timestamp", default=None)
    p.add_argument("--nonce", default=None)
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--emit-java", action="store_true",
                   help="输出 KEY=VALUE，供 CryptoSelfTest.java 用 -D 传参")
    args = p.parse_args()

    body, headers, sign_payload = build(args)

    if args.emit_java:
        d = dict(headers)
        print("TIMESTAMP=" + d["Wechatpay-Timestamp"])
        print("NONCE=" + d["Wechatpay-Nonce"])
        print("SIGNATURE=" + d["Wechatpay-Signature"])
        print("BODY=" + body)
        obj = json.loads(body)
        print("CIPHERTEXT=" + obj["resource"]["ciphertext"])
        print("RESOURCE_NONCE=" + obj["resource"]["nonce"])
        return 0

    print("=" * 78)
    print("签名串 (timestamp\\nnonce\\nbody\\n):")
    print(sign_payload[:120] + (" ...<省略>" if len(sign_payload) > 120 else ""))
    print("-" * 78)
    print("请求头:")
    for k, v in headers:
        print("  %s: %s" % (k, v if len(v) < 100 else v[:100] + " ...<省略>"))
    print("-" * 78)
    print("body:")
    print(body)
    print("=" * 78)

    if args.dry_run:
        print(to_curl(args.url, headers, body))
        return 0

    return send(args.url, headers, body)


if __name__ == "__main__":
    sys.exit(main())
