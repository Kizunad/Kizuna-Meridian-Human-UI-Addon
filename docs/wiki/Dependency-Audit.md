# 整套依赖审计结果

Framework、Human、UI、Items、Vanilla-Damage、Vanilla-Healing、Human-UI、Progression 与 Kizuna-Players-Animation 在适用端整套必装。拆仓用于管理代码与职责，运行时不提供缺包模式。源码按实际 API 调用声明依赖；客户端模块不要求在专服加载，底层库也不反向引用人体实现。

## 本轮处理

| 仓库 | 处理结果 |
|---|---|
| Framework | Wiki 和 README 统一必装原则；3D 内景标为尚未实现的独立规划。通用表现协议没有人体光迹字段。新增共享开发装配脚本 |
| Human | 动画、Damage、Healing 从 `suggests` 改为 `depends`；去掉 `withBodyUi`、`playerAnimationDir`。`HumanPlayerAnimation` 统一调度静止、打坐、行走和疾跑，直接调用动画服务端 API；`HumanFabric` 必须具备 UI 接收通道，不再跳过发送 |
| UI | 强制声明 Human 宿主、Human-UI 与动画；去掉 `humanAddonDir` 和动画开关。`BreathingCanvas`、`MeditationAvatar` 直接使用动画 API，缺动作资源报错。正式客户端固定 Fabric 宿主通道，不再自动切换公共路由；公共路由 `GatheringBridge` 必须提供聚气与开脉会话 |
| Items | 保持 Inventory UI 的 `modCompileOnly` 原样；没有缺模组判断。真实依赖关系见下节 |
| Vanilla-Damage | Human 与 Healing 为必需依赖；缺健康宿主、缺效果集成或同一命中无人处理时明确报错。删除无集成时直写伤害以及失败后继续原版的路径 |
| Vanilla-Healing | Human 为必需依赖，Damage 原有强依赖保留；`HealingBridge` 不接受空宿主列表，不再把缺宿主解释为未绑定实体 |
| Human-UI | 必需 Human 与 UI；唯一 `MeridianRouteProvider` 必须覆盖实际人体连接，无空提供者或通用寻路回退。保留正式完整装配内观 smoke，删除缺 Human-UI 的运行入口 |
| Players-Animation | 不反向依赖人体；提供直接调用的 `ServerPlayerAnimations` 和既有客户端 API。服务端播放通过唯一 Protobuf 同步本人及追踪者，缺必需接收通道报错 |
| Progression | common 与 client 固定装配；Human 适配身体内进度并在真实操作入口授权，UI 直接消费权威进度投影。Inventory UI 的统一路由负责阅读消息，不新增平行通道 |

九仓 `AGENTS.md` 均记录包含 Players-Animation、Progression 的必装名单。`isModLoaded`、反射调用、安装开关和 metadata `suggests` 已清除；命令补全的 `.suggests(...)` 与 Protobuf 的可选字段并非安装逻辑。

## 固定开发装配

Fabric 工程统一应用 Framework 的 `scripts/full-assembly.gradle`。同级九仓及 Inventory UI、BS3D 的客户端/common 服务端产物固定加入运行类路径，不再用属性决定安装哪些模组。BS3D 客户端提供其服务端必需的握手消息模块。源码 composite 与运行安装产物各司其职；Loom 开发重映射会去掉嵌套库，因此脚本显式加入其他仓库的纯 Java 服务端库，Items 使用 named dev 库。使用 `modLocalRuntime` / `localRuntime` 将整套装配限定在当前工程，不沿 composite 发布变体再次传递安装 JAR。同仓 Progression 的另一个适用端通过 `builtBy` 声明 remap 产物依赖；运行前检查安装产物，缺文件明确报错。

先构建各仓库的安装产物，再运行客户端或 GameTest。首次 UI/Human-UI 互相接线时，先构建 UI `:client:remapJar`，构建 Human-UI，再完成 UI 构建；不需要关闭必需依赖。具体命令见[构建、安装与验证](Integration.md)。

## Items 与 Inventory UI

Items 目前是普通服务端库，没有 `fabric.mod.json` 或独立 Fabric 模组 ID。它直接调用 BS3D 的物品与容器 API，当前没有直接引用 Inventory UI Java 类型。

BS3D 的 `VolumeServer` 实现 Inventory UI 的 `FabricServerModule`，通过 `ServerModuleRegistry` 接入公共宿主路由；BS3D 服务端 metadata 强制依赖 `kizuna_inventory_ui_server`。因此 Inventory UI 是 BS3D 路由运行所需，Items 间接使用该宿主能力。本轮保留 Items 的 `modCompileOnly`，不将其解释为运行时可缺少 Inventory UI，也不虚构 Items 的独立 Fabric 入口。

## 保留的业务状态

- 宿主明确返回未绑定身体的实体继续原版；空宿主列表不等于未绑定。
- 断线、玩家离开追踪及 NBT 载入阶段按生命周期处理，不跨世界保留迟到动作。
- 没有在途不画光迹，未解锁经脉隐藏几何，无玩家的只读样例使用资源皮肤。
- `bindGathering`、`bindOpening` 等本地可信宿主绑定 API 只供宿主显式调用；正式联网不会因缺通道自动改走本地绑定或公共路由。（UI 仓的标题画面 demo 已于 2026-10-22 退役。）
- 第三方内容注册的缺包诊断、通用随机部位伤害、空效果余额仍是合法内容或身体状态。

这些分支不检测九仓是否安装，不属于缺包降级，不应删除或改为安装错误。

## 验证范围

2026-10-20 先确认七仓密度基线与内观 smoke 通过，再完成残卷接线及以下完整装配验证。测试计数来自各仓 JUnit XML。

| 仓库 | 构建 | 整体回归 | 游戏验证 |
|---|---|---|---|
| Framework | 通过 | 160 项通过 | 无独立 GameTest |
| Human | 通过，聚气 demo 通过 | 25 项通过 | 1 项 GameTest 通过 |
| UI | 通过，demo 源集编译通过 | 8 项通过 | 由完整内观 smoke 覆盖 |
| Items | 通过 | 无长期测试入口 | 作为普通库加入完整运行类路径 |
| Vanilla-Damage | 通过 | 4 项通过 | 3 项 GameTest 通过 |
| Vanilla-Healing | 通过 | 2 项通过 | 默认 4 项、配置变体 4 项 GameTest 全通过 |
| Human-UI | 通过，smoke 源集编译通过 | 2 项通过 | 完整内观 smoke 通过 |
| Players-Animation | 通过 | 30 项通过 | 真实客户端加载及服务端 GameTest 装配通过 |
| Progression | 通过 | 13 项通过 | 1 项 GameTest；真实整合服完整阅读闭环通过 |
| Inventory UI Framework | 通过 | 87 项通过 | 完整整合服握手、窗口授权与重连通过 |
| BS3D | 通过 | 100 项通过 | common 与 client 同时加载，必需消息模块握手通过 |

Meridian 九仓共 244 项整体回归；加 Inventory UI 与 BS3D 为 431 项，均无失败或跳过。13 项 GameTest 通过（含 Healing 默认与配置两组）。Framework、Items、UI、Human-UI 和动画没有独立 GameTest 入口，不增加占位任务。

内观 smoke 通过 Xvfb 启动真实客户端和整合服，先输出 `PROGRESSION_JOURNEY_OK`：真实拾取、右键、取消、过早完成拒绝、完整加速阅读解锁、OP 重置、旧令牌拒绝、丢弃残卷、保存重启及真实死亡重生均通过。随后输出 `HUMAN_UI_SMOKE_OK`，验证正式 Human-UI 扩展、Human 模型、四条中焦连接光迹、人中交叉，以及移除在途后光迹消失。运行标记、卷轴截图和三张光迹截图位于忽略的 `client/build/smoke/`，不提交生成产物。

本轮修复了开发装配的依赖传递：整套 JAR 限定为 `modLocalRuntime` / `localRuntime`，避免 Healing 经 composite 再次引入自身产物。Healing 首次 GameTest 另命中了旧 Damage 的 Loom 重映射缓存，仍引用已删除的 `CommitResult.integrated()`；核对缓存与当前产物字节码后，仅删除该过期生成 JAR，由 Loom 重建，默认与配置变体均复跑通过。

## 2026-10-21 动画整合复验

以上 2026-10-20 计数保留为历史记录。本轮在九个原仓重新构建，JUnit XML 汇总如下，均无失败、错误或跳过；Inventory UI 与 BS3D 沿用本次完整装配的既有产物，没有重跑其独立回归。

| 仓库 | 整体回归 | 游戏验证 |
|---|---|---|
| Framework | 160 项通过 | 无独立 GameTest |
| Human | 33 项通过，聚气 demo 通过 | 3 项 GameTest 通过 |
| UI | 10 项通过 | 完整客户端装配通过 |
| Items | 无独立测试入口 | 普通库加入完整运行类路径 |
| Vanilla-Damage | 4 项通过 | 3 项 GameTest 通过 |
| Vanilla-Healing | 2 项通过 | 默认 5 项、配置变体 5 项 GameTest 通过 |
| Human-UI | 3 项通过，smoke 源集编译通过 | 真实开脉整体回归通过 |
| Players-Animation | 51 项通过 | 单机与双真实 Windows 客户端通过 |
| Progression | 13 项通过 | 1 项 GameTest 通过 |

Meridian 九仓共 276 项整体回归，17 项 GameTest 通过（含 Healing 两组）。Healing 新增必要整体回归，保护工作线程构造生物初始满血、初始化拦截标志复位、运行期异步查询拒绝及绑定后的权威 HP 投影。

动画当前工作区已整合双体型骨架及 walk/run/meditate 等八段资源，并随 JAR 打包。WN 在无外置补丁资源包的完整装配中通过正式吐纳、第三人称打坐、实际移动结束会话和开脉附着检查；开脉流程另通过正常完成、预警、中断、OP 反馈、三视图和减少动态配置。Human 回归覆盖撤权及移动结束吐纳后的占用释放、迟到输入拒绝和邻居状态保留。

双客户端分别使用 default、slim，完成本人及追踪者同步、原始 request_id/start_tick、重复包不重启、远处排除、卸载清理、重入恢复进度、换维度、实体替换重生、断线清理、重连无旧动作及新请求播放。服务端输出 `NETWORK_ANIMATION_ALL_PASS`，两端写入通过文件并正常退出。逐帧关节及挂点视觉校准、第一人称和装备适配仍待单独验收。

## 2026-10-22 标题画面 demo 退役

UI 仓的 demo 源集（`runDemo` 及 `uiSmoke`、`gatheringSmoke`、`responsiveSmoke`）连同其专用入口、内存宿主、运行配置、`-P` 参数、仅供 demo 的 Human composite 引用和生产代码中的 `meridian.ui.gatheringSmoke` 测试开关一并删除。原因是修炼入口与内部层由 Progression 服务端授权，标题画面没有服务器无法打开，且客户端不得自行解锁。上表中“demo 源集编译通过”等为历史记录。原检查点改由本仓整合服 smoke 的 `BodyViewerJourney`、`ResponsiveJourney`、`ManualBreathingJourney` 在真实玩家身体上执行，守恒与续窗由 Human、Framework 的逻辑回归覆盖；体表剪影与原版玩家尺寸比对在退役前已失败，暂未补回，单独记为未完成项。Human 自身的 `./gradlew demo` 不受影响。
