package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.meridian.progression.client.ClientProgression;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** 「修行」进度页的真客户端截图：拿到炁脉札记、开一条脉，真实触发弹出提示，再打开进度页切到「修行」。 */
final class AdvancementJourney {
    private static final int TIMEOUT_TICKS = 3000;
    private static final int SETTLE_TICKS = 40;
    private static final int TOAST_TICKS = 30;
    private static final int GUI_SCALE = 3;
    private static final double SCROLL_DOWN = -6;
    private static final double NO_SCROLL = 0;
    private static final long SEED = 5;
    private static final String CHINESE = "zh_cn";
    private static final Identifier ROOT = Identifier.fromNamespaceAndPath("meridian_progression", "cultivation/root");
    private static final List<Identifier> EARNED = List.of(
            Identifier.fromNamespaceAndPath("meridian_progression", "cultivation/notes"),
            Identifier.fromNamespaceAndPath("meridian_progression", "cultivation/first_meridian"));

    private enum Stage { CREATE, GIVE, TOAST, CHECK, OPEN, SHOT, LOWER, BOTTOM, DONE }

    private Stage stage = Stage.CREATE;
    private final String world = "advancement-" + System.currentTimeMillis();
    private CompletableFuture<Void> work;
    private int ticks;
    private int totalTicks;

    void register() {
        // 只在 smoke 源集注册，每个客户端 tick 推进一步。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (Exception failure) {
                throw new IllegalStateException("修行进度页截图失败：" + stage, failure);
            }
        });
    }

    private void tick(Minecraft client) throws Exception {
        // 等世界、同步、资源重载与服务器命令都完成再走下一步。
        if (++totalTicks > TIMEOUT_TICKS) {
            throw new IllegalStateException("修行进度页截图超时：" + stage);
        }
        if (client.gui.screen() instanceof AccessibilityOnboardingScreen) {
            client.gui.setScreen(new TitleScreen());
        }
        if (stage == Stage.CREATE && client.gui.screen() instanceof TitleScreen && client.gui.overlay() == null) {
            begin(client);
            return;
        }
        if (client.player == null || !ClientProgression.synchronizedWithServer() || client.gui.overlay() != null) {
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
        // 先真实触发两条进度并截弹出提示，确认服务器记下后打开进度页。
        switch (stage) {
            case GIVE -> give(client);
            case TOAST -> toast(client);
            case CHECK -> check(client);
            case OPEN -> open(client);
            case SHOT -> shot(client);
            case LOWER -> lower(client);
            case BOTTOM -> bottom(client);
            default -> { /* 建世界与结束阶段不做事。 */ }
        }
    }

    private void give(Minecraft client) {
        // 进世界稍等，再给一卷札记、开左肺经；两件事都走正式命令。
        if (ticks < SETTLE_TICKS) {
            return;
        }
        client.options.guiScale().set(GUI_SCALE);
        client.resizeGui();
        query(client, player -> {
            String name = player.getScoreboardName();
            command(player, "give " + name + " meridian_progression:fragment_scroll");
            command(player, "meridian opening complete " + name + " left_lung_meridian");
        });
        next(Stage.TOAST);
    }

    private void toast(Minecraft client) {
        // 札记入包立刻弹提示，开脉在下一次每秒检查时弹；两张都上屏后截图。
        if (ticks < TOAST_TICKS) {
            return;
        }
        UiChecks.capture(client, "advancement-01-toast.png");
        next(Stage.CHECK);
    }

    private void check(Minecraft client) {
        // 服务器端两条进度都已完成，截图里的「已完成」不是摆出来的。
        query(client, player -> {
            var server = player.level().getServer();
            for (Identifier id : EARNED) {
                AdvancementHolder holder = server.getAdvancements().get(id);
                UiChecks.require(holder != null, "没有加载进度 " + id);
                UiChecks.require(player.getAdvancements().getOrStartProgress(holder).isDone(), "进度没完成：" + id);
            }
        });
        next(Stage.OPEN);
    }

    private void open(Minecraft client) {
        // 打开原版进度页并选中「修行」页签。
        ClientAdvancements advancements = client.player.connection.getAdvancements();
        AdvancementHolder root = advancements.get(ROOT);
        UiChecks.require(root != null, "客户端没有收到「修行」根进度");
        client.gui.setScreen(new AdvancementsScreen(advancements));
        advancements.setSelectedTab(root, true /* tellServer */);
        next(Stage.SHOT);
    }

    private void shot(Minecraft client) {
        // 页签画稳后截开页时的样子，再往下拖一段。
        if (ticks < SETTLE_TICKS) {
            return;
        }
        UiChecks.require(client.gui.screen() instanceof AdvancementsScreen, "进度页被关掉了");
        UiChecks.capture(client, "advancement-02-cultivation-tab.png");
        scroll(client, NO_SCROLL, SCROLL_DOWN);
        next(Stage.LOWER);
    }

    private void lower(Minecraft client) {
        // 树的中段，再往下拖一段。
        if (ticks < SETTLE_TICKS) {
            return;
        }
        UiChecks.capture(client, "advancement-03-middle.png");
        scroll(client, NO_SCROLL, SCROLL_DOWN);
        next(Stage.BOTTOM);
    }

    private void bottom(Minecraft client) throws Exception {
        // 树的底部，截完留成功标记。
        if (ticks < SETTLE_TICKS) {
            return;
        }
        UiChecks.capture(client, "advancement-04-bottom.png");
        Files.writeString(client.gameDirectory.toPath().resolve("advancement-ok.txt"),
                "札记与一脉初通真实触发、弹出提示；进度页「修行」页签，开页、中段、底部。\n");
        System.out.println("ADVANCEMENT_JOURNEY_OK toast/tab/middle/bottom");
        next(Stage.DONE);
        client.stop();
    }

    private static void scroll(Minecraft client, double horizontal, double vertical) {
        // 走进度页自己的滚轮处理，光标放在画面正中。
        double x = client.getWindow().getGuiScaledWidth() / 2.0;
        double y = client.getWindow().getGuiScaledHeight() / 2.0;
        client.gui.screen().mouseScrolled(x, y, horizontal, vertical);
    }

    private void begin(Minecraft client) {
        // 新运行目录默认英文：先切到中文并等重载结束，再建世界；只改本次运行，不写进选项文件。
        if (!CHINESE.equals(client.getLanguageManager().getSelected())) {
            client.getLanguageManager().setSelected(CHINESE);
            client.reloadResourcePacks();
            return;
        }
        createWorld(client);
    }

    private void createWorld(Minecraft client) {
        // 平坦和平世界，开启作弊以便用正式命令。
        var difficulty = new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false /* hardcore */,
                false /* locked */);
        var info = new LevelSettings(world, GameType.SURVIVAL, difficulty, true /* allowCommands */,
                WorldDataConfiguration.DEFAULT);
        next(Stage.GIVE);
        client.createWorldOpenFlows().createFreshLevel(world, info, new WorldOptions(SEED, false /* structures */,
                false /* bonusChest */), registry -> registry.lookupOrThrow(Registries.WORLD_PRESET)
                .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
    }

    private void query(Minecraft client, Consumer<ServerPlayer> action) {
        // 放到服务器线程执行，完成前不进入下一阶段。
        UiChecks.require(work == null, "服务端操作重叠");
        UUID id = client.player.getUUID();
        var server = client.getSingleplayerServer();
        work = CompletableFuture.runAsync(() -> action.accept(server.getPlayerList().getPlayer(id)), server);
    }

    private static void command(ServerPlayer player, String command) {
        // 走玩家管理员的同一命令树。
        UiChecks.require(UiChecks.runCommand(player, command) > 0, "命令失败：" + command);
    }

    private void next(Stage target) {
        // 换阶段时清零计时，日志里能看到每一步。
        stage = target;
        ticks = 0;
        System.out.println("ADVANCEMENT_STAGE " + target);
    }
}
