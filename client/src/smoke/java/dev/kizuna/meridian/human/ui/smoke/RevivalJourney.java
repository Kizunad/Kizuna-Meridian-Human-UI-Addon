package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.WorkspaceScreen;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import io.wispforest.owo.ui.component.LabelComponent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import static com.google.common.base.Preconditions.checkState;

/** 真实整合服死亡重生后，从正式检视组件检查寿元行和缩放。 */
final class RevivalJourney {
    private static final int TIMEOUT_TICKS = 1200;
    private static final int VIEW_SETTLE_TICKS = 20;
    private static final int NORMAL_SCALE = 2;
    private static final int LARGE_SCALE = 3;
    private static final long WORLD_SEED = 5;
    private static final int OP_LEVEL = 4;
    private enum Stage { CREATE, UNLOCK, INITIAL, DIE, RESPAWN, REVIVED, LARGE, DONE }
    private Stage stage = Stage.CREATE;
    private int totalTicks;
    private int ticks;
    private BodyUiModule module;

    void register() {
        // 验收入口仅进入 smoke 源集，发布 JAR 不识别此流程。
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        // 创建真实世界并等待服务器投影，不注入展示快照或修改身体账本。
        checkState(++totalTicks < TIMEOUT_TICKS, "复活界面验收超时：" + stage);
        if (client.currentScreen instanceof AccessibilityOnboardingScreen) {
            client.setScreen(new TitleScreen());
        }
        if (stage == Stage.CREATE && client.currentScreen instanceof TitleScreen && client.getOverlay() == null) {
            createWorld(client);
            return;
        }
        if (client.player == null || !ClientProgression.synchronizedWithServer()) {
            return;
        }
        if (module == null) {
            module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                    .filter(BodyUiModule.class::isInstance)
                    .map(BodyUiModule.class::cast)
                    .findFirst().orElseThrow();
        }
        ticks++;
        advance(client);
    }

    private void advance(MinecraftClient client) {
        // 界面操作在玩家和进度同步完成后按服务器回执逐步推进。
        switch (stage) {
            case UNLOCK -> {
                command(client, "meridian progression unlock");
                next(Stage.INITIAL);
            }
            case INITIAL -> initial(client);
            case DIE -> {
                client.setScreen(null);
                command(client, "kill @s");
                next(Stage.RESPAWN);
            }
            case RESPAWN -> respawn(client);
            case REVIVED -> revived(client);
            case LARGE -> large(client);
            default -> { /* 启动和退出由各自阶段处理。 */ }
        }
    }

    private void createWorld(MinecraftClient client) {
        // 固定种子平坦世界保持启动开销小，仍使用正式玩家与健康宿主。
        String world = "revival-" + System.currentTimeMillis();
        var info = new LevelInfo(world, GameMode.SURVIVAL, false /* hardcore */, Difficulty.PEACEFUL,
                true /* allowCommands */, new GameRules(), DataConfiguration.SAFE_MODE);
        var options = new GeneratorOptions(WORLD_SEED, false /* structures */, false /* bonusChest */);
        next(Stage.UNLOCK);
        client.createIntegratedServerLoader().createAndStart(world, info, options,
                registry -> registry.get(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                        .createDimensionsRegistryHolder());
    }

    private void initial(MinecraftClient client) {
        // 出生样例先显示 18 岁、62 年，确认面板真的绑定当前玩家。
        if (!ClientProgression.canInspect() || ticks < VIEW_SETTLE_TICKS) {
            return;
        }
        if (module.panel() == null) {
            open(client);
            return;
        }
        verify("年龄 / 寿元：18.0 年 / 62.0 年");
        capture(client, "revival-before.png");
        next(Stage.DIE);
    }

    private void respawn(MinecraftClient client) {
        // 通过客户端重生包进入原版玩家替换与 Human AFTER_RESPAWN。
        if (client.player.isDead()) {
            client.player.requestRespawn();
            client.setScreen(null);
            next(Stage.REVIVED);
        }
    }

    private void revived(MinecraftClient client) {
        // 等服务器新快照扣费后再检查组件，不能把旧窗口文字当作刷新成功。
        if (client.player.isDead() || module.snapshot() == null
                || module.snapshot().getVitality().getSpentLifespanSeconds() == 0) {
            return;
        }
        if (module.panel() == null || !(client.currentScreen instanceof WorkspaceScreen)) {
            open(client);
            return;
        }
        if (ticks < VIEW_SETTLE_TICKS) {
            return;
        }
        verify("年龄 / 寿元：18.0 年 / 61.0 年");
        capture(client, "revival-after-scale-2.png");
        client.options.getGuiScale().setValue(LARGE_SCALE);
        client.onResolutionChanged();
        next(Stage.LARGE);
    }

    private void large(MinecraftClient client) {
        // 缩放只改变布局，寿元行仍由同一权威快照生成。
        if (ticks < VIEW_SETTLE_TICKS) {
            return;
        }
        verify("年龄 / 寿元：18.0 年 / 61.0 年");
        capture(client, "revival-after-scale-3.png");
        System.out.println("REVIVAL_UI_OK age=18.0 remaining=61.0 scales=2,3 actual-respawn");
        next(Stage.DONE);
        client.scheduleStop();
    }

    private void open(MinecraftClient client) {
        // 使用玩家实际打开检视的入口，保留授权和订阅检查。
        client.options.getGuiScale().setValue(NORMAL_SCALE);
        client.onResolutionChanged();
        InventoryUiClient.runtime().openWorkspace();
        InventoryUiClient.runtime().open("meridian_ui:body", null);
        ticks = 0;
    }

    private void verify(String expected) {
        // 断言正式组件树的文字，而非单独调用格式化器。
        var label = module.panel().root().childById(LabelComponent.class, "information");
        checkState(label.text().getString().startsWith(expected), "寿元行不符：" + label.text().getString());
    }

    private static void command(MinecraftClient client, String command) {
        // 权威操作通过服务端命令树；客户端不直接访问身体 Store。
        var server = client.getServer();
        var id = client.player.getUuid();
        server.execute(() -> {
            var player = server.getPlayerManager().getPlayer(id);
            int result = server.getCommandManager().executeWithPrefix(
                    player.getCommandSource().withLevel(OP_LEVEL), command);
            checkState(result == 1, "验收命令失败：" + command);
        });
    }

    private static void capture(MinecraftClient client, String name) {
        // 保存真实帧缓冲，组件文字断言独立于截图。
        checkState(client.currentScreen instanceof WorkspaceScreen, "检视工作区没有实际显示");
        ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), ignored -> {});
    }

    private void next(Stage next) {
        // 日志记录阶段，便于定位服务器投影或 GUI 等待超时。
        stage = next;
        ticks = 0;
        System.out.println("REVIVAL_UI_STAGE " + stage);
    }

}
