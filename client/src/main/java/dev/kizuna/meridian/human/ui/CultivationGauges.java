package dev.kizuna.meridian.human.ui;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.ui.client.EffectSettings;
import dev.kizuna.meridian.ui.client.MeridianTheme;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** 周天场景边缘的图像控件；详细账户随悬浮展开，画布保留给人体与光迹。 */
final class CultivationGauges {
    private static final int PERCENT = 100;
    static final Identifier QI = texture("qi");
    private static final Identifier BRAIN = texture("brain");
    private static final int TEXTURE_SIZE = 32;
    private static final int LARGE_IMAGE = 48;
    private static final int COMPACT_IMAGE = 32;
    private static final int COMPACT_WIDTH = 104;
    private static final int IMAGE_CAPTION_GAP = 6;
    private static final int DETAIL_GAP = 14;
    private static final int COMPACT_RING_CENTER = 32;
    private static final int COMPACT_RING_MAX = 26;
    private static final int COMPACT_RING_TEXT = 66;
    private static final int COMPACT_RING_DETAIL = 80;
    private static final int COMPACT_DOTS_TOP = 98;
    private static final int IMAGE_TOP = 4;
    private static final int SECOND_DETAIL_TOP = 86;
    private static final int LINE_HEIGHT = 12;
    private static final int BAR_TOP = 23;
    private static final int BAR_HEIGHT = 4;
    private static final int MARK_OVERHANG = 3;
    private static final int EDGE = 4;
    private static final double SAFE_START = 0.4;
    private static final double SAFE_END = 0.6;
    private static final int RING_SEGMENTS = 48;
    private static final int RING_CENTER_Y = 42;
    private static final int RING_MAX = 34;
    private static final int RING_TEXT_TOP = 84;
    private static final int RING_DETAIL_TOP = 99;
    private static final int DOTS_TOP = 122;
    private static final int DOT_GAP = 16;
    private static final int DOT_SIZE = 5;
    private static final int RING_THICKNESS = 3;
    private static final int READY_THICKNESS = 5;
    private static final int READY_GLOW = 0x6654EF82;
    private static final double ORBIT_SECONDS = 2.5;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final int ORBIT_RADIUS = 26;
    private static final int SPARK_RADIUS = 2;
    private static final double SMOOTHING_RATE = 12;
    private static final double MAX_FRAME_SECONDS = 0.1;
    private final CultivationView view;
    private final BooleanSupplier automatic;
    final Gauge batch = new BatchGauge();
    final Gauge gate = new GateGauge();
    final Gauge thrust = new ThrustGauge();
    final Gauge footer = new Footer();

    CultivationGauges(CultivationView view, BooleanSupplier automatic) {
        // 模式由会话提供，控件不另存一份自动开关。
        this.view = view;
        this.automatic = automatic;
        batch.id("middle-jiao-batch");
        gate.id("middle-jiao-gate");
        thrust.id("middle-jiao-thrust");
        footer.id("middle-jiao-footer");
    }

    void update() {
        // 明细随权威快照刷新，保留密度、效率和成本的可查性。
        var state = view.state();
        var cargo = view.batch();
        batch.tooltip(Component.literal(String.format(Locale.ROOT,
                "本圈真元 %.2f 元\n密度 %.4f\n已完成 %d 圈\n已过 %d/%d 关",
                cargo.amount(), cargo.density(), state.getLaps(),
                Math.min(CultivationView.GATE_COUNT, state.getGateIndex()), CultivationView.GATE_COUNT)));
        gate.tooltip(Component.literal("关口合圈变绿时按空格；吸气时等待。\n红色时冲关会撞回并耗精力。\n"
                + view.automaticNote()));
        thrust.tooltip(Component.literal("按住 G 增加推力，松开减速。保持在两条刻线之间。\n"
                + "过高推力会损伤经脉；自动模式由服务端维持推力。"));
        footer.tooltip(Component.literal("拖动旋转 · 滚轮缩放 · 中键复位\n关闭窗口会停止周天。\n"
                + state.getReason()));
    }

    List<Gauge> components() {
        // 每个图像有独立命中范围，场景空白处继续接收旋转和缩放。
        return List.of(batch, gate, thrust, footer);
    }

    private static Identifier texture(String name) {
        // 人体专属贴图随 Human-UI 正式资源发布。
        return Identifier.fromNamespaceAndPath("meridian_human_ui", "textures/gui/cultivation/" + name + ".png");
    }

    abstract class Gauge extends BaseUIComponent {
        void image(OwoUIGraphics context, Identifier texture, int top) {
            // 透明贴图直接叠在场景上，控件自身不铺背景板。
            int size = imageSize();
            int left = x + (width - size) / 2;
            context.blit(RenderPipelines.GUI_TEXTURED, texture, left, y + top, 0, 0,
                    size, size, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
        }

        int imageSize() {
            // 窄边栏使用原尺寸图标，缩小后仍保留清晰的像素轮廓。
            return width < COMPACT_WIDTH ? COMPACT_IMAGE : LARGE_IMAGE;
        }

        int captionTop() {
            // 标签跟随实际图像高度，最小窗口不会保留大图的空白。
            return IMAGE_TOP + imageSize() + IMAGE_CAPTION_GAP;
        }

        void caption(OwoUIGraphics context, String value, int top, String token) {
            // 简短标题居于图像下方，超宽文本留在该控件的完整悬浮说明中。
            var font = Minecraft.getInstance().font;
            String clipped = font.plainSubstrByWidth(value, Math.max(1, width - EDGE * 2));
            context.text(font, clipped, x + (width - font.width(clipped)) / 2, y + top,
                    MeridianTheme.color(token), false /* shadow */);
        }

        void bar(OwoUIGraphics context, int top, double ratio) {
            // 轨道只作资源读数，不添加卡片边框或装饰光圈。
            int right = x + width - EDGE;
            int fill = (int) Math.round((width - EDGE * 2) * Math.max(0, Math.min(1, ratio)));
            context.fill(x + EDGE, y + top, right, y + top + BAR_HEIGHT,
                    MeridianTheme.color(ThemeTokens.PROGRESS_TRACK));
            context.fill(x + EDGE, y + top, x + EDGE + fill, y + top + BAR_HEIGHT,
                    MeridianTheme.color(ThemeTokens.PROGRESS_FILL));
        }
    }

    private final class BatchGauge extends Gauge {
        @Override
        public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
            // 气团与绕行亮点表示在途；圈数与真元量始终读取服务端。
            image(context, QI, IMAGE_TOP);
            var state = view.state();
            if (state.getRunning() && !EffectSettings.reducedMotion()) {
                double angle = System.nanoTime() / NANOS_PER_SECOND / ORBIT_SECONDS * Math.TAU;
                int centerX = x + width / 2;
                int centerY = y + IMAGE_TOP + imageSize() / 2;
                int sparkX = centerX + (int) Math.round(Math.cos(angle) * ORBIT_RADIUS);
                int sparkY = centerY + (int) Math.round(Math.sin(angle) * ORBIT_RADIUS);
                context.fill(sparkX - SPARK_RADIUS, sparkY - SPARK_RADIUS,
                        sparkX + SPARK_RADIUS, sparkY + SPARK_RADIUS, CultivationView.GOLD);
            }
            var batch = view.batch();
            int top = captionTop();
            caption(context, String.format(Locale.ROOT, "在途 %.2f 元", batch.amount()), top, ThemeTokens.TEXT);
            caption(context, "周天 " + state.getLaps() + " 圈", top + DETAIL_GAP, ThemeTokens.MUTED_TEXT);
            if (width >= COMPACT_WIDTH) {
                bar(context, SECOND_DETAIL_TOP, batch.progress());
            }
        }
    }

    private final class ThrustGauge extends Gauge {
        private double shownThrust;
        private long frameNanos;

        @Override
        public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
            // 平滑只作用于条形长度，警告与按键判定仍使用当前权威推力。
            long now = System.nanoTime();
            double elapsed = 0;
            if (frameNanos != 0) {
                elapsed = Math.min(MAX_FRAME_SECONDS, (now - frameNanos) / NANOS_PER_SECOND);
            }
            frameNanos = now;
            double thrust = view.state().getThrust();
            shownThrust += (thrust - shownThrust) * -Math.expm1(-elapsed * SMOOTHING_RATE);
            String title = "G  推力";
            if (automatic.getAsBoolean()) {
                title = "自动维持推力";
            } else if (thrust > CultivationView.THRUST_WARNING) {
                title = "推力过高 · 松开 G";
            }
            caption(context, title, IMAGE_TOP, ThemeTokens.TEXT);
            bar(context, BAR_TOP, shownThrust / CultivationView.THRUST_MAX);
            for (double ratio : new double[]{SAFE_START, SAFE_END}) {
                int marker = x + EDGE + (int) Math.round((width - EDGE * 2) * ratio);
                context.fill(marker, y + BAR_TOP - MARK_OVERHANG, marker + 1,
                        y + BAR_TOP + BAR_HEIGHT + MARK_OVERHANG, MeridianTheme.color(ThemeTokens.TEXT));
            }
        }
    }

    private final class GateGauge extends Gauge {
        @Override
        public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
            // 自动时显示脑图与停止线，手动时显示有明确目标时机的关口收缩动画。
            if (automatic.getAsBoolean()) {
                automatic(context);
            } else {
                manual(context);
            }
        }

        private void manual(OwoUIGraphics context) {
            // 目标圈常驻，收缩圈只在可冲关阶段出现；成功扩大圈宽形成明确反馈。
            CultivationView.Gate gate = view.gate();
            int target = MeridianTheme.color(ThemeTokens.MUTED_TEXT);
            int thickness = RING_THICKNESS;
            if (gate == CultivationView.Gate.READY || gate == CultivationView.Gate.HIT) {
                target = CultivationView.GREEN;
                thickness = READY_THICKNESS;
            }
            ring(context, CultivationView.TARGET_RADIUS, thickness, target);
            if (gate == CultivationView.Gate.EARLY || gate == CultivationView.Gate.READY
                    || gate == CultivationView.Gate.LATE) {
                int color = CultivationView.RED;
                if (gate == CultivationView.Gate.READY) {
                    color = CultivationView.GREEN;
                }
                int maximum = width < COMPACT_WIDTH ? COMPACT_RING_MAX : RING_MAX;
                ring(context, view.indicatorRadius(maximum), RING_THICKNESS, color);
            }
            if (gate == CultivationView.Gate.READY) {
                int center = width < COMPACT_WIDTH ? COMPACT_RING_CENTER : RING_CENTER_Y;
                context.drawCircle(x + width / 2, y + center, RING_SEGMENTS,
                        CultivationView.TARGET_RADIUS - RING_THICKNESS, Color.ofArgb(READY_GLOW));
            }
            int textTop = width < COMPACT_WIDTH ? COMPACT_RING_TEXT : RING_TEXT_TOP;
            int detailTop = width < COMPACT_WIDTH ? COMPACT_RING_DETAIL : RING_DETAIL_TOP;
            caption(context, view.instruction(), textTop, ThemeTokens.TEXT);
            caption(context, view.gateName(), detailTop, ThemeTokens.MUTED_TEXT);
            dots(context);
        }

        private void dots(OwoUIGraphics context) {
            // 小方点逐个记录本圈已完成关口，不用环形仪表重复堆叠数字。
            int span = (CultivationView.GATE_COUNT - 1) * DOT_GAP + DOT_SIZE;
            int top = width < COMPACT_WIDTH ? COMPACT_DOTS_TOP : DOTS_TOP;
            int cleared = view.state().getGateIndex();
            for (int index = 0; index < CultivationView.GATE_COUNT; index++) {
                int left = x + (width - span) / 2 + index * DOT_GAP;
                int color = MeridianTheme.color(ThemeTokens.BORDER);
                if (index < cleared) {
                    color = CultivationView.GOLD;
                }
                context.fill(left, y + top, left + DOT_SIZE, y + top + DOT_SIZE, color);
            }
        }

        private void ring(OwoUIGraphics context, double radius, int thickness, int color) {
            // 关口用足够粗的实线，缩小窗口时也能看见合圈与颜色变化。
            Color fill = Color.ofArgb(color);
            double half = thickness / 2.0;
            int center = width < COMPACT_WIDTH ? COMPACT_RING_CENTER : RING_CENTER_Y;
            context.drawRing(x + width / 2, y + center, RING_SEGMENTS,
                    Math.max(0, radius - half), radius + half, fill, fill);
        }

        private void automatic(OwoUIGraphics context) {
            // 休息期间仍显示当前脑耐力，暂停线与恢复线均来自服务端。
            image(context, BRAIN, IMAGE_TOP);
            var state = view.state();
            int top = captionTop();
            int barTop = top + DETAIL_GAP;
            String caption = "自动周天";
            if (state.getAutomaticWaiting()) {
                caption = "休息恢复中";
            }
            if (state.getAutomatic() && (state.getRunning() || state.getAutomaticWaiting())) {
                caption = String.format(Locale.ROOT, "脑耐力 %.0f%%", state.getBrainStaminaRatio() * PERCENT);
                if (state.getAutomaticWaiting()) {
                    caption = String.format(Locale.ROOT, "恢复中 %.0f%%", state.getBrainStaminaRatio() * PERCENT);
                }
                bar(context, barTop, state.getBrainStaminaRatio());
                int marker = x + EDGE + (int) Math.round((width - EDGE * 2)
                        * state.getAutomaticMinimumStaminaRatio());
                context.fill(marker, y + barTop - MARK_OVERHANG, marker + 1,
                        y + barTop + BAR_HEIGHT + MARK_OVERHANG, MeridianTheme.color(ThemeTokens.INPUT_ERROR));
                int resumeMarker = x + EDGE + (int) Math.round((width - EDGE * 2)
                        * state.getAutomaticResumeStaminaRatio());
                context.fill(resumeMarker, y + barTop - MARK_OVERHANG, resumeMarker + 1,
                        y + barTop + BAR_HEIGHT + MARK_OVERHANG, MeridianTheme.color(ThemeTokens.PROGRESS_FILL));
            }
            caption(context, caption, top, ThemeTokens.TEXT);
            String[] lines = view.automaticNote().split("\n");
            for (int line = 0; line < lines.length; line++) {
                caption(context, lines[line], barTop + DETAIL_GAP + line * LINE_HEIGHT, ThemeTokens.MUTED_TEXT);
            }
        }
    }

    private final class Footer extends Gauge {
        @Override
        public void draw(OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
            // 底部只显示当前输入或权威失败原因，完整规则留在各控件悬浮说明。
            String hint = "G 加力 · 空格冲关 · 拖动旋转 · 滚轮缩放";
            if (automatic.getAsBoolean()) {
                hint = "自动周天 · 拖动旋转 · 滚轮缩放";
            }
            String reason = view.state().getReason();
            if (!reason.isBlank()) {
                hint = reason;
            }
            var font = Minecraft.getInstance().font;
            String clipped = font.plainSubstrByWidth(hint, Math.max(1, width));
            context.text(font, clipped, x, y, MeridianTheme.color(ThemeTokens.MUTED_TEXT), false /* shadow */);
        }
    }
}
