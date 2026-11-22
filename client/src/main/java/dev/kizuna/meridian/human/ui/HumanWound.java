package dev.kizuna.meridian.human.ui;

/** 部位伤势的轻重三档和各自的颜色；颜色只表示伤势，不随主题换。 */
enum HumanWound {
    NONE(0xFF7C8A86, 0),
    LIGHT(0xFFE8C34A, 0xFFE8C34A),
    MEDIUM(0xFFE0703A, 0xFFF0864A),
    SEVERE(0xFFC62F2F, 0xFFFF5A5A);

    private static final double LIGHT_FROM = 2.0 / 3;
    private static final double MEDIUM_FROM = 1.0 / 3;
    /** 人形上这一档的格子颜色。 */
    final int mark;
    /** 这一档的数字颜色；无伤的数字用小窗的正文色。 */
    final int text;

    HumanWound(int mark, int text) {
        this.mark = mark;
        this.text = text;
    }

    static HumanWound of(HumanInspection.Part part) {
        // 还剩三分之二以上算轻伤，三分之一以上算中等，再少算重伤；读数没到的部位按无伤画。
        if (!part.wounded()) {
            return NONE;
        }
        double remaining = part.hp() / part.limit();
        if (remaining >= LIGHT_FROM) {
            return LIGHT;
        }
        if (remaining >= MEDIUM_FROM) {
            return MEDIUM;
        }
        return SEVERE;
    }
}
