package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.WorkspaceScreen;
import dev.kizuna.meridian.burden.fabric.client.BurdenClient;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import io.wispforest.owo.ui.component.LabelComponent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.List;

import static com.google.common.base.Preconditions.checkState;

/**
 * 真实整合服里往玩家身上放石头，检视窗口一直开着，看摘要的负重行跟着服务端读数变。
 * 这一行不随身体快照来：读数包到了客户端，最迟下一个客户端 tick 就得换字，不能等下一份身体快照。
 */
final class BurdenJourney {
    private static final int TIMEOUT_TICKS = 1200;
    private static final int VIEW_SETTLE_TICKS = 20;
    // 服务端每 10 tick 核对一次读数，Weights 每 5 tick 最多重算一次；两秒内没变就是没同步过来。
    private static final int SYNC_LIMIT_TICKS = 40;
    // 文字换好以后再等几帧，截到的才是新画面。
    private static final int FRAME_SETTLE_TICKS = 5;
    // 读数包到了客户端以后，检视那一行最多晚这么多个客户端 tick 换好（本流程和检视谁先跑 tick 不定，留一个）。
    private static final int LABEL_LIMIT_TICKS = 2;
    private static final int GUI_SCALE = 2;
    private static final long WORLD_SEED = 5;
    private static final int FIRST_MAIN_SLOT = 9;
    private static final int SUMMARY_LINE = 2;
    private static final String EMPTY = "负重：0.0 / 224 kg · 轻（实重 0.0 kg）";
    // 主库存 35 块石头：实重 35 kg，折算 70 kg，正好进「重」。
    private static final String HEAVY = "负重：70.0 / 224 kg · 重（实重 35.0 kg）";
    // 主库存 64 + 48 块石头：实重 112 kg，折算 224 kg，正好到上限。
    private static final String OVERLOADED = "负重：224.0 / 224 kg · 超载（实重 112.0 kg）";
    private enum Stage { CREATE, UNLOCK, CLEAR, EMPTY, HEAVY, HOVER, OVERLOADED, DONE }
    private Stage stage = Stage.CREATE;
    private int totalTicks;
    private int ticks;
    private int shownTicks;
    private int readingTicks;
    private BodyUiModule module;

    void register() {
        // 验收入口仅进入 smoke 源集，发布 JAR 不识别此流程。
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        // 创建真实世界并等待服务器投影；负重读数只经服务端的包到客户端，不在客户端注入。
        checkState(++totalTicks < TIMEOUT_TICKS, "负重读数验收超时：" + stage);
        if (client.gui.screen() instanceof AccessibilityOnboardingScreen) {
            client.gui.setScreen(new TitleScreen());
        }
        if (stage == Stage.CREATE && client.gui.screen() instanceof TitleScreen && client.gui.overlay() == null) {
            createWorld(client);
            return;
        }
        if (stage == Stage.DONE || client.player == null || !ClientProgression.synchronizedWithServer()) {
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

    private void advance(Minecraft client) {
        // 每一步都等检视里那一行真的变成期望的字，再放下一批石头。
        switch (stage) {
            case UNLOCK -> {
                server(client, player -> checkState(
                        UiChecks.runCommand(player, "meridian progression unlock") == 1, "解锁检视失败"));
                next(Stage.CLEAR);
            }
            case CLEAR -> {
                // 出生自带的东西先清掉，空身读数才是确定的 0 kg。
                server(client, player -> player.getInventory().clearContent());
                next(Stage.EMPTY);
            }
            case EMPTY -> {
                if (!ClientProgression.canInspect() || ticks < VIEW_SETTLE_TICKS) {
                    return;
                }
                if (module.panel() == null) {
                    open(client);
                    return;
                }
                checkState(shows(EMPTY) || ticks < SYNC_LIMIT_TICKS, "清空随身物品两秒后，检视的负重行还不是「" + EMPTY + "」");
                if (!settled(EMPTY)) {
                    return;
                }
                capture(client, "burden-empty.png");
                carry(client, 35);
                next(Stage.HEAVY);
            }
            case HEAVY -> {
                // 窗口没关：这一行靠服务端新发的读数和检视自己的逐 tick 核对变过来。
                if (!follows(HEAVY, 70_000) || !settled(HEAVY)) {
                    return;
                }
                capture(client, "burden-heavy.png");
                var label = module.panel().root().childById(LabelComponent.class, "information");
                point(client, label.x() + label.width() / 4.0,
                        label.y() + (label.lineHeight() + label.lineSpacing()) * (SUMMARY_LINE + .5));
                next(Stage.HOVER);
            }
            case HOVER -> {
                // 鼠标停在负重行上，等一帧把悬浮说明画出来再截。
                if (ticks < VIEW_SETTLE_TICKS) {
                    return;
                }
                capture(client, "burden-heavy-hover.png");
                point(client, 0, 0);
                carry(client, 64, 48);
                next(Stage.OVERLOADED);
            }
            case OVERLOADED -> {
                if (!follows(OVERLOADED, 224_000) || !settled(OVERLOADED)) {
                    return;
                }
                capture(client, "burden-overloaded.png");
                System.out.println("BURDEN_UI_OK empty,heavy=70.0,overloaded=224.0 window-kept-open");
                next(Stage.DONE);
                client.stop();
            }
            default -> { /* 启动和退出由各自阶段处理。 */ }
        }
    }

    private boolean follows(String expected, long effectiveGrams) {
        // 先等服务端的新读数到客户端（两秒为限），到了以后那一行必须紧跟着换，不等下一份身体快照。
        boolean arrived = BurdenClient.reading().filter(view -> view.effectiveGrams() == effectiveGrams).isPresent();
        if (!arrived) {
            checkState(ticks < SYNC_LIMIT_TICKS, "服务端的负重读数两秒还没到客户端，期望 " + effectiveGrams + " 克");
            return false;
        }
        boolean shown = shows(expected);
        checkState(shown || ++readingTicks <= LABEL_LIMIT_TICKS,
                "读数到客户端已经 " + readingTicks + " 个 tick，检视那一行还没换成「" + expected + "」");
        return shown;
    }

    private boolean settled(String expected) {
        // 字对上以后再等几帧才算数：截图取的是上一帧画好的画面。
        if (!shows(expected)) {
            shownTicks = 0;
            return false;
        }
        return ++shownTicks > FRAME_SETTLE_TICKS;
    }

    private boolean shows(String expected) {
        // 看的是正式组件树里的文字。
        checkState(module.panel() != null, "检视窗口被关掉了");
        var label = module.panel().root().childById(LabelComponent.class, "information");
        List<String> lines = List.of(label.text().getString().split("\n"));
        return lines.size() > SUMMARY_LINE && lines.get(SUMMARY_LINE).equals(expected);
    }

    private void point(Minecraft client, double x, double y) {
        // 截图流程没有真鼠标：直接把鼠标位置（界面坐标）写进去，界面照常按它画悬浮说明。
        double scale = client.getWindow().getGuiScale();
        try {
            for (var entry : List.of(new Object[] {"xpos", x * scale}, new Object[] {"ypos", y * scale})) {
                var field = MouseHandler.class.getDeclaredField((String) entry[0]);
                field.setAccessible(true);
                field.setDouble(client.mouseHandler, (double) entry[1]);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("没法移动鼠标位置", failure);
        }
    }

    private void carry(Minecraft client, int... stones) {
        // 石头每块 1 kg，放主库存按两倍折算；先清空，读数只取决于这一批。
        server(client, player -> {
            player.getInventory().clearContent();
            for (int index = 0; index < stones.length; index++) {
                player.getInventory().setItem(FIRST_MAIN_SLOT + index, new ItemStack(Items.STONE, stones[index]));
            }
        });
    }

    private void open(Minecraft client) {
        // 使用玩家实际打开检视的入口，保留授权和订阅检查。
        client.options.guiScale().set(GUI_SCALE);
        client.resizeGui();
        InventoryUiClient.runtime().openWorkspace();
        InventoryUiClient.runtime().open("meridian_ui:body", null);
        ticks = 0;
    }

    private static void server(Minecraft client, java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> action) {
        // 改库存和发命令都在服务端线程做，客户端只看结果。
        var server = client.getSingleplayerServer();
        var id = client.player.getUUID();
        server.execute(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }

    private void createWorld(Minecraft client) {
        // 固定种子平坦世界保持启动开销小，仍使用正式玩家与健康宿主。
        String world = "burden-" + System.currentTimeMillis();
        var info = new LevelSettings(world, GameType.SURVIVAL,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false /* hardcore */, false /* locked */),
                true /* allowCommands */, WorldDataConfiguration.DEFAULT);
        var options = new WorldOptions(WORLD_SEED, false /* structures */, false /* bonusChest */);
        next(Stage.UNLOCK);
        client.createWorldOpenFlows().createFreshLevel(world, info, options,
                registry -> registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value()
                        .createWorldDimensions(),
                new TitleScreen());
    }

    private static void capture(Minecraft client, String name) {
        // 保存真实帧缓冲，组件文字断言独立于截图。
        checkState(client.gui.screen() instanceof WorkspaceScreen, "检视工作区没有实际显示");
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
    }

    private void next(Stage next) {
        // 日志记录阶段，便于定位服务器投影或 GUI 等待超时。
        stage = next;
        ticks = 0;
        shownTicks = 0;
        readingTicks = 0;
        System.out.println("BURDEN_UI_STAGE " + stage);
    }
}
