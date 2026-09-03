FROM python:3.12.14-slim@sha256:2fe5997d249a808b8eeea52c58a1dbffbba28754dc11699ef5c029f2d818ce79 AS dependencies

WORKDIR /app
COPY vendor/mem0ai-1.0.11+oryx.1-py3-none-any.whl vendor/
COPY build/runtime-requirements.txt build/
RUN python -m pip --isolated install --no-cache-dir --no-compile --no-deps \
       --require-hashes --only-binary=:all: --target /install -r build/runtime-requirements.txt \
    && PYTHONPATH=/install python -m pip check

COPY pyproject.toml uv.lock .python-version Dockerfile compose.yaml .dockerignore ./
COPY src src
COPY migrations migrations
COPY scripts scripts
COPY postgres postgres
COPY vendor/sdk-lock.json vendor/
COPY build/source-manifest.txt build/
ARG SOURCE_MANIFEST_SHA256
RUN test "$(sha256sum build/source-manifest.txt | cut -d ' ' -f 1)" = "$SOURCE_MANIFEST_SHA256" \
    && sha256sum -c build/source-manifest.txt

FROM python:3.12.14-slim@sha256:2fe5997d249a808b8eeea52c58a1dbffbba28754dc11699ef5c029f2d818ce79

WORKDIR /app
COPY --from=dependencies /install/ /usr/local/lib/python3.12/site-packages/
COPY --from=dependencies /app/src src
COPY --from=dependencies /app/migrations migrations
COPY --from=dependencies /app/build/source-manifest.txt /app/source-manifest.txt
COPY --from=dependencies --chmod=0555 /app/scripts/container-entrypoint.sh scripts/container-entrypoint.sh
COPY --from=dependencies --chown=65534:65534 /app/scripts/mem0-dir/ /var/lib/oryx-mem0/
# 运行层删除安装工具：依赖已在构建层锁定校验，运行时不需要 pip/ensurepip。
RUN rm -rf /usr/local/lib/python3.12/site-packages/pip \
           /usr/local/lib/python3.12/site-packages/pip-*.dist-info \
           /usr/local/lib/python3.12/ensurepip \
           /usr/local/bin/pip /usr/local/bin/pip3 /usr/local/bin/pip3.12
ARG SOURCE_MANIFEST_SHA256
LABEL org.oryxos.source-sha256=$SOURCE_MANIFEST_SHA256 \
      org.oryxos.sdk-version="1.0.11+oryx.1"

ENV PYTHONPATH=/app/src \
    PYTHONNOUSERSITE=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    MEM0_TELEMETRY=false \
    MEM0_DIR=/var/lib/oryx-mem0

USER 65534:65534
EXPOSE 8443
ENTRYPOINT ["/app/scripts/container-entrypoint.sh"]
