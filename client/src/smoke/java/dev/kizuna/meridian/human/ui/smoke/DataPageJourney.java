package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import dev.kizuna.meridian.ui.client.QiDisplay;
import io.wispforest.owo.ui.container.ScrollContainer;
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
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCursorPosCallback;

/**
 * 经脉窗口「数据」页的真客户端截图与排布检查：未开脉提示、经脉页签、数据总览、单条展开、悬停说明、
 * 本地五行样例、窄窗叠放、最小窗口整页滚动，以及英文界面。
 */
final class DataPageJourney {
    private static final int TIMEOUT_TICKS = 4000;
    private static final int SETTLE_TICKS = 40;
    private static final int FRAME_TICKS = 20;
    private static final int GUI_SCALE = 2;
    private static final long SEED = 5;
    private static final String FEATURE = "meridian_ui:meridians";
    private static final String DETAIL_ROW = "meridian-data-row-left_lung_meridian";
    private static final String NOTICE = "meridian-data-notice";
    private static final String FRAME = "meridian-data-frame";
    private static final String SUMMARY = "data-summary";
    private static final String ELEMENTS_CARD = "data-elements";
    private static final String HEADER = "data-header";
    private static final String ROWS = "data-rows-scroll";
    private static final String DEFAULT_WIDTH = "560";
    private static final String DEFAULT_HEIGHT = "450";
    private static final String COMPACT_SIZE = "340";
    private static final String MINIMUM_WIDTH = "280";
    private static final String MINIMUM_HEIGHT = "180";
    private static final String ENGLISH = "en_us";
    private static final String CHINESE = "zh_cn";
    private static final double CURSOR_AWAY = 1;
    private static final double ROW_TITLE_MIDDLE = 9;
    private static final List<String> OPENED = List.of("left_lung_meridian", "right_lung_meridian",
            "left_large_intestine_meridian", "right_stomach_meridian");
    private static final List<String> ELEMENTS = List.of("meridian:metal", "meridian:wood", "meridian:water",
            "meridian:fire", "meridian:earth");
    private static final String NONE = "meridian:none";

    private enum Stage {
        CREATE, UNLOCK, OPEN, EMPTY, SETUP, SCENE, DATA, DETAIL, SAMPLE, HOVER, UNHOVER, COMPACT, MINIMUM,
        MINIMUM_TABLE,
        RESTORE, ENGLISH, DONE
    }

    private Stage stage = Stage.CREATE;
    private final String world = "data-page-" + System.currentTimeMillis();
    private BodyUiModule module;
    private CompletableFuture<Void> work;
    private WindowKey window;
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
        // 每个阶段等服务器命令、资源重载完成、界面排好再截图或点击。
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
        // 先在没开脉时看提示，再布置身体看总览与明细，最后改窗口尺寸和语言。
        switch (stage) {
            case UNLOCK -> unlock(client);
            case OPEN -> open(client);
            case EMPTY -> empty(client);
            case SETUP -> setup(client);
            case SCENE -> scene(client);
            case DATA -> data(client);
            case DETAIL -> detail(client);
            case SAMPLE -> sampled(client);
            case HOVER -> hover(client);
            case UNHOVER -> unhover();
            case COMPACT -> compact(client);
            case MINIMUM -> minimum(client);
            case MINIMUM_TABLE -> minimumTable(client);
            case RESTORE -> restore();
            case ENGLISH -> english(client);
            default -> { /* 建世界与结束阶段不做事。 */ }
        }
    }

    private void unlock(Minecraft client) {
        // 解锁修炼界面，经脉全部保持未开拓。
        query(client, player -> command(player, "meridian progression unlock"));
        next(Stage.OPEN);
    }

    private void open(Minecraft client) {
        // 快照到了再开经脉窗口并切到「数据」页。
        if (ticks < SETTLE_TICKS || module.snapshot() == null) {
            return;
        }
        client.options.guiScale().set(GUI_SCALE);
        client.resizeGui();
        InventoryUiClient.runtime().openWorkspace();
        window = InventoryUiClient.runtime().open(FEATURE, null).key();
        next(Stage.EMPTY);
    }

    private void empty(Minecraft client) {
        // 一条都没开拓时明细表顶上有提示，并且提示在表格范围内。
        if (ticks == 1) {
            UiChecks.click(client, component("meridian-tab-data"), 0);
            return;
        }
        if (ticks < FRAME_TICKS) {
            return;
        }
        UiChecks.requireInside(component(ROWS), component(NOTICE));
        UiChecks.capture(client, "data-00-empty.png");
        UiChecks.click(client, component("meridian-tab-scene"), 0);
        next(Stage.SETUP);
    }

    private void setup(Minecraft client) {
        // 开脉、注入真元，等服务器推送新快照。
        query(client, this::prepare);
        next(Stage.SCENE);
    }

    private void scene(Minecraft client) {
        // 经脉页签照旧，随后切到数据页。
        if (ticks < SETTLE_TICKS) {
            return;
        }
        UiChecks.capture(client, "data-01-scene-tab.png");
        UiChecks.click(client, component("meridian-tab-data"), 0);
        next(Stage.DATA);
    }

    private void data(Minecraft client) {
        // 开了脉之后提示消失；正常尺寸两卡并排、外层不出滚动条，与初版一样。
        if (ticks < FRAME_TICKS) {
            return;
        }
        UiChecks.require(find(NOTICE) == null, "开脉后仍显示未开脉提示");
        layout();
        UiChecks.require(component(SUMMARY).y() == component(ELEMENTS_CARD).y(), "正常尺寸下两张卡没有并排");
        UiChecks.require(frame().scrollbarThiccness() == 0, "正常尺寸下出现了外层滚动条");
        report();
        UiChecks.capture(client, "data-02-overview.png");
        UiChecks.click(client, component(DETAIL_ROW), 0);
        next(Stage.DETAIL);
    }

    private void detail(Minecraft client) {
        // 展开一行后截图，再换本地五行样例。
        if (ticks < FRAME_TICKS) {
            return;
        }
        UiChecks.capture(client, "data-03-detail.png");
        sample = true;
        next(Stage.SAMPLE);
    }

    private void sampled(Minecraft client) {
        // 五行样例下的圆环与图例。
        if (ticks < FRAME_TICKS) {
            return;
        }
        UiChecks.capture(client, "data-04-element-sample.png");
        next(Stage.HOVER);
    }

    private void hover(Minecraft client) {
        // 真实光标停在展开行的标题条上，这一行挂出悬停说明。
        UIComponent row = component(DETAIL_ROW);
        if (ticks == 1) {
            cursor(client, row.x() + row.width() / 2.0, row.y() + ROW_TITLE_MIDDLE);
            return;
        }
        if (ticks < FRAME_TICKS) {
            return;
        }
        UiChecks.require(row.tooltip() != null && !row.tooltip().isEmpty(), "光标停在经脉行上没有悬停说明");
        UiChecks.capture(client, "data-05-hover.png");
        cursor(client, CURSOR_AWAY, CURSOR_AWAY);
        next(Stage.UNHOVER);
    }

    private void unhover() {
        // 光标移开后说明随之撤下，证明说明跟着光标位置走。
        if (ticks < FRAME_TICKS) {
            return;
        }
        List<?> tooltip = component(DETAIL_ROW).tooltip();
        UiChecks.require(tooltip == null || tooltip.isEmpty(), "光标移开后经脉行的悬停说明没撤");
        next(Stage.COMPACT);
    }

    private void compact(Minecraft client) {
        // 窄窗：两张卡上下叠放，整页可滚动，内容不出窗口、不互相覆盖。
        if (ticks == 1) {
            InventoryUiClient.runtime().workspace().windows().resize(window, COMPACT_SIZE, COMPACT_SIZE);
            return;
        }
        if (ticks < SETTLE_TICKS) {
            return;
        }
        stackedLayout();
        UiChecks.capture(client, "data-06-compact.png");
        next(Stage.MINIMUM);
    }

    private void minimum(Minecraft client) {
        // 最小窗口同样叠放滚动；截一张顶部，再滚到明细表。
        if (ticks == 1) {
            InventoryUiClient.runtime().workspace().windows().resize(window, MINIMUM_WIDTH, MINIMUM_HEIGHT);
            return;
        }
        if (ticks < SETTLE_TICKS) {
            return;
        }
        stackedLayout();
        UiChecks.capture(client, "data-07-minimum.png");
        frame().scrollTo(component(HEADER));
        next(Stage.MINIMUM_TABLE);
    }

    private void minimumTable(Minecraft client) {
        // 滚到底后表头完整露出在窗口里。
        if (ticks < SETTLE_TICKS) {
            return;
        }
        UiChecks.requireInside(frame(), component(HEADER));
        UiChecks.capture(client, "data-08-minimum-table.png");
        next(Stage.RESTORE);
    }

    private void restore() {
        // 回到默认尺寸，换英文前先排好。
        InventoryUiClient.runtime().workspace().windows().resize(window, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        next(Stage.ENGLISH);
    }

    private void english(Minecraft client) throws Exception {
        // 只换本次运行的语言、不写进选项文件；重载完成后同一窗口换成英文。
        if (ticks == 1) {
            client.getLanguageManager().setSelected(ENGLISH);
            work = client.reloadResourcePacks();
            return;
        }
        if (ticks < SETTLE_TICKS) {
            return;
        }
        layout();
        UiChecks.capture(client, "data-09-english.png");
        Files.writeString(client.gameDirectory.toPath().resolve("data-page-ok.txt"),
                "未开脉提示；经脉页签；数据总览；单条展开；本地五行样例（非服务器数据）；悬停说明；窄窗叠放；"
                        + "最小窗口滚动；英文界面。\n");
        System.out.println("DATA_PAGE_JOURNEY_OK empty/scene/overview/detail/sample/hover/compact/minimum/english");
        next(Stage.DONE);
        client.stop();
    }

    private void layout() {
        // 各区域都在数据页范围内，彼此不覆盖。
        UIComponent frame = frame();
        UiChecks.requireInside(module.meridianPanel().root(), frame);
        for (String id : List.of(SUMMARY, ELEMENTS_CARD, HEADER, ROWS)) {
            UiChecks.requireInside(frame, component(id));
        }
        UiChecks.requireSeparate(component(SUMMARY), component(ELEMENTS_CARD));
        UiChecks.requireSeparate(component(ELEMENTS_CARD), component(HEADER));
        UiChecks.requireSeparate(component(HEADER), component(ROWS));
    }

    private void stackedLayout() {
        // 叠放时元素卡在总览卡下方、同宽，两卡不越过滚动条；外层滚动条出现。
        ScrollContainer<?> frame = frame();
        UIComponent summary = component(SUMMARY);
        UIComponent elements = component(ELEMENTS_CARD);
        UiChecks.requireInside(module.meridianPanel().root(), frame);
        UiChecks.require(elements.y() >= summary.y() + summary.height(), "窄窗下两张卡没有上下叠放");
        UiChecks.require(elements.width() == summary.width(), "叠放的两张卡不一样宽");
        int scrollbar = frame.scrollbarThiccness();
        UiChecks.require(scrollbar > 0, "窄窗放不下时外层没有滚动条");
        int right = frame.x() + frame.width() - scrollbar;
        UiChecks.require(summary.x() >= frame.x() && summary.x() + summary.width() <= right, "总览卡压到了滚动条");
        UiChecks.requireSeparate(summary, elements);
        UiChecks.requireSeparate(elements, component(HEADER));
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
        // 平坦和平世界，开启作弊以便用正式调试命令布置身体。
        var difficulty = new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false /* hardcore */,
                false /* locked */);
        var info = new LevelSettings(world, GameType.SURVIVAL, difficulty, true /* allowCommands */,
                WorldDataConfiguration.DEFAULT);
        next(Stage.UNLOCK);
        client.createWorldOpenFlows().createFreshLevel(world, info, new WorldOptions(SEED, false /* structures */,
                false /* bonusChest */), registry -> registry.lookupOrThrow(Registries.WORLD_PRESET)
                .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
    }

    private void prepare(ServerPlayer player) {
        // 开四条脉、留四条未开；内腔外壁先铺游离态，再给三条经脉换成更密的相态，好让相态柱不止一根。
        String name = player.getScoreboardName();
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

    private static void cursor(Minecraft client, double guiX, double guiY) {
        // 把界面坐标换成窗口像素交给窗口的光标回调，和真实移动鼠标同一路径，不受桌面上系统光标的位置影响。
        long handle = client.getWindow().handle();
        double scale = client.getWindow().getGuiScale();
        GLFWCursorPosCallback callback = GLFW.glfwSetCursorPosCallback(handle, null);
        GLFW.glfwSetCursorPosCallback(handle, callback);
        UiChecks.require(callback != null, "窗口没有光标回调");
        callback.invoke(handle, guiX * scale, guiY * scale);
    }

    private ScrollContainer<?> frame() {
        // 数据页最外层的整页滚动。
        UIComponent found = component(FRAME);
        UiChecks.require(found instanceof ScrollContainer<?>, FRAME + " 不是滚动容器");
        return (ScrollContainer<?>) found;
    }

    private UIComponent component(String id) {
        // 经脉窗口里按 ID 找控件，找不到就是接线错了。
        UIComponent found = find(id);
        UiChecks.require(found != null, "经脉窗口里没有 " + id);
        return found;
    }

    private UIComponent find(String id) {
        // 当前页签下按 ID 查找，可能没有。
        return module.meridianPanel().root().childById(UIComponent.class, id);
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
