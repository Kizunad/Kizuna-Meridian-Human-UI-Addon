package dev.kizuna.meridian.human.ui.smoke;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.meridian.human.fabric.HumanHealthHost;
import dev.kizuna.meridian.human.pb.CultivationMessages.*;
import dev.kizuna.meridian.human.server.HumanCultivation;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.*;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 完整单机验收：使用正式命令和客户端网络输入，在真实整合服时间下运行。 */
final class CultivationJourney {
    private enum Stage { PREPARE, OPEN, AUTOMATIC_READY, AUTOMATIC_RUNNING, MANUAL, DEPOSIT, MOBILIZE, FAIL_START, FAIL_RUNNING, FAIL_CHECK, RECOVER, SUCCESS_START, SUCCESS_RUNNING, DONE }
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
                Screenshot.grab(client.gameDirectory, "middle-jiao-unlocked.png", client.getMainRenderTarget(), ignored -> {});
                clickControl(client, module, 1);
                clickControl(client, module, 0);
                next(Stage.AUTOMATIC_RUNNING);
            }
            case AUTOMATIC_RUNNING -> {
                if (ticks < 20) {
                    break;
                }
                require(state.getRunning() && state.getAutomatic(), "读卷后真实复选框未能启动自动中焦");
                Screenshot.grab(client.gameDirectory, "middle-jiao-automatic.png", client.getMainRenderTarget(), ignored -> {});
                automaticCaptured = true;
                clickControl(client, module, 1);
                next(Stage.MANUAL);
            }
            case MANUAL -> {
                require(state.getReason().isEmpty() || !state.getReason().contains("渗漏"), "真实推力发生非预期损伤");
                if (state.getThrust() < .95 && !state.getPushing()) {
                    send(CultivationInput.Operation.PUSH_ON);
                } else if (state.getThrust() >= 1 && state.getPushing()) {
                    send(CultivationInput.Operation.PUSH_OFF);
                }
                double offsetMillis = body.getSettledAt().getElapsedMillis() - state.getGateRetryUntil();
                if (state.getGateRetryUntil() > 0 && state.getExhaling() && Math.abs(offsetMillis) <= 350) {
                    // 截图前确认画面里确实是绿圈；画面尚未变绿时最多等到目标时刻再按，保证仍在服务端窗口内。
                    if (!greenCaptured && gateColors(client, module)[1] > 200) {
                        Screenshot.grab(client.gameDirectory, "middle-jiao-gate-green.png", client.getMainRenderTarget(), ignored -> {});
                        greenCaptured = true;
                        send(CultivationInput.Operation.STRIKE_GATE);
                    } else if (greenCaptured || offsetMillis >= 0) {
                        send(CultivationInput.Operation.STRIKE_GATE);
                    }
                } else if (!redCaptured && state.getGateRetryUntil() > 0 && state.getExhaling()
                        && offsetMillis < -(state.getGateWindowMillis() + 150)) {
                    // 红态取呼气中尚未到点的收缩圈，画面必须只有红圈没有绿圈。
                    int[] colors = gateColors(client, module);
                    if (colors[0] > 200 && colors[1] < 50) {
                        Screenshot.grab(client.gameDirectory, "middle-jiao-gate-red.png", client.getMainRenderTarget(), ignored -> {});
                        redCaptured = true;
                    }
                }
                require(state.getLaps() < 5, "五圈内没有拍到红绿关口两态：red=" + redCaptured + " green=" + greenCaptured);
                if (state.getLaps() >= 2 && redCaptured && greenCaptured) {
                    require(state.getPracticedGates() >= 8, "真实手动冲关未累计熟练度");
                    var transit = body.getTransitsList().stream().filter(item -> item.hasCircuit()).findFirst().orElseThrow();
                    require(transit.getCargo().getEntries(0).getDensity() > .01, "真实周天没有提纯");
                    Screenshot.grab(client.gameDirectory, "cultivation-manual.png", client.getMainRenderTarget(), ignored -> {});
                    send(CultivationInput.Operation.STOP);
                    next(Stage.DEPOSIT);
                    sentStart = false;
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
                    Screenshot.grab(client.gameDirectory, "middle-jiao-automatic.png", client.getMainRenderTarget(), ignored -> {});
                    automaticCaptured = true;
                }
                if (!sentStart) {
                    query(client, player -> command(player, "meridian qi set " + player.getScoreboardName() + " 61 all wall 0.099"));
                    sentStart = true;
                } else if (wall() >= 60) {
                    send(CultivationInput.Operation.START_BREAKTHROUGH);
                    next(stage == Stage.FAIL_START ? Stage.FAIL_RUNNING : Stage.SUCCESS_RUNNING);
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
                    Screenshot.grab(client.gameDirectory, "cultivation-success.png", client.getMainRenderTarget(), ignored -> {});
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

    private void next(Stage stage) {
        // 日志标记用于区分真实权威阶段和只读光迹夹具。
        this.stage = stage;
        ticks = 0;
        System.out.println("CULTIVATION_SMOKE_STAGE " + stage);
    }

    private static void clickControl(Minecraft client, BodyUiModule module, int index) {
        // 经过真实屏幕命中链点击复选框或开始按钮，保护独立窗口中的控件遮挡问题。
        var controls = module.practicePanel().root().childById(io.wispforest.owo.ui.core.Component.class, "middle-jiao-controls");
        double x = controls.x() + (index + .5) * Math.max(30, controls.width() / 5);
        double y = controls.y() + 10;
        client.screen.mouseClicked(x, y, 0);
        client.screen.mouseReleased(x, y, 0);
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

    private static int[] gateColors(Minecraft client, BodyUiModule module) {
        // 统计中焦控件区域内的纯红、纯绿像素，确认截图里真的出现红圈或绿圈。
        var controls = module.practicePanel().root().childById(io.wispforest.owo.ui.core.Component.class, "middle-jiao-controls");
        double scale = client.getWindow().getGuiScale();
        int red = 0;
        int green = 0;
        try (NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
            int bottom = Math.min(image.getHeight(), (int) ((controls.y() + controls.height()) * scale));
            int right = Math.min(image.getWidth(), (int) ((controls.x() + controls.width()) * scale));
            for (int y = Math.max(0, (int) (controls.y() * scale)); y < bottom; y++) {
                for (int x = Math.max(0, (int) (controls.x() * scale)); x < right; x++) {
                    int color = image.getPixelRGBA(x, y);
                    int r = color & 255;
                    int g = color >> 8 & 255;
                    int b = color >> 16 & 255;
                    if (r > 220 && g < 110 && b < 110) {
                        red++;
                    }
                    if (g > 210 && r < 120 && b < 160) {
                        green++;
                    }
                }
            }
        }
        return new int[]{red, green};
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

    private void send(CultivationInput.Operation operation) {
        // 与正式控件使用同一频道和消息，服务端不识别测试特例。
        var input = CultivationInput.newBuilder().setSequence(++sequence).setOperation(operation)
                .setKeyTick(body.getSettledAt().getElapsedMillis() / 50).build();
        var buffer = PacketByteBufs.create();
        buffer.writeBytes(input.toByteArray());
        ClientPlayNetworking.send(new ResourceLocation("meridian_human", "cultivation"), buffer);
    }

    private static void command(ServerPlayer player, String command) {
        // 管理指令必须真的成功，不能把错误聊天消息当作已准备好测试资源。
        int result = player.getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), command);
        require(result == 1, "调试命令未成功：" + command);
    }

    private static void require(boolean condition, String message) {
        // 测试失败直接终止验收并保留阶段，不能吞掉权威拒绝继续截图。
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
