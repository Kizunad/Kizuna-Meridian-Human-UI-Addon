package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.ui.api.InspectionSection;
import dev.kizuna.meridian.ui.client.QiDisplay;
import java.util.List;
import java.util.Locale;

/** 人体各页按构型顺序读取状态，缺项保留等待标记。 */
final class HumanInspection {
    record Part(String id, String name, double hp, double limit) {
        boolean wounded() {
            return hp >= 0 && hp < limit;
        }
    }

    record Muscle(String id, String name, String part, double hp, double fatigue, double limit) {
        double ratio() {
            return QiDisplay.ratio(fatigue, limit);
        }
    }

    record Organ(String name, double buffer, String process) { }

    private HumanInspection() {
    }

    static List<Part> parts(BodySnapshot body) {
        // 部位 HP 和覆盖其上的皮肤组织是两个账户。
        return body.getStructure().getPartsList().stream().map(def -> {
            double hp = body.getPartStatesList().stream().filter(s -> s.getId().equals(def.getId()))
                    .mapToDouble(PartState::getHp).findFirst().orElse(-1);
            return new Part(def.getId(), def.getDisplayName(), hp, def.getMaxHp());
        }).toList();
    }

    static List<Muscle> muscles(BodySnapshot body) {
        // 骨、皮和注意组织与肌肉共享状态类型，按人体构型的种类筛选。
        return body.getStructure().getTissuesList().stream().filter(def -> def.getKind().equals("human:muscle"))
                .map(def -> {
                    var state = body.getTissueStatesList().stream().filter(s -> s.getId().equals(def.getId()))
                            .findFirst().orElse(null);
                    double hp = -1;
                    double fatigue = -1;
                    if (state != null) {
                        hp = state.getHp();
                        fatigue = state.getFatigue();
                    }
                    return new Muscle(def.getId(), def.getDisplayName(), def.getPartId(), hp, fatigue,
                            def.getFatigueCapacity());
                }).toList();
    }

    static List<Organ> organs(BodySnapshot body) {
        // 加工输入已从缓存划出，单列加工状态。
        return body.getStructure().getOrgansList().stream().map(def -> {
            var state = body.getOrganStatesList().stream().filter(s -> s.getId().equals(def.getId()))
                    .findFirst().orElse(null);
            if (state == null) {
                return new Organ(def.getDisplayName(), -1, "");
            }
            String process = "";
            if (state.hasProcess()) {
                process = state.getProcess().getRecipe().getId();
            }
            return new Organ(def.getDisplayName(), QiDisplay.amount(state.getBuffer()), process);
        }).toList();
    }

    static List<String> rows(InspectionSection section, BodySnapshot body) {
        // 数字沿用小窗精度，选择模型后仍可打开完整部位说明。
        return switch (section) {
            case SURFACE -> parts(body).stream().map(p -> p.name() + " " + number(p.hp()) + "/" + number(p.limit()))
                    .toList();
            case MUSCLES -> muscles(body).stream().map(t -> t.name() + " HP " + number(t.hp())
                    + " 疲劳 " + number(t.fatigue()) + "/" + number(t.limit())).toList();
            case ORGANS -> organs(body).stream().map(o -> {
                String action = o.process();
                if (action.isEmpty()) {
                    action = "空闲";
                }
                return o.name() + " " + number(o.buffer()) + " 元 " + action;
            }).toList();
        };
    }

    static String number(double value) {
        // 相同的整数读数省末尾零，受伤后的分数保留到百分位。
        if (value < 0) {
            return "—";
        }
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
