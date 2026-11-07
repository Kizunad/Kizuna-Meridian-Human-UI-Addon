package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension.Session;
import dev.kizuna.meridian.ui.client.ActionRow;
import dev.kizuna.meridian.ui.client.BodyCanvas;
import dev.kizuna.meridian.ui.client.BodyScene;
import dev.kizuna.meridian.ui.client.EffectSettings;
import dev.kizuna.meridian.ui.client.LayerToggle;
import dev.kizuna.meridian.ui.client.MeridianTheme.Bounds;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Positioning;
import io.wispforest.owo.ui.core.Size;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** 模型、图像资源与操作组件共处一张画布，输入只提交权威周天意图。 */
final class CultivationControls extends FlowLayout implements Session {
    private static final int FULL = 100;
    private static final int EDGE = 10;
    private static final int SIDE_WIDTH = 116;
    private static final int GAUGE_HEIGHT = 96;
    private static final int GATE_HEIGHT = 138;
    private static final int GAUGE_TOP = 54;
    private static final int BUTTON_WIDTH = 112;
    private static final int BUTTON_HEIGHT = 28;
    private static final int ACTION_BOTTOM = 20;
    private static final int AUTOMATIC_BOTTOM = 52;
    private static final int FOOTER_HEIGHT = 14;
    private static final int THRUST_WIDTH = 180;
    private static final int THRUST_HEIGHT = 36;
    private static final int MOTION_WIDTH = 58;
    private static final int COMPACT_WIDTH = 440;
    private static final int COMPACT_SIDE = 96;
    private static final int COMPACT_HEIGHT = 340;
    private static final int COMPACT_TOP = 42;
    private static final int COMPACT_BATCH_HEIGHT = 70;
    private static final int COMPACT_GATE_HEIGHT = 112;
    private static final int SECONDARY_BUTTON = 1;
    private final BodyCanvas canvas;
    private final CultivationView view;
    private final CultivationGauges gauges;
    private final ActionRow start;
    private final LayerToggle automatic;
    private final ActionRow motion;
    private boolean automaticSelected;
    private boolean startRequested;
    private boolean lastPush;
    private boolean lastStrike;
    private int arrangedWidth = -1;
    private int arrangedHeight = -1;

    CultivationControls(BodyScene scene, BodyCanvas canvas) {
        // 画布先挂载，边缘控件随后绘制；没有覆盖全屏的不可见输入层。
        super(Sizing.fill(FULL), Sizing.fill(FULL), Algorithm.VERTICAL);
        this.canvas = canvas;
        view = new CultivationView(scene);
        gauges = new CultivationGauges(view, () -> automaticSelected);
        id("middle-jiao-controls");
        start = action("middle-jiao-start", this::startLabel, this::startStop);
        automatic = new LayerToggle("自动周天", () -> automaticSelected, this::toggleAutomatic);
        automatic.id("middle-jiao-automatic");
        motion = action("middle-jiao-motion", this::motionLabel, EffectSettings::toggleMotion);
        mount();
    }

    private void mount() {
        // 操作成本保留在对应控件旁，详细真元账户放在图像悬浮中。
        canvas.circuitImage(CultivationGauges.QI);
        child(canvas);
        for (var gauge : gauges.components()) {
            child(gauge);
        }
        for (UIComponent action : List.of(start, automatic, motion)) {
            child(action);
        }
        start.tooltip(Component.literal("开始或停止周天。关闭窗口也会停止。"));
        motion.tooltip(Component.literal("左键切换动态或静态，右键调整光点上限。\n关口时机与按键规则保持一致。"));
        motion.mouseDown().subscribe((click, doubled) -> {
            if (click.button() != SECONDARY_BUTTON) {
                return false;
            }
            EffectSettings.cycleLimit();
            return true;
        });
    }

    private ActionRow action(String id, java.util.function.Supplier<String> label, Runnable action) {
        // 与吐纳复用同一套鼠标、键盘及主题按钮实现。
        var component = new ActionRow(label, () -> view.body() != null, action);
        component.id(id);
        return component;
    }

    @Override
    public void layout(Size space) {
        // 窗口尺寸变化时统一重排，位置与实际命中组件始终一致。
        if (width != arrangedWidth || height != arrangedHeight) {
            arrangedWidth = width;
            arrangedHeight = height;
            runAndDeferEvents(this::arrange);
        }
        super.layout(space);
    }

    private void arrange() {
        // 图像分列在人体两侧，开始与自动集中在下方，模型不再被整块控制条覆盖。
        int side = SIDE_WIDTH;
        boolean compact = width < COMPACT_WIDTH || height < COMPACT_HEIGHT;
        if (compact) {
            side = COMPACT_SIDE;
        }
        int right = width - side - EDGE;
        int center = (width - BUTTON_WIDTH) / 2;
        place(canvas, new Bounds(0, 0, width, height));
        place(gauges.thrust, new Bounds((width - THRUST_WIDTH) / 2, EDGE, THRUST_WIDTH, THRUST_HEIGHT));
        if (compact) {
            arrangeCompact(side);
        } else {
            place(gauges.batch, new Bounds(EDGE, GAUGE_TOP, side, GAUGE_HEIGHT));
            place(gauges.gate, new Bounds(right, GAUGE_TOP, side, GATE_HEIGHT));
        }
        place(start, new Bounds(center, height - ACTION_BOTTOM - BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT));
        place(automatic, new Bounds(center, height - AUTOMATIC_BOTTOM - BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT));
        place(motion, new Bounds(width - MOTION_WIDTH - EDGE, EDGE, MOTION_WIDTH, BUTTON_HEIGHT));
        place(gauges.footer, new Bounds(EDGE, height - FOOTER_HEIGHT, width - EDGE * 2, FOOTER_HEIGHT));
    }

    private void arrangeCompact(int side) {
        // 最小窗口把次要操作收到底边，图像仍可见且命中范围互不重叠。
        int right = width - side - EDGE;
        place(gauges.batch, new Bounds(EDGE, COMPACT_TOP, side, COMPACT_BATCH_HEIGHT));
        place(gauges.gate, new Bounds(right, COMPACT_TOP, side, COMPACT_GATE_HEIGHT));
    }

    private static void place(UIComponent component, Bounds bounds) {
        // 绝对布局仍使用 owo 组件树，缩放和键盘焦点由工作区处理。
        component.positioning(Positioning.absolute(Math.max(0, bounds.x()), Math.max(0, bounds.y())));
        component.sizing(Sizing.fixed(Math.max(1, bounds.width())), Sizing.fixed(Math.max(1, bounds.height())));
    }

    @Override
    public UIComponent component() {
        // 通用 UI 挂载整个场景，不再替人体控制条预留固定底边。
        return this;
    }

    @Override
    public void update(BodySnapshot body) {
        // 运行时开关以服务端确认值为准，空闲时允许选择下一次运行模式。
        view.update(body);
        var state = view.state();
        if (state.getRunning() || state.getAutomaticWaiting()) {
            automaticSelected = state.getAutomatic();
        }
        automatic.tooltip(Component.literal(view.automaticNote()));
        gauges.update();
    }

    @Override
    public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 标记随真实周天更新，绘制不读取或触发键盘输入。
        if (view.body() == null) {
            return;
        }
        view.effects(canvas);
        super.draw(context, mouseX, mouseY, partialTicks, delta);
    }

    private String startLabel() {
        // 按钮反馈跟随权威运行状态。
        if (view.state().getAutomaticWaiting()) {
            return "休息中 · 停止";
        }
        return view.state().getRunning() ? "停止周天" : "开始周天";
    }

    private String motionLabel() {
        // 减少动态时保留静态路线和关口颜色。
        String mode = EffectSettings.reducedMotion() ? "静态" : "动态";
        return mode + " " + EffectSettings.particleLimit();
    }

    private Operation startOperation() {
        // 模式选择只决定请求类型，不在客户端启动做功。
        return automaticSelected ? Operation.START_AUTOMATIC : Operation.START_MANUAL;
    }

    private void startStop() {
        // 停止与开始各发一次明确意图。
        var state = view.state();
        if (state.getRunning() || state.getAutomaticWaiting()) {
            send(Operation.STOP);
        } else {
            startRequested = true;
            send(startOperation());
        }
    }

    private void toggleAutomatic() {
        // 运行中可以切换模式，确认前的短暂选择仍由最新快照校正。
        automaticSelected = !automaticSelected;
        if (view.state().getAutomaticWaiting()) {
            if (!automaticSelected) {
                send(Operation.STOP);
            }
            return;
        }
        if (view.state().getRunning()) {
            send(startOperation());
        }
    }

    @Override
    public boolean keyPress(int key, int modifiers) {
        // 输入来自活动窗口，自动模式和其他窗口不能触发手动周天。
        var state = view.state();
        if (modifiers != 0 || !state.getRunning() || state.getAutomatic()) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_G) {
            if (!lastPush) {
                send(Operation.PUSH_ON);
                lastPush = true;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE) {
            if (!lastStrike) {
                send(Operation.STRIKE_GATE);
                lastStrike = true;
            }
            return true;
        }
        return false;
    }

    @Override
    public void keyRelease(int key) {
        // 重复释放不发包，失焦后仍能结束原有推力。
        if (key == GLFW.GLFW_KEY_G && lastPush) {
            send(Operation.PUSH_OFF);
            lastPush = false;
        }
        if (key == GLFW.GLFW_KEY_SPACE) {
            lastStrike = false;
        }
    }

    @Override
    public void cancelInput() {
        // 取消临时按键状态不停止服务器上的自动活动。
        keyRelease(GLFW.GLFW_KEY_G);
        keyRelease(GLFW.GLFW_KEY_SPACE);
    }

    private void send(Operation operation) {
        // 两个窗口共用连接内的操作序号，仍只提交输入边沿。
        CultivationActions.send(view.body(), operation);
    }

    @Override
    public void close() {
        // 在线关窗明确停止周天并释放模型表现，离线不向已销毁的连接发包。
        var state = view.state();
        cancelInput();
        if (Minecraft.getInstance().getConnection() != null
                && (startRequested || state.getRunning() || state.getAutomaticWaiting())) {
            send(Operation.STOP);
        }
        canvas.effects(List.of(), CultivationView.GOLD);
        canvas.clearCircuitImage();
    }
}
