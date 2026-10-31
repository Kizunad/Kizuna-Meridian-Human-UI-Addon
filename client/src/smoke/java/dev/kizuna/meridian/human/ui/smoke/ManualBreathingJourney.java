package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathParticle;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import io.wispforest.owo.ui.core.UIComponent;
import org.lwjgl.glfw.GLFW;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;

import static dev.kizuna.meridian.human.ui.smoke.UiChecks.*;

/** 整合服内的手动吐纳回归：真实鼠标对准服务端粒子吸呼，验证布局、旋转、空气上下限、音效、消耗、关窗停止和独立重开。 */
final class ManualBreathingJourney {
    private int ticks;
    private boolean done;
    private WindowKey body;
    private WindowKey breathing;
    private SoundInstance lastSound;
    private final Set<String> heardSounds = new HashSet<>();
    private final SoundEventListener listener = (sound, sounds, range) -> {
        // 只记录本 UI 的吸呼声，确认真实声音管理器的播放与停止。
        if (sound.getIdentifier().getNamespace().equals("meridian_ui")) {
            lastSound = sound;
            heardSounds.add(sound.getIdentifier().getPath());
        }
    };
    private double attentionBefore;
    private double absorbedBefore;

    boolean tick(Minecraft client, BodyUiModule module) {
        // 时间轴按 tick 推进；所有资源结果读取服务端返回的吐纳视图。
        if (done) {
            return true;
        }
        ticks++;
        require(ticks < 2400, "手动吐纳回归超时");
        if (ticks == 1) {
            client.options.guiScale().set(2);
            client.resizeGui();
            client.getSoundManager().addListener(listener);
            InventoryUiClient.runtime().openWorkspace();
            body = InventoryUiClient.runtime().open("meridian_ui:body", null).key();
            breathing = InventoryUiClient.runtime().open("meridian_ui:cultivation", null).key();
            GLFW.glfwFocusWindow(client.getWindow().handle());
        }
        if (ticks == 330) {
            // 吐纳窗口已在 315 tick 关闭：确认音效完整播放后关掉身体窗口，再单独重开吐纳窗口。
            require(!client.getSoundManager().isActive(lastSound)
                    && heardSounds.containsAll(Set.of("breathing.inhale", "breathing.exhale")), "吸呼音效未完整播放或关窗后仍在播放");
            InventoryUiClient.runtime().workspace().windows().close(body);
            breathing = InventoryUiClient.runtime().open("meridian_ui:cultivation", null).key();
        }
        if (module.cultivationPanel() == null || ticks < 20) {
            return false;
        }
        BreathingView breath = module.cultivationPanel().canvas().breathingView();
        if (ticks == 20) {
            require(module.panel().root().childById(UIComponent.class, "breathing-start") == null, "身体窗口仍存在修炼操作");
            checkControls(module);
            capture(client, "breathing-ready.png");
            attentionBefore = breath.getAttentionRatio();
            absorbedBefore = breath.getAbsorbedQi();
            clickStart(client, module);
        }
        if (ticks == 26 && !breath.getActive()) {
            // 指引未读时第一次点击只显示指引，第二次才开始；已读时第一次点击已经开始。
            clickStart(client, module);
        }
        if (ticks == 30) {
            require(breath.getActive() && !breath.getAutomatic() && breath.getParticlesCount() > 0, "没有进入手动吐纳或没有可见粒子");
            // 经 Screen 中键拖动旋转；相机变化不改写服务端粒子坐标。
            var canvas = module.cultivationPanel().canvas();
            BreathParticle particle = breath.getParticles(0);
            var before = canvas.projectParticle(particle);
            double x = canvas.x() + canvas.width() * .5;
            double y = canvas.y() + canvas.height() * .5;
            client.gui.screen().mouseClicked(mouse(x, y, 2), false);
            client.gui.screen().mouseDragged(mouse(x + 85, y + 18, 2), 85, 18);
            client.gui.screen().mouseReleased(mouse(x + 85, y + 18, 2));
            require(before.distance(canvas.projectParticle(particle)) >= .01, "中键拖动没有改变三维粒子投影");
        }
        if (ticks == 35) {
            capture(client, "breathing-orbit.png");
        }
        if (ticks >= 40 && ticks <= 135 && ticks % 5 == 0) {
            steer(client, module, 0);
        }
        if (ticks == 85) {
            require(lastSound != null && client.getSoundManager().isActive(lastSound), "有效吸气没有播放呼吸音效");
            capture(client, "breathing-inhale.png");
        }
        if (ticks == 140) {
            require(Math.abs(breath.getAirLiters() - breath.getCapacityLiters()) <= .01, "持续吸气未被肺容量边界截断：" + breath.getAirLiters());
            capture(client, "breathing-full.png");
            client.gui.screen().mouseReleased(mouse(0, 0, 0));
        }
        if (ticks >= 150 && ticks <= 245 && ticks % 5 == 0) {
            steer(client, module, 1);
        }
        if (ticks == 190) {
            capture(client, "breathing-exhale.png");
        }
        if (ticks == 250) {
            require(breath.getAirLiters() <= .01, "持续呼气没有排空并停止：" + breath.getAirLiters());
            client.gui.screen().mouseReleased(mouse(0, 0, 1));
        }
        if (ticks == 256) {
            require(lastSound == null || !client.getSoundManager().isActive(lastSound), "排空或松键后呼吸音效没有停止");
        }
        if (ticks >= 260 && ticks <= 310 && ticks % 5 == 0) {
            steer(client, module, 0);
        }
        if (ticks == 315) {
            // 结束前读取服务端视图：手动吸呼确实消耗了精力，并且本连接有真实摄入。
            require(breath.getAttentionRatio() < attentionBefore && breath.getAbsorbedQi() > absorbedBefore,
                    "手动吐纳没有消耗精力或没有真实摄入：" + breath);
            client.gui.screen().mouseReleased(mouse(0, 0, 0));
            InventoryUiClient.runtime().workspace().windows().close(breathing);
        }
        if (ticks == 350) {
            // 关窗必须结束权威会话；修炼窗口可以在没有身体窗口时独立打开并再次开始。
            require(module.panel() == null && module.cultivationPanel() != null, "修炼不能独立打开");
            require(!breath.getActive(), "关窗没有结束手动吐纳");
            capture(client, "breathing-independent.png");
            clickStart(client, module);
        }
        if (ticks == 356 && !breath.getActive()) {
            clickStart(client, module);
        }
        if (ticks >= 365 && ticks <= 420 && ticks % 5 == 0) {
            steer(client, module, breath.getAirLiters() >= breath.getCapacityLiters() - .01 ? 1 : 0);
        }
        if (ticks == 425) {
            // 经脉已被前序夹具填满时服务端可提示配送暂时受阻，这不代表吐纳停止，只要求会话仍在进行。
            require(breath.getActive(), "重开窗口后无法再次吐纳：" + breath);
            client.gui.screen().mouseReleased(mouse(0, 0, 0));
            client.gui.screen().mouseReleased(mouse(0, 0, 1));
            click(client, module.cultivationPanel().root().childById(UIComponent.class, "breathing-start"), 0);
        }
        if (ticks == 440) {
            require(!breath.getActive(), "结束按钮没有停止吐纳");
            capture(client, "breathing-renewed.png");
            InventoryUiClient.runtime().workspace().windows().close(breathing);
            client.getSoundManager().removeListener(listener);
            client.gui.setScreen(null);
            System.out.println("MANUAL_BREATHING_JOURNEY_OK layout/orbit/inhale/full/exhale/empty/sound/cost/close/independent/restart");
            done = true;
        }
        return done;
    }

    private void checkControls(BodyUiModule module) {
        // 四个控件叠在三维场景内部且位于画布之上；中央仍交给相机与气息操作，按钮命中正确。
        var panel = module.cultivationPanel();
        var root = panel.root();
        var canvas = panel.canvas();
        UIComponent air = root.childById(UIComponent.class, "breathing-air");
        UIComponent reserve = root.childById(UIComponent.class, "breathing-reserve");
        UIComponent action = root.childById(UIComponent.class, "breathing-start");
        UIComponent qi = root.childById(UIComponent.class, "breathing-qi");
        // owo 0.13 已无 zIndex，同一场景按子组件顺序绘制：控件须与画布同属一个场景且排在画布之后。
        List<UIComponent> layers = canvas.parent().children();
        for (UIComponent control : List.of(air, reserve, action, qi)) {
            requireInside(canvas, control);
            require(control.parent() == canvas.parent() && layers.indexOf(control) > layers.indexOf(canvas),
                    "吐纳控件未叠在三维场景之上：" + control.id());
        }
        int centerX = canvas.x() + canvas.width() / 2;
        int bottomY = canvas.y() + canvas.height();
        // 余力在顶部，肺图靠左下，纳入量靠右下，按钮居中并在底部提示上方。
        require(reserve.y() == canvas.y() + 12 && air.x() == canvas.x() + 12 && air.y() + air.height() == bottomY - 12
                && qi.x() + qi.width() == canvas.x() + canvas.width() - 12 && qi.y() + qi.height() == bottomY - 12
                && Math.abs(action.x() + action.width() / 2 - centerX) <= 1 && action.y() + action.height() == bottomY - 34,
                "吐纳控件位置不符合场景布局");
        require(reserve.tooltip().size() == 3 && !qi.tooltip().isEmpty(), "资源悬浮明细不完整");
        require(root.childAt(centerX, canvas.y() + canvas.height() / 2) == canvas
                && root.childAt(action.x() + action.width() / 2, action.y() + action.height() / 2) == action,
                "场景旋转或按钮输入被叠加控件遮挡");
    }

    private static void clickStart(Minecraft client, BodyUiModule module) {
        // 经真实按钮链路开始或确认指引，不直接调用面板方法。
        click(client, module.cultivationPanel().root().childById(UIComponent.class, "breathing-start"), 0);
    }

    private void steer(Minecraft client, BodyUiModule module, int button) {
        // 鼠标对准相机中真实的服务端粒子并避开叠加控件；吸气选近处，呼气选远处。
        var panel = module.cultivationPanel();
        var canvas = panel.canvas();
        Comparator<BreathParticle> distance = Comparator.comparingDouble(particle ->
                Math.pow(particle.getX() - .5, 2) + Math.pow(particle.getY() - .4, 2) + Math.pow(particle.getZ() - .5, 2));
        if (button == 1) {
            distance = distance.reversed();
        }
        var particle = canvas.breathingView().getParticlesList().stream()
                .filter(value -> {
                    var point = canvas.projectParticle(value);
                    if (point.x <= .08 || point.x >= .92 || point.y <= .08 || point.y >= .85 || point.z <= -1 || point.z >= 1) {
                        return false;
                    }
                    int x = (int) (canvas.x() + point.x * canvas.width());
                    int y = (int) (canvas.y() + point.y * canvas.height());
                    return panel.root().childAt(x, y) == canvas;
                })
                .min(distance)
                .orElse(null);
        double x = canvas.x() + canvas.width() * .5;
        double y = canvas.y() + canvas.height() * .45;
        if (particle != null) {
            var point = canvas.projectParticle(particle);
            x = canvas.x() + point.x * canvas.width();
            y = canvas.y() + point.y * canvas.height();
        }
        if (button == 1) {
            client.gui.screen().mouseReleased(mouse(0, 0, 0));
        } else {
            client.gui.screen().mouseReleased(mouse(0, 0, 1));
        }
        client.gui.screen().mouseClicked(mouse(x, y, button), false);
    }
}
