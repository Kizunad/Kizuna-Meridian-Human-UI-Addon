package dev.kizuna.meridian.human.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 检视摘要里的伤势一行：身上挂着哪些伤势状态效果、各是几级，悬浮讲每一样碍着什么。没有伤势时没有这一行。
 * 效果是治疗附属挂的，名字和说明用它的语言键，这里不重算伤势。
 */
final class HumanInjuryLine {
    static final String NAMESPACE = "meridian_vanilla_healing";
    private static final String PREFIX = "伤势：";
    private static final String SEPARATOR = "、";
    private static final String EFFECT_KEY = "effect." + NAMESPACE + ".";
    private static final String NOTE_SUFFIX = ".note";
    /** 原版给效果等级用的语言键：potion.potency.1 就是 Ⅱ。 */
    private static final String POTENCY_KEY = "potion.potency.";

    private HumanInjuryLine() {
        // 只有静态入口。
    }

    /** 一个伤势效果：注册名里的路径（如 leg_injury）和放大值（0 是 Ⅰ）。 */
    record Sign(String path, int amplifier) { }

    static List<Sign> carried(Collection<MobEffectInstance> effects) {
        // 只挑治疗附属的效果，按名字排好：效果表的遍历顺序不稳，一行字不能每次重排。
        return effects.stream()
                .flatMap(effect -> effect.getEffect().unwrapKey().stream()
                        .filter(key -> key.identifier().getNamespace().equals(NAMESPACE))
                        .map(key -> new Sign(key.identifier().getPath(), effect.getAmplifier())))
                .sorted(Comparator.comparing(Sign::path))
                .toList();
    }

    static Optional<Component> of(List<Sign> signs) {
        if (signs.isEmpty()) {
            return Optional.empty();
        }
        MutableComponent text = Component.literal(PREFIX);
        MutableComponent hover = Component.empty();
        for (int index = 0; index < signs.size(); index++) {
            Sign sign = signs.get(index);
            if (index > 0) {
                text.append(SEPARATOR);
                hover.append("\n");
            }
            text.append(name(sign));
            hover.append(name(sign)).append("：")
                    .append(Component.translatable(EFFECT_KEY + sign.path() + NOTE_SUFFIX));
        }
        return Optional.of(text.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover))));
    }

    private static MutableComponent name(Sign sign) {
        // 一级只写名字，二级起后面跟等级，和原版效果列表的写法一样。
        MutableComponent name = Component.translatable(EFFECT_KEY + sign.path());
        if (sign.amplifier() > 0) {
            name.append(" ").append(Component.translatable(POTENCY_KEY + sign.amplifier()));
        }
        return name;
    }
}
