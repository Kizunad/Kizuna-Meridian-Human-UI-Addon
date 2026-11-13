package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import dev.kizuna.meridian.ui.client.QiDisplay;
import io.wispforest.owo.ui.core.UIComponent;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** 经脉窗口「数据」页的真客户端截图：经脉页签、数据总览、单条展开，以及一份本地五行样例。 */
final class DataPageJourney {
    private static final int TIMEOUT_TICKS = 3000;
    private static final int SETTLE_TICKS = 40;
    private static final int FRAME_TICKS = 20;
    private static final int GUI_SCALE = 2;
    private static final long SEED = 5;
    private static final String DETAIL_ROW = "meridian-data-row-left_lung_meridian";
    private static final List<String> OPENED = List.of("left_lung_meridian", "right_lung_meridian",
            "left_large_intestine_meridian", "right_stomach_meridian");
    private static final List<String> ELEMENTS = List.of("meridian:metal", "meridian:wood", "meridian:water",
            "meridian:fire", "meridian:earth");
    private static final String NONE = "meridian:none";

    private enum Stage { CREATE, SETUP, OPEN, SCENE, DATA, DETAIL, SAMPLE, DONE }

    private Stage stage = Stage.CREATE;
    private final String world = "data-page-" + System.currentTimeMillis();
    private BodyUiModule module;
    private CompletableFuture<Void> work;
    private int ticks;
    private int totalTicks;
    private boolean sample;

    void register() {
        // 只在 smoke 源集注册；样例阶段在每帧绘制前换上本地五行快照，服务器推送不会把截图那一帧冲掉。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (Exception failure) {
                throw new IllegalStateException("数据页实机截图失败：" + stage, failure);
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                ScreenEvents.beforeExtract(screen).register((shown, graphics, mouseX, mouseY, delta) -> {
                    if (sample && module != null && module.snapshot() != null) {
                        module.update(sample(module.snapshot()));
                    }
                }));
    }

    private void tick(Minecraft client) throws Exception {
        // 每个阶段等服务器命令完成、界面排好再截图或点击。
        if (++totalTicks > TIMEOUT_TICKS) {
            throw new IllegalStateException("数据页截图超时：" + stage);
        }
        if (client.gui.screen() instanceof AccessibilityOnboardingScreen) {
            client.gui.setScreen(new TitleScreen());
        }
        if (module == null && client.gui.overlay() == null) {
            module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                    .filter(BodyUiModule.class::isInstance).map(BodyUiModule.class::cast).findFirst().orElseThrow();
        }
        if (stage == Stage.CREATE && client.gui.screen() instanceof TitleScreen && client.gui.overlay() == null) {
            createWorld(client);
            return;
        }
        if (client.player == null || !ClientProgression.synchronizedWithServer()) {
            return;
        }
        ticks++;
        if (work != null && !work.isDone()) {
            return;
        }
        if (work != null) {
            work.join();
            work = null;
        }
        step(client);
    }

    private void step(Minecraft client) throws Exception {
        // 阶段顺序：布置身体 → 打开经脉窗口 → 经脉页签 → 数据总览 → 展开一行 → 五行样例。
        switch (stage) {
            case SETUP -> {
                query(client, this::prepare);
                next(Stage.OPEN);
            }
            case OPEN -> {
                if (ticks < SETTLE_TICKS || module.snapshot() == null) {
                    return;
                }
                client.options.guiScale().set(GUI_SCALE);
                client.resizeGui();
                InventoryUiClient.runtime().openWorkspace();
                InventoryUiClient.runtime().open("meridian_ui:meridians", null);
                next(Stage.SCENE);
            }
            case SCENE -> {
                if (ticks < SETTLE_TICKS) {
                    return;
                }
                UiChecks.capture(client, "data-01-scene-tab.png");
                UiChecks.click(client, component("meridian-tab-data"), 0);
                next(Stage.DATA);
            }
            case DATA -> {
                if (ticks < FRAME_TICKS) {
                    return;
                }
                report();
                UiChecks.capture(client, "data-02-overview.png");
                UiChecks.click(client, component(DETAIL_ROW), 0);
                next(Stage.DETAIL);
            }
            case DETAIL -> {
                if (ticks < FRAME_TICKS) {
                    return;
                }
                UiChecks.capture(client, "data-03-detail.png");
                sample = true;
                next(Stage.SAMPLE);
            }
            case SAMPLE -> {
                if (ticks < FRAME_TICKS) {
                    return;
                }
                UiChecks.capture(client, "data-04-element-sample.png");
                Files.writeString(client.gameDirectory.toPath().resolve("data-page-ok.txt"),
                        "经脉页签；数据总览；单条展开；本地五行样例（非服务器数据）。\n");
                System.out.println("DATA_PAGE_JOURNEY_OK scene/overview/detail/sample");
                next(Stage.DONE);
                client.stop();
            }
            default -> { /* 建世界与结束阶段不做事。 */ }
        }
    }

    private void createWorld(Minecraft client) {
        // 平坦和平世界，开启作弊以便用正式调试命令布置身体。
        var difficulty = new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false /* hardcore */,
                false /* locked */);
        var info = new LevelSettings(world, GameType.SURVIVAL, difficulty, true /* allowCommands */,
                WorldDataConfiguration.DEFAULT);
        next(Stage.SETUP);
        client.createWorldOpenFlows().createFreshLevel(world, info, new WorldOptions(SEED, false /* structures */,
                false /* bonusChest */), registry -> registry.lookupOrThrow(Registries.WORLD_PRESET)
                .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
    }

    private void prepare(ServerPlayer player) {
        // 开四条脉、留四条未开；内腔外壁先铺游离态，再给三条经脉换成更密的相态，好让相态柱不止一根。
        String name = player.getScoreboardName();
        command(player, "meridian progression unlock");
        for (String id : OPENED) {
            command(player, "meridian opening complete " + name + " " + id);
        }
        command(player, "meridian qi set " + name + " 12 all lumen 0.01");
        command(player, "meridian qi set " + name + " 4 all wall 0.01");
        command(player, "meridian qi set " + name + " 3 left_lung_meridian lumen 0.5");
        command(player, "meridian qi set " + name + " 5 right_lung_meridian lumen 2");
        command(player, "meridian qi set " + name + " 1.5 right_stomach_meridian wall 0.5");
    }

    private void report() {
        // 日志里留一份总数，和截图上的「经脉真元」对得上。
        BodySnapshot body = module.snapshot();
        double lumen = body.getMeridianStatesList().stream().mapToDouble(state -> QiDisplay.amount(state.getLumenQi()))
                .sum();
        double wall = body.getMeridianStatesList().stream().mapToDouble(state -> QiDisplay.amount(state.getWallQi()))
                .sum();
        System.out.println("DATA_PAGE_TOTALS lumen=" + lumen + " wall=" + wall + " total=" + QiDisplay.total(body));
    }

    private UIComponent component(String id) {
        // 经脉窗口里按 ID 找控件，找不到就是接线错了。
        UIComponent found = module.meridianPanel().root().childById(UIComponent.class, id);
        UiChecks.require(found != null, "经脉窗口里没有 " + id);
        return found;
    }

    private static BodySnapshot sample(BodySnapshot body) {
        // 只换属性、不动元数和密度：第 i 条内腔取第 i 种五行，外壁错开两位，最后一条外壁留无属性。
        var builder = body.toBuilder();
        int count = builder.getMeridianStatesCount();
        for (int index = 0; index < count; index++) {
            var state = builder.getMeridianStatesBuilder(index);
            recolor(state.getLumenQiBuilder(), ELEMENTS.get(index % ELEMENTS.size()));
            String wall = NONE;
            if (index != count - 1) {
                wall = ELEMENTS.get((index + 2) % ELEMENTS.size());
            }
            recolor(state.getWallQiBuilder(), wall);
        }
        return builder.build();
    }

    private static void recolor(QiInventory.Builder inventory, String attribute) {
        // 账户里每笔真元都改成同一属性。
        for (int entry = 0; entry < inventory.getEntriesCount(); entry++) {
            inventory.getEntriesBuilder(entry).setAttribute(attribute);
        }
    }

    private void query(Minecraft client, Consumer<ServerPlayer> action) {
        // 命令放到服务器线程执行，完成前不进入下一阶段。
        UiChecks.require(work == null, "服务端操作重叠");
        UUID id = client.player.getUUID();
        var server = client.getSingleplayerServer();
        work = CompletableFuture.runAsync(() -> action.accept(server.getPlayerList().getPlayer(id)), server);
    }

    private static void command(ServerPlayer player, String command) {
        // 走玩家管理员的同一命令树。
        UiChecks.require(UiChecks.runCommand(player, command) == 1, "命令失败：" + command);
    }

    private void next(Stage target) {
        // 换阶段时清零计时，日志里能看到每一步。
        stage = target;
        ticks = 0;
        System.out.println("DATA_PAGE_STAGE " + target);
    }
}
