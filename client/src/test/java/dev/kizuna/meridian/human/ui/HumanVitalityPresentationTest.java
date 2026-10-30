package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.VitalityState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
