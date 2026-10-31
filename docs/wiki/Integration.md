# 构建、安装与验证

检视窗口的信息区首行显示「年龄 / 寿元：18.0 年 / 62.0 年」等只读数据，未选中或已选中对象时都保留。年龄来自权威 `vitality.age_seconds`，剩余寿元为上限减年龄和额外支出，最低为零；按一年 365.25 天换算并保留一位小数，不在客户端累计时间。玩家死亡重生后收到同一身体的新版本，初值一年费用会使 62.0 年变为 61.0 年。

`env -u DISPLAY -u WAYLAND_DISPLAY LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "-screen 0 1920x1100x24" ./gradlew :client:runSmoke -PrevivalSmoke=true` 在真实整合服执行死亡、客户端重生与检视刷新，检查实际组件文字和 GUI 缩放 2、3，并输出 `REVIVAL_UI_OK` 与 `revival-*.png`。验收代码只在 smoke 源集中运行。

Players-Animation 的必装 JAR 提供双体型骨架与打坐资源，验收装配不启用外置动画资源补丁。吐纳预览按当前玩家体型采样骨矩阵，本包开脉光迹继续通过同一挂点附着；外世界动画由 Human 的统一调度点选择。实际位移先正式结束权威吐纳，再切换走路或疾跑，客户端光迹不参与这一判定。

2026-10-21 在原仓完成 Java 17 构建、3 项整体回归及 smoke 源集编译，完整装配重建到 `D:/Minecraft/Meridian-Animation-Integrated-20261021`。Windows 单机通过真实走跑停止、正式吐纳、第三人称打坐、移动结束会话和开脉骨架附着；开脉整体回归通过真实 15 秒流程、三视图、预警、中断、正常完成、OP 反馈、减少动态及零粒子设置。两个真实客户端分别使用 default、slim，完成追踪、补发去重、卸载重入、换维度、实体替换重生、断线清理、重连无旧动作和新请求播放，最终标记 `NETWORK_ANIMATION_ALL_PASS` 与双方客户端通过文件齐全。日志、截图及装配哈希保存在该目录的 `evidence/20261021/`；临时动画诊断入口已在验收后归档清理。逐帧关节和持物挂点校准、第一人称及装备适配仍按动画仓库记录单独验收。

开脉表现新增必需 `meridian_ui_opening_effects` 接线。`HumanOpeningEffects` 从 Human 原经脉网格提取沿解剖方向的路线，为打坐附着提供骨架混合权重，读取 Human shared 中 `HumanOpeningConditions` 的全身／局部维持阈值。UI 的 `OpeningOverlay` 统一渲染进度分界、流光、预警、结果和声音，三个视图共用权威快照及持久显示设置。没有人体坐标进入 Framework，也不依赖 Human 服务端计算类。

开脉单机回归使用 `./gradlew :client:runSmoke -PopeningEffectsSmoke=true`。这是测试入口选择，完整模组装配不变。它创建独立世界，只用 OP 解锁和补量，然后通过正式吐纳按钮及画布输入维持真实 15 秒开脉，检查三视图、自然完成、接近维持阈值、暂停回退、OP 完成及减少动态设置。结果标记 `OPENING_EFFECTS_JOURNEY_OK`，关键帧为 `screenshots/opening-*.png`；测试代码不进入生产 JAR。打坐验收要求动画模型及 `meditate` 资源齐全。

验收截图等待 UI 收到对应权威状态，不能以服务端命令返回代替客户端实际显示。暂停额外检测 3D 画布内的暗红像素；活动期间验证减少动态和零粒子上限，完成后恢复默认显示设置。WN 使用独立目录 `D:/Minecraft/Meridian-Opening-Effects-20261020`，自动验收与手动游玩分别使用 `smoke/`、`play/` 存档。运行依赖复制后校验 SHA-256，开发源集验收 JAR 不进入手动游玩装配。

必需接线 `meridian_ui_scene` 现提供窗口标题、锁定提示及组件。`HumanCultivationPanel` 通过修炼页独立“中焦”弹窗展示周天、自动复选框、动员、突破、G推力与空格冲关；普通经脉内观只保留选脉及开脉。未使用中焦卷时菜单隐藏“中焦”，直接打开也被拒绝；真实右键后立即显示入口并可使用自动，无须看完，没有八次练习门槛。吐纳同样在右键使用聚气卷后显示。中文锁定原因仍由人体扩展提供，窗口工厂和服务端保留各自校验。

中焦控件分左右两栏。左栏依次为推力条（两条竖线是安全带）、本批/密度/圈数/关口、整圈或突破进度、外壁与服务端原因，以及最多三行的直白说明；底行提示动员另耗脾耐力和精力、关窗会停止。右栏手动时是放大的关口指示盘：白色固定圈是目标，红/绿收缩圈只在呼气且服务端已武装关口时出现，到目标时刻恰与白圈重合，过点后缩进白圈内表示按晚。盘下大字依次为“未运转 / 真元在路上 / 吸气中（N.N 秒后呼气）/ 等变绿 / 按空格！/ 错过了 / 命中！/ 撞回！/ 四关已过”；只有“按空格！”和“命中！”是绿色。小字给出当前关口名和序号，如“人中 · 1/4”“中焦 · 2/4”：大肠经入胃的跨侧连接交于人中，脾经入肺的连接回到中焦，按本圈实际线路排序。三维模型上的人中/中焦标记使用同一语义的实线圆环。右栏勾选自动时改为脑耐力条、红色停止线、“不耗精力”和效率；脑耐力条只在服务端报告自动运行时绘制（停下后存档值会过时），未运行时写“开始后显示实时脑耐力”。客户端收到的是身体快照中持久化的 `human:cultivation`，从未运转过的身体还没有该进度，此时说明只写规则（“低于停止线自动停”“效率低于手动”），不显示数字，首次开始周天后即带服务端数值。停止线、效率、呼吸周期、收缩时长与命中半窗都来自 `CultivationState` 的服务端字段，客户端预测只决定颜色，结算仍以服务器收到按键的时刻为准。UI管理通用窗口生命周期，人体含义与路线留在本仓，客户端不引用服务端计算。

完整 smoke 增加聚气和中焦两卷真实右键阅读及独立解锁、自动吐纳复选框真实点击和80%停止。仅使用札记时菜单只有经脉；右键聚气卷后新增吐纳，右键中焦卷后新增中焦，无须完成播放。逐阶段检查实际菜单按钮与权限目录一致、没有“固定／取消固定”按钮，未解锁及重置后直接打开均被拒绝，截图为 `cultivation-menu-notes.png`、`cultivation-menu-gathering.png`、`cultivation-menu-all.png`。两卷阅读各截 `gathering-reading.png`、`middle-jiao-reading.png`。中焦阶段截 `middle-jiao-unlocked.png`、`middle-jiao-automatic.png`，红/绿关口截图前统计控件区域的纯红、纯绿像素，红态必须只有红圈、绿态必须出现绿圈，否则继续等下一关（最多五圈）。突破前先停周天，等脑耐力自然恢复到 97%，再用沉淀阶段同一条 OP 命令补回候选内腔后自动启动，避免自动周天按 80% 规则中途停机；突破提前停止时失败信息附带候选、外壁、运转与脑耐力，便于区分原因。自动周天只耗脑耐力；主动动员仍另耗脾耐力与精力。自动效果与突破状态以服务端身体为准。

UI 仓的标题画面 demo 已于 2026-10-22 退役，其检查点改在本 smoke 的真实玩家身体上执行，保存重启后依次运行：
- `BodyViewerJourney`（原 `uiSmoke`）：五层显示与 GPU 实际出图，骨架/肌肉点击，背侧肌肉聚焦转向，全部可见器官与当前境界可见经脉的聚焦、真实点击及信息，服务端推送刷新保留视角与选择，中键回到全身，多层勾选、隐藏清除选择、全层关闭不可选，眼球叠加点击，关窗清理。截图 `body-*.png`，标记 `BODY_VIEWER_JOURNEY_OK`。
- `ResponsiveJourney`（原 `responsiveSmoke`）：1440×1000（GUI 2/4）、960×720（GUI 3）、1920×1080（自动/GUI 2）下，身体与吐纳窗口的边界、互不覆盖、组件与选择保留、详情滚轮、心脏点击、吐纳控件命中、权威会话保持；首次开始必须先显示指引。截图 `responsive-*.png`，标记 `RESPONSIVE_JOURNEY_OK`。
- `ManualBreathingJourney`（原 `gatheringSmoke`）：检视窗口不含修炼操作、吐纳控件布局与悬浮明细、中键旋转、真实鼠标对准服务端粒子吸呼、吸满截断与呼空停止、吸呼音效播放与停止、精力消耗与真实摄入、关窗结束会话、独立重开与再次开始。截图 `breathing-*.png`，标记 `MANUAL_BREATHING_JOURNEY_OK`。

smoke 启动时删除运行目录中的吐纳指引已读偏好，按新玩家配置验收。手动吐纳失焦即停止，Windows 运行时需让游戏窗口保持前台。体表剪影与原版玩家模型尺寸比对没有移植：该比对在退役前已失败，作为未完成项单独跟踪。环境守恒与续窗由 Human、Framework 逻辑回归覆盖。总时限放宽到 40 分钟（48 000 tick）。

完整单机 smoke 在上述三段与自动吐纳之后、死亡重生前运行 `CultivationJourney`，通过正式 OP 命令跳过八脉等待和攒量，真实网络手动两圈八次冲关、验证提纯、脾沉淀和动员、先测一次中断不退燃料，再保持真实60秒突破成功。标记为 `CULTIVATION_JOURNEY_OK` / `cultivation-journey-ok.txt`，截图 `cultivation-manual.png` 和 `cultivation-success.png`。随后继续残卷重生回归和原 GPU 内观 smoke。测试源集不进入正式JAR。

2026-10-20 已在完整装配的真实单机整合服通过，三个标记 `CULTIVATION_JOURNEY_OK`、`PROGRESSION_JOURNEY_OK`、`HUMAN_UI_SMOKE_OK` 均出现。成功相变实际连续运行60模拟秒，没有用调试指令设置境界或成功进度；另测中断清零、不退已耗燃料。验收还定位并修复了活动同tick重复提交造成伤害版本冲突的问题。

默认工作区中，本仓与 Human、UI、Progression、Framework、Inventory UI 等独立仓库同级，位置由 Framework 的 `scripts/workspace.gradle` 按装配清单提供，单个仓库可用 `-Pkizuna.dir.<清单 id>=<目录>` 改指。产物面向 Java 25；Gradle 守护进程按 `gradle/gradle-daemon-jvm.properties` 使用 JDK 25（Gradle 9.8.0、Loom 1.19.3）。这些仓库都通过 composite build 组合实际源码，编译直接取上游普通 JAR（Minecraft 26.2 不混淆，无映射）并随本仓先行构建，不复制协议或模型。

编译依赖随组合构建先行构建，不再需要先在 UI 仓打 JAR。开发运行的完整装配里，未以源码组合的仓库仍按清单读取安装产物，需事先构建。在本仓验证：

```bash
./gradlew build --offline --console=plain --no-watch-fs
python3 "scripts/check_docs.py"
env -u DISPLAY -u WAYLAND_DISPLAY LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "-screen 0 1920x1100x24" ./gradlew :client:runSmoke --offline --console=plain --no-watch-fs
```

Linux 无显示环境统一清掉 `DISPLAY`、`WAYLAND_DISPLAY` 并用 Xvfb 加 Mesa 软件渲染（`LIBGL_ALWAYS_SOFTWARE=1`，llvmpipe）：26.2 客户端在 WSLg 有显示时会卡在 `glfwShowWindow`，Xvfb 下默认的 Mesa d3d12 驱动在退出时让 JVM 崩溃，Gradle 判为失败。屏幕取 1920x1100，与自适应检查最大的 1920×1080 窗口相符。离线命令要求依赖已缓存，首次构建可去掉 `--offline`。Windows 使用 `gradlew.bat`，图形桌面直接执行 `:client:runSmoke`，无需 Xvfb。开发运行统一应用 Framework 的 `scripts/full-assembly.gradle`，固定装配 Meridian 九仓（含 Progression 和 Players-Animation）及 Inventory UI、BS3D 的 client/common 安装产物，不提供安装开关。源码依赖位置与必装关系是不同概念。

本轮要求 UI checkout 已提供 `dev.kizuna.meridian.ui.api.MeridianRouteProvider`；组合构建直接编译 UI 当前源码。`local.meridian:*` 是 Framework 的本地替换键；Progression、Human、UI 与 Inventory UI 的替换键用各自工程的 group 与工程名，同样不是已发布的 Maven 坐标。

安装产物为 `client/build/libs/kizuna-meridian-human-ui-addon-client-0.1.0-SNAPSHOT.jar`。Meridian 各 Addon 按适用端整套安装；真实玩家由 Human Fabric 权威宿主提供身体快照。本包不内嵌任何依赖，`fabric.mod.json` 强制声明 `meridian_framework`、`meridian_human` 与 `meridian_ui`，UI 也强制声明 `meridian_human_ui`；Gradle 以组合工程的普通 JAR 编译 UI、Inventory UI 与 Progression，中焦面板的 `meridian_human:cultivation` 通道直接取用 Human Fabric 模组的 `HumanChannels.CULTIVATION`（字节通道类型来自 Framework Fabric 模组），人体展示包作为普通库直接依赖；共享脚本用 `modLocalRuntime` / `localRuntime` 按替换坐标或安装产物装配其他模组。开发装配不沿 composite 发布变体传递，避免其他工程重复加载当前工程的安装 JAR。缺包由 Loader 拒绝启动，客户端路线必须唯一装配且返回有效坐标。本仓仅客户端产物，不安装到专服。

`HumanMeridianRoutesTest` 使用 Human 实际模板与成品模型，验证全部人体连接有路线、四条专属连接、左右镜像、人中交汇、坐标边界、只读结果及无效绑定拒绝。旧同侧连接按实际端点展示，不迁移成新拓扑。UI 整体回归覆盖路线绘制、坏路径、缺连接和境界隐藏，不保留缺少 Human-UI 的测试或 smoke。

`runSmoke` 先创建独立整合服世界，通过真实拾取、右键和统一协议验证即时身体解锁、取消及立即完成保留进度、完整播放、OP 重置、保存重启及玩家实体替换。播放使用真实空格输入和客户端单调时间，不提供测试解锁旁路。两卷新卷在未播完时合卷，确认相应菜单仍已解锁。解锁后再打开生产内观窗口，用 Human 出生身体和真实连接上的展示在途夹具检查 GPU 光迹；看到人中光尾后，移除在途并检查残留消失。运行中世界、身体存档、画面和声音使用完整安装产物，扩散数值另由 Human 回归及 GameTest 验证。

残卷音效回归由 `ScrollAudioCheck` 观察真实 SoundManager：展开纸声不循环、正文只有一个音量 0.05 的低风声源、窗口重排不重播、Esc 立即发出停止并释放声源、完整空格加速阅读后只触发一次合拢声。`SCROLL_AUDIO_ESC_OK` 和 `SCROLL_AUDIO_COMPLETE_OK` 分别记录取消和读完的验证结果，测试只观察声音，不替正式界面执行播放或停止。

2026-10-23 复验：即时右键解锁规则与三卷独立生成背景/图标接入后，本仓构建、3 项 JUnit、完整 Xvfb 客户端 smoke 通过。`PROGRESSION_JOURNEY_OK` 与 `HUMAN_UI_SMOKE_OK` 均出现；两卷新卷在播放约 4.5 秒时关闭，菜单仍已解锁；保存重启、OP 重置和重生保留规则通过。三卷截图为 `client/build/smoke/screenshots/qi-notes-reading.png`、`gathering-reading.png`、`middle-jiao-reading.png`，属于 Linux 实际客户端画面。本轮没有更新 Windows Native 装配。

闭环必须输出 `PROGRESSION_JOURNEY_OK`，内观成功标记为 `HUMAN_UI_SMOKE_OK` 与 `client/build/smoke/human-ui-smoke-ok.txt`，截图在同目录 `screenshots/qi-notes-reading.png` 和 `screenshots/middle-jiao-*.png`。`smoke` 源集和测试用 Human 服务端依赖不进入生产 JAR，运行日志、截图和构建产物均被 Git 忽略。

2026-11-01 迁移到 Minecraft 26.2：本仓 `test build` 通过，JUnit 4 项（`HumanMeridianRoutesTest` 3、`HumanVitalityPresentationTest` 1）零失败、错误、跳过，与迁移前相同；`smoke` 源集编译通过；`check_docs.py` 通过。smoke 的帧缓冲像素检查改用 26.2 的异步回读：提交时取到已画好的上一帧，回读完成后再判定，依赖结果的下一步先等回读完成；人中光尾和红/绿关口截图改为直接保存参与判定的那一帧。三种 `runSmoke` 都依赖完整装配，而完整装配尚未迁到 26.2，本次没有运行；内观、中焦与开脉画面也尚未实看。本页其余 1.20.1 时期的结果不代表 26.2 已验证。
