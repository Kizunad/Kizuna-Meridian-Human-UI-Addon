package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.BuiltinThemes;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import io.wispforest.owo.ui.component.LabelComponent;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.meridian.human.fabric.HumanUiSmokeInputs;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.UIComponent;
import org.lwjgl.glfw.GLFW;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;

import static dev.kizuna.meridian.human.ui.smoke.UiChecks.*;

/** 整合服内的响应式回归：同一组“检视”“吐纳”窗口经历多组真实分辨率与 GUI 缩放，布局、选择、输入和权威吐纳状态都要保留。 */
final class ResponsiveJourney {
    // 宽、高、GUI 缩放（0 为自动）；每组 90 tick。
    private static final int[][] SIZES = {
            {1440, 1000, 2}, {1440, 1000, 4}, {960, 720, 3}, {1920, 1080, 0}, {1920, 1080, 2}
    };
    private static final int SCENARIO_TICKS = 90;
    private static final int RESIZE_TICK = 2;
    private static final int BODY_CHECK_TICK = 15;
    private static final int SCROLL_TICK = 20;
    private static final int SCROLL_CHECK_TICK = 27;
    private static final int BODY_CAPTURE_TICK = 30;
    private static final int BREATHING_CHECK_TICK = 45;
    private static final int TUTORIAL_CONFIRM_TICK = 55;
    private static final int MINIMIZE_TICK = 70;
    private static final int DETAILS_SCROLL = -30;
    private static final int HINT_RESERVED_HEIGHT = 24;
    private static final List<String> THEMES = List.of(
            BuiltinThemes.INK_JADE.id(), BuiltinThemes.XUAN_PAPER.id(), BuiltinThemes.XUAN_HUANG.id());
    private int ticks;
    private final HumanUiSmokeInputs inputs = new HumanUiSmokeInputs();
    private boolean awaitingStart;
    private String breathingSession;
    private boolean done;
    private boolean tutorialSeen;
    private WindowKey body;
    private WindowKey breathing;
    private UIComponent bodyRoot;
    private UIComponent breathingRoot;
    private int informationTop;
    private String originalTheme;

    boolean tick(Minecraft client, BodyUiModule module) throws Exception {
        // 每组先调整真实窗口，再检查身体窗口和吐纳窗口；吐纳从第一组开始保持服务端活动。
        if (done) {
            return true;
        }
        inputs.check();
        if (!confirmStart(module)) {
            return false;
        }
        int scenario = ticks / SCENARIO_TICKS;
        int step = ++ticks % SCENARIO_TICKS;
        if (scenario == SIZES.length) {
            finish(client, module);
            return true;
        }
        if (scenario == 0 && step == 1) {
            open(client);
        }
        resize(client, scenario, step);
        bodyStep(client, module, scenario, step);
        breathingStep(client, module, scenario, step);
        return false;
    }

    private boolean confirmStart(BodyUiModule module) {
        // 第一组等待真实按钮的权威开始回执，迟到的响应不能被当作启动失败或跳过后续检查。
        if (!awaitingStart) {
            return true;
        }
        var breath = module.cultivationPanel().canvas().breathingView();
        if (!breath.getActive()) {
            return false;
        }
        breathingSession = breath.getSessionId();
        awaitingStart = false;
        return true;
    }

    private void open(Minecraft client) {
        // 清理旧窗口布局，让同一组实际组件依次经历全部尺寸。
        originalTheme = InventoryUiClient.runtime().theme();
        client.gui.setScreen(null);
        InventoryUiClient.runtime().workspace().windows().reset();
        InventoryUiClient.runtime().openWorkspace();
        body = InventoryUiClient.runtime().open("meridian_ui:body", null).key();
        GLFW.glfwFocusWindow(client.getWindow().handle());
    }

    private void resize(Minecraft client, int scenario, int step) {
        // 改真实窗口和 GUI 缩放，不能只缩测试夹具的组件边界。
        if (step == RESIZE_TICK) {
            selectTheme(scenario);
            int[] size = SIZES[scenario];
            GLFW.glfwSetWindowSize(client.getWindow().handle(), size[0], size[1]);
            client.options.guiScale().set(size[2]);
            client.resizeGui();
        }
    }

    private void bodyStep(Minecraft client, BodyUiModule module, int scenario, int step) {
        // 新尺寸下检查检视布局，并用真实滚轮读取被裁掉的详情。
        if (step == BODY_CHECK_TICK) {
            checkBody(client, module, scenario);
        }
        if (step == SCROLL_TICK) {
            // 新尺寸下详情可能变长；用真实滚轮确认被裁掉的文字仍能读到。
            var root = module.panel().root();
            UIComponent details = root.childById(UIComponent.class, "information-scroll");
            informationTop = root.childById(UIComponent.class, "information").y();
            client.gui.screen().mouseScrolled(details.x() + details.width() / 2.0,
                    details.y() + details.height() / 2.0, 0, DETAILS_SCROLL);
        }
        if (step == SCROLL_CHECK_TICK) {
            var root = module.panel().root();
            UIComponent details = root.childById(UIComponent.class, "information-scroll");
            UIComponent information = root.childById(UIComponent.class, "information");
            require(information.height() <= details.height() || information.y() < informationTop, "窄窗口详情无法通过实际滚轮查看");
        }
        if (step == BODY_CAPTURE_TICK) {
            capture(client, "responsive-body-" + scenario + ".png");
            breathing = InventoryUiClient.runtime().open("meridian_ui:cultivation", null).key();
        }
    }

    private void breathingStep(Minecraft client, BodyUiModule module, int scenario, int step) {
        // 第一组经真实按钮开始，后续尺寸必须始终保留同一窗口和手动会话。
        if (step == BREATHING_CHECK_TICK) {
            checkBreathing(client, module, scenario);
        }
        if (step == TUTORIAL_CONFIRM_TICK && scenario == 0) {
            // 首次开始先出现指引，再次点击才真正开始；此后各组都要保持同一权威会话。
            var panel = module.cultivationPanel();
            tutorialSeen = !panel.canvas().breathingView().getActive();
            capture(client, "responsive-tutorial.png");
            inputs.keepBreathing(client.getSingleplayerServer(), client.player.getUUID());
            click(client, panel.root().childById(UIComponent.class, "breathing-start"), 0);
            awaitingStart = true;
        }
        if (step == MINIMIZE_TICK) {
            require(module.cultivationPanel().canvas().breathingView().getActive(), "调整大小后不能通过实际按钮开始吐纳");
            // 最小化吐纳窗口，让下一组的身体窗口重新接收鼠标输入。
            require(InventoryUiClient.runtime().workspace().windows().minimize(breathing), "无法让身体窗口重新接收鼠标输入");
        }
    }

    private void selectTheme(int scenario) {
        // 同一组件轮流使用三套主题，截图覆盖不同 GUI 缩放下的文字、控制按钮与输入状态。
        String theme = THEMES.get(scenario % THEMES.size());
        InventoryUiClient.runtime().theme(theme);
    }

    private void checkBody(Minecraft client, BodyUiModule module, int scenario) {
        // 身体组件、选择与图层跨尺寸保留；各区域都在内容区内且互不覆盖。
        var panel = module.panel();
        if (scenario == 0) {
            bodyRoot = panel.root();
            panel.layer("organs");
        } else {
            require(panel.root() == bodyRoot && panel.selectedId().equals("heart")
                    && panel.visibleLayers().equals(Set.of("organs")), "改变分辨率丢失了身体组件、选择或图层状态");
        }
        var root = panel.root();
        var title = root.childById(LabelComponent.class, "body-title");
        require(title.color().get().argb() == InventoryUiClient.runtime().color(ThemeTokens.TEXT),
                "已打开的检视窗口未跟随主题更新文字颜色");
        UIComponent layers = root.childById(UIComponent.class, "layers-scroll");
        UIComponent details = root.childById(UIComponent.class, "information-scroll");
        for (UIComponent area : List.of(layers, details, panel.canvas(),
                root.childById(UIComponent.class, "body-title"), root.childById(UIComponent.class, "body-hint"))) {
            requireInside(root, area);
        }
        requireSeparate(layers, panel.canvas());
        requireSeparate(layers, details);
        requireSeparate(panel.canvas(), details);
        panel.overview();
        panel.canvas().camera().settle();
        clickBody(client, module, "heart");
        root.childById(ScrollContainer.class, "information-scroll").scrollTo(0);
        System.out.println("RESPONSIVE_BODY " + scenario + " " + client.getWindow().getGuiScaledWidth() + "x"
                + client.getWindow().getGuiScaledHeight() + " content=" + root.width() + "x" + root.height());
    }

    private void checkBreathing(Minecraft client, BodyUiModule module, int scenario) {
        // 吐纳控件都叠在三维画布内、互不覆盖且命中与绘制一致；尺寸变化不重建组件也不中断权威会话。
        var panel = module.cultivationPanel();
        var root = panel.root();
        if (scenario == 0) {
            breathingRoot = root;
        } else {
            var breath = panel.canvas().breathingView();
            require(root == breathingRoot && breath.getActive(), "改变分辨率重建或中断了吐纳窗口");
            require(breath.getSessionId().equals(breathingSession), "改变分辨率重建了权威吐纳会话");
        }
        checkBreathingControls(module);
        capture(client, "responsive-breathing-" + scenario + ".png");
        if (scenario == 0) {
            click(client, root.childById(UIComponent.class, "breathing-start"), 0);
        }
    }

    private void checkBreathingControls(BodyUiModule module) {
        // 控件必须都在场景中、互不遮挡，并把底部操作提示留出来。
        var panel = module.cultivationPanel();
        var root = panel.root();
        var canvas = panel.canvas();
        // 自动吐纳复选框与四个资源控件同样叠在场景里，不能互相遮挡命中。
        var controls = List.of(root.childById(UIComponent.class, "breathing-air"),
                root.childById(UIComponent.class, "breathing-reserve"),
                root.childById(UIComponent.class, "breathing-start"),
                root.childById(UIComponent.class, "breathing-qi"),
                root.childById(UIComponent.class, "breathing-automatic"));
        int footerTop = canvas.y() + canvas.height() - HINT_RESERVED_HEIGHT;
        for (int index = 0; index < controls.size(); index++) {
            UIComponent control = controls.get(index);
            requireInside(canvas, control);
            int left = control.x();
            int top = control.y();
            int height = control.height();
            require(top + height <= footerTop, "吐纳控件覆盖底部操作提示：" + control.id());
            require(root.childAt(left + control.width() / 2, top + height / 2) == control,
                    "吐纳控件的命中与绘制不一致：" + control.id());
            for (int other = index + 1; other < controls.size(); other++) {
                requireSeparate(control, controls.get(other));
            }
        }
    }

    private void finish(Minecraft client, BodyUiModule module) {
        // 结束吐纳并关闭两个窗口，恢复默认窗口尺寸，后续旅程从同一基准开始。
        InventoryUiClient.runtime().theme(originalTheme);
        require(tutorialSeen, "首次开始吐纳没有先显示操作指引");
        inputs.stop();
        InventoryUiClient.runtime().workspace().windows().close(breathing);
        InventoryUiClient.runtime().workspace().windows().close(body);
        require(module.panel() == null && module.cultivationPanel() == null, "关闭后窗口组件未清理");
        int[] initialSize = SIZES[0];
        GLFW.glfwSetWindowSize(client.getWindow().handle(), initialSize[0], initialSize[1]);
        client.options.guiScale().set(initialSize[2]);
        client.resizeGui();
        client.gui.setScreen(null);
        System.out.println("RESPONSIVE_JOURNEY_OK five sizes; body layout/selection/scroll/click; breathing layout/hit/active session; tutorial");
        done = true;
    }
}
