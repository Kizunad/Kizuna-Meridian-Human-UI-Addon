package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.HumanOpeningConditions;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyVisual;
import dev.kizuna.meridian.ui.api.OpeningEffectProvider;
import dev.kizuna.meridian.ui.client.MeridianFlow;
import dev.kizuna.meridian.ui.client.QiDisplay;
import org.joml.Vector3f;
import java.util.List;

/** 人体开脉光迹的解剖方向与坐姿蒙皮；坐标直接取 Human 的现有经脉网格。 */
public final class HumanOpeningEffects implements OpeningEffectProvider {
    @Override
    public List<Node> route(BodyVisual visual) {
        // 手阴由胸向手、手阳由手向头；足阳向足、足阴向腹，推进方向与人体流向一致。
        String id = visual.getTargetId();
        boolean arm = id.contains("lung_") || id.contains("heart_") || id.contains("pericardium_")
                || id.contains("intestine_") || id.contains("triple_burner_");
        boolean ascending = id.contains("intestine_") || id.contains("triple_burner_")
                || id.contains("spleen_") || id.contains("kidney_") || id.contains("liver_");
        var origin = visual.getVerticesList().stream().min((a, b) -> ascending
                ? Float.compare(a.getY(), b.getY()) : Float.compare(b.getY(), a.getY())).orElseThrow();
        List<Vector3f> path = MeridianFlow.branch(visual, new Vector3f(origin.getX(), origin.getY(), origin.getZ()));
        String side = "";
        if (id.startsWith("left_")) {
            side = "l_";
        } else if (id.startsWith("right_")) {
            side = "r_";
        }
        String attachmentSide = side;
        return path.stream().map(point -> attach(point, arm, attachmentSide)).toList();
    }

    private static Node attach(Vector3f point, boolean arm, String side) {
        // 肘、膝附近采用连续权重，避免盘坐时的折断和长直线。
        String upper;
        String lower;
        float blend;
        if (arm && Math.abs(point.x) > .20f) {
            upper = side + "upper_arm";
            lower = side + "forearm";
            blend = clamp((1.23f - point.y) / .13f);
            if (point.y < .96f) {
                upper = lower;
                lower = side + "hand";
                blend = clamp((.96f - point.y) / .09f);
            }
        } else if (point.y < .98f && !side.isEmpty()) {
            upper = side + "thigh";
            lower = side + "shin";
            blend = clamp((.59f - point.y) / .14f);
            if (point.y < .18f) {
                upper = lower;
                lower = side + "foot";
                blend = clamp((.18f - point.y) / .10f);
            }
        } else {
            upper = "waist";
            lower = "chest";
            blend = clamp((point.y - 1.12f) / .15f);
            if (side.isEmpty() && point.y < 1.12f) {
                upper = "pelvis";
                lower = "waist";
                blend = clamp((point.y - .9f) / .22f);
            }
            if (point.y > 1.49f) {
                upper = "chest";
                lower = "head";
                blend = clamp((point.y - 1.49f) / .10f);
            }
        }
        return new Node(point.x, point.y, point.z, upper, lower, blend);
    }

    private static float clamp(float fraction) {
        // 混合权重只在相邻骨之间，不向身体外外推。
        return Math.max(0, Math.min(1, fraction));
    }

    @Override
    public double holdMargin(BodySnapshot body) {
        // 读取任务固定的人体策略，不在通用 UI 写死 60% 或依赖服务端实现。
        try {
            var conditions = body.getOpeningTask().getRule().getPolicyConfig().unpack(HumanOpeningConditions.class);
            double capacity = body.getStructure().getMeridiansList().stream().mapToDouble(MeridianDefinition::getLumenVolume).sum();
            String target = body.getOpeningTask().getRule().getTargetMeridianId();
            MeridianDefinition definition = body.getStructure().getMeridiansList().stream()
                    .filter(item -> item.getId().equals(target)).findFirst().orElseThrow();
            MeridianState state = body.getMeridianStatesList().stream()
                    .filter(item -> item.getId().equals(target)).findFirst().orElseThrow();
            double whole = QiDisplay.ratio(QiDisplay.lumenVolume(body), capacity);
            double local = QiDisplay.storageFraction(state.getLumenQi(), definition.getLumenVolume());
            return Math.min(whole - conditions.getBodyHoldFraction(), local - conditions.getTargetHoldFraction());
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException(HumanUiErrors.INVALID_OPENING_EFFECTS, invalid);
        }
    }
}
