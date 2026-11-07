package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import dev.kizuna.meridian.ui.api.MeridianAction;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation;
import dev.kizuna.meridian.progression.client.ClientProgression;
import java.util.List;
import net.minecraft.resources.Identifier;
import dev.kizuna.meridian.ui.client.BodyCanvas;
import dev.kizuna.meridian.ui.client.BodyScene;

/** 人体中焦周天入口；场景和图像控件共用服务端身体快照。 */
public final class HumanCultivationPanel implements MeridianSceneExtension {
    @Override
    public String title() {
        // 生物玩法名称由人体附属提供。
        return "中焦";
    }

    @Override
    public String lockedReason() {
        // 提示说明解锁方式，实际权限仍由服务端验证。
        if (dev.kizuna.meridian.progression.client.ClientProgression.canUseMiddleJiao()) {
            return "";
        }
        return "右键使用「中焦残卷」后，即可开始周天与自动修炼。";
    }

    @Override
    public String bodySummary(BodySnapshot body) {
        // 身体检视与修炼继续使用相同寿元摘要。
        return HumanVitalityPresentation.summary(body);
    }

    @Override
    public List<MeridianAction> actions(BodySnapshot body) {
        // 突破入口只读取服务端资格；执行中的入口保留进度，完成后自然移除。
        var state = CultivationActions.state(body);
        if (!ClientProgression.canUseMiddleJiao() || body.getVitality().getDead()
                || !state.getBreakthroughAvailable() && !state.getBreakthrough()) {
            return List.of();
        }
        String label = "突破真炁";
        if (state.getBreakthrough()) {
            label = "正在突破";
        }
        var icon = Identifier.fromNamespaceAndPath("meridian_ui", "textures/gui/actions/breakthrough.png");
        var action = new MeridianAction("meridian-breakthrough", label, icon,
                "候选与外壁储备已满足突破条件。保持周天运转，突破会持续消耗外壁真元。",
                () -> CultivationActions.send(body, Operation.START_BREAKTHROUGH));
        return List.of(action);
    }

    @Override
    public Session create(BodyScene scene, BodyCanvas canvas) {
        // 每个窗口持有独立输入状态，关窗时释放服务端运转会话。
        return new CultivationControls(scene, canvas);
    }
}
