package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;

import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 年龄与剩余寿元只读取服务端快照，不在客户端累计时间。 */
final class HumanVitalityPresentation {
    private static final double SECONDS_PER_YEAR = 365.25 * 24 * 60 * 60;

    static final String REALM_PREFIX = "境界：";
    private static final Map<Realm, String> REALMS = Map.of(
            Realm.WANDERING_QI, "游炁", Realm.TRUE_QI, "真炁", Realm.ESSENCE_TRANSFORMATION, "化元",
            Realm.ESSENCE_CONDENSATION, "凝元", Realm.HEAVENLY_PATTERN, "天纹", Realm.VOID_TRANSFORMATION, "化虚");
    private static final String REALM_HOVER = "境界决定能开哪些经脉、能用哪些功法。";
    private static final String WANDERING_HOVER = "\n游炁境的经脉全开且完好、周天运转着、炼出的真炁和外壁储备都够了，中焦窗口里会出现「突破真炁」。";
    private static final String LIFESPAN_HOVER = "左边是年龄，随游戏时间增长；右边是剩下的寿元。\n每次死后重生折损一年寿元；寿元耗尽，寿终而死。";

    private HumanVitalityPresentation() {
        // 单位换算属于人体展示，不让通用 UI 解释生理时间。
    }

    static List<Component> lines(BodySnapshot body) {
        // 检视顶上的摘要：当前境界一行、年龄和寿元一行，各带一句悬浮说明。
        String hover = REALM_HOVER;
        if (body.getRealm() == Realm.WANDERING_QI) {
            hover += WANDERING_HOVER;
        }
        return List.of(hovering(REALM_PREFIX + REALMS.getOrDefault(body.getRealm(), "未知"), hover),
                hovering(summary(body), LIFESPAN_HOVER));
    }

    private static Component hovering(String text, String hover) {
        // 悬浮说明挂在文字样式上，由检视窗口的标签显示。
        return Component.literal(text)
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    static List<Component> narrowLines(BodySnapshot body) {
        // 窄窗口里境界、年龄、寿元各占一行，数值不会被折开；悬浮说明和宽版相同。
        String hover = REALM_HOVER;
        if (body.getRealm() == Realm.WANDERING_QI) {
            hover += WANDERING_HOVER;
        }
        var vitality = body.getVitality();
        double remainingSeconds = Math.max(0, vitality.getLifespanLimitSeconds()
                - vitality.getAgeSeconds() - vitality.getSpentLifespanSeconds());
        String realm = REALM_PREFIX + REALMS.getOrDefault(body.getRealm(), "未知");
        String age = String.format(Locale.ROOT, "年龄 %.1f 年", vitality.getAgeSeconds() / SECONDS_PER_YEAR);
        String lifespan = String.format(Locale.ROOT, "寿元 %.1f 年", remainingSeconds / SECONDS_PER_YEAR);
        return List.of(hovering(realm, hover), hovering(age, LIFESPAN_HOVER), hovering(lifespan, LIFESPAN_HOVER));
    }

    static String compact(BodySnapshot body) {
        // 概况一行：境界、年龄和剩余寿元，换算和上面的摘要相同；只写整年，不满一年的零头舍去。
        var vitality = body.getVitality();
        double remainingSeconds = Math.max(0, vitality.getLifespanLimitSeconds()
                - vitality.getAgeSeconds() - vitality.getSpentLifespanSeconds());
        long age = (long) Math.floor(vitality.getAgeSeconds() / SECONDS_PER_YEAR);
        long remaining = (long) Math.floor(remainingSeconds / SECONDS_PER_YEAR);
        return String.format(Locale.ROOT, "%s · %d/%d 年", REALMS.getOrDefault(body.getRealm(), "未知"), age,
                remaining);
    }

    static String summary(BodySnapshot body) {
        // 已支出寿元独立于自然年龄，余额为零时仍显示非负数。
        var vitality = body.getVitality();
        double remainingSeconds = Math.max(0, vitality.getLifespanLimitSeconds()
                - vitality.getAgeSeconds() - vitality.getSpentLifespanSeconds());
        return String.format(Locale.ROOT, "年龄 / 寿元：%.1f 年 / %.1f 年",
                vitality.getAgeSeconds() / SECONDS_PER_YEAR, remainingSeconds / SECONDS_PER_YEAR);
    }
}
