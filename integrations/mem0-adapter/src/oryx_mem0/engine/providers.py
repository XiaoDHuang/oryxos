"""模型协议只产生已校验事实、动作和向量，不替SDK执行存储。"""

from dataclasses import dataclass, field
import ipaddress
import http.client
import queue
import re
import socket
import ssl
import threading
from urllib.parse import urlsplit, urlunsplit

from oryx_mem0.audit.calls import CallValue
from oryx_mem0.engine.staging import Code, EngineError, MAX_ACTIONS, MAX_FACTS, MAX_IO_BYTES, json_bytes, strict_json, text_value, vector_value


def normalized_url(value, origin_only=False):
    try:
        if (not isinstance(value, str) or not value or len(value) > 4096
                or any(ord(char) <= 32 for char in value) or "\\" in value or "?" in value or "#" in value):
            raise ValueError()
        parts = urlsplit(value)
        if parts.scheme != "https" or parts.username is not None or parts.password is not None or not parts.hostname:
            raise ValueError()
        if origin_only and parts.path not in {"", "/"}:
            raise ValueError()
        host = parts.hostname
        if "%" in host:
            raise ValueError()
        try:
            parsed_ip = ipaddress.ip_address(host)
            host = "[" + parsed_ip.compressed + "]" if parsed_ip.version == 6 else str(parsed_ip)
        except ValueError:
            host = host.encode("idna").decode("ascii").lower().removesuffix(".")
            if len(host) > 253 or any(not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) for label in host.split(".")):
                raise ValueError()
        port = parts.port
        if port is not None and not 1 <= port <= 65535:
            raise ValueError()
        authority = host + (f":{port}" if port not in {None, 443} else "")
        return urlunsplit(("https", authority, "" if origin_only else parts.path.rstrip("/"), "", ""))
    except (ValueError, UnicodeError):
        raise EngineError(Code.INVALID_CONFIG) from None


@dataclass(frozen=True)
class Endpoint:
    base_url: str
    model: str
    api_key: str = field(repr=False)
    allowed_origins: tuple[str, ...]

    def __post_init__(self):
        if (not isinstance(self.allowed_origins, (list, tuple)) or not self.allowed_origins
                or not isinstance(self.model, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/:-]{0,255}", self.model)
                or not isinstance(self.api_key, str) or not 1 <= len(self.api_key) <= 16384
                or any(not 33 <= ord(char) <= 126 for char in self.api_key)):
            raise EngineError(Code.INVALID_CONFIG)
        base = normalized_url(self.base_url)
        allowed = tuple(normalized_url(origin, origin_only=True) for origin in self.allowed_origins)
        origin = normalized_url(urlunsplit((*urlsplit(base)[:2], "", "", "")), origin_only=True)
        if len(set(allowed)) != len(allowed) or origin not in allowed:
            raise EngineError(Code.ACCESS_DENIED)
        object.__setattr__(self, "base_url", base)
        object.__setattr__(self, "allowed_origins", allowed)

    def target(self, route):
        if not isinstance(route, str) or route not in {"chat/completions", "embeddings"}:
            raise EngineError(Code.ACCESS_DENIED)
        target = self.base_url + "/" + route
        origin = normalized_url(urlunsplit((*urlsplit(target)[:2], "", "", "")), origin_only=True)
        if origin not in self.allowed_origins:
            raise EngineError(Code.ACCESS_DENIED)
        return target


def usage_value(context, value):
    if value is None:
        return None
    if not isinstance(value, dict):
        context.fail(Code.INVALID_RESULT)
    result = {name: value.get(name) for name in ("prompt_tokens", "completion_tokens", "total_tokens")}
    if any(number is not None and (type(number) is not int or not 0 <= number <= 9223372036854775807) for number in result.values()):
        context.fail(Code.INVALID_RESULT)
    if all(number is not None for number in result.values()) and result["total_tokens"] != result["prompt_tokens"] + result["completion_tokens"]:
        context.fail(Code.INVALID_RESULT)
    return result if any(number is not None for number in result.values()) else None


def _resolve(host, port, context):
    result = queue.Queue(maxsize=1)

    def lookup():
        try:
            result.put((socket.getaddrinfo(host, port, 0, socket.SOCK_STREAM), None))
        except Exception:
            result.put((None, Code.SERVICE_FAILURE))

    # 解析助手不接触请求正文；超期后即便解析完成，也不会继续建立连接。
    thread = threading.Thread(target=lookup, name="oryx-memory-dns", daemon=True)
    thread.start()
    try:
        addresses, error = result.get(timeout=context.check())
    except queue.Empty:
        context.fail(Code.DEADLINE)
    context.check()
    if error is not None or not addresses:
        context.fail(Code.SERVICE_FAILURE)
    return addresses


class _BoundedHttpsConnection(http.client.HTTPSConnection):
    def __init__(self, host, port, context, tls):
        self.state = context
        self._active_socket = None
        self._cancelled = False
        self._socket_lock = threading.Lock()
        super().__init__(host, port, timeout=context.check(), context=tls)
        self._create_connection = self._connect_deadline

    def _register(self, sock):
        with self._socket_lock:
            if self._cancelled:
                sock.close()
                raise TimeoutError()
            self._active_socket = sock

    def _connect_deadline(self, address, timeout=None, source_address=None):
        host, port = address
        for family, kind, protocol, _, location in _resolve(host, port, self.state):
            self.state.check()
            sock = socket.socket(family, kind, protocol)
            try:
                self._register(sock)
                sock.settimeout(self.state.check())
                if source_address is not None:
                    sock.bind(source_address)
                sock.connect(location)
                sock.settimeout(self.state.check())
                return sock
            except EngineError:
                sock.close()
                raise
            except OSError:
                sock.close()
        self.state.check()
        raise OSError("模型连接失败")

    def connect(self):
        super().connect()
        self._register(self.sock)
        self.sock.settimeout(self.state.check())

    def send(self, data):
        self.state.check()
        super().send(data)
        self.state.check()

    def abort(self):
        with self._socket_lock:
            self._cancelled = True
            sock = self._active_socket
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass
        try:
            super().close()
        except OSError:
            pass


class HttpsTransport:
    def post(self, endpoint, route, body, context):
        context.check()
        if context.active_call is None:
            context.fail(Code.AUDIT_FAILURE)
        if context.scope != "ARCHIVAL":
            context.fail(Code.ACCESS_DENIED)
        if type(body) is not bytes:
            context.fail(Code.INVALID_RESULT)
        if len(body) > MAX_IO_BYTES:
            context.fail(Code.LIMIT_EXCEEDED)
        target = urlsplit(endpoint.target(route))
        tls = ssl.create_default_context()
        if tls.verify_mode != ssl.CERT_REQUIRED or not tls.check_hostname:
            context.fail(Code.INVALID_CONFIG)
        connection = _BoundedHttpsConnection(target.hostname, target.port or 443, context, tls)
        expired = threading.Event()

        def expire():
            expired.set()
            connection.abort()

        def check():
            if expired.is_set():
                context.fail(Code.DEADLINE)
            return context.check()

        timer = threading.Timer(context.check(), expire)
        timer.daemon = True
        timer.start()
        try:
            check()
            # 标准库直连固定目标，不读取代理环境，也不自动跟随Location。
            connection.request("POST", target.path, body=body,
                               headers={"Authorization": "Bearer " + endpoint.api_key,
                                        "Content-Type": "application/json", "Accept-Encoding": "identity"})
            check()
            with connection.getresponse() as response:
                if response.status != 200:
                    context.fail(Code.SERVICE_FAILURE)
                if response.getheader("Content-Encoding", "identity").lower() != "identity":
                    context.fail(Code.INVALID_RESULT)
                if response.getheader("Content-Type", "").split(";")[0].strip().lower() != "application/json":
                    context.fail(Code.INVALID_RESULT)
                length_header = response.getheader("Content-Length")
                transfer = response.getheader("Transfer-Encoding")
                if transfer is not None and (transfer.lower() != "chunked" or length_header is not None):
                    context.fail(Code.INVALID_RESULT)
                declared_length = None
                if length_header is not None:
                    if len(length_header) > 20 or not re.fullmatch(r"[0-9]+", length_header):
                        context.fail(Code.INVALID_RESULT)
                    declared_length = int(length_header)
                    if declared_length > MAX_IO_BYTES:
                        context.fail(Code.LIMIT_EXCEEDED)
                data = bytearray()
                while True:
                    check()
                    chunk = response.read(min(65536, MAX_IO_BYTES + 1 - len(data)))
                    check()
                    if not chunk:
                        break
                    data.extend(chunk)
                    if len(data) > MAX_IO_BYTES:
                        context.fail(Code.LIMIT_EXCEEDED)
                if declared_length is not None and len(data) != declared_length:
                    context.fail(Code.INVALID_RESULT)
                return bytes(data)
        except EngineError:
            raise
        except TimeoutError:
            context.fail(Code.DEADLINE)
        except Exception:
            check()
            context.fail(Code.SERVICE_FAILURE)
        finally:
            timer.cancel()
            connection.abort()


class AuditedLlm:
    def __init__(self, context, endpoint, auditor, transport=None):
        self.context = context
        self.endpoint = endpoint
        self.auditor = auditor
        self.transport = transport or HttpsTransport()
        self.facts = None
        self.actions = None
        self._calls = 0
        if auditor.context is not context:
            context.fail(Code.INVALID_CONFIG)
        auditor.protect(endpoint.api_key)

    def generate_response(self, messages, response_format=None):
        state = self.context
        state.check()
        if state.scope != "ARCHIVAL":
            state.fail(Code.ACCESS_DENIED)
        if self._calls >= 2 or self._calls == 1 and not self.facts:
            state.fail(Code.INVALID_RESULT)
        if response_format not in (None, {"type": "json_object"}):
            state.fail(Code.INVALID_RESULT)
        if not isinstance(messages, list) or not messages:
            state.fail(Code.INVALID_RESULT)
        for message in messages:
            if (not isinstance(message, dict) or set(message) != {"role", "content"}
                    or not isinstance(message["role"], str) or message["role"] not in {"system", "user"}
                    or not isinstance(message["content"], str)):
                state.fail(Code.INVALID_RESULT)
        phase = "FACT_EXTRACTION" if self._calls == 0 else "ACTION_SELECTION"
        self._calls += 1
        request = {"model": self.endpoint.model, "messages": messages, "response_format": {"type": "json_object"}}
        body = json_bytes(state, request)
        self.endpoint.target("chat/completions")

        def perform():
            raw = self.transport.post(self.endpoint, "chat/completions", body, state)
            response = strict_json(state, raw)
            if not isinstance(response, dict) or response.get("model", self.endpoint.model) != self.endpoint.model:
                state.fail(Code.INVALID_RESULT)
            choices = response.get("choices")
            if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict):
                state.fail(Code.INVALID_RESULT)
            choice = choices[0]
            if type(choice.get("index", 0)) is not int or choice.get("index", 0) != 0:
                state.fail(Code.INVALID_RESULT)
            message = choice.get("message")
            if (choice.get("finish_reason") != "stop" or not isinstance(message, dict)
                    or message.get("role") != "assistant" or message.get("refusal") or message.get("tool_calls")):
                state.fail(Code.INVALID_RESULT)
            content = message.get("content")
            if not isinstance(content, str):
                state.fail(Code.INVALID_RESULT)
            try:
                parsed = strict_json(state, content.encode("utf-8"))
            except UnicodeError:
                state.fail(Code.INVALID_RESULT)
            if phase == "FACT_EXTRACTION":
                parsed = self._facts(parsed)
            else:
                parsed = self._actions(parsed)
            json_bytes(state, parsed)
            return CallValue(parsed, {"content": parsed}, usage_value(state, response.get("usage")))

        result = self.auditor.invoke("LLM", phase, self.endpoint.model, request, perform)
        if phase == "FACT_EXTRACTION":
            self.facts = tuple(result["facts"])
        else:
            self.actions = tuple(dict(action) for action in result["memory"])
        return json_bytes(state, result).decode("utf-8")

    def _facts(self, value):
        state = self.context
        if not isinstance(value, dict) or set(value) != {"facts"} or not isinstance(value["facts"], list):
            state.fail(Code.INVALID_RESULT)
        if len(value["facts"]) > MAX_FACTS:
            state.fail(Code.LIMIT_EXCEEDED)
        for fact in value["facts"]:
            text_value(state, fact)
        return value

    def _actions(self, value):
        state = self.context
        if not isinstance(value, dict) or set(value) != {"memory"} or not isinstance(value["memory"], list) or not value["memory"]:
            state.fail(Code.INVALID_RESULT)
        if len(value["memory"]) > MAX_ACTIONS:
            state.fail(Code.LIMIT_EXCEEDED)
        for action in value["memory"]:
            if not isinstance(action, dict) or not {"event", "text"} <= action.keys() or action.keys() - {"id", "event", "text", "old_memory"}:
                state.fail(Code.INVALID_RESULT)
            if not isinstance(action["event"], str) or action["event"] not in {"ADD", "UPDATE", "DELETE", "NONE"}:
                state.fail(Code.INVALID_RESULT)
            text_value(state, action["text"])
            if action["event"] != "ADD" and (not isinstance(action.get("id"), str) or not re.fullmatch(r"0|[1-9][0-9]{0,3}", action["id"])):
                state.fail(Code.INVALID_RESULT)
            if action.get("old_memory") is not None:
                text_value(state, action["old_memory"])
        return value


class AuditedEmbedding:
    def __init__(self, context, endpoint, auditor, transport=None):
        self.context = context
        self.endpoint = endpoint
        self.auditor = auditor
        self.transport = transport or HttpsTransport()
        if auditor.context is not context:
            context.fail(Code.INVALID_CONFIG)
        auditor.protect(endpoint.api_key)

    def embed(self, text, memory_action="add"):
        state = self.context
        state.check()
        if state.scope != "ARCHIVAL":
            state.fail(Code.ACCESS_DENIED)
        text_value(state, text)
        if not isinstance(memory_action, str) or memory_action not in {"add", "update", "search"}:
            state.fail(Code.INVALID_RESULT)
        request = {"model": self.endpoint.model, "input": text, "encoding_format": "float"}
        body = json_bytes(state, request)
        self.endpoint.target("embeddings")

        def perform():
            response = strict_json(state, self.transport.post(self.endpoint, "embeddings", body, state))
            if not isinstance(response, dict) or response.get("model", self.endpoint.model) != self.endpoint.model:
                state.fail(Code.INVALID_RESULT)
            data = response.get("data")
            if (not isinstance(data, list) or len(data) != 1 or not isinstance(data[0], dict)
                    or type(data[0].get("index")) is not int or data[0]["index"] != 0):
                state.fail(Code.INVALID_RESULT)
            vector = vector_value(state, data[0].get("embedding"))
            return CallValue(list(vector), {"embedding": list(vector)}, usage_value(state, response.get("usage")))

        phase = "QUERY_EMBEDDING" if memory_action == "search" else "ARCHIVE_EMBEDDING"
        return self.auditor.invoke("EMBEDDING", phase, self.endpoint.model, request, perform)
