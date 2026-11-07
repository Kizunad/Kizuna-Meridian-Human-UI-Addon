package dev.kizuna.meridian.human.ui;

/** 与剖面脂肪蒙版一致的圆环面积换算，半壁高度不等于半壁容量。 */
final class WallReserveFill {
    static final double INNER_RADIUS = 0.270;
    static final double OUTER_RADIUS = 0.447;
    private static final double CENTER = 0.5;
    private static final int SEARCH_STEPS = 20;
    private static final double AREA = Math.PI * (OUTER_RADIUS * OUTER_RADIUS - INNER_RADIUS * INNER_RADIUS);

    private WallReserveFill() {
        // 归一化坐标与纹理大小无关，窗口缩放不会改变占用比例。
    }

    static double top(double fraction) {
        // 查找下方圆环面积等于储量占比的裁切线，而不是直接线性截取图片高度。
        double low = -OUTER_RADIUS;
        double high = OUTER_RADIUS;
        double target = Math.clamp(fraction, 0, 1) * AREA;
        for (int step = 0; step < SEARCH_STEPS; step++) {
            double middle = (low + high) / 2;
            double area = below(OUTER_RADIUS, middle) - below(INNER_RADIUS, middle);
            if (area > target) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return CENTER + (low + high) / 2;
    }

    private static double below(double radius, double height) {
        // 圆弓形积分在圆外截断，避免空壁和满壁时出现无效平方根。
        double clipped = Math.clamp(height, -radius, radius);
        return radius * radius * Math.acos(clipped / radius)
                - clipped * Math.sqrt(Math.max(0, radius * radius - clipped * clipped));
    }
}
