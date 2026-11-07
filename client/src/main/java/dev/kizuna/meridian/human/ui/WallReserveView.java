package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;

/** 全身经脉外壁的只读汇总；剖面表达总容量比例，不冒充某条经脉的解剖尺寸。 */
record WallReserveView(double amount, double volume, double capacity, double lumen, CultivationState state) {
    static WallReserveView from(BodySnapshot body) {
        // 混合密度分别换算体积后相加，不能用真元量直接计算填充比例。
        double amount = 0;
        double volume = 0;
        double lumen = 0;
        for (var meridian : body.getMeridianStatesList()) {
            for (Qi qi : meridian.getWallQi().getEntriesList()) {
                amount += qi.getAmount();
                volume += qi.getAmount() / qi.getDensity();
            }
            for (Qi qi : meridian.getLumenQi().getEntriesList()) {
                lumen += qi.getAmount();
            }
        }
        double capacity = body.getStructure().getMeridiansList().stream()
                .mapToDouble(meridian -> meridian.getWallVolume()).sum();
        return new WallReserveView(amount, volume, capacity, lumen, CultivationActions.state(body));
    }

    double fraction() {
        // 空容量显示为空，绘制边界不因舍入误差越出经脉外壁。
        if (capacity <= 0) {
            return 0;
        }
        return Math.clamp(volume / capacity, 0, 1);
    }
}
