package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import dev.kizuna.meridian.ui.client.MeridianPanel;
import io.wispforest.owo.ui.core.UIComponent;
import java.util.List;
import net.minecraft.client.Minecraft;
import static dev.kizuna.meridian.human.ui.smoke.UiChecks.*;

/** 真实侧栏打开独立练习窗口，保留完整画面并验证输入释放与关闭隔离。 */
final class MeridianSceneJourney {
    private static final int STEP_TICKS = 10;
    private static final double INPUT_X = 0.85;
    private static final double INPUT_Y = 0.4;
    private static final String MERIDIANS = "meridian_ui:meridians";
    private static final String BREATHING = "meridian_ui:cultivation";
    private static final String PRACTICE = "meridian_ui:practice";
    private int ticks;
    private WindowState meridians;
    private WindowState breathing;
    private WindowState practice;
    private MeridianPanel panel;
    private boolean done;

    boolean tick(Minecraft client, BodyUiModule module) {
        // 输入之后等待真实服务端回执和界面排布，窗口通过正式菜单和侧栏打开。
        if (done) {
            return true;
        }
        ticks++;
        if (ticks == 1) {
            InventoryUiClient.runtime().openWorkspace();
            meridians = InventoryUiClient.runtime().open(MERIDIANS, null);
            panel = module.meridianPanel();
        }
        if (ticks % STEP_TICKS != 0) {
            return false;
        }
        switch (ticks / STEP_TICKS) {
            case 1 -> openBreathing(client, module);
            case 2 -> start(client, module);
            case 3 -> inhale(client, module);
            case 4 -> openPractice(client, module);
            case 5 -> checkIndependent(client, module);
            case 6 -> closeMeridians(module);
            case 7 -> finish(client, module);
            default -> { /* 等待权威状态。 */ }
        }
        return done;
    }

    private void openBreathing(Minecraft client, BodyUiModule module) {
        // 图标位于模型下方，真实点击打开独立窗口。
        var queue = panel.root().childById(UIComponent.class, "meridian-actions");
        requireInside(panel.root(), queue);
        requireSeparate(panel.canvas(), queue);
        require(panel.root().childById(UIComponent.class, "meridian-breakthrough") == null,
                "不满足条件时仍显示突破入口");
        capture(client, "meridian-action-queue.png");
        rail(client, panel, BREATHING);
        require(module.cultivationPanel() != null, "侧栏未打开吐纳");
        breathing = InventoryUiClient.runtime().open(BREATHING, null);
        require(!breathing.key().equals(meridians.key()), "吐纳仍与经脉共用窗口");
        require(panel.root().childById(UIComponent.class, "breathing-start") == null, "吐纳嵌在经脉窗口内");
        var original = module.cultivationPanel();
        InventoryUiClient.runtime().open(BREATHING, null);
        require(module.cultivationPanel() == original, "重复打开重建了吐纳场景");
    }

    private void start(Minecraft client, BodyUiModule module) {
        // 保留原有肺、气流和打坐画布，按钮通过真实屏幕点击。
        capture(client, "meridian-restored-breathing.png");
        var action = module.cultivationPanel().root().childById(UIComponent.class, "breathing-start");
        click(client, action, 0);
    }

    private void inhale(Minecraft client, BodyUiModule module) {
        // 未被边缘控件覆盖的画布区域接收手动吸气。
        var canvas = module.cultivationPanel().canvas();
        require(canvas.breathingView().getActive(), "独立吐纳未开始");
        double x = canvas.x() + canvas.width() * INPUT_X;
        double y = canvas.y() + canvas.height() * INPUT_Y;
        client.gui.screen().mouseClicked(mouse(x, y, 0), false /* doubled */);
    }

    private void openPractice(Minecraft client, BodyUiModule module) {
        // 按住吸气时经侧栏切到另一窗口，旧鼠标方向必须被释放。
        require(module.cultivationPanel().canvas().breathingView().getAction() == BreathAction.INHALE,
                "真实画布未收到吸气");
        InventoryUiClient.runtime().open(MERIDIANS, null);
        rail(client, panel, PRACTICE);
        require(module.practicePanel() != null, "侧栏未打开周天");
        practice = InventoryUiClient.runtime().open(PRACTICE, null);
    }

    private void checkIndependent(Minecraft client, BodyUiModule module) {
        // 三个场景在窗口栏各有身份，完整周天布局只挂载在自己的窗口中。
        require(!practice.key().equals(meridians.key()) && !practice.key().equals(breathing.key()),
                "周天未使用独立窗口");
        require(module.cultivationPanel().canvas().breathingView().getAction() == BreathAction.HOLD,
                "切窗后旧吸气输入未释放");
        require(module.practicePanel().canvas() != panel.canvas(), "周天替换了经脉画布");
        require(panel.root().childById(UIComponent.class, "middle-jiao-start") == null,
                "周天嵌在经脉窗口内");
        requireInside(module.practicePanel().root(), module.practicePanel().canvas());
        var original = module.practicePanel();
        require(original.root().childById(UIComponent.class, "middle-jiao-reserves") == null,
                "中焦仍显示外壁储备按钮");
        InventoryUiClient.runtime().open(PRACTICE, null);
        require(module.practicePanel() == original, "重复打开重建了周天场景");
        capture(client, "meridian-restored-practice.png");
    }

    private void closeMeridians(BodyUiModule module) {
        // 关掉导航窗口不终止另两个独立练习。
        InventoryUiClient.runtime().workspace().windows().close(meridians.key());
        require(module.meridianPanel() == null, "经脉窗口未关闭");
        require(module.cultivationPanel() != null && module.practicePanel() != null,
                "关闭经脉误关独立练习窗口");
    }

    private void finish(Minecraft client, BodyUiModule module) {
        // 独立关闭仍需释放各自资源，不能遗留组件或继续接收输入。
        InventoryUiClient.runtime().workspace().windows().close(breathing.key());
        require(module.cultivationPanel() == null && module.practicePanel() != null,
                "关闭吐纳影响周天窗口");
        InventoryUiClient.runtime().workspace().windows().close(practice.key());
        require(module.practicePanel() == null, "周天关窗遗留会话");
        client.gui.setScreen(null);
        done = true;
        System.out.println("MERIDIAN_SCENE_JOURNEY_OK independent/rail/reuse/input-release/close");
    }

    private static void rail(Minecraft client, MeridianPanel panel, String feature) {
        // 按功能身份寻找真实导航控件，不依赖窗口位置。
        String id = "meridian-rail-" + feature.replace(':', '-');
        click(client, panel.root().childById(UIComponent.class, id), 0);
    }

    static void locked(Minecraft client, BodyUiModule module) {
        // 未读对应残卷时队列隐藏练习，和修炼菜单使用同一权限。
        var window = InventoryUiClient.runtime().open(MERIDIANS, null);
        var panel = module.meridianPanel();
        for (String feature : List.of(BREATHING, PRACTICE)) {
            String id = "meridian-rail-" + feature.replace(':', '-');
            require(panel.root().childById(UIComponent.class, id) == null, "未解锁操作未从队列隐藏");
            require(module.cultivationPanel() == null && module.practicePanel() == null,
                    "侧栏绕过了残卷权限");
        }
        InventoryUiClient.runtime().workspace().windows().close(window.key());
    }
}
