package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.protocol.pb.BodyMessages.VitalityState;
import net.minecraft.network.chat.HoverEvent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 年龄与寿元使用同一权威快照，复活费用不会显示成年龄增长。 */
public class HumanVitalityPresentationTest {
    private static final double AGE_YEARS = 18;
    private static final double LIMIT_YEARS = 80;

    @Test
    public void showsRemainingLifespanAndClampsExhaustion() {
        // 与人体出生数据采用相同年长度，检查一位小数及扣款后的余额。
        assertEquals("年龄 / 寿元：18.0 年 / 62.0 年", summary(0));
        assertEquals("年龄 / 寿元：18.0 年 / 61.0 年", summary(1));
        assertEquals("年龄 / 寿元：18.0 年 / 0.0 年", summary(LIMIT_YEARS));
    }

    @Test
    public void summaryLinesNameTheRealmAndExplainLifespanOnHover() {
        // 摘要第一行是当前境界；游炁境的悬浮说明里讲怎么突破，别的境界不讲。第二行还是年龄和寿元，悬浮讲怎么少。
        var wandering = BodySnapshot.newBuilder().setRealm(Realm.WANDERING_QI).build();
        var lines = HumanVitalityPresentation.lines(wandering);
        assertEquals("境界：游炁", lines.get(0).getString());
        assertTrue(hover(lines.get(0)).contains("突破真炁"));
        assertEquals(HumanVitalityPresentation.summary(wandering), lines.get(1).getString());
        assertTrue(hover(lines.get(1)).contains("折损一年"));

        var trueQi = HumanVitalityPresentation.lines(wandering.toBuilder().setRealm(Realm.TRUE_QI).build());
        assertEquals("境界：真炁", trueQi.get(0).getString());
        assertFalse(hover(trueQi.get(0)).contains("突破"));
    }

    private static String hover(net.minecraft.network.chat.Component line) {
        // 悬浮说明挂在样式上。
        return ((HoverEvent.ShowText) line.getStyle().getHoverEvent()).value().getString();
    }

    private static String summary(double spentYears) {
        // 测试只构造展示所需字段，不启动 Minecraft 或另一份身体账本。
        var vitality = VitalityState.newBuilder()
                .setAgeSeconds(AGE_YEARS * HumanBirth.SECONDS_PER_YEAR)
                .setLifespanLimitSeconds(LIMIT_YEARS * HumanBirth.SECONDS_PER_YEAR)
                .setSpentLifespanSeconds(spentYears * HumanBirth.SECONDS_PER_YEAR)
                .build();
        var body = BodySnapshot.newBuilder().setVitality(vitality).build();
        return HumanVitalityPresentation.summary(body);
    }
}
