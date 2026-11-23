package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.ui.api.SurfaceReadout;
import dev.kizuna.meridian.ui.client.CompactNumbers;
import dev.kizuna.meridian.ui.client.SummaryCanvas;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 新版体表窗口的正文：人形按伤势上色，受伤部位从重到轻逐行，下面交代其余部位，再写境界和寿元。 */
final class HumanSurfaceReadout {
    private HumanSurfaceReadout() {
    }

    /** 按伤势上色的平面人形。 */
    private record Doll(List<HumanInspection.Part> parts) implements SurfaceReadout.Figure {
        @Override
        public int width() {
            return HumanCompactViews.FIGURE_WIDTH;
        }

        @Override
        public int height() {
            return HumanCompactViews.FIGURE_HEIGHT;
        }

        @Override
        public void draw(SummaryCanvas canvas) {
            HumanCompactViews.marks(canvas, 0, parts);
        }
    }

    static SurfaceReadout of(BodySnapshot body) {
        // 身上带着伤势效果时，人形下方多一行伤势；内容和悬浮说明同检视摘要里那一行。
        var footnotes = new ArrayList<Component>();
        var player = Minecraft.getInstance().player;
        if (player != null) {
            HumanInjuryLine.of(HumanInjuryLine.carried(player.getActiveEffects())).ifPresent(footnotes::add);
        }
        return of(HumanInspection.parts(body), HumanVitalityPresentation.compact(body), footnotes);
    }

    /** vitality 是境界和寿元那一行，排在说明行最后；footnotes 是人形下方的脚注。 */
    static SurfaceReadout of(List<HumanInspection.Part> parts, String vitality, List<Component> footnotes) {
        var wounds = HumanCompactViews.wounds(parts);
        var rows = wounds.stream().map(HumanSurfaceReadout::row).toList();
        var notes = new ArrayList<String>();
        if (wounds.isEmpty()) {
            notes.add("全身无伤");
        } else if (wounds.size() < parts.size()) {
            notes.add("其余" + CompactNumbers.chinese(parts.size() - wounds.size()) + "处无伤");
        }
        notes.add(vitality);
        return new SurfaceReadout(new Doll(parts), rows, notes, footnotes);
    }

    private static SurfaceReadout.Row row(HumanInspection.Part part) {
        // 读数和体表小窗一样取整：受伤的当前值舍去零头，不会把没满写成满。
        String value = HumanCompactViews.reading(part) + "/" + CompactNumbers.whole(part.limit());
        return new SurfaceReadout.Row(HumanCompactViews.shortName(part), value, part.hp() / part.limit());
    }
}
