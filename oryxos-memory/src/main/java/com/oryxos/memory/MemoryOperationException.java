package com.oryxos.memory;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 远端记忆失败只携带固定分类与操作编号，不接受服务端任意错误文本.
 *
 * @author OryxOS Contributors
 */
public final class MemoryOperationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 公开分类与Java契约保持一一对应，调用方不得根据任意字符串猜测状态. */
  public enum Code {
    /** 启动参数或必需安全接线无效. */
    MEMORY_INVALID_CONFIG,
    /** 最终目标或调用身份不获许可. */
    MEMORY_ACCESS_DENIED,
    /** 响应无法按固定协议验证. */
    MEMORY_PROTOCOL_ERROR,
    /** 远端服务暂时不可用. */
    MEMORY_SERVICE_FAILURE,
    /** 只读或确定未派发的操作超时. */
    MEMORY_TIMEOUT,
    /** 保存可能生效但没有取得可信持久终态. */
    MEMORY_OUTCOME_UNKNOWN,
    /** 基线revision或所有者检查冲突. */
    MEMORY_WRITE_CONFLICT,
    /** 旧版本或原始输入未能完整保全. */
    MEMORY_HISTORY_FAILURE
  }

  private final Code code;
  private final UUID operationId;

  /** 不需要操作编号的确定失败使用此入口. */
  public MemoryOperationException(Code code) {
    this(code, null);
  }

  /**
   * 已生成逻辑操作编号后使用此入口，异常类型不接受任意消息或cause.
   *
   * @param code 固定失败分类
   * @param operationId 可追溯操作编号，结果未知时必填
   */
  public MemoryOperationException(Code code, UUID operationId) {
    super(message(code, operationId), null, false, false);
    this.code = Objects.requireNonNull(code, "记忆错误分类不能为空");
    this.operationId = operationId;
  }

  /** 返回固定失败分类，不从消息反向解析. */
  public Code code() {
    return code;
  }

  /** 返回已生成的逻辑操作编号，未派发前的确定失败允许为空. */
  public Optional<UUID> operationId() {
    return Optional.ofNullable(operationId);
  }

  /** 仅此固定文本允许映射到工具失败与审计字段. */
  public String safeMessage() {
    return getMessage();
  }

  private static String message(Code code, UUID operationId) {
    Code required = Objects.requireNonNull(code, "记忆错误分类不能为空");
    if (required == Code.MEMORY_OUTCOME_UNKNOWN && operationId == null) {
      throw new IllegalArgumentException("记忆结果不确定时必须携带操作编号");
    }
    String text = required.name() + "：" + label(required);
    return operationId == null ? text : text + "；operationId=" + operationId;
  }

  private static String label(Code code) {
    return switch (code) {
      case MEMORY_INVALID_CONFIG -> "记忆配置无效";
      case MEMORY_ACCESS_DENIED -> "记忆访问被拒绝";
      case MEMORY_PROTOCOL_ERROR -> "记忆服务响应无效";
      case MEMORY_SERVICE_FAILURE -> "记忆服务暂不可用";
      case MEMORY_TIMEOUT -> "记忆操作超时";
      case MEMORY_OUTCOME_UNKNOWN -> "记忆保存结果不确定，请勿重复保存";
      case MEMORY_WRITE_CONFLICT -> "记忆写入冲突";
      case MEMORY_HISTORY_FAILURE -> "记忆历史保全失败";
    };
  }
}
