package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;

import java.util.Locale;

/** 年龄与剩余寿元只读取服务端快照，不在客户端累计时间。 */
final class HumanVitalityPresentation {
    private static final double SECONDS_PER_YEAR = 365.25 * 24 * 60 * 60;

    private HumanVitalityPresentation() {
        // 单位换算属于人体展示，不让通用 UI 解释生理时间。
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
