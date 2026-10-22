# 第三方说明

PolyForm Noncommercial 1.0.0 仅适用于本项目有权许可的原创部分。

| 组件 | 使用与许可 |
|---|---|
| Meridian Human Addon、Meridian UI Addon、Meridian Mod Framework | 同级独立仓库，PolyForm Noncommercial 1.0.0；外部依赖，不内嵌源码、模型或 JAR |
| Inventory UI Framework | 同级独立仓库，MIT；外部客户端依赖，不内嵌源码或 JAR |
| Gradle Wrapper 8.8 | Apache License 2.0；从 Human 仓库复制启动脚本、Wrapper JAR 与分发校验和，保留原版权 |
| Fabric Loader 0.16.10 / Fabric API 0.92.3+1.20.1 / Loom 1.6 | Apache License 2.0；前两者由游戏安装提供，Loom 仅构建使用 |
| owo-lib 0.11.2+1.20 | MIT；由 UI 的运行环境提供，不内嵌 |
| Protocol Buffers 4.36.2 | BSD 3-Clause；复用框架生成消息与运行依赖，不复制协议 |
| JUnit 4.13.2 / Hamcrest Core 1.3 | Eclipse Public License 1.0 / BSD 3-Clause；仅测试依赖 |

Minecraft 属于 Mojang/Microsoft，本仓库不分发其实现或素材。人体几何、贴图仍从 Human Addon 读取，本仓仅包含独立设计的光迹坐标与客户端适配。
