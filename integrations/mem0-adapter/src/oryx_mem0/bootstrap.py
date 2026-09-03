"""组件启动入口不在模块导入时加载SDK。"""

from dataclasses import dataclass
import importlib
import os
import sys
from tempfile import TemporaryFile

from oryx_mem0.settings import ConfigError, Settings


@dataclass(frozen=True, repr=False)
class Runtime:
    settings: Settings
    tls_context: object
    staged_memory_type: type


def bootstrap(environ=None):
    settings = Settings.load(environ)
    tls_context = settings.tls_context()
    if any(name == "mem0" or name.startswith("mem0.") or name == "oryx_mem0.engine.staged_memory" for name in sys.modules):
        # 已缓存的SDK可能读过旧环境；禁止热重配掩盖导入期副作用。
        raise ConfigError("SDK_IMPORT")
    try:
        config_path = settings.mem0_dir / "config.json"
        # SDK会以普通open创建配置，预先阻断悬空链接借导入写到目录外。
        if config_path.is_symlink() or (config_path.exists() and not config_path.is_file()):
            raise ConfigError("MEM0_DIR")
        with TemporaryFile(dir=settings.mem0_dir):
            pass
    except OSError:
        raise ConfigError("MEM0_DIR") from None
    os.environ["MEM0_TELEMETRY"] = "false"
    os.environ["MEM0_DIR"] = str(settings.mem0_dir)
    try:
        module = importlib.import_module("oryx_mem0.engine.staged_memory")
        return Runtime(settings, tls_context, module.StagedMemory)
    except Exception:
        raise ConfigError("SDK_IMPORT") from None
