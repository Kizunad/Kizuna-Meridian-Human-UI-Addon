package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.BuiltinThemes;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationState;
import dev.kizuna.meridian.human.ui.WallReserveModule;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import io.wispforest.owo.ui.core.UIComponent;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import static dev.kizuna.meridian.human.ui.smoke.UiChecks.*;

/** 用正式补量命令和真实窗口输入验证储备剖面、动员以及跨窗请求。 */
final class WallReserveJourney {
    private static final String FEATURE = "meridian_human_ui:reserves";
    private static final int SETTLE_TICKS = 20;
    private static final int MOTION_TICKS = 100;
    private static final int FRAME_INTERVAL = 4;
    private static final int MAX_SAMPLE_TICKS = 200;
    private static final int RESERVE_SETTLE_TICKS = 60;
    private static final int CURSOR_EDGE = 8;
    private static final double DENSITY = 0.099;
    private static final double LUMEN_AMOUNT = 800;
    private static final double HALF = 0.5;
    private static final double QUARTER = 0.25;
    private static final double EPSILON = 0.001;
    private static final List<Double> SAMPLES = List.of(0.0, QUARTER, HALF, 1.0);
    private enum Stage { OPEN, SETTLE, SAMPLES, COMPACT, RESTORE, MOBILIZE, CONCURRENT, RESTART, CLOSE, FINISH, DONE }
    private Stage stage = Stage.OPEN;
    private int ticks;
    private int sample;
    private String originalTheme;
    private WindowKey key;
    private WallReserveModule reserves;
    private CompletableFuture<Void> fixture;
    private double wallBefore;

    boolean tick(Minecraft client, BodyUiModule bodies) throws Exception {
        // 命令在真实整合服线程执行，显示和判定始终等最新权威推送。
        if (fixture != null) {
            if (!fixture.isDone()) {
                return false;
            }
            fixture.join();
            fixture = null;
        }
        ticks++;
        switch (stage) {
            case OPEN -> open(client, bodies);
            case SETTLE -> settle(bodies);
            case SAMPLES -> sample(client, bodies);
            case COMPACT -> compact(client);
            case RESTORE -> restore(client, bodies);
            case MOBILIZE -> mobilize(client, bodies);
            case CONCURRENT -> concurrent(client, bodies);
            case RESTART -> restart(client, bodies);
            case CLOSE -> closed(client, bodies);
            case FINISH -> finish(bodies);
            case DONE -> { return true; }
        }
        return false;
    }

    private void open(Minecraft client, BodyUiModule bodies) {
        // 外壁使用独立入口，再次打开应聚焦同一窗口；中焦不再放重复导航。
        reserves = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(WallReserveModule.class::isInstance)
                .map(WallReserveModule.class::cast)
                .findFirst().orElseThrow();
        originalTheme = InventoryUiClient.runtime().theme();
        InventoryUiClient.runtime().open(FEATURE, null);
        require(reserves.root() != null, "独立外壁窗口未打开");
        UIComponent originalRoot = reserves.root();
        key = InventoryUiClient.runtime().open(FEATURE, null).key();
        require(reserves.root() == originalRoot, "重复打开创建了第二份储备会话");
        GLFW.glfwSetCursorPos(client.getWindow().handle(), CURSOR_EDGE, CURSOR_EDGE);
        fill(client, bodies.snapshot(), 0);
        next(Stage.SETTLE);
    }

    private void settle(BodyUiModule bodies) throws Exception {
        // 上一轮周天留下的脾加工仍会真实入账，先让它完成，再准备精确比例样本。
        require(ticks < MAX_SAMPLE_TICKS, "上一轮脾加工未完成");
        if (ticks >= RESERVE_SETTLE_TICKS && !state(bodies).getReserveProcessing()) {
            next(Stage.SAMPLES);
        }
    }

    private void sample(Minecraft client, BodyUiModule bodies) {
        // 空壁到满壁使用同一窗口，逐档留存真实 GPU 画面。
        double expected = SAMPLES.get(sample);
        if (ticks == 1) {
            fill(client, bodies.snapshot(), expected);
        }
        if (ticks < SETTLE_TICKS) {
            return;
        }
        BodySnapshot body = bodies.snapshot();
        double volume = body.getMeridianStatesList().stream()
                .flatMap(item -> item.getWallQi().getEntriesList().stream())
                .mapToDouble(qi -> qi.getAmount() / qi.getDensity()).sum();
        double capacity = body.getStructure().getMeridiansList().stream()
                .mapToDouble(item -> item.getWallVolume()).sum();
        double fraction = volume / capacity;
        if (Math.abs(fraction - expected) >= EPSILON) {
            require(ticks < MAX_SAMPLE_TICKS, "真实外壁补量比例不符：expected=" + expected
                    + " actual=" + fraction + " amount=" + wall(body) + " capacity=" + capacity);
            return;
        }
        layout();
        capture(client, "wall-reserve-fill-" + sample + ".png");
        sample++;
        ticks = 0;
        if (sample == SAMPLES.size()) {
            next(Stage.COMPACT);
        }
    }

    private void compact(Minecraft client) {
        // 浅色最小窗口仍保留剖面与按钮，不重建窗口。
        if (ticks == 1) {
            InventoryUiClient.runtime().theme(BuiltinThemes.LIGHT.id());
            InventoryUiClient.runtime().workspace().windows().resize(key, "340", "340");
        }
        if (ticks >= SETTLE_TICKS) {
            layout();
            capture(client, "wall-reserve-light-compact.png");
            next(Stage.RESTORE);
        }
    }

    private void restore(Minecraft client, BodyUiModule bodies) {
        // 动员前回到半壁储量；内腔保持在自然提取和沉淀阈值之间。
        if (ticks == 1) {
            InventoryUiClient.runtime().theme(originalTheme);
            InventoryUiClient.runtime().workspace().windows().resize(key, "460", "440");
            fill(client, bodies.snapshot(), HALF);
        }
        if (ticks >= SETTLE_TICKS) {
            wallBefore = wall(bodies.snapshot());
            click(client, control(), 0);
            GLFW.glfwSetCursorPos(client.getWindow().handle(), CURSOR_EDGE, CURSOR_EDGE);
            next(Stage.MOBILIZE);
        }
    }

    private void mobilize(Minecraft client, BodyUiModule bodies) throws Exception {
        // 连续截图只在真实动员阶段录制，空壁动画不能通过此检查。
        if (ticks % FRAME_INTERVAL == 0 && ticks <= MOTION_TICKS) {
            capture(client, String.format(Locale.ROOT, "wall-reserve-motion-%02d.png", ticks / FRAME_INTERVAL));
        }
        if (ticks < MOTION_TICKS) {
            return;
        }
        require(state(bodies).getMobilizing(), "独立窗口点击未开始动员");
        require(wall(bodies.snapshot()) < wallBefore, "动员没有提取真实外壁储备");
        practice(client, bodies, "middle-jiao-automatic");
        practice(client, bodies, "middle-jiao-start");
        InventoryUiClient.runtime().open(FEATURE, null);
        click(client, control(), 0);
        next(Stage.CONCURRENT);
    }

    private void concurrent(Minecraft client, BodyUiModule bodies) throws Exception {
        // 同一帧跨两个窗口发送请求，两个意图都必须被服务器接受。
        if (ticks < SETTLE_TICKS) {
            return;
        }
        CultivationState state = state(bodies);
        require(state.getRunning() && state.getAutomatic() && !state.getMobilizing(), "跨窗操作序号冲突");
        click(client, control(), 0);
        next(Stage.RESTART);
    }

    private void restart(Minecraft client, BodyUiModule bodies) throws Exception {
        // 关闭独立窗口只结束动员，不应顺带停止正在运行的周天。
        if (ticks < SETTLE_TICKS) {
            return;
        }
        require(state(bodies).getMobilizing(), "停止后无法重新动员");
        InventoryUiClient.runtime().workspace().windows().close(key);
        next(Stage.CLOSE);
    }

    private void closed(Minecraft client, BodyUiModule bodies) throws Exception {
        // 检查真实服务端状态后再恢复旧旅程所需的空壁起点。
        if (ticks < SETTLE_TICKS) {
            return;
        }
        CultivationState state = state(bodies);
        require(reserves.root() == null && state.getRunning() && !state.getMobilizing(), "关窗误停周天或遗留动员");
        practice(client, bodies, "middle-jiao-start");
        fill(client, bodies.snapshot(), 0);
        next(Stage.FINISH);
    }

    private void finish(BodyUiModule bodies) throws Exception {
        // 等停止确认后交还完整周天回归，不能把后台周天带进沉淀测试。
        if (ticks >= SETTLE_TICKS) {
            require(!state(bodies).getRunning(), "储备验收后周天未停止");
            System.out.println("WALL_RESERVE_JOURNEY_OK fill/layout/mobilize/concurrent/close");
            next(Stage.DONE);
        }
    }

    private void fill(Minecraft client, BodySnapshot body, double fraction) {
        // 只用已有管理员命令准备资源，生产界面仍通过正式操作通道动员。
        double capacity = body.getStructure().getMeridiansList().stream()
                .mapToDouble(item -> item.getWallVolume()).sum();
        var id = client.player.getUUID();
        var server = client.getSingleplayerServer();
        fixture = CompletableFuture.runAsync(() -> {
            var player = server.getPlayerList().getPlayer(id);
            String prefix = "meridian qi set " + player.getScoreboardName() + " ";
            require(runCommand(player, prefix + LUMEN_AMOUNT + " all lumen " + DENSITY) == 1, "内腔补量失败");
            double amount = capacity * fraction * DENSITY;
            require(runCommand(player, prefix + amount + " all wall " + DENSITY) == 1, "外壁补量失败");
        }, server);
    }

    private void layout() {
        // 场景读数和使用按钮必须在真实内容区内且互不覆盖。
        var root = reserves.root();
        var canvas = root.childById(UIComponent.class, "wall-reserve-canvas");
        var action = control();
        for (UIComponent item : List.of(canvas, action)) {
            requireInside(root, item);
        }
        requireSeparate(canvas, action);
    }

    private UIComponent control() {
        // 通过正式组件身份定位按钮，屏幕输入仍经过工作区遮挡和焦点处理。
        return reserves.root().childById(UIComponent.class, "wall-reserve-mobilize");
    }

    private static void practice(Minecraft client, BodyUiModule bodies, String id) {
        // 周天窗口先取焦点，避免隐藏在储备窗口下方的按钮被错误点击。
        InventoryUiClient.runtime().open("meridian_ui:practice", null);
        click(client, bodies.practicePanel().root().childById(UIComponent.class, id), 0);
    }

    private static CultivationState state(BodyUiModule bodies) throws Exception {
        // 操作结果只从客户端收到的权威身体读取。
        return bodies.snapshot().getAddonProgressOrThrow("human:cultivation").unpack(CultivationState.class);
    }

    private static double wall(BodySnapshot body) {
        // 动员前后比较真实外壁账户，不从显示动画推导资源变化。
        return body.getMeridianStatesList().stream().flatMap(item -> item.getWallQi().getEntriesList().stream())
                .mapToDouble(Qi::getAmount).sum();
    }

    private void next(Stage next) {
        // 阶段只安排验收输入，不改变服务器修炼时钟。
        stage = next;
        ticks = 0;
    }
}
