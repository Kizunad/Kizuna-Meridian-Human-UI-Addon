package dev.kizuna.meridian.human.ui.smoke;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.meridian.human.fabric.HumanChannels;
import dev.kizuna.meridian.human.fabric.HumanHealthHost;
import dev.kizuna.meridian.human.pb.CultivationMessages.*;
import dev.kizuna.meridian.human.server.HumanCultivation;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.*;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.lwjgl.glfw.GLFW;

/** 完整单机验收：使用正式命令和客户端网络输入，在真实整合服时间下运行。 */
final class CultivationJourney {
    private final CultivationLayoutCheck layoutCheck = new CultivationLayoutCheck();
    private final WallReserveJourney reserveJourney = new WallReserveJourney();
    private static final int MOTION_INTERVAL = 4;
    private static final int MOTION_FRAMES = 32;
    private static final int MANUAL_LAPS = 2;
    private static final int MANUAL_GATES = 8;
    private static final int MANUAL_LAP_LIMIT = 5;
    private static final int BREAKTHROUGH_VISIBLE_TICKS = 10;
    private int breakthroughVisibleTicks;
    private int motionFrames;
    private int manualStartLaps = -1;
    private int manualStartGates;
    private enum Stage {
        PREPARE, OPEN, AUTOMATIC_READY, AUTOMATIC_RUNNING, MANUAL, RESERVES, DEPOSIT, MOBILIZE,
        FAIL_START, FAIL_RUNNING, FAIL_CHECK, RECOVER, SUCCESS_START, SUCCESS_RUNNING, DONE
    }
    private Stage stage = Stage.PREPARE;
    private CompletableFuture<BodySnapshot> work;
    private BodySnapshot body;
    private long sequence;
    private int ticks;
    private double wallBefore;
    private boolean sentStart;
    private boolean redCaptured;
    private boolean greenCaptured;
    private boolean automaticCaptured;

    boolean tick(Minecraft client, BodyUiModule module) throws Exception {
        // 一个查询完成后才进行下一步，测试不从客户端写入任何资源或成功标记。
        ticks++;
        if (work != null) {
            if (!work.isDone()) {
                return false;
            }
            body = work.join();
            work = null;
        }
        if (stage == Stage.DONE) {
            return true;
        }
        if (stage == Stage.PREPARE) {
            query(client, player -> {
                // 只跳过八脉等待和攒量，手动门关、脾加工、燃料与相变均真实执行。
                for (String id : HumanCultivation.MERIDIANS) {
                    command(player, "meridian opening complete " + player.getScoreboardName() + " " + id);
                }
                // 登录后已有自然扩散在途，预留其真实返回体积，不能把库存单独灌到150元。
                command(player, "meridian qi set " + player.getScoreboardName() + " 145 all lumen 0.01");
            });
            next(Stage.OPEN);
            return false;
        }
        if (body == null) {
            query(client, player -> {});
            return false;
        }
        CultivationState state = body.containsAddonProgress("human:cultivation")
                ? body.getAddonProgressOrThrow("human:cultivation").unpack(CultivationState.class)
                : CultivationState.getDefaultInstance();
        sequence = Math.max(sequence, state.getLastSequence());
        switch (stage) {
            case OPEN -> {
                InventoryUiClient.runtime().openWorkspace();
                InventoryUiClient.runtime().open("meridian_ui:practice", null);
                next(Stage.AUTOMATIC_READY);
            }
            case AUTOMATIC_READY -> {
                if (ticks < 20 || module.practicePanel() == null) {
                    break;
                }
                require(state.getPracticedGates() == 0, "自动解锁验收必须从零次手动冲关开始");
                Screenshot.grab(client.gameDirectory, "middle-jiao-unlocked.png", client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
                clickControl(client, module, "middle-jiao-automatic");
                clickControl(client, module, "middle-jiao-start");
                next(Stage.AUTOMATIC_RUNNING);
            }
            case AUTOMATIC_RUNNING -> {
                if (ticks < 20) {
                    break;
                }
                require(state.getRunning() && state.getAutomatic(), "读卷后真实复选框未能启动自动中焦");
                if (!layoutCheck.tick(client, module)) {
                    break;
                }
                Screenshot.grab(client.gameDirectory, "middle-jiao-automatic.png", client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
                automaticCaptured = true;
                clickControl(client, module, "middle-jiao-automatic");
                next(Stage.MANUAL);
            }
            case MANUAL -> {
                if (state.getAutomatic()) {
                    break;
                }
                if (manualStartLaps < 0) {
                    manualStartLaps = state.getLaps();
                    manualStartGates = state.getPracticedGates();
                    focusScene(client, module);
                }
                captureMotion(client, state);
                require(state.getReason().isEmpty() || !state.getReason().contains("渗漏"), "真实推力发生非预期损伤");
                if (state.getThrust() < .95 && !state.getPushing()) {
                    client.gui.screen().keyPressed(new KeyEvent(GLFW.GLFW_KEY_G, 0, 0));
                } else if (state.getThrust() >= 1 && state.getPushing()) {
                    client.gui.screen().keyReleased(new KeyEvent(GLFW.GLFW_KEY_G, 0, 0));
                }
                double offsetMillis = body.getSettledAt().getElapsedMillis() - state.getGateRetryUntil();
                if (state.getGateRetryUntil() > 0 && state.getExhaling() && Math.abs(offsetMillis) <= 350) {
                    // 截图前确认画面里确实是绿圈；画面尚未变绿时最多等到目标时刻再按，保证仍在服务端窗口内。
                    // 26.2 只能异步回读：同时只留一次回读，确认是绿圈的那一帧在回读完成时原样存档，随后的 tick 再按。
                    if (!greenCaptured && UiChecks.framesRead()) {
                        gateColors(client, module, (colors, image) -> {
                            if (colors[1] > 200) {
                                UiChecks.save(client, image, "middle-jiao-gate-green.png");
                                greenCaptured = true;
                            }
                        });
                    }
                    if (greenCaptured || offsetMillis >= 0) {
                        var strike = new KeyEvent(GLFW.GLFW_KEY_SPACE, 0, 0);
                        client.gui.screen().keyPressed(strike);
                        client.gui.screen().keyReleased(strike);
                    }
                } else if (!redCaptured && state.getGateRetryUntil() > 0 && state.getExhaling()
                        && offsetMillis < -(state.getGateWindowMillis() + 150)) {
                    // 红态取呼气中尚未到点的收缩圈，画面必须只有红圈没有绿圈；同样存下参与判定的那一帧。
                    if (UiChecks.framesRead()) {
                        gateColors(client, module, (colors, image) -> {
                            if (colors[0] > 200 && colors[1] < 50) {
                                UiChecks.save(client, image, "middle-jiao-gate-red.png");
                                redCaptured = true;
                            }
                        });
                    }
                }
                require(state.getLaps() - manualStartLaps < MANUAL_LAP_LIMIT,
                        "手动五圈内未完成冲关或红绿截图：red=" + redCaptured + " green=" + greenCaptured);
                if (state.getLaps() - manualStartLaps >= MANUAL_LAPS
                        && state.getPracticedGates() - manualStartGates >= MANUAL_GATES
                        && redCaptured && greenCaptured) {
                    var transit = body.getTransitsList().stream().filter(item -> item.hasCircuit()).findFirst().orElseThrow();
                    require(transit.getCargo().getEntries(0).getDensity() > .01, "真实周天没有提纯");
                    Screenshot.grab(client.gameDirectory, "cultivation-manual.png", client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
                    client.gui.screen().keyReleased(new KeyEvent(GLFW.GLFW_KEY_G, 0, 0));
                    clickControl(client, module, "middle-jiao-start");
                    next(Stage.RESERVES);
                    sentStart = false;
                }
            }
            case RESERVES -> {
                if (reserveJourney.tick(client, module)) {
                    next(Stage.DEPOSIT);
                }
            }
            case DEPOSIT -> {
                if (state.getRunning()) {
                    break;
                }
                if (!sentStart) {
                    // 高于90%沉淀线，同时给停周天后重新启动的自然扩散留下预约空间。
                    query(client, player -> command(player, "meridian qi set " + player.getScoreboardName() + " 1400 all lumen 0.099"));
                    sentStart = true;
                } else if (wall() > .05) {
                    wallBefore = wall();
                    query(client, player -> command(player, "meridian qi set " + player.getScoreboardName() + " 80 all lumen 0.099"));
                    send(CultivationInput.Operation.MOBILIZE_ON);
                    next(Stage.MOBILIZE);
                }
            }
            case MOBILIZE -> {
                double fatigue = body.getOrganStatesList().stream().filter(item -> item.getId().equals("spleen"))
                        .findFirst().orElseThrow().getFatigueJoules();
                if (wall() < wallBefore && fatigue > 0) {
                    send(CultivationInput.Operation.MOBILIZE_OFF);
                    send(CultivationInput.Operation.START_AUTOMATIC);
                    next(Stage.FAIL_START);
                    sentStart = false;
                }
            }
            case FAIL_START, SUCCESS_START -> {
                // 等待周天真正运转并取得批次；一分钟内未就绪说明服务端拒绝了启动，带原因失败。
                require(ticks < 1200, diagnosis("周天一分钟内未进入可突破状态", state));
                if (!state.getRunning() || body.getTransitsList().stream().noneMatch(QiTransit::hasCircuit)) {
                    break;
                }
                if (!automaticCaptured && state.getAutomatic()) {
                    Screenshot.grab(client.gameDirectory, "middle-jiao-automatic.png", client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
                    automaticCaptured = true;
                }
                if (!sentStart) {
                    // 动员停下后仍有低密度真元落回部分经脉内腔，把整条经脉稀释到候选密度以下，剩余候选随时序在 50 元上下波动；
                    // 起突破前与外壁一起用 DEPOSIT 阶段同一条正式命令补回候选内腔，两项门槛都由本步备齐。
                    query(client, player -> {
                        command(player, "meridian qi set " + player.getScoreboardName() + " 80 all lumen 0.099");
                        command(player, "meridian qi set " + player.getScoreboardName() + " 61 all wall 0.099");
                    });
                    sentStart = true;
                } else if (wall() >= 60) {
                    if (clickBreakthrough(client, module)) {
                        next(stage == Stage.FAIL_START ? Stage.FAIL_RUNNING : Stage.SUCCESS_RUNNING);
                    }
                }
            }
            case FAIL_RUNNING -> {
                if (state.getBreakthroughSeconds() >= 2) {
                    wallBefore = wall();
                    send(CultivationInput.Operation.STOP);
                    next(Stage.FAIL_CHECK);
                } else if (!state.getBreakthrough() && ticks > 40) {
                    // 服务端拒绝或中断突破时立即带上原因和关键余额失败，不能无限等待。
                    throw new IllegalStateException(diagnosis("中断回归的突破没有开始", state));
                }
            }
            case FAIL_CHECK -> {
                if (!state.getRunning()) {
                    require(!state.getBreakthrough() && state.getBreakthroughSeconds() == 0, "中断没有清零相变进度");
                    require(body.getRealm() == Realm.WANDERING_QI && wall() <= wallBefore + 1e-6, "失败退还燃料或错误升境");
                    next(Stage.RECOVER);
                    sentStart = false;
                }
            }
            case RECOVER -> {
                // 自动周天低于80%脑耐力会按规则停机；正式60秒突破前先停下休息，让脑耐力自然恢复。
                // 休息期间自然扩散会把环上候选摊到其他账户，重启前用 DEPOSIT 阶段同一条正式命令补回候选内腔。
                if (brainRatio() < .97) {
                    require(ticks < 4800, "停周天四分钟后脑耐力仍未恢复：" + brainRatio());
                } else if (!sentStart) {
                    query(client, player -> command(player, "meridian qi set " + player.getScoreboardName() + " 80 all lumen 0.099"));
                    sentStart = true;
                } else {
                    send(CultivationInput.Operation.START_AUTOMATIC);
                    next(Stage.SUCCESS_START);
                    sentStart = false;
                }
            }
            case SUCCESS_RUNNING -> {
                if (body.getRealm() == Realm.TRUE_QI) {
                    require(state.getBreakthroughSeconds() == 60, "未保持60秒即升境");
                    require(body.getMeridianStatesList().stream().flatMap(item -> item.getLumenQi().getEntriesList().stream())
                            .anyMatch(qi -> qi.getDensity() == .1), "突破没有转换候选密度");
                    Screenshot.grab(client.gameDirectory, "cultivation-success.png", client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
                    send(CultivationInput.Operation.STOP);
                    System.out.println("CULTIVATION_JOURNEY_OK manual/purify/gates/deposit/mobilize/failure/success");
                    java.nio.file.Files.writeString(client.gameDirectory.toPath().resolve("cultivation-journey-ok.txt"),
                            "真实单机命令与网络：手动两圈八次冲关、提纯、脾加工、动员、中断不退燃料、60秒相变真炁。");
                    next(Stage.DONE);
                } else if (!state.getBreakthrough() && ticks > 40) {
                    throw new IllegalStateException(diagnosis("真实突破提前停止", state));
                }
            }
            default -> { /* 初始化和结束由入口处理。 */ }
        }
        if (work == null && ticks % 3 == 0) {
            query(client, player -> {});
        }
        return stage == Stage.DONE;
    }

    private void captureMotion(Minecraft client, CultivationState state) {
        // 从首次关口出现起连续等间隔截图，供检查收缩圈与真实在途光迹的动画。
        if (motionFrames >= MOTION_FRAMES || ticks % MOTION_INTERVAL != 0
                || (motionFrames == 0 && state.getGateRetryUntil() == 0)) {
            return;
        }
        String name = String.format(java.util.Locale.ROOT, "middle-jiao-motion-%02d.png", motionFrames++);
        UiChecks.capture(client, name);
    }

    private boolean clickBreakthrough(Minecraft client, BodyUiModule module) throws Exception {
        // 等到客户端权威投影可用，再经模型下方真实按钮启动，不能直接发测试输入绕过显隐。
        InventoryUiClient.runtime().open("meridian_ui:meridians", null);
        var state = module.snapshot().getAddonProgressOrThrow(HumanCultivation.PROGRESS)
                .unpack(CultivationState.class);
        if (!state.getBreakthroughAvailable()) {
            breakthroughVisibleTicks = 0;
            return false;
        }
        var panel = module.meridianPanel();
        var entry = panel.root().childById(io.wispforest.owo.ui.core.UIComponent.class, "meridian-breakthrough");
        require(entry != null, "满足条件时未显示突破按钮");
        if (entry.width() == 0 || entry.height() == 0) {
            return false;
        }
        if (++breakthroughVisibleTicks < BREAKTHROUGH_VISIBLE_TICKS) {
            return false;
        }
        breakthroughVisibleTicks = 0;
        UiChecks.requireSeparate(panel.canvas(), entry);
        UiChecks.capture(client, "meridian-breakthrough-ready.png");
        UiChecks.click(client, entry, 0);
        return true;
    }

    private void next(Stage stage) {
        // 日志标记用于区分真实权威阶段和只读光迹夹具。
        this.stage = stage;
        ticks = 0;
        System.out.println("CULTIVATION_SMOKE_STAGE " + stage);
    }

    private static void clickControl(Minecraft client, BodyUiModule module, String id) {
        // 按实际组件身份点击，布局变化不能让测试绕过屏幕命中或误点相邻操作。
        var control = module.practicePanel().root().childById(io.wispforest.owo.ui.core.UIComponent.class, id);
        double x = control.x() + control.width() / 2.0;
        double y = control.y() + control.height() / 2.0;
        client.gui.screen().mouseClicked(UiChecks.mouse(x, y, 0), false /* doubled */);
        client.gui.screen().mouseReleased(UiChecks.mouse(x, y, 0));
    }

    private String diagnosis(String title, CultivationState state) {
        // 汇总服务端原因、候选、外壁、运转、八脉完好与脑耐力，区分燃料、候选、损伤和停机。
        long damaged = body.getMeridianStatesList().stream()
                .filter(item -> HumanCultivation.MERIDIANS.contains(item.getId()))
                .filter(item -> item.getCondition() != MeridianCondition.MERIDIAN_CONDITION_UNSPECIFIED
                        && item.getCondition() != MeridianCondition.INTACT)
                .count();
        return String.format(java.util.Locale.ROOT, "%s：%s（候选 %.2f 元，外壁 %.2f 元，运转 %s，自动 %s，受损周天经脉 %d 条，脑耐力 %.3f）",
                title, state.getReason(), candidates(), wall(), state.getRunning(), state.getAutomatic(), damaged, brainRatio());
    }

    private double candidates() {
        // 失败时报告内腔与周天在途中达到候选密度的真元，便于区分燃料、候选和停机原因。
        double lumen = body.getMeridianStatesList().stream().flatMap(item -> item.getLumenQi().getEntriesList().stream())
                .filter(qi -> qi.getDensity() >= .09).mapToDouble(Qi::getAmount).sum();
        double circuit = body.getTransitsList().stream().filter(QiTransit::hasCircuit)
                .flatMap(item -> item.getCargo().getEntriesList().stream())
                .filter(qi -> qi.getDensity() >= .09).mapToDouble(Qi::getAmount).sum();
        return lumen + circuit;
    }

    private double brainRatio() {
        // 只读身体快照中的脑疲劳，不改写耐力；验收身体无伤，比例与服务端有效耐力一致。
        double capacity = body.getStructure().getOrgansList().stream().filter(item -> item.getId().equals("brain"))
                .findFirst().orElseThrow().getEndurance().getEnduranceJoules();
        double fatigue = body.getOrganStatesList().stream().filter(item -> item.getId().equals("brain"))
                .findFirst().orElseThrow().getFatigueJoules();
        return 1 - fatigue / capacity;
    }

    private static void gateColors(Minecraft client, BodyUiModule module, BiConsumer<int[], NativeImage> result) {
        // 只统计关口图像，人体或其他资源图的颜色不能冒充红绿冲关时机。
        var controls = module.practicePanel().root()
                .childById(io.wispforest.owo.ui.core.UIComponent.class, "middle-jiao-gate");
        double scale = client.getWindow().getGuiScale();
        int top = Math.max(0, (int) (controls.y() * scale));
        int left = Math.max(0, (int) (controls.x() * scale));
        int bottom = (int) ((controls.y() + controls.height()) * scale);
        int right = (int) ((controls.x() + controls.width()) * scale);
        UiChecks.readFrame(client, image -> {
            int red = 0;
            int green = 0;
            for (int y = top; y < Math.min(image.getHeight(), bottom); y++) {
                for (int x = left; x < Math.min(image.getWidth(), right); x++) {
                    int color = image.getPixel(x, y);
                    int r = ARGB.red(color);
                    int g = ARGB.green(color);
                    int b = ARGB.blue(color);
                    if (r > 220 && g < 110 && b < 110) {
                        red++;
                    }
                    if (g > 210 && r < 120 && b < 160) {
                        green++;
                    }
                }
            }
            result.accept(new int[]{red, green}, image);
        });
    }

    private double wall() {
        // 只读实际外壁明细，不把缓存或在途计入燃料。
        return body.getMeridianStatesList().stream().flatMap(item -> item.getWallQi().getEntriesList().stream()).mapToDouble(Qi::getAmount).sum();
    }

    private void query(Minecraft client, java.util.function.Consumer<ServerPlayer> action) {
        // 真实管理员命令和身体查询在整合服线程执行，不使用反射或测试资源入口。
        UUID playerId = client.player.getUUID();
        var server = client.getSingleplayerServer();
        work = CompletableFuture.supplyAsync(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            action.accept(player);
            try {
                var request = HealthHostRequest.newBuilder().setProtocolVersion(1).setRequestId(UUID.randomUUID().toString())
                        .setSubjectId(player.getStringUUID()).setInspect(true).build();
                var response = HealthHostResponse.parseFrom(new HumanHealthHost().exchange(request.toByteArray()));
                require(response.hasBody(), "身体查询失败：" + response);
                return response.getBody();
            } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                throw new IllegalStateException(invalid);
            }
        }, server);
    }

    private static void focusScene(Minecraft client, BodyUiModule module) {
        // 经真实鼠标链聚焦周天人体下方的空白画布，随后按键经过独立窗口。
        var canvas = module.practicePanel().canvas();
        double x = canvas.x() + canvas.width() / 2.0;
        double y = canvas.y() + canvas.height() / 2.0;
        var click = UiChecks.mouse(x, y, 0);
        client.gui.screen().mouseClicked(click, false /* doubled */);
        client.gui.screen().mouseReleased(click);
    }

    private void send(CultivationInput.Operation operation) {
        // 与正式控件使用同一频道和消息，服务端不识别测试特例。
        var input = CultivationInput.newBuilder().setSequence(++sequence).setOperation(operation)
                .setKeyTick(body.getSettledAt().getElapsedMillis() / 50).build();
        ClientPlayNetworking.send(HumanChannels.CULTIVATION.payload(input.toByteArray()));
    }

    private static void command(ServerPlayer player, String command) {
        // 管理指令必须真的成功，不能把错误聊天消息当作已准备好测试资源。
        require(UiChecks.runCommand(player, command) == 1, "调试命令未成功：" + command);
    }

    private static void require(boolean condition, String message) {
        // 测试失败直接终止验收并保留阶段，不能吞掉权威拒绝继续截图。
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
