package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.burden.fabric.BurdenView;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** 检视摘要的负重行照服务端读数显示：实重、负重、上限、档位，悬浮讲腿多累几成。 */
public class HumanBurdenLineTest {
    private static final double REFERENCE_KG = 70;
    private static final double OVERLOAD = 4.2;

    @Test
    public void showsWeightLimitAndTierWithExtraLegWorkOnHover() {
        // 主库存 9 kg 石头折算成 18 kg 负重，倍率 1 + 18/70。
        Component line = HumanBurdenLine.of(Optional.of(view(9_000, 18_000)));
        assertEquals("负重：18.0 / 224 kg · 轻（实重 9.0 kg）", line.getString());
        String hover = hover(line);
        assertTrue(hover, hover.contains("现在走、跑时腿多累 26%"));
        assertTrue(hover, hover.contains("不到 35 kg 轻，不到 70 kg 中，不到 224 kg 重，再多超载"));
        // 70 kg 正好进「重」这一档，224 kg 正好超载。
        assertEquals("负重：70.0 / 224 kg · 重（实重 35.0 kg）", HumanBurdenLine.of(Optional.of(view(35_000, 70_000))).getString());
        assertEquals("负重：224.0 / 224 kg · 超载（实重 112.0 kg）",
                HumanBurdenLine.of(Optional.of(view(112_000, 224_000))).getString());
    }

    @Test
    public void unavailableAndMissingReadingsNeverShowZeroKilograms() {
        // 算不出时说按哪一档计；读数还没到时说在等。
        var unavailable = new BurdenView(false, 0, 0, OVERLOAD, REFERENCE_KG, OVERLOAD);
        Component line = HumanBurdenLine.of(Optional.of(unavailable));
        assertEquals("负重：算不出 · 按超载计", line.getString());
        assertTrue(hover(line), hover(line).contains("腿多累 320%"));
        Component waiting = HumanBurdenLine.of(Optional.empty());
        assertEquals("负重：等待同步", waiting.getString());
        assertEquals("服务端的负重读数还没到。", hover(waiting));
    }

    @Test
    public void linesOfDifferentReadingsCompareUnequal() {
        // 检视窗口靠比较前后两行决定要不要重排文字。
        assertEquals(HumanBurdenLine.of(Optional.of(view(9_000, 18_000))), HumanBurdenLine.of(Optional.of(view(9_000, 18_000))));
        assertNotEquals(HumanBurdenLine.of(Optional.of(view(9_000, 18_000))), HumanBurdenLine.of(Optional.of(view(9_500, 19_000))));
    }

    private static BurdenView view(long totalGrams, long effectiveGrams) {
        // 和服务端一样的算法：倍率 = 1 + 负重千克 ÷ 参考负重。
        return new BurdenView(true, totalGrams, effectiveGrams, 1 + effectiveGrams / 1000.0 / REFERENCE_KG,
                REFERENCE_KG, OVERLOAD);
    }

    private static String hover(Component line) {
        // 悬浮说明挂在样式上。
        return ((HoverEvent.ShowText) line.getStyle().getHoverEvent()).value().getString();
    }
}
