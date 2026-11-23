package dev.kizuna.meridian.human.ui;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.ui.api.SurfaceReadout;
import dev.kizuna.meridian.ui.client.SummaryProbe;
import dev.kizuna.meridian.ui.client.SurfaceSheet;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 新版体表窗口的正文：受伤几处就列几行，每行有「当前/上限」和一条 HP 条；人形按三档伤势上色。 */
public class HumanSurfaceReadoutTest {
    private static final String VITALITY = "游炁 · 18/62 年";
    private static final int LIST_WIDTH = 96;
    private static final int PARTS = 17;
    private static final int WELL_BLOCKS = 2;
    private static final int BLOCKS_PER_ROW = 2;
    private static final int TEXTS_PER_ROW = 2;
    private static final int BLOCKS_PER_MARK = 2;
    private static final int DESIGN_WOUNDS = 3;
    private static final String READING = "\\d+/\\d+";

    private static void assertRowsMatchWounds(List<HumanInspection.Part> parts) {
        // 每处受伤部位一行：名字靠左、读数右端贴边、下面一条槽和一段按比例的填充。
        var readout = HumanSurfaceReadout.of(parts, VITALITY, List.of());
        long wounded = parts.stream().filter(HumanInspection.Part::wounded).count();
        assertEquals(wounded, readout.wounds().size());
        var probe = SummaryProbe.reference();
        SurfaceSheet.list(probe, LIST_WIDTH, readout);
        assertEquals(wounded * BLOCKS_PER_ROW, probe.blocks().size());
        for (int index = 0; index < readout.wounds().size(); index++) {
            var row = readout.wounds().get(index);
            int top = index * SurfaceSheet.ROW;
            assertTrue(row.name() + " 的读数 " + row.value(), row.value().matches(READING));
            var name = probe.texts().get(index * TEXTS_PER_ROW);
            var value = probe.texts().get(index * TEXTS_PER_ROW + 1);
            assertEquals(new SummaryProbe.Text(0, top, row.name(), name.width(), name.color()), name);
            assertEquals(row.value(), value.text());
            assertEquals(top, value.y());
            assertEquals(row.name() + " 的读数右端", LIST_WIDTH, value.x() + value.width());
            assertTrue(row.name() + " 的名字和读数相叠", name.x() + name.width() < value.x());
            var track = probe.blocks().get(index * BLOCKS_PER_ROW);
            var fill = probe.blocks().get(index * BLOCKS_PER_ROW + 1);
            assertEquals(LIST_WIDTH, track.right() - track.left());
            assertEquals(probe.color(ThemeTokens.PROGRESS_FILL), fill.color());
            assertEquals(Math.round(LIST_WIDTH * row.fraction()), fill.right() - fill.left());
            assertTrue(row.name() + " 的 HP 条在字的下面", track.top() >= top + SurfaceSheet.LINE);
        }
    }

    @Test
    public void listsEachWoundWithItsReadingAndBarWorstFirst() {
        var readout = HumanSurfaceReadout.of(HumanCompactViewsTest.designWounds(), VITALITY, List.of());
        assertEquals(List.of("左前臂", "右小腿", "胸"), readout.wounds().stream().map(SurfaceReadout.Row::name).toList());
        assertEquals(List.of("3/12", "9/18", "31/32"),
                readout.wounds().stream().map(SurfaceReadout.Row::value).toList());
        assertEquals(List.of("其余十四处无伤", VITALITY), readout.notes());
        assertRowsMatchWounds(HumanCompactViewsTest.designWounds());
    }

    @Test
    public void keepsOneRowPerWoundHoweverManyThereAre() {
        // 受伤再多也逐处列出，靠窗口里的滚动看全，不折成「另几处」。
        var parts = HumanCompactViewsTest.healthy();
        for (var part : HumanCompactViewsTest.healthy()) {
            parts = HumanCompactViewsTest.hurt(parts, part.id(), part.limit() / 2);
        }
        var readout = HumanSurfaceReadout.of(parts, VITALITY, List.of());
        assertEquals(PARTS, readout.wounds().size());
        assertEquals(List.of(VITALITY), readout.notes());
        assertEquals(PARTS * SurfaceSheet.ROW + SurfaceSheet.LINE, SurfaceSheet.listHeight(readout));
        assertRowsMatchWounds(parts);
    }

    @Test
    public void fractionalHealthNeverReadsAsFull() {
        var parts = HumanCompactViewsTest.hurt(HumanCompactViewsTest.healthy(), "chest",
                HumanCompactViewsTest.CHEST_LIMIT - 0.01);
        var readout = HumanSurfaceReadout.of(parts, VITALITY, List.of());
        assertEquals("31/32", readout.wounds().getFirst().value());
        assertRowsMatchWounds(parts);
    }

    @Test
    public void carriesTheInjuryLineAsAFootnoteOnlyWhenThereIsOne() {
        // 伤势一行和检视摘要里的是同一份；没有伤势效果时没有脚注。
        var injury = HumanInjuryLine.of(List.of(new HumanInjuryLine.Sign("leg_injury", 1))).orElseThrow();
        var hurt = HumanSurfaceReadout.of(HumanCompactViewsTest.designWounds(), VITALITY, List.of(injury));
        assertEquals(List.of(injury), hurt.footnotes());
        assertEquals(List.of("其余十四处无伤", VITALITY), hurt.notes());
        var unhurt = HumanSurfaceReadout.of(HumanCompactViewsTest.healthy(), VITALITY, List.of());
        assertEquals(List.of(), unhurt.footnotes());
    }

    @Test
    public void saysSoWhenNothingIsWounded() {
        var readout = HumanSurfaceReadout.of(HumanCompactViewsTest.healthy(), VITALITY, List.of());
        assertEquals(List.of(), readout.wounds());
        assertEquals(List.of("全身无伤", VITALITY), readout.notes());
        var probe = SummaryProbe.reference();
        SurfaceSheet.list(probe, LIST_WIDTH, readout);
        assertEquals(List.of(), probe.blocks());
        assertEquals(SurfaceSheet.LINE + SurfaceSheet.NOTE_GAP, probe.texts().getLast().y());
    }

    @Test
    public void figureColoursPartsByWoundAndStaysInsideItsBacking() {
        var readout = HumanSurfaceReadout.of(HumanCompactViewsTest.designWounds(), VITALITY, List.of());
        var probe = SummaryProbe.reference();
        SurfaceSheet.figure(probe, readout.figure());
        int width = SurfaceSheet.figureWidth(readout.figure());
        int height = SurfaceSheet.figureHeight(readout.figure());
        assertEquals(WELL_BLOCKS + PARTS * BLOCKS_PER_MARK, probe.blocks().size());
        for (var block : probe.blocks()) {
            assertTrue("色块出了人形的底 " + block, block.left() >= 0 && block.top() >= 0
                    && block.right() <= width && block.bottom() <= height);
        }
        var marks = HumanCompactViewsTest.fills(probe).subList(WELL_BLOCKS, WELL_BLOCKS + PARTS);
        for (var wound : List.of(HumanWound.LIGHT, HumanWound.MEDIUM, HumanWound.SEVERE)) {
            assertEquals(wound.name(), 1, marks.stream().filter(mark -> mark.color() == wound.mark).count());
        }
        long healthy = marks.stream().filter(mark -> mark.color() == HumanWound.NONE.mark).count();
        assertEquals(PARTS - DESIGN_WOUNDS, healthy);
    }
}
