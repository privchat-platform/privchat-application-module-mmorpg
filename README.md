# privchat-application-module-mmorpg

MMORPG 业务模块(**尚未实现**,当前只有协议部分)。

按 `MMO_ARCHITECTURE_SPEC`,本模块是 MMO 核心玩法与战斗的唯一 owner,
同时**自持**自己的协议与错误码:

```text
protocol/
  schemas/      FlatBuffers .fbs(场景已有,战斗待补)
  fixtures/     跨语言 golden fixtures 与负向样本
  scripts/      生成与语义校验
src/            业务实现(待建)
```

## 为什么协议放在这里,而不是独立仓

协议的 owner 就是本模块。独立成仓会长出一个无人明确负责的中间物;
只有当它需要独立发布周期、被多个不同游戏消费时,拆分才有意义。

生成物的去向:

| 目标 | 用途 |
|---|---|
| Kotlin | 本模块服务端自用 |
| C++ header artifact | 带版本发布给 Menghuan 的**游戏专用** GDExtension |

## 错误码

本模块自持 **21400-21499**(战斗)与 **21600-21699**(场景)。
`privchat-protocol` 的 registry 只**保留**这两个段位、不登记具体码 ——
核心通信层不需要认识 `SceneNotFound` 这类玩法概念,
`TransferResponse.code` 本来就是整数,SDK 与客户端原样透传即可。

## 与 privchat 各层的边界

`privchat-sdk` / `privchat-sdk-c-api` / `privchat-godot` / `privchat-godot-demo`
**只搬运二进制**,不认识这里的任何 schema。
