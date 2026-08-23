# Quickstart: ReAct 循环验证指南

## 日常验证(全单测,无网络无密钥)

```bash
mvn test -pl oryxos-core,oryxos-storage -am
```

预期 5 个 harness 类全绿:
- `ReActLoopTest`:一轮收尾 / 工具调用回填进下一轮 / **转满最大轮数强制停**(恰好 N 次调用)/ 逐轮累积
- `PromptBuilderTest`:四段顺序 / 超 N 截断 / system 末尾含当前日期时间
- `ToolExecutorTest`:成功审计 success=true / 失败 success=false 带原因且不吞 / 未知工具失败路径
- `AgentServiceTest`:执行期 ProfileContext 可取 / **抛异常 finally 也清掉** / 结束后 Session 持久化
- `ContextLoaderTest`:改文件下次 build 立即生效(无缓存)/ Skill 缺失报错 / Bootstrap 缺失 WARN

## 全量门禁

```bash
mvn clean verify -Ddependency-check.skip=true
```

预期:9 模块 BUILD SUCCESS(含 16 节全部测试回归)。

## 人工项(harness 覆盖不到)

- 真模型跑 Demo 一对话版(问天气→调 http_get→给建议)——需 18 节 CLI 就位后进行;
- code review 确认循环未使用框架 Agent 封装。
