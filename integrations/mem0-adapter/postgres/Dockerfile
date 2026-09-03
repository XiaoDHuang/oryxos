# 唯一来源是固定上游 digest；仅移除 gosu 二进制并应用发行版已发布修复。
# 本部署容器直接以 postgres(999) 运行，不经过 root 启动再降权，gosu 在运行层无用途。
FROM pgvector/pgvector:0.8.6-pg17-trixie@sha256:71f806616c90e1de182cacfae8ac1f9ef00f0f34ee8c53b9042495db8689ccf2
USER root
# PG/pgvector 版本锁定：hold 全部 postgresql/libpq 包，只升级发行版基础包。
RUN set -eu; \
    apt-get update; \
    apt-mark hold 'postgresql*' 'libpq*' >/dev/null; \
    DEBIAN_FRONTEND=noninteractive apt-get upgrade -y --no-install-recommends -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold; \
    rm -f /usr/local/bin/gosu; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/*
USER 999:999
