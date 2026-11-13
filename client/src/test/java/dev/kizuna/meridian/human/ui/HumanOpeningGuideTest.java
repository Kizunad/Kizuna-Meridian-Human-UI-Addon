package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.human.protocol.pb.HumanOpeningMessages.HumanOpeningConditions;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyStructure;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianDefinition;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 「i」里写的数字要和人体开脉规则一致：规则改了，这里先红。 */
public class HumanOpeningGuideTest {
    private static final double MILLIS_PER_SECOND = 1000;

    @Test
    public void guideNumbersMatchTheOpeningRule() throws Exception {
        var structure = BodyStructure.newBuilder()
                .addMeridians(MeridianDefinition.newBuilder().setId("left_lung_meridian").setDisplayName("手太阴肺经"))
                .build();
        var rule = HumanOpening.definitions(structure).get(0);
        var conditions = rule.getPolicyConfig().unpack(HumanOpeningConditions.class);
        String guide = HumanOpeningGuide.TEXT;

        assertEquals(0.8, conditions.getBodyStartFraction(), 0);
        assertEquals(0.8, conditions.getTargetStartFraction(), 0);
        assertTrue(guide.contains("补到八成满"));
        assertEquals(0.6, conditions.getBodyHoldFraction(), 0);
        assertEquals(0.6, conditions.getTargetHoldFraction(), 0);
        assertTrue(guide.contains("跌破六成"));
        assertTrue(guide.contains("第一条 " + seconds(conditions.getBaseDurationMillis()) + " 秒"));
        assertTrue(guide.contains("每多开一条多 " + seconds(conditions.getAdditionalMillisPerOpened()) + " 秒"));
        assertTrue(guide.contains(seconds(conditions.getIntakes(0).getWindowMillis()) + " 秒没纳入新的灵气"));
        assertTrue(rule.getContinuousHold());
        assertTrue(guide.contains("暂停以后要从头再来"));
        assertTrue(guide.contains("每秒耗 " + rule.getHoldQiPerSecond() + " 元真元"));
    }

    private static String seconds(long millis) {
        // 规则里都是整秒。
        return String.valueOf(Math.round(millis / MILLIS_PER_SECOND));
    }
}
