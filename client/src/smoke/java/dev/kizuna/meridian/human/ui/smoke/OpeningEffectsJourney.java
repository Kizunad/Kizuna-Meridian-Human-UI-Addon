package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.meridian.human.fabric.HumanHealthHost;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.*;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.*;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import dev.kizuna.meridian.ui.client.EffectSettings;
import io.wispforest.owo.ui.core.UIComponent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 正常开脉的整合服＋GPU 回归；Windows 与 Linux 共用正式命令和输入入口。 */
final class OpeningEffectsJourney {
    private static final int START_CONFIRM_TICKS = 40;
    private enum Stage { CREATE, SETUP, OPEN, START, DETAILS, RUNNING, SUCCESS, REFILLED, WARNING_START, WARNING, QUIET,
        PAUSE, FAILED, DEBUG, COMPLETE, STATIC, DONE }
    private Stage stage = Stage.CREATE;
    private final String world = "opening-effects-" + System.currentTimeMillis();
    private BodyUiModule module;
    private BodySnapshot body;
    private CompletableFuture<BodySnapshot> work;
    private int ticks;
    private int totalTicks;
    private int shownViews;
    private boolean early;
    private boolean middle;
    private boolean late;
    private boolean lateFrame;
    private int breathButton = -1;

    void register() {
        // 此入口仅存在验收源集；真实逻辑服不识别测试模式，也不缩短维持时长。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (Exception failure) {
                throw new IllegalStateException("开脉视听实机验收失败：" + stage, failure);
            }
        });
    }

    private void tick(Minecraft client) throws Exception {
        // 用实际客户端与整合服 tick，所有状态必须等权威回执后再进入下一阶段。
        if (++totalTicks > 6000) {
            throw new IllegalStateException("正常开脉超时：" + stage + " / " + (body == null ? "" : body.getOpeningTask()));
        }
        if (client.gui.screen() instanceof AccessibilityOnboardingScreen) {
            client.gui.setScreen(new TitleScreen());
        }
        if (module == null && client.gui.overlay() == null) {
            module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                    .filter(BodyUiModule.class::isInstance).map(BodyUiModule.class::cast).findFirst().orElseThrow();
        }
        if (stage == Stage.CREATE && client.gui.screen() instanceof TitleScreen && client.gui.overlay() == null) {
            var info = new LevelSettings(world, GameType.SURVIVAL,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false /* hardcore */, false /* locked */),
                    true /* allowCommands */, WorldDataConfiguration.DEFAULT);
            next(Stage.SETUP);
            client.createWorldOpenFlows().createFreshLevel(world, info, new WorldOptions(5, false, false),
                    registry -> registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                    new TitleScreen());
            return;
        }
        if (client.player == null || !ClientProgression.synchronizedWithServer()) {
            return;
        }
        ticks++;
        if (work != null && work.isDone()) {
            body = work.join();
            work = null;
        }
        if (work == null && module.snapshot() != null) {
            // 日常观察使用生产推送；健康宿主 inspect 会结时，不能用它高频改写验收中的身体版本。
            body = module.snapshot();
        }
        if (module.cultivationPanel() != null && breathingInputAllowed()) {
            breathe();
        }
        switch (stage) {
            case SETUP -> {
                query(client, player -> {
                    command(player, "meridian progression unlock");
                    command(player, "meridian qi set " + player.getScoreboardName() + " 18 all lumen 0.01");
                });
                next(Stage.OPEN);
            }
            case OPEN -> {
                if (work != null || !ClientProgression.canCultivate() || ticks < 20) {
                    return;
                }
                client.options.guiScale().set(2);
                client.resizeGui();
                InventoryUiClient.runtime().openWorkspace();
                InventoryUiClient.runtime().open("meridian_ui:meridians", null);
                InventoryUiClient.runtime().open("meridian_ui:cultivation", null);
                org.lwjgl.glfw.GLFW.glfwFocusWindow(client.getWindow().handle());
                next(Stage.START);
            }
            case START -> {
                if (ticks < 20 || work != null) {
                    return;
                }
                InventoryUiClient.runtime().open("meridian_ui:meridians", null);
                var entry = module.meridianPanel().root().childById(UIComponent.class,
                        "meridian-rail-meridian_ui-meridian_operation");
                require(entry != null, "满足条件时未出现开脉入口");
                UiChecks.requireSeparate(module.meridianPanel().canvas(), entry);
                UiChecks.click(client, entry, 0);
                next(Stage.DETAILS);
            }
            case DETAILS -> {
                // 正式入口自动选择可开脉目标，图像和操作都经过真实窗口链。
                if (ticks < 20 || module.openingOperationRoot() == null) {
                    return;
                }
                var root = (io.wispforest.owo.ui.container.FlowLayout) module.openingOperationRoot();
                require(root.childById(UIComponent.class, "opening-visual") != null, "开脉缺少主图");
                capture(client, "opening-00-diagram.png");
                UiChecks.click(client, root.childById(UIComponent.class, "opening-action"), 0);
                next(Stage.RUNNING);
            }
            case RUNNING -> {
                if (body == null) {
                    return;
                }
                if (ticks >= START_CONFIRM_TICKS && !module.snapshot().hasOpeningTask()) {
                    capture(client, "opening-start-failed.png");
                    throw new IllegalStateException("点击开脉后未收到权威任务");
                }
                double progress = module.snapshot().getOpeningTask().getHeldMillis() / 15000.0;
                if (!early && progress > .08) {
                    InventoryUiClient.runtime().open("meridian_ui:meridians", null);
                    early = true;
                    shownViews = 1;
                } else if (shownViews == 1 && ticks % 3 == 0) {
                    capture(client, "opening-01-progress.png");
                    shownViews = 2;
                }
                if (!middle && progress > .35) {
                    InventoryUiClient.runtime().open("meridian_ui:cultivation", null);
                    middle = true;
                    shownViews = 3;
                } else if (shownViews == 3 && ticks % 3 == 0) {
                    capture(client, "opening-02-meditation.png");
                    shownViews = 4;
                }
                if (!late && progress > .55) {
                    InventoryUiClient.runtime().open("meridian_ui:body", null);
                    module.panel().layer("meridians");
                    late = true;
                    shownViews = 5;
                } else if (shownViews == 5 && ticks % 3 == 0) {
                    capture(client, "opening-03-inspection.png");
                    InventoryUiClient.runtime().open("meridian_ui:meridians", null);
                    shownViews = 6;
                }
                if (shownViews == 6 && !lateFrame && progress > .78) {
                    capture(client, "opening-03b-progress-late.png");
                    lateFrame = true;
                }
                if (module.snapshot().getOpeningTask().getStatus() == OpeningTaskStatus.OPENING_COMPLETED) {
                    require(early && middle && late, "未经历正常进行中三个视图");
                    require(module.snapshot().getOpeningTask().getHeldMillis() == 15000, "未完整维持15秒");
                    capture(client, "opening-04-natural-complete.png");
                    next(Stage.SUCCESS);
                }
                if (ticks % 100 == 0) {
                    System.out.println("OPENING_HOLD " + body.getOpeningTask().getStatus() + " " + progress
                            + " intake=" + module.cultivationPanel().canvas().breathingView().getAbsorbedQi());
                }
            }
            case SUCCESS -> {
                if (ticks >= 60 && work == null) {
                    capture(client, "opening-05-stable.png");
                    query(client, player -> {
                        command(player, "meridian qi set " + player.getScoreboardName() + " 33 all lumen 0.01");
                    });
                    next(Stage.REFILLED);
                }
            }
            case REFILLED -> {
                // 补量仍预留真实在途，等目标实际到账后才满足正常启动条件。
                var target = body.getMeridianStatesList().stream().filter(state -> state.getId().equals("right_lung_meridian"))
                        .findFirst().orElseThrow();
                double localVolume = dev.kizuna.meridian.ui.client.QiDisplay.volume(target.getLumenQi());
                if (work == null && localVolume >= 40) {
                    query(client, player -> command(player, "meridian opening start right_lung_meridian"));
                    next(Stage.WARNING_START);
                }
            }
            case WARNING_START -> {
                if (body.getOpeningTask().getStatus() == OpeningTaskStatus.OPENING_HOLDING
                        && body.getOpeningTask().getHeldMillis() > 3000 && work == null) {
                    // 仅降低已开左脉储量，保留目标与在途，稳定构造接近全身维持线的状态。
                    double otherQi = body.getMeridianStatesList().stream()
                            .filter(state -> !state.getId().equals("left_lung_meridian"))
                            .mapToDouble(state -> dev.kizuna.meridian.ui.client.QiDisplay.amount(state.getLumenQi())).sum();
                    double leftQi = 36.25 * .66 - otherQi;
                    require(leftQi > 0, "预警夹具无法保留正储量");
                    query(client, player -> command(player, "meridian qi set " + player.getScoreboardName()
                            + " " + leftQi + " left_lung_meridian lumen 0.01"));
                    next(Stage.WARNING);
                }
            }
            case WARNING -> {
                double margin = new dev.kizuna.meridian.human.ui.HumanOpeningEffects().holdMargin(module.snapshot());
                if (ticks >= 5 && work == null && margin < .08 && margin > 0) {
                    capture(client, "opening-06-warning.png");
                    EffectSettings.toggleMotion();
                    while (EffectSettings.particleLimit() != 0) {
                        EffectSettings.cycleLimit();
                    }
                    next(Stage.QUIET);
                }
            }
            case QUIET -> {
                // 在任务仍活动时验证零装饰粒子和静态表现，不用结束后的静止画面冒充测试。
                if (ticks >= 5 && work == null) {
                    require(EffectSettings.reducedMotion() && EffectSettings.particleLimit() == 0, "显示设置没有生效");
                    capture(client, "opening-06a-reduced-active.png");
                    EffectSettings.toggleMotion();
                    while (EffectSettings.particleLimit() != 96) {
                        EffectSettings.cycleLimit();
                    }
                    query(client, player -> command(player, "meridian opening pause"));
                    next(Stage.PAUSE);
                }
            }
            case PAUSE -> {
                if (module.snapshot().getOpeningTask().getStatus() == OpeningTaskStatus.OPENING_PAUSED) {
                    next(Stage.FAILED);
                }
            }
            case FAILED -> {
                if (ticks == 5) {
                    requireRedPixels(client);
                    capture(client, "opening-07-interrupted.png");
                }
                // 暗红回退的像素回读判定完才进入 OP 完成。
                if (ticks >= 55 && work == null && UiChecks.framesRead()) {
                    query(client, player -> command(player, "meridian opening complete " + player.getScoreboardName() + " right_lung_meridian"));
                    next(Stage.DEBUG);
                }
            }
            case DEBUG -> {
                boolean opened = module.snapshot().getMeridianStatesList().stream().anyMatch(state -> state.getId().equals("right_lung_meridian")
                        && state.getOpening().getStage() == MeridianOpeningStage.OPENED);
                if (opened) {
                    next(Stage.COMPLETE);
                }
            }
            case COMPLETE -> {
                if (ticks == 2) {
                    capture(client, "opening-08-debug-complete.png");
                }
                if (ticks > 55) {
                    if (!EffectSettings.reducedMotion()) {
                        EffectSettings.toggleMotion();
                    }
                    next(Stage.STATIC);
                }
            }
            case STATIC -> {
                if (ticks == 5) {
                    capture(client, "opening-09-reduced-motion.png");
                    EffectSettings.toggleMotion();
                    Files.writeString(client.gameDirectory.toPath().resolve("opening-effects-ok.txt"),
                            "正常15秒开脉；站姿/检视/打坐；自然完成；阈值预警；暂停回退；OP完成；减少动态。\n");
                    System.out.println("OPENING_EFFECTS_JOURNEY_OK normal/opened/warning/paused/debug/three-views");
                    next(Stage.DONE);
                    client.stop();
                }
            }
            default -> { /* 生命周期阶段无需操作。 */ }
        }
    }

    private boolean breathingInputAllowed() {
        // 点击另一窗口时遵守生产焦点释放；确认开脉后再继续手动吐纳维持任务。
        if (stage == Stage.START || stage == Stage.DETAILS) {
            return false;
        }
        return stage != Stage.RUNNING || module.snapshot().hasOpeningTask();
    }

    private void breathe() {
        // 使用正式吐纳按钮与画布鼠标事件，气流、粒子捕获和持续摄入由服务端计算。
        var panel = module.cultivationPanel();
        var canvas = panel.canvas();
        BreathingView view = canvas.breathingView();
        if (!view.getActive()) {
            if (ticks % 10 == 0) {
                panel.root().childById(UIComponent.class, "breathing-start").onMouseDown(UiChecks.mouse(1, 1, 0), false);
            }
            return;
        }
        int desired = breathButton < 0 ? 0 : breathButton;
        if (view.getAirLiters() >= view.getCapacityLiters() * .9) {
            desired = 1;
        } else if (view.getAirLiters() <= view.getCapacityLiters() * .1) {
            desired = 0;
        }
        // 靠近口鼻的灵气优先；这是鼠标选择目标而非伪造命中或摄入量。
        var nearest = view.getParticlesList().stream().min(java.util.Comparator.comparingDouble(particle ->
                Math.pow(particle.getX() - .5, 2) + Math.pow(particle.getY() - .4, 2)
                        + Math.pow(particle.getZ() - .5, 2))).orElse(null);
        double x = canvas.width() * .5;
        double y = canvas.height() * .45;
        if (nearest != null && desired == 0) {
            var point = canvas.projectParticle(nearest);
            x = point.x * canvas.width();
            y = point.y * canvas.height();
        }
        if (desired != breathButton) {
            if (breathButton >= 0) {
                canvas.onMouseUp(UiChecks.mouse(x, y, breathButton));
            }
            canvas.onMouseDown(UiChecks.mouse(x, y, desired), false);
            breathButton = desired;
        } else if (ticks % 5 == 0) {
            // 切换观察窗口会触发失焦释放，验收重新按下同一鼠标键续接正式输入。
            canvas.onMouseDown(UiChecks.mouse(x, y, desired), false);
        } else {
            canvas.onMouseDrag(UiChecks.mouse(x, y, desired), 0, 0);
        }
    }

    private void query(Minecraft client, Consumer<ServerPlayer> action) {
        // 查询与命令串行放入真正服务器线程，未完成时不发出第二笔操作。
        require(work == null, "服务端验收操作重叠");
        UUID id = client.player.getUUID();
        var server = client.getSingleplayerServer();
        work = CompletableFuture.supplyAsync(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            action.accept(player);
            try {
                var request = HealthHostRequest.newBuilder().setProtocolVersion(1).setRequestId(UUID.randomUUID().toString())
                        .setSubjectId(player.getStringUUID()).setInspect(true).build();
                var response = HealthHostResponse.parseFrom(new HumanHealthHost().exchange(request.toByteArray()));
                require(response.hasBody(), "正式身体宿主拒绝查询");
                return response.getBody();
            } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                throw new IllegalStateException(invalid);
            }
        }, server);
    }

    private static void command(ServerPlayer player, String command) {
        // 和玩家管理员使用同一命令树，不直接改身体或开脉状态。
        require(UiChecks.runCommand(player, command) == 1, "命令失败：" + command);
    }

    private void next(Stage stage) {
        // 记录阶段便于从 WN 日志复核每张截图对应的权威流程。
        this.stage = stage;
        ticks = 0;
        System.out.println("OPENING_EFFECTS_STAGE " + stage);
    }

    private static void capture(Minecraft client, String name) {
        // 保存真正GPU帧缓冲，截图不会替代权威结果断言。
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
    }

    private void requireRedPixels(Minecraft client) {
        // 排除窗口文字和底部控制条，只在实际3D画布里验证退光已经被GPU绘制。
        // 画布范围在提交回读时取定，与回读到的那一帧一致；计数在回读完成后判定。
        var canvas = module.meridianPanel().canvas();
        double scale = client.getWindow().getGuiScale();
        int top = (int) ((canvas.y() + 12) * scale);
        double bottom = (canvas.y() + canvas.height() - 110) * scale;
        int left = (int) ((canvas.x() + 12) * scale);
        double right = (canvas.x() + canvas.width() - 12) * scale;
        UiChecks.readFrame(client, image -> {
            int count = 0;
            for (int y = top; y < bottom; y++) {
                for (int x = left; x < right; x++) {
                    int color = image.getPixel(x, y);
                    int red = ARGB.red(color);
                    int green = ARGB.green(color);
                    int blue = ARGB.blue(color);
                    if (red > 60 && red > green * 1.5 && blue > green * .7) {
                        count++;
                    }
                }
            }
            System.out.println("OPENING_GPU_RED_PIXELS " + count);
            require(count > 10, "权威暂停后，GPU没有显示暗红回退");
        });
    }

    private static void require(boolean condition, String message) {
        // 失败必须中止验收，不能留下一个虚假的成功标记。
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
