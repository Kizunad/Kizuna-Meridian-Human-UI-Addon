package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import dev.kizuna.meridian.ui.api.InspectionSection;
import dev.kizuna.inventoryui.client.CompactProvider;
import java.util.function.Supplier;
import dev.kizuna.meridian.ui.api.MeridianAction;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.burden.fabric.client.BurdenClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import dev.kizuna.meridian.ui.client.BodyCanvas;
import dev.kizuna.meridian.ui.client.BodyScene;

/** 人体中焦周天入口；场景和图像控件共用服务端身体快照。 */
public final class HumanCultivationPanel implements MeridianSceneExtension {
    private static final double GRAMS_PER_KG = 1000;
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
        return "还没使用过「中焦残卷」。它在沙漠神殿和丛林神庙的箱子里；找到后右键一次，即可开始周天与自动修炼，无须看完。";
    }

    @Override
    public String bodySummary(BodySnapshot body) {
        // 身体检视与修炼继续使用相同寿元摘要。
        return HumanVitalityPresentation.summary(body);
    }

    @Override
    public List<String> inspectionLayers(InspectionSection section) {
        // 图层名属于人体模型，通用检视页按返回的顺序生成开关。
        return switch (section) {
            // 体表初始只开体表，保留旧检视的叠层勾选，用于观察骨架及内部的相对位置。
            case SURFACE -> List.of("surface", "skeleton", "muscles", "organs", "meridians");
            case MUSCLES -> List.of("muscles");
            case ORGANS -> List.of("organs");
        };
    }

    @Override
    public List<String> inspectionRows(InspectionSection section, BodySnapshot body) {
        // 展开页从同一身体快照按构型列出所有部位。
        if (section == InspectionSection.SURFACE) {
            var parts = HumanInspection.parts(body);
            var rows = new ArrayList<>(parts.stream().filter(HumanInspection.Part::wounded)
                    .map(p -> p.name() + " " + HumanInspection.number(p.hp()) + "/"
                            + HumanInspection.number(p.limit())).toList());
            long healthy = parts.stream().filter(p -> !p.wounded()).count();
            rows.add("其余" + healthy + "处无伤");
            return List.copyOf(rows);
        }
        return HumanInspection.rows(section, body);
    }

    @Override
    public String overviewLine(BodySnapshot body) {
        // 负重独立同步，首份读数到达前保留明确的等待提示。
        String burden = BurdenClient.reading().map(view -> {
            if (!view.available()) {
                return "负重未知";
            }
            return String.format(Locale.ROOT, "负重 %.1f kg", view.effectiveGrams() / GRAMS_PER_KG);
        }).orElse(HumanBurdenLine.WAITING);
        return HumanVitalityPresentation.compact(body) + " · " + burden;
    }

    @Override
    public CompactProvider compactBody(InspectionSection section, Supplier<BodySnapshot> snapshot) {
        // 读取活动连接的身体提供者，绘制时不发送意图。
        return HumanCompactViews.body(section, snapshot);
    }

    @Override
    public CompactProvider compactPractice(Supplier<BodySnapshot> snapshot) {
        // 后台观察中焦时不创建带输入的练习场景。
        return HumanCompactViews.practice(snapshot);
    }

    @Override
    public String meridianGuide() {
        // 开脉规则是人体的，通用经脉窗口只负责显示。
        return HumanOpeningGuide.TEXT;
    }

    @Override
    public List<net.minecraft.network.chat.Component> surfaceSummaryLines(BodySnapshot body) {
        // 新版体表窗口：境界、年龄、寿元各一行，后面接的负重和伤势两行和宽版相同。
        var lines = new ArrayList<>(HumanVitalityPresentation.narrowLines(body));
        var wide = bodySummaryLines(body);
        lines.addAll(wide.subList(HumanVitalityPresentation.lines(body).size(), wide.size()));
        return List.copyOf(lines);
    }

    @Override
    public List<net.minecraft.network.chat.Component> bodySummaryLines(BodySnapshot body) {
        // 检视摘要：当前境界、年龄和寿元，再接一行服务端发来的负重读数；身上有伤势时再接一行伤势。每行各带悬浮说明。
        var lines = new ArrayList<>(HumanVitalityPresentation.lines(body));
        lines.add(HumanBurdenLine.of(BurdenClient.reading()));
        var player = Minecraft.getInstance().player;
        if (player != null) {
            HumanInjuryLine.of(HumanInjuryLine.carried(player.getActiveEffects())).ifPresent(lines::add);
        }
        return List.copyOf(lines);
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
