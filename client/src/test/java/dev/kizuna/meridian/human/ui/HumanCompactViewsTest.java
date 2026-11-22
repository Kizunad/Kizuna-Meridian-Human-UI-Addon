package dev.kizuna.meridian.human.ui;

import com.google.protobuf.Any;
import dev.kizuna.inventoryui.client.CompactVariant;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.burden.fabric.BurdenView;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationState;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.ControlledCircuit;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiTransit;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.protocol.pb.BodyMessages.VitalityState;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import dev.kizuna.meridian.ui.client.SummaryProbe;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 人体五个小窗的摘要：文字原字号、不出小窗、同一行不相叠，伤势分三档，器官默认只列有活动的。 */
public class HumanCompactViewsTest {
    private static final List<String> SIDES = List.of("left", "right");
    private static final List<String> SIDE_NAMES = List.of("左", "右");
    private static final double CHEST_LIMIT = 32;
    private static final double FOREARM_LIMIT = 12;
    private static final double LOWER_LEG_LIMIT = 18;
    private static final int PARTS = 17;
    private static final double REFERENCE_KG = 70;
    private static final double OVERLOAD = 4.2;
    private static final double LARGE = 9999;

    private static HumanInspection.Part part(String id, String name, double limit) {
        return new HumanInspection.Part(id, name, limit, limit);
    }

    /** 人体构型的十七个部位，全部无伤。 */
    private static List<HumanInspection.Part> healthy() {
        var parts = new ArrayList<>(List.of(part("head", "头部", 20), part("neck", "颈部", 10),
                part("chest", "胸部", CHEST_LIMIT), part("abdomen", "腹部", 30), part("pelvis", "骨盆", 30)));
        for (int side = 0; side < SIDES.size(); side++) {
            String id = SIDES.get(side) + "_";
            String name = SIDE_NAMES.get(side);
            parts.add(part(id + "upper_arm", name + "上臂", 15));
            parts.add(part(id + "forearm", name + "前臂", FOREARM_LIMIT));
            parts.add(part(id + "hand", name + "手", 8));
            parts.add(part(id + "thigh", name + "大腿", 25));
            parts.add(part(id + "lower_leg", name + "小腿", LOWER_LEG_LIMIT));
            parts.add(part(id + "foot", name + "足", 10));
        }
        return parts;
    }

    private static List<HumanInspection.Part> hurt(List<HumanInspection.Part> parts, String id, double hp) {
        return parts.stream().map(part -> {
            if (part.id().equals(id)) {
                return new HumanInspection.Part(part.id(), part.name(), hp, part.limit());
            }
            return part;
        }).toList();
    }

    /** 三档伤势各一处：胸轻伤、右小腿中等、左前臂重伤。 */
    private static List<HumanInspection.Part> designWounds() {
        var parts = hurt(healthy(), "chest", CHEST_LIMIT - 1);
        parts = hurt(parts, "right_lower_leg", LOWER_LEG_LIMIT / 2);
        return hurt(parts, "left_forearm", FOREARM_LIMIT / 4);
    }

    private static void assertClean(SummaryProbe probe, CompactVariant variant) {
        assertEquals(variant.id(), List.of(), probe.problems(variant));
    }

    @Test
    public void dollListsWoundsWorstFirstAndColoursThreeLevels() {
        var probe = SummaryProbe.reference();
        HumanCompactViews.doll(probe, designWounds());
        assertEquals(List.of("左前臂 3", "右小腿 9", "胸 31", "余十四处无伤"), probe.lines());
        assertClean(probe, HumanCompactViews.DOLL);
        var fills = probe.blocks().subList(PARTS, PARTS * 2);
        assertEquals("每个部位一格", PARTS * 2, probe.blocks().size());
        assertEquals(1, fills.stream().filter(block -> block.color() == HumanWound.LIGHT.mark).count());
        assertEquals(1, fills.stream().filter(block -> block.color() == HumanWound.MEDIUM.mark).count());
        assertEquals(1, fills.stream().filter(block -> block.color() == HumanWound.SEVERE.mark).count());
        assertEquals(PARTS - 3, fills.stream().filter(block -> block.color() == HumanWound.NONE.mark).count());
        int accent = probe.color(ThemeTokens.ACCENT);
        assertTrue(probe.texts().stream().anyMatch(text -> text.text().equals("31") && text.color() == accent));
    }

    @Test
    public void dollCapsTheListAndNeverShowsAWoundAsFull() {
        var parts = healthy();
        for (String id : List.of("head", "neck", "chest", "abdomen", "pelvis", "left_hand", "right_hand")) {
            parts = hurt(parts, id, 1);
        }
        parts = hurt(parts, "left_forearm", FOREARM_LIMIT - 0.01);
        var probe = SummaryProbe.reference();
        HumanCompactViews.doll(probe, parts);
        assertEquals(List.of("胸 1", "腹 1", "胯 1", "头 1", "颈 1", "另三处有伤"), probe.lines());
        assertClean(probe, HumanCompactViews.DOLL);

        var grazed = SummaryProbe.reference();
        HumanCompactViews.doll(grazed, hurt(healthy(), "left_forearm", FOREARM_LIMIT - 0.01));
        assertEquals(List.of("左前臂 11", "余十六处无伤"), grazed.lines());

        var unhurt = SummaryProbe.reference();
        HumanCompactViews.doll(unhurt, healthy());
        assertEquals(List.of("全身无伤"), unhurt.lines());
        assertClean(unhurt, HumanCompactViews.DOLL);
    }

    @Test
    public void tableKeepsColumnsApartAndColoursOnlyWoundedReadings() {
        var probe = SummaryProbe.reference();
        HumanCompactViews.table(probe, HumanCompactViews.TABLE, designWounds());
        assertEquals(List.of("头20 | 颈10 | 胸31", "腹30 | 胯30", "左臂15·3·8 | 右臂15·12·8",
                "左腿25·18·10 | 右腿25·9·10"), probe.lines());
        assertClean(probe, HumanCompactViews.TABLE);
        assertEquals(HumanWound.LIGHT.text, colourOf(probe, "31"));
        assertEquals(HumanWound.SEVERE.text, colourOf(probe, "3"));
        assertEquals(HumanWound.MEDIUM.text, colourOf(probe, "9"));
        assertEquals(probe.color(ThemeTokens.COMPACT_TEXT), colourOf(probe, "20"));
        var rows = probe.texts().stream().map(SummaryProbe.Text::y).distinct().sorted().toList();
        assertEquals("行距 10", List.of(0, 10, 20, 30), rows);
    }

    private static int colourOf(SummaryProbe probe, String reading) {
        return probe.texts().stream().filter(text -> text.text().equals(reading)).findFirst().orElseThrow().color();
    }

    private static HumanInspection.Muscle muscle(String name, String part, double fatigue) {
        return new HumanInspection.Muscle(part + "_muscle", name, part, 10, fatigue, 150);
    }

    @Test
    public void musclesNameTheMostTiredOneInFull() {
        var muscles = List.of(muscle("左肱二头肌", "left_upper_arm", 58.44), muscle("右肱二头肌", "right_upper_arm", 0),
                muscle("左股直肌", "left_thigh", 15), muscle("左腓肠肌", "left_lower_leg", 0),
                muscle("右股直肌", "right_thigh", -1), muscle("膈肌", "chest", 3));
        var probe = SummaryProbe.reference();
        HumanCompactViews.muscles(probe, HumanCompactViews.MUSCLES, muscles);
        assertEquals(List.of("肌 左肱二头肌 | 58.4 J", "左臂 | 39%", "右臂 | 0%", "左腿 | 5%", "右腿 | 0%"),
                probe.lines());
        assertClean(probe, HumanCompactViews.MUSCLES);

        var exhausted = SummaryProbe.reference();
        var worst = List.of(muscle("左拇对掌肌", "left_hand", LARGE), muscle("右趾短屈肌", "right_foot", LARGE));
        HumanCompactViews.muscles(exhausted, HumanCompactViews.MUSCLES, worst);
        assertEquals("肌 左拇对掌肌 | 9999 J", exhausted.lines().getFirst());
        assertClean(exhausted, HumanCompactViews.MUSCLES);

        var waiting = SummaryProbe.reference();
        HumanCompactViews.muscles(waiting, HumanCompactViews.MUSCLES, List.of(muscle("膈肌", "chest", -1)));
        assertEquals("肌 —", waiting.lines().getFirst());
    }

    private static List<HumanInspection.Organ> organs(int busy, int processing) {
        var names = List.of("脑", "左眼", "右眼", "左肺", "右肺", "心脏", "肝脏", "胆囊", "脾脏", "胃", "小肠", "大肠",
                "左肾", "右肾", "膀胱");
        var organs = new ArrayList<HumanInspection.Organ>();
        for (int index = 0; index < names.size(); index++) {
            double buffer = 0;
            String process = "";
            if (index < busy) {
                buffer = 12.5 + index;
            }
            if (index >= busy - processing && index < busy) {
                process = "human:refine";
            }
            organs.add(new HumanInspection.Organ(names.get(index), buffer, process));
        }
        return organs;
    }

    @Test
    public void organsListOnlyActiveOnesByDefault() {
        var probe = SummaryProbe.reference();
        HumanCompactViews.activeOrgans(probe, HumanCompactViews.ORGANS_ACTIVE, organs(2, 1));
        assertEquals(List.of("器 余 13 个空闲", "左眼 | 13.5 元", "脑 | 12.5 元"), probe.lines());
        assertClean(probe, HumanCompactViews.ORGANS_ACTIVE);
        assertEquals("正在加工的排前面，方块用进度色", probe.color(ThemeTokens.PROGRESS_FILL),
                probe.blocks().getFirst().color());

        var idle = SummaryProbe.reference();
        HumanCompactViews.activeOrgans(idle, HumanCompactViews.ORGANS_ACTIVE, organs(0, 0));
        assertEquals(List.of("器 全部空闲"), idle.lines());

        var crowded = SummaryProbe.reference();
        HumanCompactViews.activeOrgans(crowded, HumanCompactViews.ORGANS_ACTIVE, organs(15, 0));
        assertEquals(List.of("器 ", "脑 | 12.5 元", "左眼 | 13.5 元", "右眼 | 14.5 元", "另 12 个有活动"),
                crowded.lines());
        assertClean(crowded, HumanCompactViews.ORGANS_ACTIVE);
    }

    @Test
    public void allOrgansFitInThreeColumnsWithoutShrinking() {
        var probe = SummaryProbe.reference();
        HumanCompactViews.allOrgans(probe, HumanCompactViews.ORGANS_ALL, organs(15, 0));
        assertEquals("脑 | 12.5 | 左眼 | 13.5 | 右眼 | 14.5", probe.lines().getFirst());
        assertEquals(5, probe.lines().size());
        assertClean(probe, HumanCompactViews.ORGANS_ALL);

        var extra = new ArrayList<>(organs(15, 0));
        extra.add(new HumanInspection.Organ("丹田", LARGE, ""));
        extra.add(new HumanInspection.Organ("气海", LARGE, ""));
        var crowded = SummaryProbe.reference();
        HumanCompactViews.allOrgans(crowded, HumanCompactViews.ORGANS_ALL, extra);
        assertEquals("左肾 | 24.5 | 右肾 | 25.5 | 另 3 个", crowded.lines().getLast());
        assertClean(crowded, HumanCompactViews.ORGANS_ALL);
    }

    private static BodySnapshot practising(int laps, double brain, double amount, double progress) {
        var state = CultivationState.newBuilder().setLaps(laps).setBrainStaminaRatio(brain).build();
        var cargo = QiInventory.newBuilder().addEntries(Qi.newBuilder().setAmount(amount).setDensity(1));
        var circuit = ControlledCircuit.newBuilder().setWorkMillis(progress).setDurationMillis(1);
        var transit = QiTransit.newBuilder().setId("human-middle-jiao").setCargo(cargo).setCircuit(circuit);
        return BodySnapshot.newBuilder()
                .putAddonProgress("human:cultivation", Any.pack(state))
                .addTransits(transit)
                .build();
    }

    @Test
    public void practiceShowsBrainReadingBesideItsLabel() {
        var probe = SummaryProbe.reference();
        HumanCompactViews.practice(probe, HumanCompactViews.PRACTICE, practising(3, 0.99, 0.42, 0.63));
        assertEquals(List.of("焦 在途 | 0.42 元", "周天 3 圈 | 脑 99%"), probe.lines());
        assertClean(probe, HumanCompactViews.PRACTICE);
        var fill = probe.blocks().getLast();
        assertEquals("在途下面的条按这一批走了多少", 63, fill.right() - fill.left());

        var longest = SummaryProbe.reference();
        HumanCompactViews.practice(longest, HumanCompactViews.PRACTICE, practising((int) LARGE, 1, LARGE, 1));
        assertEquals(List.of("焦 在途 | 9999 元", "周天 9999 圈 | 脑 100%"), longest.lines());
        assertClean(longest, HumanCompactViews.PRACTICE);
    }

    private static BodySnapshot aged(double ageYears, double limitYears) {
        var vitality = VitalityState.newBuilder()
                .setAgeSeconds(ageYears * HumanBirth.SECONDS_PER_YEAR)
                .setLifespanLimitSeconds(limitYears * HumanBirth.SECONDS_PER_YEAR);
        return BodySnapshot.newBuilder().setRealm(Realm.WANDERING_QI).setVitality(vitality).build();
    }

    private static BurdenView carrying(long effectiveGrams) {
        return new BurdenView(true /* available */, effectiveGrams, effectiveGrams, 1, REFERENCE_KG, OVERLOAD);
    }

    @Test
    public void overviewLineFitsItsWindowAtFullTextSize() {
        String young = HumanVitalityPresentation.compact(aged(18.6, 81.2));
        assertEquals("游炁 · 18/62 年", young);
        assertEquals("负 18/224", HumanCultivationPanel.burdenReading(Optional.of(carrying(18_400))));
        assertEquals("负 待同步", HumanCultivationPanel.burdenReading(Optional.empty()));
        var unavailable = new BurdenView(false /* available */, 0, 0, OVERLOAD, REFERENCE_KG, OVERLOAD);
        assertEquals("负 未知", HumanCultivationPanel.burdenReading(Optional.of(unavailable)));
        var heaviest = new BurdenView(true /* available */, 9_999_000, 9_999_000, 1, 9999, 2);
        String longest = HumanVitalityPresentation.compact(aged(999, 1998)) + " · "
                + HumanCultivationPanel.burdenReading(Optional.of(heaviest));
        assertEquals("游炁 · 999/999 年 · 负 9999/9999", longest);
        int width = SummaryProbe.referenceWidth(longest);
        assertTrue("概况最长 " + width, width <= MeridianSceneExtension.OVERVIEW_WIDTH);
    }
}
