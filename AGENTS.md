# 仓库协作约定

- 这是独立的 Kizuna's Meridian Human UI Addon，客户端同时依赖 Human Addon 和 Meridian UI Addon。人体专属内观、周天光迹与模型表现放在本仓库。
- Meridian 的 Framework、Human、UI、Items、Vanilla-Damage、Vanilla-Healing、Human-UI、Progression 和 Kizuna-Players-Animation 在各自适用端均为整套必装模块；拆仓仅用于代码管理与职责划分。不得添加缺少某个 Addon 时的替代路径、降级、可选依赖判断或对应测试/smoke；缺少必需接线应明确报错。运行时必装不意味着跨越客户端/服务端边界或让底层源码依赖具体人体实现。
- Human 保留权威人体构型与已有模型资源；本包直接使用 Human 的经脉 ID、模型和 UI 的 Java 扩展点，不为本地表现向 Framework 增加通信协议。
- 仅提供客户端模块。测试或演示可以装配 Human 服务端创建样例，生产 JAR 不依赖或内嵌服务端计算，不保存身体账本。
- 简体中文沟通、文档和函数内部注释，英文公开标识符。代码按读取、校验、计算、结果顺序展开，简单查找可用 Stream；保持清晰直白。
- 新增和修改的方法、构造器都应有内部中文意图注释，解释业务原因，避免逐行翻译。
- 光迹仅装饰真实运输；不能凭坐标新增连接、入账、开脉或改变服务端判定。
- 跨端身体通信仍复用框架 Protobuf；本地客户端回调直接使用 Java，不复制协议或模型。
- 文档编辑源为 `docs/wiki/`，README 只作入口。交付运行构建、必要整体回归、客户端内观 smoke 和文档检查。
- 仅保留必要整体 Test；临时探针验证后清理，测试入口不进入发布 JAR。
- 修改使用 patch/Edit，路径用双引号。保留用户修改，提交、推送、远端建仓分别依用户授权执行。
- PolyForm Noncommercial 1.0.0；第三方依赖保留各自许可，未发布产物不编造 Maven 坐标。
