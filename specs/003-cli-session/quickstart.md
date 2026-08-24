# Quickstart: CLI 与会话持久化验证指南

## 自动化验收(无网络无密钥)

```bash
mvn test -pl oryxos-storage -am
```

预期两个 harness 类全绿:
- `SessionManagerTest`:同一三元组两次 getOrCreate 同一 sessionId(幂等);channel/user/profile 任一不同则不同;换全新上下文按 id 取回历史完整(模拟重启)
- `SessionRepositoryTest`:真实 schema.sql 建表存读往返;messages_json 回读消息角色/内容/工具调用意图完整

## 全量门禁

```bash
mvn clean verify -Ddependency-check.skip=true   # 预期:10 reactor SUCCESS
```

## 人工项(课件第五部分)

```bash
java -jar oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar profile list   # 预期:秒回
java -jar oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar chat           # 预期:交互进入,/quit 退出;日志 Found 2 JPA repository interfaces(N>0)
```

- `oryxos chat` 真模型多轮对话走通(Demo 一对话版);
- 三种运行模式共享同一份 Profile 与会话存储,切换不丢数据;
- 12 个子命令 `--help` 均正常(Picocli 自带)。
