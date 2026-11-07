package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.meridian.human.fabric.HumanHealthHost;
import dev.kizuna.meridian.progression.ProgressionProtocol;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.progression.client.ScrollScreen;
import dev.kizuna.meridian.progression.fabric.ProgressionFabric;
import dev.kizuna.meridian.progression.pb.ProgressionMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.*;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 完整整合服回归：真实拾取、物品右键、统一协议、保存重启和管理员撤权。 */
final class ProgressionJourney {
    private static final Set<String> PRACTICE_FEATURES = Set.of(
            "meridian_ui:meridians", "meridian_ui:cultivation", "meridian_ui:practice");
    private enum Stage { CREATE, LOCKED, PICKUP, CANCEL, CANCELLED, EARLY, COMPLETED, READ,
        READING, LOCKED_MENU, NEW_SCROLL_SETUP, NEW_SCROLL_OPEN, NEW_SCROLL_READING, NEW_SCROLL_MENU, RESTORE_NOTES,
        UNLOCKED, RUNNING, RESET_READING, RESET, RESET_DONE, RESTORE, RESTART, REJOIN, DEAD, RESPAWN, DONE }
    private final String world = "progression-smoke-" + System.currentTimeMillis();
    private Stage stage = Stage.CREATE;
    // 三卷可能按正常速度播放，且包含检视、响应式与吐纳整体回归，总时限留足 40 分钟。
    private long deadline = Util.getMillis() + 2_400_000;
    private final CultivationJourney cultivation = new CultivationJourney();
    private final ScrollAudioCheck audio = new ScrollAudioCheck();
    private int ticks;
    private CompletableFuture<Void> serverWork;
    private String oldToken;
    private net.minecraft.client.player.LocalPlayer oldPlayer;
    private int newScroll;
    private final AutomaticBreathingJourney automaticBreathing = new AutomaticBreathingJourney();
    private final BodyViewerJourney bodyViewer = new BodyViewerJourney();
    private final ResponsiveJourney responsive = new ResponsiveJourney();
    private final ManualBreathingJourney manualBreathing = new ManualBreathingJourney();
    private final MeridianSceneJourney independentScenes = new MeridianSceneJourney();

    boolean tick(Minecraft client, BodyUiModule module) throws Exception {
        // 每一阶段等待真实权威或屏幕事实；右键解锁与播放结束分别验证。
        if (Util.getMillis() > deadline) {
            throw new IllegalStateException("残卷闭环超时：" + stage + " / " + InventoryUiClient.runtime().protocolError());
        }
        if (serverWork != null) {
            if (!serverWork.isDone()) {
                return false;
            }
            serverWork.join();
            serverWork = null;
        }
        if (stage == Stage.CREATE && client.gui.screen() instanceof TitleScreen && client.gui.overlay() == null) {
            var info = new LevelSettings(world, GameType.SURVIVAL,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false /* hardcore */, false /* locked */),
                    true /* allowCommands */, WorldDataConfiguration.DEFAULT);
            next(Stage.LOCKED);
            client.createWorldOpenFlows().createFreshLevel(world, info, new WorldOptions(1L, false, false),
                    registry -> registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                    new TitleScreen());
            return false;
        }
        if (stage == Stage.RESTART && client.player == null && client.gui.overlay() == null) {
            next(Stage.REJOIN);
            client.createWorldOpenFlows().openWorld(world, () -> client.gui.setScreen(new TitleScreen()));
            return false;
        }
        // 26.2 服务端在收到客户端「载入完成」回执前丢弃使用物品等操作（进世界、重进与重生后各一次），
        // 真实玩家在载入画面结束前同样无法操作，先等这一回执再推进阶段。
        if (client.player == null || !client.getConnection().hasClientLoaded()
                || !ClientProgression.synchronizedWithServer()) {
            return false;
        }
        ticks++;
        switch (stage) {
            case LOCKED -> {
                require(!ClientProgression.canCultivate(), "新玩家不应解锁");
                require(visiblePracticeFeatures().isEmpty(), "未读时修炼入口泄漏");
                boolean denied = false;
                try {
                    InventoryUiClient.runtime().open("meridian_ui:meridians", null);
                } catch (IllegalStateException expected) {
                    denied = true;
                }
                require(denied, "直接打开绕过解锁");
                server(client, player -> {
                    BodySnapshot snapshot = inspect(player);
                    require(!ProgressionFabric.canCultivate(player), "服务端错误解锁");
                    // 登录时自然扩散已经开始，出生总量必须包含在途，不能只数内腔。
                    double qi = dev.kizuna.meridian.protocol.BodyProtobuf.fromMessage(snapshot).totalQi();
                    require(Math.abs(qi - .36) < 1e-9, "出生密度余额变化");
                    var item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("meridian_progression", "fragment_scroll"));
                    var drop = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), new ItemStack(item));
                    drop.setPickUpDelay(0);
                    player.level().addFreshEntity(drop);
                });
                next(Stage.PICKUP);
            }
            case PICKUP -> {
                if (!holdingScroll(client)) {
                    return false;
                }
                audio.begin(client);
                use(client);
                next(Stage.CANCEL);
            }
            case CANCEL -> {
                if (!(client.gui.screen() instanceof ScrollScreen screen)) {
                    return false;
                }
                if (ticks == 40) {
                    // 正文中重排窗口，确保 init 不会再播放展开声或叠加低风。
                    screen.resize(client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
                }
                if (ticks < 60) {
                    return false;
                }
                require(ClientProgression.canCultivate(), "真实右键未立即解锁札记");
                audio.body(client);
                oldToken = screen.sessionId();
                screen.keyPressed(new KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0));
                System.out.println("SCROLL_AUDIO_ESC at=" + System.currentTimeMillis());
                next(Stage.CANCELLED);
            }
            case CANCELLED -> {
                if (ticks < 10) {
                    return false;
                }
                require(ClientProgression.canCultivate(), "中途关卷撤销了右键解锁");
                audio.ended(client, false);
                use(client);
                next(Stage.EARLY);
            }
            case EARLY -> {
                if (!(client.gui.screen() instanceof ScrollScreen screen)) {
                    return false;
                }
                finish(screen.sessionId());
                next(Stage.COMPLETED);
            }
            case COMPLETED -> {
                if (client.gui.screen() instanceof ScrollScreen || ticks < 10) {
                    return false;
                }
                require(ClientProgression.canCultivate(), "立即完成播放撤销了右键解锁");
                audio.begin(client);
                use(client);
                next(Stage.READ);
            }
            case READ -> {
                if (!(client.gui.screen() instanceof ScrollScreen screen)) {
                    return false;
                }
                oldToken = screen.sessionId();
                // 先以正常速度完成展开和几秒正文，再用真实空格读完，覆盖两种音频时序。
                org.lwjgl.glfw.GLFW.glfwFocusWindow(client.getWindow().handle());
                next(Stage.READING);
            }
            case READING -> {
                if (ticks == 100) {
                    audio.body(client);
                    Screenshot.grab(client.gameDirectory, "qi-notes-reading.png", client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
                    DesktopKeyboard.space(true);
                }
                if (client.gui.screen() instanceof ScrollScreen) {
                    return false;
                }
                DesktopKeyboard.space(false);
                require(ClientProgression.canCultivate(), "完整播放后丢失解锁");
                require(holdingScroll(client), "阅读错误消耗残卷");
                server(client, player -> {
                    require(ProgressionFabric.canCultivate(player), "客户端解锁未写身体");
                    BodySnapshot snapshot = inspect(player);
                    require(snapshot.getAddonProgressMap().containsKey(ProgressionProtocol.PROGRESS_KEY), "身体未保存进度");
                    command(player, "meridian qi fill");
                });
                require(!ClientProgression.canGather() && !ClientProgression.canUseMiddleJiao(), "札记错误代解锁两卷");
                next(Stage.LOCKED_MENU);
            }
            case LOCKED_MENU -> {
                // 只读札记时仅显示经脉，隐藏与直接打开必须使用相同的权威进度投影。
                if (ticks == 1) {
                    client.options.guiScale().set(2);
                    client.resizeGui();
                    verifyFeatureMenu(client, Set.of("meridian_ui:meridians"));
                    requireFeatureDenied("meridian_ui:cultivation");
                    requireFeatureDenied("meridian_ui:practice");
                }
                if (ticks == 20) {
                    Screenshot.grab(client.gameDirectory, "cultivation-menu-notes.png", client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
                    MeridianSceneJourney.locked(client, module);
                    client.gui.setScreen(null);
                    next(Stage.NEW_SCROLL_SETUP);
                }
            }
            case NEW_SCROLL_SETUP -> {
                if (ticks < 25) {
                    return false;
                }
                if (newScroll == 0) {
                    audio.ended(client, true);
                }
                String item = newScroll == 0 ? "gathering_scroll" : "middle_jiao_scroll";
                server(client, player -> command(player, "item replace entity " + player.getScoreboardName()
                        + " weapon.mainhand with meridian_progression:" + item));
                next(Stage.NEW_SCROLL_OPEN);
            }
            case NEW_SCROLL_OPEN -> {
                String item = newScroll == 0 ? "gathering_scroll" : "middle_jiao_scroll";
                if (!BuiltInRegistries.ITEM.getKey(client.player.getMainHandItem().getItem()).getPath().equals(item)) {
                    return false;
                }
                use(client);
                next(Stage.NEW_SCROLL_READING);
            }
            case NEW_SCROLL_READING -> {
                if (client.gui.screen() instanceof ScrollScreen && ticks == 20) {
                    org.lwjgl.glfw.GLFW.glfwFocusWindow(client.getWindow().handle());
                    DesktopKeyboard.space(true);
                }
                if (client.gui.screen() instanceof ScrollScreen && ticks == 90) {
                    // 每卷各留一张正文截图，核对播放的是本卷正文而非札记。
                    Screenshot.grab(client.gameDirectory,
                            newScroll == 0 ? "gathering-reading.png" : "middle-jiao-reading.png", client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
                }
                boolean read = newScroll == 0 ? ClientProgression.requireProgress().getGatheringScrollRead()
                        : ClientProgression.requireProgress().getMiddleJiaoScrollRead();
                if (!read || ticks < 90) {
                    return false;
                }
                DesktopKeyboard.space(false);
                require(client.gui.screen() instanceof ScrollScreen, "即时解锁意外关闭了新卷画面");
                // 未播放完直接关闭，后续菜单仍须出现本卷授予的入口。
                client.gui.screen().onClose();
                next(Stage.NEW_SCROLL_MENU);
            }
            case NEW_SCROLL_MENU -> {
                // 每次真实阅读只新增对应入口，并通过实际菜单控件检查没有固定按钮。
                if (ticks == 1) {
                    if (newScroll == 0) {
                        require(!ClientProgression.canUseMiddleJiao(), "聚气残卷错误解锁中焦");
                        verifyFeatureMenu(client, Set.of("meridian_ui:meridians", "meridian_ui:cultivation"));
                        requireFeatureDenied("meridian_ui:practice");
                    } else {
                        verifyFeatureMenu(client, Set.of("meridian_ui:meridians", "meridian_ui:cultivation", "meridian_ui:practice"));
                    }
                }
                if (ticks < 20) {
                    return false;
                }
                Screenshot.grab(client.gameDirectory,
                        newScroll == 0 ? "cultivation-menu-gathering.png" : "cultivation-menu-all.png", client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
                client.gui.setScreen(null);
                if (newScroll++ == 0) {
                    next(Stage.NEW_SCROLL_SETUP);
                } else {
                    server(client, player -> command(player, "item replace entity " + player.getScoreboardName()
                            + " weapon.mainhand with meridian_progression:fragment_scroll"));
                    next(Stage.RESTORE_NOTES);
                }
            }
            case RESTORE_NOTES -> {
                if (holdingScroll(client)) {
                    next(Stage.UNLOCKED);
                }
            }
            case UNLOCKED -> {
                if (ticks < 25) {
                    return false;
                }
                require(visiblePracticeFeatures().equals(PRACTICE_FEATURES), "解锁后修炼菜单不完整");
                InventoryUiClient.runtime().openWorkspace();
                // 本闭环用开脉验证真实主动会话撤权；吐纳撤权由 Human 的身体回归覆盖。
                InventoryUiClient.runtime().open("meridian_ui:meridians", null);
                server(client, player -> command(player, "meridian opening start left_lung_meridian"));
                next(Stage.RUNNING);
            }
            case RUNNING -> {
                if (ticks < 8) {
                    return false;
                }
                server(client, player -> {
                    BodySnapshot active = inspect(player);
                    require(active.hasOpeningTask(), "开脉未实际启动");
                    require(!active.getActionsList().isEmpty(), "主动会话没有动作占用");
                });
                // 重置一个仍在阅读的有效令牌，不能只验证早已完成而失效的旧令牌。
                use(client);
                next(Stage.RESET_READING);
            }
            case RESET_READING -> {
                if (!(client.gui.screen() instanceof ScrollScreen screen)) {
                    return false;
                }
                oldToken = screen.sessionId();
                server(client, player -> {
                    command(player, "meridian progression reset");
                    BodySnapshot reset = inspect(player);
                    require(!ProgressionFabric.canCultivate(player), "重置未写身体");
                    require(reset.getActionsList().isEmpty(), "重置未解除主动修炼占用");
                });
                next(Stage.RESET);
            }
            case RESET -> {
                if (ClientProgression.canCultivate() || ticks < 10) {
                    return false;
                }
                require(module.meridianPanel() == null, "重置未关闭经脉内观窗口");
                require(!(client.gui.screen() instanceof ScrollScreen), "重置未关闭进行中的残卷阅读");
                require(visiblePracticeFeatures().isEmpty(), "重置菜单仍显示需要残卷授权的练习入口");
                requireFeatureDenied("meridian_ui:cultivation");
                requireFeatureDenied("meridian_ui:practice");
                finish(oldToken);
                next(Stage.RESET_DONE);
            }
            case RESET_DONE -> {
                if (ticks < 10) {
                    return false;
                }
                require(!ClientProgression.canCultivate(), "旧完成令牌绕过重置");
                client.gui.setScreen(null);
                server(client, player -> {
                    command(player, "meridian progression unlock");
                    player.getInventory().clearContent();
                });
                next(Stage.RESTORE);
            }
            case RESTORE -> {
                if (!ClientProgression.canCultivate() || ticks < 20) {
                    return false;
                }
                next(Stage.RESTART);
                client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
            }
            case REJOIN -> {
                if (ticks < 30) {
                    return false;
                }
                require(ClientProgression.canCultivate(), "重启世界丢失进度");
                require(!holdingScroll(client), "丢弃残卷没有持久化");
                require(ClientProgression.canGather() && ClientProgression.canUseMiddleJiao(), "重启世界丢失新残卷进度");
                // 原标题画面 demo 的检视、响应式和手动吐纳检查点改在整合服真实身体上执行，再进入自动吐纳和中焦。
                if (!bodyViewer.tick(client, module) || !responsive.tick(client, module)
                        || !manualBreathing.tick(client, module)) {
                    return false;
                }
                if (!independentScenes.tick(client, module) || !automaticBreathing.tick(client, module)) {
                    return false;
                }
                if (!cultivation.tick(client, module)) {
                    return false;
                }
                oldPlayer = client.player;
                server(client, player -> {
                    require(ProgressionFabric.canCultivate(player), "重启后身体未解锁");
                    // 单击只伤一个随机部位；重复原版致死伤害直到人体判死，再走真实重生包。
                    for (int hit = 0; hit < 128 && player.isAlive(); hit++) {
                        player.invulnerableTime = 0;
                        player.kill(player.level());
                    }
                    require(!player.isAlive(), "真实伤害没有触发身体死亡");
                });
                next(Stage.DEAD);
            }
            case DEAD -> {
                if (client.player.isAlive() || ticks < 25) {
                    return false;
                }
                require(ClientProgression.canCultivate(), "死亡清除了阅读进度");
                client.player.respawn();
                next(Stage.RESPAWN);
            }
            case RESPAWN -> {
                if (ticks < 30 || !ClientProgression.synchronizedWithServer()) {
                    return false;
                }
                require(client.player != oldPlayer, "未发生真实玩家实体替换");
                require(ClientProgression.canCultivate(), "重生丢失阅读进度");
                client.gui.setScreen(null);
                InventoryUiClient.runtime().workspace().windows().reset();
                System.out.println("PROGRESSION_JOURNEY_OK pickup/read/cancel/early/unlock/reset/save/restart/respawn");
                next(Stage.DONE);
            }
            default -> { /* 创建、重连和完成阶段由上方生命周期条件处理。 */ }
        }
        return stage == Stage.DONE;
    }

    private void next(Stage stage) {
        // 日志保留每个真实阶段，超时可直接定位具体接线。
        this.stage = stage;
        ticks = 0;
        System.out.println("PROGRESSION_SMOKE_STAGE " + stage);
    }

    private void server(Minecraft client, Consumer<ServerPlayer> action) {
        // 所有身体、命令、物品和实体操作在整合服主线程执行，异常回传客户端测试。
        UUID playerId = client.player.getUUID();
        var server = client.getSingleplayerServer();
        serverWork = CompletableFuture.runAsync(() -> action.accept(server.getPlayerList().getPlayer(playerId)), server);
    }

    private static BodySnapshot inspect(ServerPlayer player) {
        // 只读检查复用正式宿主协议，不反射访问 Human 私有存档。
        try {
            var request = HealthHostRequest.newBuilder().setProtocolVersion(1).setRequestId(UUID.randomUUID().toString())
                    .setSubjectId(player.getStringUUID()).setInspect(true).build();
            var response = HealthHostResponse.parseFrom(new HumanHealthHost().exchange(request.toByteArray()));
            require(response.hasBody(), "身体宿主拒绝查询：" + response);
            return response.getBody();
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static void command(ServerPlayer player, String command) {
        // 与管理员在游戏中执行同一命令树，不绕过权限或身体事务。
        player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER), command);
    }

    private static void verifyFeatureMenu(Minecraft client, Set<String> expected) {
        // 真实服务端解锁后检查目录与屏幕按钮，防止只改数据列表却留下旧菜单入口。
        var runtime = InventoryUiClient.runtime();
        var features = runtime.workspace().features("修炼");
        Set<String> actual = visiblePracticeFeatures();
        require(actual.equals(expected), "修炼菜单权限不匹配：" + actual);
        runtime.openWorkspace();
        var screen = client.gui.screen();
        var menu = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals("功能")).findFirst().orElseThrow();
        screen.mouseClicked(UiChecks.mouse(menu.getX() + menu.getWidth() / 2.0, menu.getY() + menu.getHeight() / 2.0, 0), false);
        screen.mouseReleased(UiChecks.mouse(menu.getX() + menu.getWidth() / 2.0, menu.getY() + menu.getHeight() / 2.0, 0));
        var search = screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .findFirst().orElseThrow();
        search.setValue("修炼");
        var labels = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .map(button -> button.getMessage().getString()).toList();
        require(labels.stream().noneMatch(label -> label.startsWith("固定 ") || label.startsWith("取消固定 ")),
                "菜单仍包含固定或取消固定按钮");
        Set<String> menuLabels = labels.stream().filter(label -> label.startsWith("修炼 / "))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> expectedLabels = features.stream().map(feature -> feature.category() + " / " + feature.title())
                .collect(java.util.stream.Collectors.toSet());
        require(menuLabels.equals(expectedLabels), "实际菜单与解锁目录不一致：" + menuLabels);
    }

    private static Set<String> visiblePracticeFeatures() {
        // 功法配置始终可见；残卷权限只约束经脉、吐纳、中焦三个练习入口。
        return InventoryUiClient.runtime().workspace().features("修炼").stream()
                .map(feature -> feature.id())
                .filter(PRACTICE_FEATURES::contains)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static void requireFeatureDenied(String featureId) {
        // 直接打开与菜单隐藏共用权限，不能绕过未读残卷限制。
        boolean denied = false;
        try {
            InventoryUiClient.runtime().open(featureId, null);
        } catch (IllegalStateException expected) {
            denied = true;
        }
        require(denied, "直接打开绕过残卷权限：" + featureId);
    }

    private static boolean holdingScroll(Minecraft client) {
        // 自动拾取必须先被原版库存同步到客户端才允许右键。
        return BuiltInRegistries.ITEM.getKey(client.player.getMainHandItem().getItem())
                .equals(Identifier.fromNamespaceAndPath("meridian_progression", "fragment_scroll"));
    }

    private static void use(Minecraft client) {
        // 真实 use-item 网络消息触发服务端 Item.use，读卷不是管理员解锁。
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
    }

    private static void finish(String token) {
        // 伪造完成仍经过统一网络路由，验证服务端凭据与实际时间检查。
        InventoryUiClient.runtime().protocol().request(ProgressionProtocol.INTENT,
                ReadingIntent.newBuilder().setSessionId(token).setFinished(Finished.getDefaultInstance()).build(),
                System.currentTimeMillis());
    }

    private static void require(boolean condition, String message) {
        // 失败中止本次完整装配验收，不能产出成功标记。
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
