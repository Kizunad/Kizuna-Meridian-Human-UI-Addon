package dev.kizuna.meridian.human.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

/**
 * 检视摘要的伤势行：挂着哪些伤势效果、各几级；没有伤势时没有这一行。
 * 单测里只有原版的英文语言文件：治疗附属的名字显示成语言键本身，等级显示成原版的 II。这里盯的是拼法和先后。
 */
public class HumanInjuryLineTest {
    private static final String LEG = "effect.meridian_vanilla_healing.leg_injury";
    private static final String HAND = "effect.meridian_vanilla_healing.crippled_hand";

    @Test
    public void listsEachInjuryWithItsLevelAndExplainsThemOnHover() {
        // 一级只写名字，二级后面跟原版的等级写法；悬浮里一样一行，名字后面接这一样的说明。
        Component line = HumanInjuryLine.of(List.of(new HumanInjuryLine.Sign("crippled_hand", 0),
                new HumanInjuryLine.Sign("leg_injury", 1))).orElseThrow();
        assertEquals("伤势：" + HAND + "、" + LEG + " II", line.getString());
        var hover = (HoverEvent.ShowText) line.getStyle().getHoverEvent();
        assertEquals(HAND + "：" + HAND + ".note\n" + LEG + " II：" + LEG + ".note",
                hover.value().getString());
    }

    @Test
    public void noInjuryMeansNoLineAndDifferentInjuriesCompareUnequal() {
        // 没伤就不占一行；检视窗口靠比较前后两行决定要不要重排文字。
        assertFalse(HumanInjuryLine.of(List.of()).isPresent());
        var first = HumanInjuryLine.of(List.of(new HumanInjuryLine.Sign("leg_injury", 0)));
        assertEquals(first, HumanInjuryLine.of(List.of(new HumanInjuryLine.Sign("leg_injury", 0))));
        assertNotEquals(first, HumanInjuryLine.of(List.of(new HumanInjuryLine.Sign("leg_injury", 1))));
    }
}
