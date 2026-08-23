package com.oryxos.core.react;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * ReAct 循环发起 LLM 调用所经过的端口. 之所以在 core 中保持为接口,是因为 Maven 模块 方向禁止 core 引用 provider 模块的具体类 {@code
 * ProviderService} —— 后者以完全相同的 签名实现本接口。
 *
 * @author OryxOS Contributors
 */
public interface LlmGateway {

  /**
   * 为给定的 profile/prompt 发起一次 LLM 调用,成功与失败都审计.
   *
   * @param sessionId {@code llm_calls} 的审计关联键
   * @param profile 选定 provider/model 的 agent profile
   * @param prompt 本轮组装好的 prompt
   * @return 原始模型响应,工具调用意图包含在内且未执行
   */
  ChatResponse chat(String sessionId, Profile profile, Prompt prompt);
}
