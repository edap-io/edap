#!/usr/bin/env python3
"""
edap-s3 SigV4 签名 + curl —— python 版,zsh/bash 通用
用法:
  python3 /tmp/sigv4-curl.py PUT '/images?acl' -H 'x-amz-acl: public-read'
  python3 /tmp/sigv4-curl.py PUT /images/hello.txt -H 'Content-Type: text/plain' -d 'hello'
环境变量:
  HOST=localhost:8080   AKID=AKIDEXAMPLE
  SECRET=wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY   REGION=us-east-1
"""
import os, sys, hmac, hashlib, datetime, subprocess

HOST   = os.environ.get('HOST',   'localhost:8080')
AKID   = os.environ.get('AKID',   'AKIDEXAMPLE')
SECRET = os.environ.get('SECRET', 'wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY')
REGION = os.environ.get('REGION', 'us-east-1')

METHOD = sys.argv[1]
PATH_Q = sys.argv[2]

if '?' in PATH_Q:
    path, query = PATH_Q.split('?', 1)
else:
    path, query = PATH_Q, ''
if not path.startswith('/'):
    path = '/' + path

body = ''
extra = []
i = 3
while i < len(sys.argv):
    if sys.argv[i] == '-H' and i + 1 < len(sys.argv):
        extra.append(sys.argv[i + 1])
        i += 2
    elif sys.argv[i] == '-d' and i + 1 < len(sys.argv):
        body = sys.argv[i + 1]
        i += 2
    else:
        i += 1

now = datetime.datetime.now(datetime.timezone.utc)
amz_date = now.strftime('%Y%m%dT%H%M%SZ')
datestamp = amz_date[:8]
payload_hash = hashlib.sha256(body.encode('utf-8')).hexdigest()

# canonical headers: host + x-amz-date + x-amz-content-sha256 + 用户的
canon_headers = {
    'host': HOST,
    'x-amz-date': amz_date,
    'x-amz-content-sha256': payload_hash,
}
for h in extra:
    k, _, v = h.partition(':')
    canon_headers[k.strip().lower()] = v.strip()

sorted_h = sorted(canon_headers.items())
signed_list = ';'.join(k for k, _ in sorted_h)

canon_headers_block = ''.join(f'{k}:{v.strip()}\n' for k, v in sorted_h)
# SigV4 canonical query 要求空 value 也带 '='(Java uriEncode("") + "=" + uriEncode(key))
canon_query = '&'.join(
    sorted(kv if '=' in kv else kv + '=' for kv in query.split('&'))
) if query else ''

canonical_request = '\n'.join([
    METHOD,
    path,
    canon_query,
    canon_headers_block,
    signed_list,
    payload_hash,
])

sts = '\n'.join([
    'AWS4-HMAC-SHA256',
    amz_date,
    f'{datestamp}/{REGION}/s3/aws4_request',
    hashlib.sha256(canonical_request.encode('utf-8')).hexdigest(),
])

def H(k, d):
    return hmac.new(k, d.encode('utf-8'), hashlib.sha256).digest()

k_signing = H(H(H(H(b'AWS4' + SECRET.encode(), datestamp), REGION), 's3'), 'aws4_request')
sig = hmac.new(k_signing, sts.encode('utf-8'), hashlib.sha256).hexdigest()

auth = (f'AWS4-HMAC-SHA256 Credential={AKID}/{datestamp}/{REGION}/s3/aws4_request,'
        f' SignedHeaders={signed_list}, Signature={sig}')

url = f'http://{HOST}{path}' + (f'?{query}' if query else '')

cmd = ['curl', '-sS', '-X', METHOD, url,
       '-H', f'Authorization: {auth}',
       '-H', f'x-amz-date: {amz_date}',
       '-H', f'x-amz-content-sha256: {payload_hash}',
       '-w', '\n>>> HTTP %{http_code}\n']
for h in extra:
    cmd += ['-H', h]
if body:
    cmd += ['-d', body]

sys.exit(subprocess.call(cmd))
