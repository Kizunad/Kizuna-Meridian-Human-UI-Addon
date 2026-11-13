package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingView;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import io.wispforest.owo.ui.core.UIComponent;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** 重生后宿主新建吐纳会话、序号从头算；吐纳窗不点按钮也要换成新会话的回执，再经正式按钮开停一次。 */
final class RespawnBreathingJourney {
    /** 打开窗口后等这么多 tick 再读视图，和其他吐纳验收一样。 */
    private static final int SETTLE_TICKS = 20;
    /** 等新会话回执、等开始、等结束，每一步各自的时限；刚复活耐力为 0，要等它回上来才开始得了。 */
    private static final int STEP_TIMEOUT_TICKS = 600;
    /** 开始被拒（耐力还没回上来）后隔这么多 tick 再点一次。 */
    private static final int START_RETRY_TICKS = 40;
    /** 吐纳开始后等这么多 tick 再截图，让窗口按新回执至少重画一帧（截图取的是上一帧）。 */
    private static final int CAPTURE_DELAY_TICKS = 3;
    private enum Step { NEW_SESSION, REOPENED, STARTING, RUNNING, STOPPING, DONE }
    private int beforeTicks;
    private int afterTicks;
    private int stepTicks;
    private Step step = Step.NEW_SESSION;
    private BreathingView deathView;
    private String respawnSession;

    boolean beforeDeath(Minecraft client, BodyUiModule module) {
        // 死前打开吐纳窗记下当前会话；序号必须往上走过，否则旧的比较规则也不会拒新会话的回执。
        if (deathView != null) {
            return true;
        }
        if (++beforeTicks == 1) {
            open(client);
        }
        var panel = module.cultivationPanel();
        if (panel == null || beforeTicks < SETTLE_TICKS) {
            return false;
        }
        var breath = panel.canvas().breathingView();
        UiChecks.require(!breath.getSessionId().isEmpty(), "吐纳回执没带会话标识");
        UiChecks.require(breath.getSequence() > 0, "死前吐纳序号没往上走过：" + breath.getSequence());
        deathView = breath;
        return true;
    }

    boolean afterRespawn(Minecraft client, BodyUiModule module) {
        // 重生后打开吐纳窗，按步骤等权威回执；每步超时就报出卡住时窗口里的吐纳状态。
        if (step == Step.DONE) {
            return true;
        }
        if (++afterTicks == 1) {
            // 不关死前的窗口、不点按钮：客户端不发新控制，序号停在死前，只比序号的旧规则会拒掉新会话的回执。
            open(client);
        }
        var panel = module.cultivationPanel();
        if (panel == null || afterTicks < SETTLE_TICKS) {
            return false;
        }
        var breath = panel.canvas().breathingView();
        UiChecks.require(++stepTicks < STEP_TIMEOUT_TICKS, "重生后吐纳窗停在 " + step + "：" + breath);
        advance(client, panel.root().childById(UIComponent.class, "breathing-start"), breath);
        return step == Step.DONE;
    }

    private void advance(Minecraft client, UIComponent start, BreathingView breath) {
        // 先等新会话回执（不点按钮），换新窗后点开始等吐纳开始（被拒就隔一会儿重点），吐纳几 tick 后截图、点结束，等吐纳停下。
        switch (step) {
            case NEW_SESSION -> awaitNewSession(client, breath);
            case REOPENED -> clickStart(client, start);
            case STARTING -> awaitStart(client, start, breath);
            case RUNNING -> captureAndStop(client, start, breath);
            case STOPPING -> awaitStop(breath);
            default -> { /* DONE 在 afterRespawn 开头返回。 */ }
        }
    }

    private void awaitNewSession(Minecraft client, BreathingView breath) {
        // 窗口还显示死前会话就接着等；换成新会话后它必须是停着的。
        // 然后关掉死前的窗口再开新窗：新面板不勾自动，点开始走手动吐纳；复活后耐力低于自动开始线，自动会按规则被拒。
        if (breath.getSessionId().equals(deathView.getSessionId())) {
            return;
        }
        UiChecks.require(!breath.getActive(), "重生后的新会话不该在吐纳：" + breath);
        respawnSession = breath.getSessionId();
        InventoryUiClient.runtime().workspace().windows().reset();
        open(client);
        next(Step.REOPENED);
    }

    private void clickStart(Minecraft client, UIComponent start) {
        // 新窗口和首次打开一样等布局好再点开始。
        if (stepTicks < SETTLE_TICKS) {
            return;
        }
        UiChecks.click(client, start, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        next(Step.STARTING);
    }

    private void awaitStart(Minecraft client, UIComponent start, BreathingView breath) {
        // 刚复活耐力为 0，开始会被拒；隔一会儿重点，直到回执显示吐纳进行中，而且还是同一个新会话。
        if (!breath.getActive()) {
            if (stepTicks % START_RETRY_TICKS == 0) {
                UiChecks.click(client, start, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            }
            return;
        }
        UiChecks.require(breath.getSessionId().equals(respawnSession), "开始吐纳时会话标识变了：" + breath);
        next(Step.RUNNING);
    }

    private void captureAndStop(Minecraft client, UIComponent start, BreathingView breath) {
        // 留下重生后吐纳窗显示吐纳进行中的画面，再点结束；耐力太低时手动吐纳可能已自己停下，那就不用点。
        if (stepTicks < CAPTURE_DELAY_TICKS) {
            return;
        }
        UiChecks.capture(client, "respawn-breathing.png");
        if (breath.getActive()) {
            UiChecks.click(client, start, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
        next(Step.STOPPING);
    }

    private void awaitStop(BreathingView breath) {
        // 吐纳停下就算走完，日志留下死前和重生后的回执序号。
        if (breath.getActive()) {
            return;
        }
        System.out.println("RESPAWN_BREATHING_JOURNEY_OK death=" + deathView.getSequence()
                + " respawn=" + breath.getSequence());
        next(Step.DONE);
    }

    private static void open(Minecraft client) {
        // 和玩家一样经工作区打开吐纳窗，并把焦点给游戏窗口以便按钮命中。
        InventoryUiClient.runtime().openWorkspace();
        InventoryUiClient.runtime().open("meridian_ui:cultivation", null);
        GLFW.glfwFocusWindow(client.getWindow().handle());
    }

    private void next(Step step) {
        // 每步重新计时，日志留下真实步骤。
        this.step = step;
        stepTicks = 0;
        System.out.println("RESPAWN_BREATHING_STEP " + step);
    }
}
