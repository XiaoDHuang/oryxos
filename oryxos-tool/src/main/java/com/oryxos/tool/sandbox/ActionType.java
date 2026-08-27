package com.oryxos.tool.sandbox;

/**
 * 动作按技术方案的三类表达，避免未来白名单实现影响调用方签名.
 *
 * @author OryxOS Contributors
 */
public enum ActionType {
  /** 文件路径权限统一交给同一类动作校验. */
  FILE_ACCESS,
  /** 命令与MCP进程启动共用进程执行权限边界. */
  SHELL_EXEC,
  /** HTTP工具和通知发送共用目标地址权限边界. */
  HTTP_REQUEST
}
