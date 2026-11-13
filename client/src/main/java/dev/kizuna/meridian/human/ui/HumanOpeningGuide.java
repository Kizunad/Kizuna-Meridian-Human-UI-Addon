package dev.kizuna.meridian.human.ui;

/** 经脉窗口「i」里的开脉三步；数字和人体开脉规则一致，单元测试对着规则核对。 */
final class HumanOpeningGuide {
    static final String TEXT = """
            开一条经脉分三步：
            一、先吐纳，把全身经脉和要开的那条都补到八成满。
            二、点「开脉」选中那条经脉，再点「凝聚真元」。
            三、吐纳一直别停，等计时走完：第一条 15 秒，每多开一条多 5 秒。

            计时期间全身或那条经脉跌破六成，或者 10 秒没纳入新的灵气，计时归零。
            暂停以后要从头再来。一次只能开一条；计时每秒耗 0.1 元真元。""";

    private HumanOpeningGuide() {
        // 只有这一段文字。
    }
}
