package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.human.fabric.HumanChannels;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Connection;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiTransit;
import dev.kizuna.meridian.ui.api.MeridianSceneExtension;
import dev.kizuna.meridian.ui.client.BodyCanvas;
import dev.kizuna.meridian.ui.client.BodyScene;
import dev.kizuna.meridian.ui.client.EffectSettings;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 人体内观的中焦周天操作层；只有输入和插值，所有数值读取服务端身体。 */
public final class HumanCultivationPanel implements MeridianSceneExtension {
    private static final int DIAL_WIDTH = 116;
    private static final int DIAL_RING_MAX = 34;
    private static final float TARGET_RADIUS = 14;
    private static final float MODEL_RING_MAX = 42;
    private static final int GREEN = 0xFF54EF82;
    private static final int RED = 0xFFFF5858;
    private static final int WHITE = 0xFFE8ECEA;
    private static final int GREY = 0xFF55615E;

    /** 关口提示状态；绿色只对应 READY 与 HIT，其余可按/不可按一眼区分。 */
    private enum Gate { IDLE, TRAVELLING, INHALE, EARLY, READY, LATE, HIT, MISS, CLEARED }

    @Override
    public String title() {
        // 生物玩法名称由人体附属提供，通用UI只负责登记入口。
        return "中焦";
    }

    @Override
    public String lockedReason() {
        // 客户端提示不代替服务端拦截；本卷同时解锁手动和自动。
        return dev.kizuna.meridian.progression.client.ClientProgression.canUseMiddleJiao()
                ? "" : "还没使用过「中焦残卷」。找到后右键一次，就能使用中焦周天和「自动」，无须看完。";
    }

    @Override
    public String bodySummary(BodySnapshot body) {
        // 身体主界面与部位详情使用同一寿元行，随权威版本更新。
        return HumanVitalityPresentation.summary(body);
    }

    @Override
    public Session create(BodyScene scene, BodyCanvas canvas) {
        // 每次打开独立窗口创建输入状态，避免关窗后旧按键继续发送。
        return new Controls(scene, canvas);
    }

    private static final class Controls extends BaseUIComponent implements Session {
        private final BodyScene scene;
        private final BodyCanvas canvas;
        private final HumanMeridianRoutes routes = new HumanMeridianRoutes();
        private BodySnapshot body;
        private CultivationState state = CultivationState.getDefaultInstance();
        private QiTransit transit;
        private List<Connection> gates = List.of();
        private long sequence;
        private long receivedNanos;
        private boolean lastPush;
        private boolean lastStrike;
        private boolean automaticSelected;

        Controls(BodyScene scene, BodyCanvas canvas) {
            // 独立中焦弹窗保留人体模型，控件只负责提交意图。
            this.scene = scene;
            this.canvas = canvas;
            id("middle-jiao-controls");
        }

        @Override
        public UIComponent component() {
            // UI 框架只挂载组件，不解释人体状态或命令。
            return this;
        }

        @Override
        public void update(BodySnapshot body) {
            // 进度和货物来自同一版本身体，不能从客户端累计出第二份真元余额。
            this.body = body;
            var progress = body.getAddonProgressMap().get("human:cultivation");
            try {
                state = progress == null ? CultivationState.getDefaultInstance() : progress.unpack(CultivationState.class);
            } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                throw new IllegalStateException(HumanUiErrors.INVALID_CULTIVATION, invalid);
            }
            sequence = Math.max(sequence, state.getLastSequence());
            if (state.getRunning()) {
                automaticSelected = state.getAutomatic();
            }
            transit = body.getTransitsList().stream().filter(item -> item.getId().equals("human-middle-jiao")).findFirst().orElse(null);
            // 四个关口按本圈实际线路排序：大肠经入胃交于人中，脾经入肺回到中焦。
            gates = transit == null ? List.of() : transit.getCircuit().getConnectionIdsList().stream()
                    .filter(id -> id.contains("large_intestine_meridian.to.") || id.contains("spleen_meridian.to."))
                    .map(id -> body.getStructure().getConnectionsList().stream().filter(item -> item.getId().equals(id)).findFirst().orElseThrow())
                    .toList();
            receivedNanos = System.nanoTime();
            effects();
        }

        @Override
        public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
            // 逐帧只画图并检测输入边沿；每帧不提交资源或时间推进。
            if (body == null) {
                return;
            }
            pollKeys();
            effects();
            context.fill(x, y, x + width, y + height, 0xE6080D12);
            int buttonWidth = Math.max(30, width / 5);
            button(context, 0, buttonWidth, state.getRunning() ? "停止周天" : "开始周天", state.getRunning());
            // 方框和勾号明确表示可切换模式，不再画成另一个启动按钮。
            int checkboxX = x + buttonWidth + 8;
            context.fill(checkboxX, y + 5, checkboxX + 12, y + 17, automaticSelected ? 0xFF70EE9B : 0xFFCAD7CE);
            context.fill(checkboxX + 1, y + 6, checkboxX + 11, y + 16, 0xFF15201C);
            text(context, automaticSelected ? "✓" : "", buttonWidth + 9, 6, 0xFF70EE9B);
            text(context, "自动", buttonWidth + 25, 7, automaticSelected ? 0xFF9BF2B8 : 0xFFE2E8E4);
            button(context, 2, buttonWidth, state.getMobilizing() ? "停止动员" : "动员外壁", state.getMobilizing());
            button(context, 3, buttonWidth, state.getBreakthrough() ? "正在冲境" : "突破真炁", state.getBreakthrough());
            button(context, 4, buttonWidth, (EffectSettings.reducedMotion() ? "静态 " : "动态 ")
                    + EffectSettings.particleLimit(), EffectSettings.reducedMotion());

            // 左栏放推力、批次和说明；右栏固定给关口指示盘或自动耐力，避免文字挤压圆圈。
            int infoWidth = width - DIAL_WIDTH - 18;
            double density = transit == null ? .01 : density(transit.getCargo());
            double qi = transit == null ? 0 : transit.getCargo().getEntriesList().stream().mapToDouble(Qi::getAmount).sum();
            int thrustColor = state.getThrust() > 1.2 ? 0xFFFF8067 : 0xFFDDCCA5;
            String thrustCaption = automaticSelected ? "推力由自动维持" : "G 推力：按住加力，松开减速，保持在两条竖线之间";
            text(context, trim(thrustCaption, infoWidth), 7, 26, thrustColor);
            bar(context, 7, 39, infoWidth, state.getThrust() / 2, thrustColor);
            // 安全带的窄区间始终可见，避免只靠颜色猜测力度。
            int low = x + 7 + (int) (infoWidth * .4);
            int high = x + 7 + (int) (infoWidth * .6);
            context.fill(low, y + 37, low + 1, y + 46, 0xFF81A991);
            context.fill(high, y + 37, high + 1, y + 46, 0xFF81A991);
            text(context, trim(String.format(Locale.ROOT, "本批 %.2f 元  ρ %.4f  周天 %d 圈  已过 %d/4 关",
                    qi, density, state.getLaps(), Math.min(4, state.getGateIndex())), infoWidth), 7, 49, 0xFFD5D9D6);
            double progress = state.getBreakthrough() ? state.getBreakthroughSeconds() / 60
                    : transit == null ? 0 : transit.getCircuit().getWorkMillis() / transit.getCircuit().getDurationMillis();
            bar(context, 7, 61, infoWidth, progress, state.getBreakthrough() ? 0xFF99E0DF : 0xFFCFBF8A);
            double wall = body.getMeridianStatesList().stream().flatMap(item -> item.getWallQi().getEntriesList().stream()).mapToDouble(Qi::getAmount).sum();
            text(context, trim(String.format(Locale.ROOT, "外壁 %.2f 元   %s", wall, state.getReason()), infoWidth), 7, 69, 0xFFB3BDBB);

            // 勾选自动后立即写明代价和限制；手动时说明红绿圈怎么看。
            // 从未运转过的身体还没有服务端周天状态，此时只写规则不写数字，不能显示成“低于0%”。
            String guide = "关口：小圈缩到白圈、变绿时按空格；红色是还没到或已错过，红色时按会撞回并耗精力。";
            if (automaticSelected && serverParameters()) {
                guide = String.format(Locale.ROOT, "自动：持续消耗脑耐力，不耗精力；脑耐力低于%.0f%%自动停下；提纯效率只有手动满分的%.0f%%。",
                        state.getAutomaticMinimumStaminaRatio() * 100, state.getAutomaticEfficiency() * 100);
            } else if (automaticSelected) {
                guide = "自动：持续消耗脑耐力，不耗精力；脑耐力低于停止线自动停下；提纯效率低于手动。点开始周天后显示具体数值。";
            }
            paragraph(context, guide, 7, 83, infoWidth, automaticSelected ? 0xFF9BF2B8 : 0xFFE6EAE7);
            text(context, trim("动员外壁另耗脾耐力和精力；关闭窗口会停止周天", infoWidth), 7, height - 12, 0xFF8E9A96);

            int dialLeft = width - DIAL_WIDTH - 6;
            context.fill(x + dialLeft, y + 24, x + width - 6, y + height - 4, 0xFF0E161B);
            if (automaticSelected) {
                automaticStatus(context, dialLeft);
            } else {
                gateDial(context, dialLeft, clock());
            }
            if (hovered) {
                tooltip(net.minecraft.network.chat.Component.literal("中焦八脉开通后运转。按住 G 增推力，松开减速。\n"
                        + "关口：小圈缩到白圈并变绿时按空格；红色表示还没到或已错过。\n"
                        + "只在呼气阶段能冲关，吸气时请等待。\n"
                        + "勾选自动：持续消耗脑耐力、不耗精力，低于停止线自动停，提纯效率低于手动。\n"
                        + "主动动员仍耗脾耐力和精力。"));
            }
        }

        private void gateDial(OwoUIGraphics context, int left, double clock) {
            // 右栏用同一套红绿语义画放大的关口圈，玩家不必在三维模型里找小圆。
            Gate gate = gate(clock);
            int localCenterY = 24 + DIAL_RING_MAX + 6;
            int centerX = x + left + DIAL_WIDTH / 2;
            int centerY = y + localCenterY;
            int targetColor = gate == Gate.READY || gate == Gate.HIT ? GREEN : gate == Gate.IDLE ? GREY : WHITE;
            ring(context, centerX, centerY, TARGET_RADIUS, gate == Gate.READY ? 4 : 2, targetColor);
            if (gate == Gate.READY || gate == Gate.EARLY || gate == Gate.LATE) {
                // 指示圈只在呼气且服务端已武装关口时出现，吸气或在途时不让玩家误以为可按。
                ring(context, centerX, centerY, indicatorRadius(clock, TARGET_RADIUS, DIAL_RING_MAX), 3,
                        gate == Gate.READY ? GREEN : RED);
            }
            if (gate == Gate.READY) {
                context.drawCircle(centerX, centerY, 32, TARGET_RADIUS - 3, io.wispforest.owo.ui.core.Color.ofArgb(0x6654EF82));
            }
            String title = switch (gate) {
                case IDLE -> "未运转";
                case TRAVELLING -> "真元在路上";
                case INHALE -> "吸气中";
                case EARLY -> "等变绿";
                case READY -> "按空格！";
                case LATE -> "错过了";
                case HIT -> "命中！";
                case MISS -> "撞回！";
                case CLEARED -> "四关已过";
            };
            String detail = switch (gate) {
                case IDLE -> "开始周天后看这里";
                case TRAVELLING -> "下一关：" + gateName();
                case INHALE -> String.format(Locale.ROOT, "%.1f 秒后呼气", secondsUntilExhale(clock));
                case EARLY -> gateName();
                case READY -> gateName();
                case LATE -> "等下一次收缩";
                case HIT -> "进入下一关";
                case MISS -> "红色时别按";
                case CLEARED -> "等本圈走完";
            };
            int titleColor = gate == Gate.READY || gate == Gate.HIT ? GREEN
                    : gate == Gate.IDLE || gate == Gate.TRAVELLING || gate == Gate.CLEARED ? WHITE : RED;
            centered(context, title, left + DIAL_WIDTH / 2, localCenterY + DIAL_RING_MAX + 4, titleColor, 1.25f);
            centered(context, detail, left + DIAL_WIDTH / 2, localCenterY + DIAL_RING_MAX + 18, 0xFFB3BDBB, 1);
        }

        private void automaticStatus(OwoUIGraphics context, int left) {
            // 自动模式只展示脑耐力和停止线，数值全部来自服务端写入的周天状态。
            int barLeft = left + 10;
            int barWidth = DIAL_WIDTH - 20;
            boolean live = state.getRunning() && state.getAutomatic();
            double stop = state.getAutomaticMinimumStaminaRatio();
            centered(context, live ? "自动运行中" : "已选自动", left + DIAL_WIDTH / 2, 32, 0xFF9BF2B8, 1.25f);
            if (live) {
                // 运行中每 250ms 结算一次，脑耐力是实时值；停下后的存档值会过时，因此只在运行中画条。
                double ratio = Math.max(0, Math.min(1, state.getBrainStaminaRatio()));
                text(context, String.format(Locale.ROOT, "脑耐力 %.0f%%", ratio * 100), barLeft, 50, 0xFFE6EAE7);
                context.fill(x + barLeft, y + 62, x + barLeft + barWidth, y + 70, 0xFF273139);
                context.fill(x + barLeft, y + 62, x + barLeft + (int) (barWidth * ratio), y + 70,
                        ratio < stop + .05 ? 0xFFFFB45C : 0xFF70EE9B);
                int stopX = x + barLeft + (int) (barWidth * stop);
                context.fill(stopX, y + 59, stopX + 1, y + 73, RED);
            } else {
                text(context, "开始后显示", barLeft, 50, 0xFFB3BDBB);
                text(context, "实时脑耐力", barLeft, 62, 0xFFB3BDBB);
            }
            if (serverParameters()) {
                text(context, String.format(Locale.ROOT, "低于 %.0f%% 自动停", stop * 100), barLeft, 77, RED);
                text(context, String.format(Locale.ROOT, "效率 %.0f%%", state.getAutomaticEfficiency() * 100), barLeft, 101, 0xFFB3BDBB);
            } else {
                text(context, "低于停止线自动停", barLeft, 77, RED);
                text(context, "效率低于手动", barLeft, 101, 0xFFB3BDBB);
            }
            text(context, "不耗精力", barLeft, 90, 0xFFB3BDBB);
        }

        private boolean serverParameters() {
            // 服务端写过周天状态后才带停止线、效率等展示参数；从未运转的身体只有默认空状态。
            return state.getSchemaVersion() > 0 && state.getAutomaticMinimumStaminaRatio() > 0;
        }

        private void effects() {
            // 人中与中焦坐标直接取人体路线，所有标记随原3D模型一起旋转。
            if (body == null) {
                return;
            }
            var markers = new ArrayList<BodyCanvas.Marker>();
            if (transit != null && state.getGateIndex() < gates.size()) {
                Connection gate = gates.get(state.getGateIndex());
                var points = routes.waypoints(scene.presentation(), body.getStructure(), gate);
                var point = points.get(points.size() / 2);
                double clock = clock();
                Gate status = gate(clock);
                // 白色固定圈标出目标；只有呼气且关口已到时才出现红/绿收缩圈。
                int targetColor = status == Gate.READY || status == Gate.HIT ? GREEN : WHITE;
                markers.add(new BodyCanvas.Marker(point.x(), point.y(), point.z(), TARGET_RADIUS,
                        status == Gate.READY ? 4 : 2, targetColor));
                if (status == Gate.READY || status == Gate.EARLY || status == Gate.LATE) {
                    float radius = EffectSettings.reducedMotion() ? TARGET_RADIUS + 6
                            : (float) indicatorRadius(clock, TARGET_RADIUS, MODEL_RING_MAX);
                    markers.add(new BodyCanvas.Marker(point.x(), point.y(), point.z(), radius, 3,
                            status == Gate.READY ? GREEN : RED));
                }
            }
            double purity = transit == null ? 0 : Math.max(0, Math.min(1, Math.log10(density(transit.getCargo()) / .01)));
            int red = (int) (255 - 20 * purity);
            int green = (int) (220 + 15 * purity);
            int blue = (int) (125 + 110 * purity);
            int color = 0xFF000000 | red << 16 | green << 8 | blue;
            if (state.getThrust() > 1.2) {
                color = 0xFFFF7062;
            }
            canvas.effects(markers, color);
        }

        private void pollKeys() {
            // 边沿发送避免按住键每帧刷包；窗口关闭单独撤销会话。
            long handle = Minecraft.getInstance().getWindow().handle();
            boolean push = state.getRunning() && !state.getAutomatic() && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_G) == GLFW.GLFW_PRESS;
            boolean strike = state.getRunning() && !state.getAutomatic() && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
            if (push != lastPush) {
                send(push ? CultivationInput.Operation.PUSH_ON : CultivationInput.Operation.PUSH_OFF);
            }
            if (strike && !lastStrike) {
                send(CultivationInput.Operation.STRIKE_GATE);
            }
            lastPush = push;
            lastStrike = strike;
        }

        @Override
        public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
            // 控件按实际可用宽度均分，缩放后仍使用同一命中布局；owo 传入的是组件内相对坐标。
            double mouseX = click.x();
            double mouseY = click.y();
            int button = click.button();
            int index = Math.min(4, (int) mouseX / Math.max(30, width / 5));
            if (button == 1 && mouseY <= 22 && index == 4) {
                EffectSettings.cycleLimit();
                return true;
            }
            if (button != 0 || mouseY > 22) {
                return super.onMouseDown(click, doubled);
            }
            switch (index) {
                case 0 -> send(state.getRunning() ? CultivationInput.Operation.STOP
                        : automaticSelected ? CultivationInput.Operation.START_AUTOMATIC : CultivationInput.Operation.START_MANUAL);
                case 1 -> {
                    automaticSelected = !automaticSelected;
                    if (state.getRunning()) {
                        send(automaticSelected ? CultivationInput.Operation.START_AUTOMATIC : CultivationInput.Operation.START_MANUAL);
                    }
                }
                case 2 -> send(state.getMobilizing() ? CultivationInput.Operation.MOBILIZE_OFF : CultivationInput.Operation.MOBILIZE_ON);
                case 3 -> send(CultivationInput.Operation.START_BREAKTHROUGH);
                case 4 -> EffectSettings.toggleMotion();
                default -> { /* 布局外不生成意图。 */ }
            }
            return true;
        }

        @Override
        public boolean keyPress(int key, int modifiers) {
            // 运转时 G 属于推力，不能同时触发原窗口的开脉快捷键。
            return state.getRunning() && (key == GLFW.GLFW_KEY_G || key == GLFW.GLFW_KEY_SPACE);
        }

        @Override
        public void close() {
            // 连接断开是生命周期结束；还在线时明确通知停止，服务端统一归还在途。
            if (Minecraft.getInstance().getConnection() != null && (state.getRunning() || state.getMobilizing())) {
                send(CultivationInput.Operation.STOP);
            }
            canvas.effects(List.of(), 0xFFFFE48A);
        }

        private void send(CultivationInput.Operation operation) {
            // 不上传推力数值、密度或门槛结果，服务端只接收边沿和所见模拟tick。
            var request = CultivationInput.newBuilder().setSequence(++sequence).setOperation(operation)
                    .setKeyTick(body.getSettledAt().getElapsedMillis() / 50).build();
            ClientPlayNetworking.send(HumanChannels.CULTIVATION.payload(request.toByteArray()));
        }

        private double clock() {
            // 有界预测只用于画圈，按键仍携带最近权威tick，由服务器裁定。
            return body.getSettledAt().getElapsedMillis() + Math.min(250, (System.nanoTime() - receivedNanos) / 1_000_000.0);
        }

        private Gate gate(double clock) {
            // 命中半窗、呼吸周期都来自权威协议；先显示服务端刚给出的命中/撞回，再按在途、吸气、未到、可按、错过判断。
            if (!state.getRunning() || state.getAutomatic()) {
                return Gate.IDLE;
            }
            if (state.getFeedbackUntil() > clock) {
                return state.getGateSuccess() ? Gate.HIT : Gate.MISS;
            }
            if (state.getGateIndex() >= 4) {
                return Gate.CLEARED;
            }
            if (state.getGateRetryUntil() == 0) {
                return Gate.TRAVELLING;
            }
            if (!exhaling(clock)) {
                return Gate.INHALE;
            }
            double offsetMillis = clock - state.getGateRetryUntil();
            if (Math.abs(offsetMillis) <= state.getGateWindowMillis()) {
                return Gate.READY;
            }
            return offsetMillis < 0 ? Gate.EARLY : Gate.LATE;
        }

        private String gateName() {
            // 名称取自实际连接，与三维模型上的标记位置对应；序号让玩家知道本圈还剩几关。
            int index = state.getGateIndex();
            if (index >= gates.size()) {
                return "关口 " + (index + 1) + "/4";
            }
            String name = gates.get(index).getId().contains("large_intestine_meridian.to.") ? "人中" : "中焦";
            return name + " · " + (index + 1) + "/" + gates.size();
        }

        private boolean exhaling(double clock) {
            // 与服务端同一呼吸周期公式预测相位，只决定颜色；按键结果仍按服务器收到时刻裁定。
            return clock % state.getBreathCycleMillis() >= state.getExhaleStartMillis();
        }

        private double secondsUntilExhale(double clock) {
            // 吸气阶段给出倒计时，让玩家知道要等多久而不是反复试按。
            double phaseMillis = clock % state.getBreathCycleMillis();
            return Math.max(0, state.getExhaleStartMillis() - phaseMillis) / 1000;
        }

        private double indicatorRadius(double clock, double target, double maximum) {
            // 到目标时刻正好与白圈重合；过了目标继续缩进白圈内，直观表示已经按晚。
            double remainingMillis = state.getGateRetryUntil() - clock;
            if (remainingMillis >= 0) {
                return target + Math.min(1, remainingMillis / state.getGateRetryMillis()) * (maximum - target);
            }
            double late = Math.min(1, -remainingMillis / state.getGateWindowMillis());
            return target * (1 - .5 * late);
        }

        private static void ring(OwoUIGraphics context, int centerX, int centerY, double radius, double thickness, int color) {
            // 二维指示盘与三维标记共用“白圈目标、红绿收缩圈”的语义，线宽足够看清颜色。
            var fill = io.wispforest.owo.ui.core.Color.ofArgb(color);
            context.drawRing(centerX, centerY, 48, Math.max(0, radius - thickness / 2), radius + thickness / 2, fill, fill);
        }

        private void centered(OwoUIGraphics context, String value, int center, int top, int color, float scale) {
            // 状态字放大居中，玩家余光就能看到“按空格！”或“吸气中”。
            var font = Minecraft.getInstance().font;
            float left = x + center - font.width(value) * scale / 2;
            context.drawText(net.minecraft.network.chat.Component.literal(value), left, y + top, scale, color);
        }

        private void paragraph(OwoUIGraphics context, String value, int left, int top, int maxWidth, int color) {
            // 说明按实际宽度折行，窄窗口下也不截掉停止线、效率等关键限制；最多三行以免压到底部提示。
            var font = Minecraft.getInstance().font;
            var lines = font.split(net.minecraft.network.chat.Component.literal(value), maxWidth);
            for (int line = 0; line < Math.min(3, lines.size()); line++) {
                context.text(font, lines.get(line), x + left, y + top + line * 11, color, false);
            }
        }

        private static String trim(String value, int maxWidth) {
            // 单行数据超宽时截断尾部，不让文字压进右侧关口盘。
            return Minecraft.getInstance().font.plainSubstrByWidth(value, Math.max(0, maxWidth));
        }

        private static double density(QiInventory inventory) {
            // 展示平均密度采用总量除以总体积，不能对各条密度直接算术平均。
            double amount = inventory.getEntriesList().stream().mapToDouble(Qi::getAmount).sum();
            double volume = inventory.getEntriesList().stream().mapToDouble(qi -> qi.getAmount() / qi.getDensity()).sum();
            return volume == 0 ? .01 : amount / volume;
        }

        private void button(OwoUIGraphics context, int index, int buttonWidth, String title, boolean active) {
            // 简洁自绘控件保持既有内观风格，避免原版按钮占去整块场景。
            int start = index * buttonWidth;
            context.fill(x + start + 2, y + 2, x + start + buttonWidth - 2, y + 20, active ? 0xFF414A3C : 0xFF202B31);
            text(context, title, start + 6, 7, active ? 0xFFFFE9A5 : 0xFFCBD3D4);
        }

        private void bar(OwoUIGraphics context, int left, int top, int barWidth, double fraction, int color) {
            // 进度仅显示权威快照，不在客户端继续累计突破秒数。
            context.fill(x + left, y + top, x + left + barWidth, y + top + 4, 0xFF273139);
            context.fill(x + left, y + top, x + left + (int) (barWidth * Math.max(0, Math.min(1, fraction))), y + top + 4, color);
        }

        private void text(OwoUIGraphics context, String text, int left, int top, int color) {
            // 控制条使用常规文本层，始终位于3D帧缓冲合成之后。
            context.text(Minecraft.getInstance().font, text, x + left, y + top, color, false);
        }
    }
}
