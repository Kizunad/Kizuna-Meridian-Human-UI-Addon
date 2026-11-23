package dev.kizuna.meridian.human.ui;

import dev.kizuna.inventoryui.client.CompactProvider;
import dev.kizuna.inventoryui.client.CompactVariant;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.ui.api.InspectionSection;
import dev.kizuna.meridian.ui.client.CompactNumbers;
import dev.kizuna.meridian.ui.client.CompactSummary;
import dev.kizuna.meridian.ui.client.CompactSummary.Run;
import dev.kizuna.meridian.ui.client.QiDisplay;
import dev.kizuna.meridian.ui.client.SummaryCanvas;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static dev.kizuna.meridian.ui.client.CompactSummary.LINE;
import static dev.kizuna.meridian.ui.client.CompactSummary.accent;
import static dev.kizuna.meridian.ui.client.CompactSummary.dim;
import static dev.kizuna.meridian.ui.client.CompactSummary.main;

/** 人体的五个小窗摘要：体表（人形、全表两种样式）、肌肉、器官（有活动的、全部两种样式）、中焦。 */
final class HumanCompactViews {
    private static final String NAME_KEY = "meridian_human_ui.compact.";
    private static final int TABLE_ROW = LINE + 1;
    private static final int TABLE_ROWS = 4;
    private static final int LIST_LINES = 6;
    private static final int SUMMARY_LINES = 5;
    static final CompactVariant DOLL = new CompactVariant("body_doll", NAME_KEY + "body_doll", 88, 56);
    static final CompactVariant TABLE =
            new CompactVariant("body_table", NAME_KEY + "body_table", 140, TABLE_ROW * TABLE_ROWS - 1);
    static final CompactVariant MUSCLES =
            new CompactVariant("muscles", NAME_KEY + "muscles", 100, LINE * SUMMARY_LINES);
    static final CompactVariant ORGANS_ACTIVE =
            new CompactVariant("organs_active", NAME_KEY + "organs_active", 100, LINE * SUMMARY_LINES);
    static final CompactVariant ORGANS_ALL =
            new CompactVariant("organs_all", NAME_KEY + "organs_all", 194, LINE * SUMMARY_LINES);
    static final CompactVariant PRACTICE =
            new CompactVariant("practice", NAME_KEY + "practice", 100, CompactSummary.METER_HEIGHT);

    /** 人形里一个部位的格子，坐标是人形左上角起的 GUI 像素。 */
    private record Mark(String part, int x, int y, int width, int height) { }

    /** 躯干部位在小窗里的单字叫法。 */
    private record Trunk(String part, String label) { }

    /** 一条肢体和它从近端到远端的部位。 */
    private record Limb(String label, List<String> parts) { }

    private static final List<Mark> MARKS = List.of(
            new Mark("head", 9, 0, 8, 8), new Mark("neck", 11, 8, 4, 2),
            new Mark("chest", 7, 10, 12, 9), new Mark("abdomen", 7, 19, 12, 7),
            new Mark("pelvis", 7, 26, 12, 4), new Mark("left_upper_arm", 3, 10, 4, 9),
            new Mark("left_forearm", 3, 19, 4, 8), new Mark("left_hand", 3, 27, 4, 3),
            new Mark("right_upper_arm", 19, 10, 4, 9), new Mark("right_forearm", 19, 19, 4, 8),
            new Mark("right_hand", 19, 27, 4, 3), new Mark("left_thigh", 7, 30, 6, 11),
            new Mark("left_lower_leg", 7, 41, 6, 10), new Mark("left_foot", 7, 51, 6, 3),
            new Mark("right_thigh", 13, 30, 6, 11), new Mark("right_lower_leg", 13, 41, 6, 10),
            new Mark("right_foot", 13, 51, 6, 3));
    private static final List<Trunk> TRUNK = List.of(new Trunk("head", "头"), new Trunk("neck", "颈"),
            new Trunk("chest", "胸"), new Trunk("abdomen", "腹"), new Trunk("pelvis", "胯"));
    private static final List<Limb> LIMBS = List.of(
            new Limb("左臂", List.of("left_upper_arm", "left_forearm", "left_hand")),
            new Limb("右臂", List.of("right_upper_arm", "right_forearm", "right_hand")),
            new Limb("左腿", List.of("left_thigh", "left_lower_leg", "left_foot")),
            new Limb("右腿", List.of("right_thigh", "right_lower_leg", "right_foot")));
    /** 人形的格子占的范围：左右各留出三像素，脚底到 54。 */
    static final int FIGURE_WIDTH = 26;
    static final int FIGURE_HEIGHT = 54;
    static final int MARK_OUTLINE = 0x8C000000;
    private static final int DOLL_TOP = 1;
    private static final int LIST_LEFT = 32;
    private static final int LISTED_WOUNDS = LIST_LINES - 1;
    private static final int TRUNK_COLUMNS = 3;
    private static final int LIMB_COLUMNS = 2;
    private static final int LABEL_GAP = 2;
    private static final String SEPARATOR = "·";
    private static final int BAR_LEFT = 22;
    private static final int BAR_RIGHT_INSET = 28;
    private static final int BAR_TOP = 3;
    private static final int DOT = 4;
    private static final int DOT_TOP = 2;
    private static final int NAME_LEFT = DOT + 3;
    private static final int ORGAN_COLUMNS = 3;
    private static final int ORGAN_COLUMN_GAP = 4;
    private static final int LISTED_ORGANS = SUMMARY_LINES - 1;

    private HumanCompactViews() {
    }

    static CompactProvider body(InspectionSection section, Supplier<BodySnapshot> source) {
        // 每次绘制读当前连接已确认的快照，重进后不会留着旧的部位表。
        return switch (section) {
            case SURFACE -> new CompactSummary(List.of(DOLL, TABLE), (canvas, variant) -> {
                var parts = HumanInspection.parts(source.get());
                if (variant.equals(TABLE)) {
                    table(canvas, variant, parts);
                } else {
                    doll(canvas, parts);
                }
            });
            case MUSCLES -> new CompactSummary(List.of(MUSCLES), (canvas, variant) ->
                    muscles(canvas, variant, HumanInspection.muscles(source.get())));
            case ORGANS -> new CompactSummary(List.of(ORGANS_ACTIVE, ORGANS_ALL), (canvas, variant) -> {
                var organs = HumanInspection.organs(source.get());
                if (variant.equals(ORGANS_ALL)) {
                    allOrgans(canvas, variant, organs);
                } else {
                    activeOrgans(canvas, variant, organs);
                }
            });
        };
    }

    private static Map<String, HumanInspection.Part> byId(List<HumanInspection.Part> parts) {
        return parts.stream().collect(Collectors.toMap(HumanInspection.Part::id, part -> part));
    }

    static String shortName(HumanInspection.Part part) {
        // 躯干用单字，四肢的部位沿用构型里的名字。
        return TRUNK.stream()
                .filter(trunk -> trunk.part().equals(part.id()))
                .map(Trunk::label)
                .findFirst()
                .orElse(part.name());
    }

    static String reading(HumanInspection.Part part) {
        if (part.wounded()) {
            return CompactNumbers.floored(part.hp());
        }
        return CompactNumbers.whole(part.hp());
    }

    static void doll(SummaryCanvas canvas, List<HumanInspection.Part> parts) {
        marks(canvas, DOLL_TOP, parts);
        woundList(canvas, parts);
    }

    /** 人形的格子，最上面一格的上沿在 top；每格外有一像素深色描边，相邻两格之间因此有一道分隔线。 */
    static void marks(SummaryCanvas canvas, int top, List<HumanInspection.Part> parts) {
        // 从大格画到小格，每格先描边再上色：分隔线落在大的那一格上，脖子和手这样的小格颜色完整。
        var byId = byId(parts);
        var known = MARKS.stream()
                .filter(mark -> byId.containsKey(mark.part()))
                .sorted(Comparator.comparingInt((Mark mark) -> mark.width() * mark.height()).reversed())
                .toList();
        for (Mark mark : known) {
            int y = top + mark.y();
            canvas.fill(mark.x() - 1, y - 1, mark.x() + mark.width() + 1, y + mark.height() + 1, MARK_OUTLINE);
            int color = HumanWound.of(byId.get(mark.part())).mark;
            canvas.fill(mark.x(), y, mark.x() + mark.width(), y + mark.height(), color);
        }
    }

    /** 受伤的部位，剩得少的排前面。 */
    static List<HumanInspection.Part> wounds(List<HumanInspection.Part> parts) {
        return parts.stream()
                .filter(HumanInspection.Part::wounded)
                .sorted(Comparator.comparingDouble(part -> part.hp() / part.limit()))
                .toList();
    }

    private static void woundList(SummaryCanvas canvas, List<HumanInspection.Part> parts) {
        // 伤得重的排前面，最多列五处；最后一行交代剩下的。
        var wounds = wounds(parts);
        if (wounds.isEmpty()) {
            CompactSummary.left(canvas, LIST_LEFT, 0, List.of(dim(canvas, "全身无伤")));
            return;
        }
        int listed = Math.min(wounds.size(), LISTED_WOUNDS);
        for (int line = 0; line < listed; line++) {
            var part = wounds.get(line);
            var runs = List.of(main(canvas, shortName(part) + " "), accent(canvas, reading(part)));
            CompactSummary.left(canvas, LIST_LEFT, line * LINE, runs);
        }
        String rest = "余" + CompactNumbers.chinese(parts.size() - wounds.size()) + "处无伤";
        if (wounds.size() > listed) {
            rest = "另" + CompactNumbers.chinese(wounds.size() - listed) + "处有伤";
        } else if (wounds.size() == parts.size()) {
            return;
        }
        CompactSummary.left(canvas, LIST_LEFT, listed * LINE, List.of(dim(canvas, rest)));
    }

    static void table(SummaryCanvas canvas, CompactVariant variant, List<HumanInspection.Part> parts) {
        // 躯干五处三个一行，四肢两条一行；每条肢体的三个数从近端排到远端。
        var byId = byId(parts);
        int trunkColumn = variant.width() / TRUNK_COLUMNS;
        for (int index = 0; index < TRUNK.size(); index++) {
            var trunk = TRUNK.get(index);
            int x = index % TRUNK_COLUMNS * trunkColumn;
            int y = index / TRUNK_COLUMNS * TABLE_ROW;
            cell(canvas, x, y, trunk.label(), present(byId, List.of(trunk.part())));
        }
        int trunkRows = Math.ceilDiv(TRUNK.size(), TRUNK_COLUMNS);
        int limbColumn = variant.width() / LIMB_COLUMNS;
        for (int index = 0; index < LIMBS.size(); index++) {
            var limb = LIMBS.get(index);
            int x = index % LIMB_COLUMNS * limbColumn;
            int y = (trunkRows + index / LIMB_COLUMNS) * TABLE_ROW;
            cell(canvas, x, y, limb.label(), present(byId, limb.parts()));
        }
    }

    private static List<HumanInspection.Part> present(Map<String, HumanInspection.Part> byId, List<String> ids) {
        // 构型里没有的部位不占位置。
        return ids.stream().filter(byId::containsKey).map(byId::get).toList();
    }

    private static void cell(SummaryCanvas canvas, int x, int y, String label, List<HumanInspection.Part> parts) {
        // 只有受伤的数字换色。
        int cursor = CompactSummary.left(canvas, x, y, List.of(main(canvas, label))) + LABEL_GAP;
        var runs = new ArrayList<Run>();
        for (var part : parts) {
            if (!runs.isEmpty()) {
                runs.add(main(canvas, SEPARATOR));
            }
            var wound = HumanWound.of(part);
            if (wound == HumanWound.NONE) {
                runs.add(main(canvas, reading(part)));
            } else {
                runs.add(new Run(reading(part), wound.text));
            }
        }
        CompactSummary.left(canvas, cursor, y, runs);
    }

    static void muscles(SummaryCanvas canvas, CompactVariant variant, List<HumanInspection.Muscle> muscles) {
        // 第一行是最累的一块肌肉；下面每条肢体一行，条长是这条肢体所有肌肉的疲劳占它们容量之和的比例。
        var measured = muscles.stream().filter(muscle -> muscle.fatigue() >= 0).toList();
        var tired = measured.stream().max(Comparator.comparingDouble(HumanInspection.Muscle::ratio));
        var head = new ArrayList<Run>(List.of(main(canvas, "肌 ")));
        if (tired.isPresent()) {
            head.add(dim(canvas, tired.get().name()));
            String fatigue = CompactNumbers.coarse(tired.get().fatigue()) + " J";
            CompactSummary.right(canvas, variant.width(), 0, main(canvas, fatigue));
        } else {
            head.add(dim(canvas, CompactNumbers.coarse(-1)));
        }
        CompactSummary.left(canvas, 0, 0, head);
        for (int index = 0; index < LIMBS.size(); index++) {
            var limb = LIMBS.get(index);
            var group = measured.stream().filter(muscle -> limb.parts().contains(muscle.part())).toList();
            double used = group.stream().mapToDouble(HumanInspection.Muscle::fatigue).sum();
            double limit = group.stream().mapToDouble(HumanInspection.Muscle::limit).sum();
            double ratio = QiDisplay.ratio(used, limit);
            int y = (index + 1) * LINE;
            CompactSummary.left(canvas, 0, y, List.of(dim(canvas, limb.label())));
            CompactSummary.track(canvas, BAR_LEFT, variant.width() - BAR_RIGHT_INSET, y + BAR_TOP, ratio);
            CompactSummary.right(canvas, variant.width(), y, main(canvas, CompactNumbers.percent(ratio)));
        }
    }

    private static boolean active(HumanInspection.Organ organ) {
        return !organ.process().isEmpty() || organ.buffer() > 0;
    }

    private static int dotColor(SummaryCanvas canvas, HumanInspection.Organ organ) {
        if (organ.process().isEmpty()) {
            return canvas.color(ThemeTokens.COMPACT_MUTED_TEXT);
        }
        return canvas.color(ThemeTokens.PROGRESS_FILL);
    }

    private static void organName(SummaryCanvas canvas, int x, int y, HumanInspection.Organ organ) {
        // 名字前的小方块：正在加工是进度色，其余是次要文字色。
        canvas.fill(x, y + DOT_TOP, x + DOT, y + DOT_TOP + DOT, dotColor(canvas, organ));
        CompactSummary.left(canvas, x + NAME_LEFT, y, List.of(main(canvas, organ.name())));
    }

    static void activeOrgans(SummaryCanvas canvas, CompactVariant variant, List<HumanInspection.Organ> organs) {
        // 只列正在加工或缓存里有真元的器官，正在加工的排前面；其余的只报个数。
        var busy = organs.stream()
                .filter(HumanCompactViews::active)
                .sorted(Comparator.comparing(organ -> organ.process().isEmpty()))
                .toList();
        int idle = organs.size() - busy.size();
        var head = new ArrayList<Run>(List.of(main(canvas, "器 ")));
        if (busy.isEmpty()) {
            head.add(dim(canvas, "全部空闲"));
        } else if (idle > 0) {
            head.add(dim(canvas, "余 " + idle + " 个空闲"));
        }
        CompactSummary.left(canvas, 0, 0, head);
        int listed = busy.size();
        if (listed > LISTED_ORGANS) {
            listed = LISTED_ORGANS - 1;
        }
        for (int index = 0; index < listed; index++) {
            var organ = busy.get(index);
            int y = (index + 1) * LINE;
            organName(canvas, 0, y, organ);
            String buffer = CompactNumbers.coarse(organ.buffer()) + " 元";
            CompactSummary.right(canvas, variant.width(), y, main(canvas, buffer));
        }
        if (busy.size() > listed) {
            String more = "另 " + (busy.size() - listed) + " 个有活动";
            CompactSummary.left(canvas, 0, (listed + 1) * LINE, List.of(dim(canvas, more)));
        }
    }

    static void allOrgans(SummaryCanvas canvas, CompactVariant variant, List<HumanInspection.Organ> organs) {
        // 三列排开，每格是名字和缓存里的真元数；格子不够时最后一格报还有几个没列。
        int column = (variant.width() - (ORGAN_COLUMNS - 1) * ORGAN_COLUMN_GAP) / ORGAN_COLUMNS;
        int cells = variant.height() / LINE * ORGAN_COLUMNS;
        int listed = organs.size();
        if (listed > cells) {
            listed = cells - 1;
        }
        for (int index = 0; index < listed; index++) {
            var organ = organs.get(index);
            int x = index % ORGAN_COLUMNS * (column + ORGAN_COLUMN_GAP);
            int y = index / ORGAN_COLUMNS * LINE;
            organName(canvas, x, y, organ);
            CompactSummary.right(canvas, x + column, y, main(canvas, CompactNumbers.coarse(organ.buffer())));
        }
        if (organs.size() > listed) {
            int x = listed % ORGAN_COLUMNS * (column + ORGAN_COLUMN_GAP);
            int y = listed / ORGAN_COLUMNS * LINE;
            String more = "另 " + (organs.size() - listed) + " 个";
            CompactSummary.left(canvas, x, y, List.of(dim(canvas, more)));
        }
    }

    static CompactProvider practice(Supplier<BodySnapshot> source) {
        // 展开窗的批次投影同时供小窗使用，绘制不创建周天输入会话。
        return new CompactSummary(List.of(PRACTICE), (canvas, variant) -> practice(canvas, variant, source.get()));
    }

    static void practice(SummaryCanvas canvas, CompactVariant variant, BodySnapshot body) {
        // 条长是这一批真元在周天里走了多少。
        var state = CultivationActions.state(body);
        var batch = CultivationView.batch(CultivationView.transit(body));
        var head = List.of(main(canvas, "焦 "), dim(canvas, "在途"));
        var amount = main(canvas, CompactNumbers.fine(batch.amount()) + " 元");
        var laps = dim(canvas, "周天 " + CompactNumbers.whole(state.getLaps()) + " 圈");
        var brain = dim(canvas, "脑 " + CompactNumbers.percent(state.getBrainStaminaRatio()));
        CompactSummary.meter(canvas, variant, new CompactSummary.Meter(head, amount, batch.progress(), laps, brain));
    }
}
