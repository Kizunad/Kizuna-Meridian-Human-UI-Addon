package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.BuiltinThemes;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import io.wispforest.owo.ui.component.LabelComponent;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
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
    private static final int HINT_RESERVED_HEIGHT = 24;
    private int ticks;
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
        int scenario = ticks / SCENARIO_TICKS;
        int step = ++ticks % SCENARIO_TICKS;
        if (scenario == SIZES.length) {
            finish(client, module);
            return true;
        }
        if (scenario == 0 && step == 1) {
            originalTheme = InventoryUiClient.runtime().theme();
            client.gui.setScreen(null);
            InventoryUiClient.runtime().workspace().windows().reset();
            InventoryUiClient.runtime().openWorkspace();
            body = InventoryUiClient.runtime().open("meridian_ui:body", null).key();
            GLFW.glfwFocusWindow(client.getWindow().handle());
        }
        if (step == 2) {
            selectTheme(scenario);
            int[] size = SIZES[scenario];
            GLFW.glfwSetWindowSize(client.getWindow().handle(), size[0], size[1]);
            client.options.guiScale().set(size[2]);
            client.resizeGui();
        }
        if (step == 15) {
            checkBody(client, module, scenario);
        }
        if (step == 20) {
            // 新尺寸下详情可能变长；用真实滚轮确认被裁掉的文字仍能读到。
            var root = module.panel().root();
            UIComponent details = root.childById(UIComponent.class, "information-scroll");
            informationTop = root.childById(UIComponent.class, "information").y();
            client.gui.screen().mouseScrolled(details.x() + details.width() / 2.0, details.y() + details.height() / 2.0, 0, -30);
        }
        if (step == 27) {
            var root = module.panel().root();
            UIComponent details = root.childById(UIComponent.class, "information-scroll");
            UIComponent information = root.childById(UIComponent.class, "information");
            require(information.height() <= details.height() || information.y() < informationTop, "窄窗口详情无法通过实际滚轮查看");
        }
        if (step == 30) {
            capture(client, "responsive-body-" + scenario + ".png");
            breathing = InventoryUiClient.runtime().open("meridian_ui:cultivation", null).key();
        }
        if (step == 45) {
            checkBreathing(client, module, scenario);
        }
        if (step == 55 && scenario == 0) {
            // 首次开始先出现指引，再次点击才真正开始；此后各组都要保持同一权威会话。
            tutorialSeen = !module.cultivationPanel().canvas().breathingView().getActive();
            capture(client, "responsive-tutorial.png");
            click(client, module.cultivationPanel().root().childById(UIComponent.class, "breathing-start"), 0);
        }
        if (step == 70) {
            require(module.cultivationPanel().canvas().breathingView().getActive(), "调整大小后不能通过实际按钮开始吐纳");
            // 最小化吐纳窗口，让下一组的身体窗口重新接收鼠标输入。
            require(InventoryUiClient.runtime().workspace().windows().minimize(breathing), "无法让身体窗口重新接收鼠标输入");
        }
        return false;
    }

    private void selectTheme(int scenario) {
        // 同一组件跨深浅主题继续接收输入，截图同时覆盖不同 GUI 缩放下的文字对比度。
        String theme;
        if (scenario % 2 == 0) {
            theme = BuiltinThemes.DARK.id();
        } else {
            theme = BuiltinThemes.LIGHT.id();
        }
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
            require(root == breathingRoot && panel.canvas().breathingView().getActive(), "改变分辨率重建或中断了吐纳窗口");
        }
        // 自动吐纳复选框与四个资源控件同样叠在场景里，不能互相遮挡命中。
        var controls = List.of(root.childById(UIComponent.class, "breathing-air"),
                root.childById(UIComponent.class, "breathing-reserve"),
                root.childById(UIComponent.class, "breathing-start"),
                root.childById(UIComponent.class, "breathing-qi"),
                root.childById(UIComponent.class, "breathing-automatic"));
        for (int index = 0; index < controls.size(); index++) {
            UIComponent control = controls.get(index);
            requireInside(panel.canvas(), control);
            int footerTop = panel.canvas().y() + panel.canvas().height() - HINT_RESERVED_HEIGHT;
            require(control.y() + control.height() <= footerTop, "吐纳控件覆盖底部操作提示：" + control.id());
            require(root.childAt(control.x() + control.width() / 2, control.y() + control.height() / 2) == control,
                    "吐纳控件的命中与绘制不一致：" + control.id());
            for (int other = index + 1; other < controls.size(); other++) {
                requireSeparate(control, controls.get(other));
            }
        }
        capture(client, "responsive-breathing-" + scenario + ".png");
        if (scenario == 0) {
            click(client, root.childById(UIComponent.class, "breathing-start"), 0);
        }
    }

    private void finish(Minecraft client, BodyUiModule module) {
        // 结束吐纳并关闭两个窗口，恢复默认窗口尺寸，后续旅程从同一基准开始。
        InventoryUiClient.runtime().theme(originalTheme);
        require(tutorialSeen, "首次开始吐纳没有先显示操作指引");
        InventoryUiClient.runtime().workspace().windows().close(breathing);
        InventoryUiClient.runtime().workspace().windows().close(body);
        require(module.panel() == null && module.cultivationPanel() == null, "关闭后窗口组件未清理");
        GLFW.glfwSetWindowSize(client.getWindow().handle(), 1440, 1000);
        client.options.guiScale().set(2);
        client.resizeGui();
        client.gui.setScreen(null);
        System.out.println("RESPONSIVE_JOURNEY_OK five sizes; body layout/selection/scroll/click; breathing layout/hit/active session; tutorial");
        done = true;
    }
}
