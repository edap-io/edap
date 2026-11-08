#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 edap 可用的 JWT（HS256），用于调需要 @RequireAuth 的接口。

签名密钥 = 容器默认 key（edap-container-parent/.../Container.java:325）:
    20a4d5e1-3c3d-4259-962f-78b2c07b2b06
若容器 props 配了 jwt.signKey，用 --sign-key 覆盖。

claims 与 JwtUserResolver 的取法对齐:
    sub        -> RequestContext.userId
    userName   -> RequestContext.userName
    nickname   -> RequestContext.nickname
    roles      -> RequestContext.roles（JSON 数组）
    avatarUrl  -> RequestContext.avatarUrl

注意：edap 的 JWT.verify **不校验 exp**（只解析不比对），
这里仍然写一个正常的 exp 以便其它系统兼容。

用法:
    python3 gen_jwt.py                 # 打印 token
    python3 gen_jwt.py --sub 10086 --roles USER,ADMIN
"""

import argparse
import base64
import hashlib
import hmac
import json
import time
import uuid

DEFAULT_SIGN_KEY = "20a4d5e1-3c3d-4259-962f-78b2c07b2b06"


def b64url(data: bytes) -> str:
    """base64url 无填充 —— 与 io.edap.auth.jwt.utils.Base64URL.encodeToString 一致。"""
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def make_token(sign_key: str, claims: dict, alg: str = "HS256") -> str:
    header = {"alg": alg, "typ": "JWT"}
    h = b64url(json.dumps(header, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
    p = b64url(json.dumps(claims, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
    signing_input = "%s.%s" % (h, p)
    sig = hmac.new(sign_key.encode("utf-8"), signing_input.encode("utf-8"), hashlib.sha256).digest()
    return "%s.%s" % (signing_input, b64url(sig))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--sign-key", default=DEFAULT_SIGN_KEY)
    p.add_argument("--sub", default="10086")
    p.add_argument("--user-name", default="mockuser")
    p.add_argument("--nickname", default="模拟用户")
    p.add_argument("--avatar-url", default="https://example.com/avatar.png")
    p.add_argument("--roles", default="USER")
    p.add_argument("--iss", default="edap-test")
    p.add_argument("--aud", default="edap-test")
    p.add_argument("--ttl", type=int, default=86400, help="exp 偏移秒数")
    args = p.parse_args()

    now = int(time.time())
    roles = [r for r in args.roles.split(",") if r]
    claims = {
        "iss": args.iss,
        "sub": args.sub,
        "aud": args.aud,
        "exp": now + args.ttl,
        "iat": now,
        "nbf": now,
        "jti": uuid.uuid4().hex,
        "userName": args.user_name,
        "nickname": args.nickname,
        "avatarUrl": args.avatar_url,
        "roles": roles,
    }
    token = make_token(args.sign_key, claims)
    print(token)


if __name__ == "__main__":
    main()
