package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.BuiltinThemes;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import dev.kizuna.meridian.ui.client.EffectSettings;
import io.wispforest.owo.ui.core.UIComponent;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import static dev.kizuna.meridian.human.ui.smoke.UiChecks.*;

/** 同一真实周天会话经历深浅主题和最小内容区，图像控件不得遮挡彼此或模型输入。 */
final class CultivationLayoutCheck {
    private static final int SCENARIO_TICKS = 30;
    private static final int CHECK_TICK = 16;
    private static final int MOTION_CHANGE_TICK = 20;
    private static final int MOTION_CAPTURE_TICK = 24;
    private static final int MOTION_RESTORE_TICK = 28;
    private static final int PARTICLE_PRESETS = 4;
    private static final int SECONDARY_BUTTON = 1;
    private static final int SCENARIO_COUNT = 3;
    private static final int NORMAL_WIDTH = 1440;
    private static final int NORMAL_HEIGHT = 1000;
    private static final int NORMAL_SCALE = 2;
    private static final int SMALL_WIDTH = 1280;
    private static final int SMALL_HEIGHT = 960;
    private static final int SMALL_SCALE = 3;
    private static final int DRAG_DISTANCE = 18;
    private static final double ROTATION_EPSILON = 0.001;
    private static final List<String> IDS = List.of("middle-jiao-batch", "middle-jiao-gate",
            "middle-jiao-thrust", "middle-jiao-start", "middle-jiao-automatic",
            "middle-jiao-motion", "middle-jiao-footer");
    private int ticks;
    private String originalTheme;
    private UIComponent originalRoot;

    boolean tick(Minecraft client, BodyUiModule module) {
        // 先等待真实窗口重排，再用屏幕输入与当前组件边界验证。
        int scenario = ticks / SCENARIO_TICKS;
        int step = ++ticks % SCENARIO_TICKS;
        if (scenario == SCENARIO_COUNT) {
            return true;
        }
        if (ticks == 1) {
            originalTheme = InventoryUiClient.runtime().theme();
            originalRoot = module.practicePanel().root();
        }
        if (step == 1) {
            resize(client, scenario);
        }
        if (step == CHECK_TICK) {
            check(module);
            rotate(client, module);
            capture(client, "middle-jiao-layout-" + scenario + ".png");
            System.out.println("CULTIVATION_LAYOUT_OK " + scenario);
        }
        if (scenario == SCENARIO_COUNT - 1) {
            motion(client, module, step);
        }
        return false;
    }

    private static void motion(Minecraft client, BodyUiModule module, int step) {
        // 通过真实点击验证左右键分工，静态截图等待数帧后再读取。
        if (step == MOTION_CHANGE_TICK) {
            int originalLimit = EffectSettings.particleLimit();
            clickMotion(client, module, SECONDARY_BUTTON);
            require(EffectSettings.particleLimit() != originalLimit, "动态按钮右键未调整光点上限");
            for (int preset = 1; preset < PARTICLE_PRESETS; preset++) {
                clickMotion(client, module, SECONDARY_BUTTON);
            }
            require(EffectSettings.particleLimit() == originalLimit, "光点档位切换未形成完整循环");
            clickMotion(client, module, 0);
            require(EffectSettings.reducedMotion(), "动态按钮左键未启用静态模式");
        } else if (step == MOTION_CAPTURE_TICK) {
            capture(client, "middle-jiao-static.png");
        } else if (step == MOTION_RESTORE_TICK) {
            clickMotion(client, module, 0);
            require(!EffectSettings.reducedMotion(), "动态按钮左键未恢复动画");
            System.out.println("CULTIVATION_MOTION_CONTROLS_OK left/right/static/restore");
        }
    }

    private static void clickMotion(Minecraft client, BodyUiModule module, int button) {
        // 点击组件中心，既经过工作区命中检测，也经过 ActionRow 的上下文按键转发。
        var control = module.practicePanel().root().childById(UIComponent.class, "middle-jiao-motion");
        double x = control.x() + control.width() / 2.0;
        double y = control.y() + control.height() / 2.0;
        var screen = client.gui.screen();
        screen.mouseClicked(mouse(x, y, button), false /* doubled */);
        screen.mouseReleased(mouse(x, y, button));
    }

    private void resize(Minecraft client, int scenario) {
        // 中间一组逼近窗口最小尺寸，最后恢复正常尺寸与原主题。
        String theme = BuiltinThemes.DARK.id();
        int width = NORMAL_WIDTH;
        int height = NORMAL_HEIGHT;
        int scale = NORMAL_SCALE;
        if (scenario == 1) {
            theme = BuiltinThemes.LIGHT.id();
            width = SMALL_WIDTH;
            height = SMALL_HEIGHT;
            scale = SMALL_SCALE;
        } else if (scenario == SCENARIO_COUNT - 1) {
            theme = originalTheme;
        }
        InventoryUiClient.runtime().theme(theme);
        GLFW.glfwSetWindowSize(client.getWindow().handle(), width, height);
        client.options.guiScale().set(scale);
        client.resizeGui();
    }

    private void check(BodyUiModule module) {
        // 各控件实际命中范围既不越界也不互相覆盖，缩放不能重建原会话。
        var root = module.practicePanel().root();
        require(root == originalRoot, "缩放重建了周天场景");
        require(root.childById(UIComponent.class, "middle-jiao-reserves") == null, "中焦仍有外壁储备入口");
        require(root.childById(UIComponent.class, "middle-jiao-breakthrough") == null, "突破入口仍放在中焦");
        var scene = root.childById(UIComponent.class, "middle-jiao-controls");
        List<UIComponent> controls = IDS.stream().map(id -> root.childById(UIComponent.class, id)).toList();
        for (int index = 0; index < controls.size(); index++) {
            UIComponent control = controls.get(index);
            requireInside(scene, control);
            for (int other = index + 1; other < controls.size(); other++) {
                requireSeparate(control, controls.get(other));
            }
        }
    }

    private static void rotate(Minecraft client, BodyUiModule module) {
        // 空白模型区通过真实屏幕拖动，保护完整场景挂载后的输入穿透。
        var canvas = module.practicePanel().canvas();
        var camera = canvas.camera();
        float previous = camera.yaw();
        double x = canvas.x() + canvas.width() / 2.0;
        double y = canvas.y() + canvas.height() / 2.0;
        var screen = client.gui.screen();
        screen.mouseClicked(mouse(x, y, 0), false /* doubled */);
        screen.mouseDragged(mouse(x + DRAG_DISTANCE, y, 0), DRAG_DISTANCE, 0);
        screen.mouseReleased(mouse(x + DRAG_DISTANCE, y, 0));
        camera.settle();
        require(Math.abs(camera.yaw() - previous) > ROTATION_EPSILON, "周天控件挡住了人体拖动");
        canvas.overview();
        camera.settle();
    }
}
