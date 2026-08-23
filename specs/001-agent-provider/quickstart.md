# Quickstart: Agent Provider 验证指南

## 前置

- JDK 21 + Maven;仓库根目录。
- (仅冒烟需要)`export DEEPSEEK_API_KEY=<真实 key>`(Windows PowerShell:`$env:DEEPSEEK_API_KEY="..."`)。

## 日常验证(单测,无网络无密钥)

```bash
mvn test -pl oryxos-core,oryxos-provider,oryxos-storage -am
```

预期:4 个单测类全绿——
`ProfileLoaderTest`(全字段解析/未知 provider 报错/坏文件不阻断/`${ENV}` 占位)、
`ProviderServiceTest`(双 provider 不串台/未知名抛异常/成败均落审计/自动执行关闭)、
`ToolSchemaAdapterTest`(字段一一对齐/只翻译)、
`LlmCallRepositoryTest`(手工脚本建表能存能读,`success`/`error_message` 列真实存在)。

## 全量门禁

```bash
mvn clean verify -Ddependency-check.skip=true
```

预期:BUILD SUCCESS(Spotless/P3C/Checkstyle/SpotBugs 全过)。

## 真机冒烟(手动,CI 跳过)

```bash
mvn test -pl oryxos-provider -am -Dtest=ProviderSmokeIT -DexcludedGroups=
```

（`*IT` 命名默认不进 surefire 运行集、且父 pom 默认 `excludedGroups=integration`,所以用 `-Dtest` 点名 + 清空排除组。)

预期:`ProviderSmokeIT` 通过——真调一次模型拿到非空响应,且 `llm_calls` 多出一条 `success=1` 记录。

## 无明文密钥自查

```bash
grep -rn "sk-" oryxos-*/src oryxos-boot/src/main/resources || echo "no plaintext key"
```

预期:搜不到真实 key(只有 `${...}` 占位)。
