package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import io.wispforest.owo.ui.core.UIComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** 通过正式窗口复选框与按钮验证自动吐纳，不直接调用服务端活动接口。 */
final class AutomaticBreathingJourney {
    private int ticks;
    private boolean begun;
    private boolean captured;
    private boolean done;
    private double attention;

    boolean tick(Minecraft client, BodyUiModule module) {
        // 等待权威视图后点击真实组件，窗口开关不改写身体耐力。
        if (done) {
            return true;
        }
        if (++ticks == 1) {
            client.options.guiScale().set(2);
            client.resizeGui();
            InventoryUiClient.runtime().openWorkspace();
            InventoryUiClient.runtime().open("meridian_ui:cultivation", null);
            org.lwjgl.glfw.GLFW.glfwFocusWindow(client.getWindow().handle());
        }
        var panel = module.cultivationPanel();
        if (panel == null || ticks < 20) {
            return false;
        }
        var breath = panel.canvas().breathingView();
        if (!begun && ticks == 20) {
            require(!breath.getActive(), "自动验收开始前旧吐纳未停止：" + breath);
            attention = breath.getAttentionRatio();
            click(client, panel.root().childById(UIComponent.class, "breathing-automatic"));
            click(client, panel.root().childById(UIComponent.class, "breathing-start"));
        }
        if (!begun && ticks == 30 && !breath.getActive()) {
            // 首次教学需要再次确认，仍经过按钮命中链进入。
            click(client, panel.root().childById(UIComponent.class, "breathing-start"));
        }
        if (breath.getActive() && breath.getAutomatic()) {
            begun = true;
            require(breath.getAttentionRatio() >= attention - 1e-9, "自动吐纳消耗了精力");
            if (!captured && breath.getStaminaRatio() < .99) {
                Screenshot.grab(client.gameDirectory, "automatic-breathing.png", client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
                captured = true;
            }
        } else if (begun) {
            require(breath.getStaminaRatio() < .8, "自动吐纳未到耐力停止线就中断：stamina="
                    + breath.getStaminaRatio() + " status=" + breath.getStatus() + " failure=" + breath.getFailure());
            require(captured, "缺少自动吐纳截图");
            System.out.println("AUTOMATIC_BREATHING_JOURNEY_OK stamina=" + breath.getStaminaRatio());
            done = true;
        }
        require(ticks < 2400, "自动吐纳实际窗口回归超时");
        return done;
    }

    private static void click(Minecraft client, UIComponent component) {
        // 屏幕命中链保护复选框和按钮没有被三维画布遮挡。
        double x = component.x() + component.width() / 2.0;
        double y = component.y() + component.height() / 2.0;
        client.gui.screen().mouseClicked(UiChecks.mouse(x, y, 0), false);
        client.gui.screen().mouseReleased(UiChecks.mouse(x, y, 0));
    }

    private static void require(boolean condition, String message) {
        // 验收失败立即返回明确原因，不能继续生成通过标记。
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
