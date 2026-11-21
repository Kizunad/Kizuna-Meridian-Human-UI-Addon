package dev.kizuna.meridian.human.ui;

import dev.kizuna.inventoryui.client.CompactProvider;
import dev.kizuna.inventoryui.client.CompactVariant;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.ui.api.InspectionSection;
import dev.kizuna.meridian.ui.client.CompactSummary;
import dev.kizuna.meridian.ui.client.MeridianTheme;
import dev.kizuna.meridian.ui.client.QiDisplay;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import static dev.kizuna.meridian.human.ui.HumanInspection.number;
import static dev.kizuna.meridian.ui.client.CompactSummary.LINE;
import static dev.kizuna.meridian.ui.client.CompactSummary.bar;
import static dev.kizuna.meridian.ui.client.CompactSummary.fit;
import static dev.kizuna.meridian.ui.client.CompactSummary.text;

final class HumanCompactViews {
    private static final String NAME_KEY = "meridian_human_ui.compact.";
    private static final CompactVariant DOLL = new CompactVariant("body_doll", NAME_KEY + "body_doll", 88, 58);
    private static final CompactVariant TABLE = new CompactVariant("body_table", NAME_KEY + "body_table", 118, 36);
    private static final CompactVariant FATIGUE = new CompactVariant("muscles", NAME_KEY + "muscles", 80, 60);
    private static final CompactVariant ORGANS = new CompactVariant("organs", NAME_KEY + "organs", 194, 60);
    private static final CompactVariant PRACTICE = new CompactVariant("practice", NAME_KEY + "practice", 75, 30);
    private static final int LIMB_COLUMN = TABLE.width() / 2;
    private static final int TABLE_LINE = 8;
    private static final int TRUNK_COUNT = 5;
    private static final int TRUNK_COLUMNS = 3;
    private static final int ORGAN_MARK = 8;
    private static final int ORGAN_COLUMNS = 3;
    private static final int ORGAN_CELL_WIDTH = 64;
    private static final int ORGAN_TEXT_OFFSET = 9;
    private static final int LABEL_WIDTH = 48;
    private static final int DOLL_WIDTH = 31;
    private static final int INJURY_LINES = 6;
    private static final int PERCENT = 100;
    private static final List<String> LIMBS = List.of("左臂", "右臂", "左腿", "右腿");
    private static final List<String> ARM = List.of("upper_arm", "forearm", "hand");
    private static final List<String> LEG = List.of("thigh", "lower_leg", "foot");

    /** 人形摘要的逻辑像素矩形，坐标只属于人体小窗。 */
    private record Mark(String id, int x, int y, int w, int h) { }

    private static final List<Mark> MARKS = List.of(
            new Mark("head", 10, 0, 8, 8), new Mark("neck", 12, 8, 4, 2),
            new Mark("chest", 8, 10, 12, 9), new Mark("abdomen", 8, 19, 12, 7),
            new Mark("pelvis", 8, 26, 12, 4), new Mark("left_upper_arm", 3, 10, 4, 9),
            new Mark("left_forearm", 3, 19, 4, 8), new Mark("left_hand", 3, 27, 4, 3),
            new Mark("right_upper_arm", 21, 10, 4, 9), new Mark("right_forearm", 21, 19, 4, 8),
            new Mark("right_hand", 21, 27, 4, 3), new Mark("left_thigh", 8, 31, 5, 11),
            new Mark("left_lower_leg", 8, 42, 5, 10), new Mark("left_foot", 7, 52, 6, 3),
            new Mark("right_thigh", 15, 31, 5, 11), new Mark("right_lower_leg", 15, 42, 5, 10),
            new Mark("right_foot", 15, 52, 6, 3));

    private HumanCompactViews() {
    }

    static CompactProvider body(InspectionSection section, Supplier<BodySnapshot> source) {
        // 每次绘制读当前 scope 已确认的快照，重进后不会保留旧部位表。
        return switch (section) {
            case SURFACE -> new CompactSummary(List.of(DOLL, TABLE), (g, variant) -> {
                var parts = HumanInspection.parts(source.get());
                if (TABLE.id().equals(variant)) {
                    table(g, parts);
                } else {
                    doll(g, parts);
                }
            });
            case MUSCLES -> new CompactSummary(List.of(FATIGUE), (g, v) -> muscles(g, source.get()));
            case ORGANS -> new CompactSummary(List.of(ORGANS), (g, v) -> organs(g, source.get()));
        };
    }

    private static void doll(GuiGraphicsExtractor g, List<HumanInspection.Part> parts) {
        // 健康部位只画轮廓色；伤处在旁边按构型顺序列出。
        var byId = parts.stream().collect(Collectors.toMap(HumanInspection.Part::id, p -> p));
        for (Mark mark : MARKS) {
            var part = byId.get(mark.id());
            if (part != null) {
                String token = part.wounded() ? ThemeTokens.INPUT_ERROR : ThemeTokens.MUTED_TEXT;
                g.fill(mark.x(), mark.y(), mark.x() + mark.w(), mark.y() + mark.h(), MeridianTheme.color(token));
            }
        }
        var wounds = parts.stream().filter(HumanInspection.Part::wounded).toList();
        int columns = Math.max(1, Math.ceilDiv(wounds.size(), INJURY_LINES));
        int cell = Math.max(1, (DOLL.width() - DOLL_WIDTH) / columns);
        for (int index = 0; index < wounds.size(); index++) {
            var part = wounds.get(index);
            fit(g, DOLL_WIDTH + index / INJURY_LINES * cell, index % INJURY_LINES * LINE, cell,
                    part.name() + " " + number(part.hp()));
        }
    }

    private static void table(GuiGraphicsExtractor g, List<HumanInspection.Part> parts) {
        // 表格与人形按同一部位 ID 取色；每侧肢体的三项顺着近端到远端排列。
        var map = parts.stream().collect(Collectors.toMap(HumanInspection.Part::id, p -> p));
        int index = 0;
        for (var mark : MARKS.subList(0, TRUNK_COUNT)) {
            var part = map.get(mark.id());
            if (part != null) {
                int left = index % TRUNK_COLUMNS * (TABLE.width() / TRUNK_COLUMNS);
                var label = colored(part.name() + number(part.hp()), part);
                fittedCell(g, left, index / TRUNK_COLUMNS * TABLE_LINE, TABLE.width() / TRUNK_COLUMNS, label);
            }
            index++;
        }
        for (int limb = 0; limb < LIMBS.size(); limb++) {
            int left = limb % 2 * LIMB_COLUMN;
            int y = (2 + limb / 2) * TABLE_LINE;
            var label = Component.literal(LIMBS.get(limb) + " ");
            String side = limb % 2 == 0 ? "left_" : "right_";
            var ids = limb < 2 ? ARM : LEG;
            for (String id : ids) {
                var part = map.get(side + id);
                if (part != null) {
                    if (!id.equals(ids.getFirst())) {
                        label.append("·");
                    }
                    label.append(colored(number(part.hp()), part));
                }
            }
            fittedCell(g, left, y, LIMB_COLUMN - 1, label);
        }
    }

    private static Component colored(String value, HumanInspection.Part part) {
        // 同一条肢体中只给受伤位置的数字换色。
        String token = part.wounded() ? ThemeTokens.INPUT_ERROR : ThemeTokens.TEXT;
        return Component.literal(value).withColor(MeridianTheme.color(token));
    }

    private static void fittedCell(GuiGraphicsExtractor g, int x, int y, int width, Component label) {
        // 小数伤情可比健康整数更长，整组三个数字共同缩放，保留分隔和对齐。
        var font = Minecraft.getInstance().font;
        float scale = Math.min(1f, (float) width / Math.max(1, font.width(label)));
        var pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate(x, y);
            pose.scale(scale, scale);
            g.text(font, label, 0, 0, MeridianTheme.color(ThemeTokens.TEXT), true /* dropShadow */);
        } finally {
            pose.popMatrix();
        }
    }

    private static void muscles(GuiGraphicsExtractor g, BodySnapshot body) {
        // 每条四肢条表示该肢所辖肌肉总疲劳占总容量，最累项按各自容量归一化比较。
        var muscles = HumanInspection.muscles(body);
        for (int limb = 0; limb < LIMBS.size(); limb++) {
            String side = limb % 2 == 0 ? "left_" : "right_";
            var ids = limb < 2 ? ARM : LEG;
            var group = muscles.stream().filter(t -> ids.stream().anyMatch(id -> (side + id).equals(t.part())))
                    .filter(t -> t.fatigue() >= 0).toList();
            double used = group.stream().mapToDouble(HumanInspection.Muscle::fatigue).sum();
            double limit = group.stream().mapToDouble(HumanInspection.Muscle::limit).sum();
            double ratio = QiDisplay.ratio(used, limit);
            String caption = String.format(Locale.ROOT, "%s %.0f%%", LIMBS.get(limb), ratio * PERCENT);
            text(g, 0, limb * LINE, "肌 " + caption);
            bar(g, LABEL_WIDTH, limb * LINE + LINE / 2, FATIGUE.width() - LABEL_WIDTH, ratio);
        }
        muscles.stream().filter(t -> t.fatigue() >= 0).max(Comparator.comparingDouble(HumanInspection.Muscle::ratio))
                .ifPresent(t -> text(g, 0, LIMBS.size() * LINE,
                        t.name() + " 疲劳 " + number(t.fatigue()) + " J"));
    }

    private static void organs(GuiGraphicsExtractor g, BodySnapshot body) {
        // 器官按三列换行，每格保留完整名称和缓存数值，状态色只放在名称左侧。
        int index = 0;
        for (var organ : HumanInspection.organs(body)) {
            String token = organ.process().isEmpty() ? ThemeTokens.MUTED_TEXT : ThemeTokens.PROGRESS_FILL;
            int x = index % ORGAN_COLUMNS * ORGAN_CELL_WIDTH;
            int y = index / ORGAN_COLUMNS * LINE;
            g.fill(x, y + 2, x + ORGAN_MARK, y + ORGAN_MARK - 2, MeridianTheme.color(token));
            fit(g, x + ORGAN_TEXT_OFFSET, y, ORGAN_CELL_WIDTH - ORGAN_TEXT_OFFSET,
                    organ.name() + " " + number(organ.buffer()));
            index++;
        }
    }

    static CompactProvider practice(Supplier<BodySnapshot> source) {
        // 展开窗的批次投影同时供 HUD 使用，绘制不创建周天输入会话。
        return new CompactSummary(List.of(PRACTICE), (g, variant) -> {
            var body = source.get();
            var state = CultivationActions.state(body);
            var batch = CultivationView.batch(CultivationView.transit(body));
            text(g, 0, 0, String.format(Locale.ROOT, "焦  在途 %.2f 元", batch.amount()));
            text(g, 0, LINE, "周天 " + state.getLaps() + " 圈    脑");
            double brainStaminaPercent = state.getBrainStaminaRatio() * PERCENT;
            text(g, 46, LINE, String.format(Locale.ROOT, "%.0f%%", brainStaminaPercent));
        });
    }
}
