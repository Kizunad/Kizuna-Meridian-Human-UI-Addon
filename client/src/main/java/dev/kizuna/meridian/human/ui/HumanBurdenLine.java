package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.burden.fabric.BurdenReadout;
import dev.kizuna.meridian.burden.fabric.BurdenView;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.Optional;

/** 检视摘要里的负重一行：数和文字都来自负重附属，这里只挂到界面上，不重算。 */
final class HumanBurdenLine {
    static final String WAITING = "负重：等待同步";
    private static final String WAITING_HOVER = "服务端的负重读数还没到。";

    private HumanBurdenLine() {
        // 只有静态入口。
    }

    static Component of(Optional<BurdenView> reading) {
        // 进服后第一份读数还没到时照实说在等，不显示成 0 kg。
        String text = reading.map(BurdenReadout::line).orElse(WAITING);
        String hover = reading.map(view -> String.join("\n", BurdenReadout.hover(view))).orElse(WAITING_HOVER);
        return Component.literal(text)
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }
}
