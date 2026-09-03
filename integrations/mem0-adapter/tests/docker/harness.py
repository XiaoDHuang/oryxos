"""只创建带唯一项目名的隔离部署；停止时保留测试卷，不触及已有服务。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT.parents[1] / ".verification/007-memory-backends"
PYTHON_IMAGE = "python:3.12.14-slim@sha256:2fe5997d249a808b8eeea52c58a1dbffbba28754dc11699ef5c029f2d818ce79"
LLM_MODEL = "mistral-nemo:12b"
EMBEDDING_MODEL = "bge-m3"
EMBEDDING_DIMENSIONS = "1024"
DATABASE = "oryx_mem0_t049_test"
WORKSPACE = "11111111-1111-4111-8111-111111111111"


class Deployment:
    def __init__(self, directory, docker):
        self.directory = Path(directory).resolve()
        if not self.directory.is_relative_to(EVIDENCE.resolve()) or not self.directory.name.startswith("docker-t049-"):
            raise ValueError("隔离部署路径无效")
        self.docker = docker
        self.state = json.loads((self.directory / "state.json").read_text(encoding="utf-8"))
        if not re.fullmatch(r"oryx007-t049-[0-9a-f]{12}", self.state["project"]):
            raise ValueError("隔离部署项目名无效")

    def run(self, arguments, text=None, timeout=180):
        result = subprocess.run([self.docker, *arguments], input=text, capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=timeout)
        if result.returncode:
            output = result.stdout + result.stderr
            for value in self.state.get("secrets", {}).values():
                output = output.replace(value, "<redacted>")
            (self.directory / "last-command-error.log").write_text(output, encoding="utf-8")
            raise RuntimeError("隔离Docker命令失败；详情见部署目录中的脱敏日志")
        return result.stdout

    def compose(self, *arguments, text=None):
        files = ["-f", str(ROOT / "compose.yaml"), "-f", str(ROOT / "tests/docker/compose.test.yaml")]
        if self.state.get("real_models"):
            files += ["-f", str(ROOT / "tests/docker/compose.real-models.yaml")]
        return self.run(["compose", "--env-file", str(self.directory / "compose.env"),
            "-p", self.state["project"], *files, "--profile", "mem0", *arguments], text)

    def sql(self, statement, admin=False):
        # 项目名和数据库名固定，不能把任意连接串或现有业务容器传给这个入口。
        role = "oryx_mem0_bootstrap" if admin else "oryx_mem0_app"
        return self.compose("exec", "-T", "postgres", "psql", "-X", "-U", role,
                            "-d", DATABASE, "-At", "-v", "ON_ERROR_STOP=1", text=statement)

    def ports(self):
        return {service: int(self.compose("port", service, port).strip().rsplit(":", 1)[1])
                for service, port in (("postgres", "5432"), ("adapter", "8443"), ("models", "9443"))}

    def stop(self):
        self.compose("stop")

    def use_image(self, image):
        if not re.fullmatch(r"oryxos/mem0-adapter:t049-[0-9a-f]{12}", image):
            raise ValueError("测试镜像tag无效")
        inspected = json.loads(self.run(["image", "inspect", image]))[0]
        source = inspected["Config"]["Labels"]["org.oryxos.source-sha256"]
        if not re.fullmatch(r"[0-9a-f]{64}", source):
            raise ValueError("测试镜像源码标签无效")
        path = self.directory / "compose.env"
        lines = path.read_text(encoding="utf-8").splitlines()
        replaced = {"T049_IMAGE": image, "SOURCE_MANIFEST_SHA256": source}
        lines = [line.split("=", 1)[0] + "='" + replaced[line.split("=", 1)[0]] + "'"
                 if line.split("=", 1)[0] in replaced else line for line in lines]
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        self.state["image"] = image
        (self.directory / "state.json").write_text(json.dumps(self.state), encoding="utf-8")
        self.compose("up", "-d", "--no-build", "--wait", "adapter")

    def save_logs(self):
        logs = self.compose("logs", "--no-color", "--tail", "100")
        for value in self.state["secrets"].values():
            logs = logs.replace(value, "<redacted>")
        (self.directory / "services.log").write_text(logs, encoding="utf-8")


def prepare_models(deployment):
    """真实模型预拉取：使用主机 Ollama（GPU 原生），只确认或拉取锁定模型，不做任何云端上传。"""
    for model in (LLM_MODEL, EMBEDDING_MODEL):
        result = subprocess.run(["ollama", "pull", model], capture_output=True, timeout=7200)
        if result.returncode != 0:
            raise RuntimeError("模型预拉取失败：" + model)
    listed = subprocess.run(["ollama", "list"], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=60)
    for model in (LLM_MODEL, EMBEDDING_MODEL):
        if not any(line.split()[0] == model or line.split()[0] == model + ":latest"
                   for line in listed.stdout.splitlines()[1:] if line.split()):
            raise RuntimeError("模型未出现在主机 Ollama 清单：" + model)
    # 预热：让模型加载进显存，避免首个真实操作把加载时间算进协议期限。
    import urllib.request
    warmup = urllib.request.Request("http://127.0.0.1:11434/v1/chat/completions",
        json.dumps({"model": LLM_MODEL, "messages": [{"role": "user", "content": "ping"}],
                    "stream": False}).encode(), {"Content-Type": "application/json"})
    urllib.request.urlopen(warmup, timeout=300).read()
    warmup = urllib.request.Request("http://127.0.0.1:11434/v1/embeddings",
        json.dumps({"model": EMBEDDING_MODEL, "input": "ping", "encoding_format": "float"}).encode(),
        {"Content-Type": "application/json"})
    urllib.request.urlopen(warmup, timeout=300).read()


def setup(docker, image, real_models=False):
    if not re.fullmatch(r"oryxos/mem0-adapter:t049-[0-9a-f]{12}", image):
        raise ValueError("只接受本次源码绑定的测试镜像tag")
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="docker-t049-", dir=EVIDENCE))
    certificates = directory / "certificates"
    certificates.mkdir()
    values = {name: secrets.token_urlsafe(32) for name in ("admin", "app", "client", "cursor", "model", "control")}
    state = {"project": "oryx007-t049-" + uuid4().hex[:12], "image": image,
             "workspace": WORKSPACE, "database": DATABASE, "secrets": values}
    if real_models:
        state["real_models"] = True
        state["llm_model"] = LLM_MODEL
        state["embedding_model"] = EMBEDDING_MODEL
    (directory / "state.json").write_text(json.dumps(state), encoding="utf-8")
    deployment = Deployment(directory, docker)
    if real_models:
        prepare_models(deployment)
    inspected = json.loads(deployment.run(["image", "inspect", image]))[0]
    source_digest = inspected["Config"]["Labels"]["org.oryxos.source-sha256"]
    deployment.run(["run", "--rm", "--network", "none", "--entrypoint", "sh",
        "-v", str(certificates) + ":/certs", "-v", str(ROOT / "tests/docker") + ":/fixtures:ro",
        PYTHON_IMAGE, "/fixtures/create-certificates.sh"])
    secret_data = {
        "postgres_database": DATABASE, "postgres_user": "oryx_mem0_bootstrap",
        "postgres_password": values["admin"], "postgres_app_password": values["app"],
        "adapter_database_url": "postgresql://oryx_mem0_app:" + values["app"]
            + "@postgres:5432/" + DATABASE + "?sslmode=disable&connect_timeout=3&application_name=oryx_mem0_integration",
        "adapter_client_bindings": json.dumps([{"workspace_id": WORKSPACE,
            "key_sha256": hashlib.sha256(values["client"].encode("ascii")).hexdigest()}]),
        "adapter_cursor_secret": values["cursor"], "adapter_llm_api_key": values["model"],
        "adapter_embedding_api_key": values["model"], "model_control": values["control"],
    }
    paths = {}
    for name, content in secret_data.items():
        path = directory / name
        path.write_text(content, encoding="utf-8")
        paths[name] = str(path).replace("\\", "/")
    environment = {name.upper() + "_FILE": path for name, path in paths.items() if name != "model_control"}
    environment.update({"SOURCE_MANIFEST_SHA256": source_digest, "T049_IMAGE": image, "ORYX_MEM0_PORT": "0",
        "ADAPTER_LLM_BASE_URL": "https://models:9443/v1",
        "ADAPTER_LLM_MODEL": LLM_MODEL if real_models else "fixture-llm",
        "ADAPTER_EMBEDDING_BASE_URL": "https://models:9443/v1",
        "ADAPTER_EMBEDDING_MODEL": EMBEDDING_MODEL if real_models else "fixture-embedding",
        "ADAPTER_EMBEDDING_DIMENSIONS": EMBEDDING_DIMENSIONS if real_models else "2",
        "ADAPTER_ALLOWED_ORIGINS": '["https://models:9443"]',
        "T049_FIXTURES": str(ROOT / "tests/docker").replace("\\", "/"),
        "T049_CERTIFICATES": str(certificates).replace("\\", "/"),
        "T049_CA_FILE": str(certificates / "ca.pem").replace("\\", "/"),
        "ADAPTER_TLS_CERT_FILE": str(certificates / "server.pem").replace("\\", "/"),
        "ADAPTER_TLS_KEY_FILE": str(certificates / "server-key.pem").replace("\\", "/"),
        "T049_MODEL_CONTROL_FILE": paths["model_control"]})
    (directory / "compose.env").write_text("".join(name + "='" + value + "'\n" for name, value in environment.items()), encoding="utf-8")
    print(str(directory), flush=True)
    deployment.compose("config", "--quiet")
    deployment.compose("up", "-d", "--no-build", "--wait", "postgres", "models")
    version = deployment.sql("SELECT current_setting('server_version_num'); SELECT extversion FROM pg_extension WHERE extname='vector';")
    if version.split() != ["170011", "0.8.6"]:
        deployment.save_logs()
        raise RuntimeError("隔离PG版本或vector扩展不符合测试基线")
    deployment.sql("COMMENT ON DATABASE " + DATABASE + " IS 'ORYXOS_DISPOSABLE_TEST_DATABASE';", admin=True)
    deployment.compose("up", "-d", "--no-build", "--wait", "adapter")
    state["ports"] = deployment.ports()
    (directory / "state.json").write_text(json.dumps(state), encoding="utf-8")
    return directory


def main():
    parser = argparse.ArgumentParser(description="T049显式隔离部署，不自动读取生产.env或模型凭证")
    parser.add_argument("action", choices=("setup", "stop", "logs", "use-image"))
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--image")
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--real-models", action="store_true",
                        help="部署真实本地模型（Ollama qwen2.5:7b-instruct + bge-m3），不使用合成对端")
    args = parser.parse_args()
    if args.action == "setup":
        setup(args.docker, args.image, real_models=args.real_models)
    else:
        deployment = Deployment(args.directory, args.docker)
        if args.action == "use-image":
            deployment.use_image(args.image)
        else:
            deployment.stop() if args.action == "stop" else deployment.save_logs()


if __name__ == "__main__":
    main()
