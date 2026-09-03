#!/bin/sh
set -eu
# 证书只写入本次隔离目录，不进入运行镜像或系统信任库。
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 2 \
  -keyout /certs/ca-key.pem -out /certs/ca.pem -subj '/CN=Oryx T049 Test CA' \
  -addext 'basicConstraints=critical,CA:TRUE'
openssl req -new -newkey rsa:2048 -nodes -sha256 \
  -keyout /certs/server-key.pem -out /certs/server.csr -subj '/CN=models' \
  -addext 'subjectAltName=DNS:models,DNS:localhost,IP:127.0.0.1'
openssl x509 -req -in /certs/server.csr -CA /certs/ca.pem -CAkey /certs/ca-key.pem \
  -CAcreateserial -days 2 -sha256 -copy_extensions copy -out /certs/server.pem
chmod 644 /certs/server-key.pem /certs/server.pem /certs/ca.pem
