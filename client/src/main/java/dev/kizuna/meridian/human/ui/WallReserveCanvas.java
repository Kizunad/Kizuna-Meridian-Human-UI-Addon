package dev.kizuna.meridian.human.ui;

import dev.kizuna.inventoryui.theme.ThemeTokens;
import dev.kizuna.meridian.ui.client.EffectSettings;
import dev.kizuna.meridian.ui.client.MeridianTheme;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** 经脉空壁底图与真元脂肪分层绘制；动画仅连接已收到的权威储量。 */
final class WallReserveCanvas extends BaseUIComponent {
    private static final Identifier SECTION = texture("section");
    private static final Identifier FAT = texture("fat");
    private static final int TEXTURE_SIZE = 256;
    private static final int MAX_IMAGE = 256;
    private static final int EDGE = 12;
    private static final int IMAGE_TOP = 24;
    private static final int CAPTION_SPACE = 32;
    private static final int LINE_HEIGHT = 16;
    private static final int PERCENT = 100;
    private static final double CENTER = 0.5;
    private static final double NANOS_PER_MILLI = 1_000_000;
    private static final double BLEND_MILLIS = 180;
    private static final double RELEASE_MILLIS = 700;
    private static final double MINIMUM_TRANSFER = 0.000001;
    private static final int RELEASE_PARTICLES = 5;
    private static final int SPARK_RADIUS = 2;
    private static final double RELEASE_START = 0.35;
    private static final double RELEASE_END = 0.19;
    private static final double RELEASE_ARC = Math.PI / 3;
    private static final int GOLD = 0xFFF0CE87;
    private WallReserveView view;
    private double previousFraction;
    private long receivedNanos;
    private long releaseNanos;

    WallReserveCanvas() {
        // 组件负责剖面和读数，开始停止属于独立的可聚焦按钮。
        id("wall-reserve-canvas");
    }

    void update(WallReserveView next) {
        // 只有真实外壁减少才发出释放亮点；拥挤、空壁或燃料消耗不伪装成流入内腔。
        long now = System.nanoTime();
        if (next == null) {
            view = null;
            releaseNanos = 0;
            return;
        }
        double nextFraction = next.fraction();
        if (view == null) {
            previousFraction = nextFraction;
            receivedNanos = now;
        } else {
            if (view.fraction() != nextFraction) {
                previousFraction = shownFraction(now);
                receivedNanos = now;
            }
            if (next.state().getMobilizing() && !next.state().getBreakthrough()
                    && view.amount() - next.amount() > MINIMUM_TRANSFER
                    && (now - releaseNanos) / NANOS_PER_MILLI >= RELEASE_MILLIS) {
                releaseNanos = now;
            }
        }
        view = next;
        tooltip(Component.literal(String.format(Locale.ROOT,
                "全身外壁：%.2f 元\n占用体积：%.2f / %.2f\n比例按各份真元量 ÷ 密度累计。\n"
                        + "剖面为全身储备示意，不对应某条经脉。", next.amount(), next.volume(), next.capacity())));
    }

    @Override
    public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
        // 内腔不参与脂肪填充，图片裁切面积与权威百分比保持一致。
        if (view == null) {
            caption(graphics, "等待身体状态", height / 2, ThemeTokens.MUTED_TEXT);
            return;
        }
        long now = System.nanoTime();
        int size = Math.max(1, Math.min(MAX_IMAGE, Math.min(width - EDGE * 2, height - IMAGE_TOP - CAPTION_SPACE)));
        int left = x + (width - size) / 2;
        int top = y + IMAGE_TOP;
        image(graphics, SECTION, left, top, size);
        int fillTop = top + (int) Math.round(size * WallReserveFill.top(shownFraction(now)));
        graphics.enableScissor(left, fillTop, left + size, top + size);
        image(graphics, FAT, left, top, size);
        graphics.disableScissor();
        release(graphics, new MeridianTheme.Bounds(left, top, size, size), now);
        caption(graphics, String.format(Locale.ROOT, "全身外壁  %.1f%%", view.fraction() * PERCENT),
                0, ThemeTokens.TEXT);
        caption(graphics, "内腔", IMAGE_TOP + size / 2 - LINE_HEIGHT, ThemeTokens.TEXT);
        caption(graphics, String.format(Locale.ROOT, "%.1f 元", view.lumen()),
                IMAGE_TOP + size / 2, ThemeTokens.MUTED_TEXT);
        caption(graphics, String.format(Locale.ROOT, "外壁储备  %.2f 元", view.amount()),
                IMAGE_TOP + size + EDGE, ThemeTokens.TEXT);
    }

    private double shownFraction(long now) {
        // 静态模式直接显示最新值，普通模式只在两份已知快照之间过渡。
        double target = view.fraction();
        if (EffectSettings.reducedMotion()) {
            return target;
        }
        double progress = Math.clamp((now - receivedNanos) / NANOS_PER_MILLI / BLEND_MILLIS, 0, 1);
        return previousFraction + (target - previousFraction) * progress;
    }

    private void release(OwoUIGraphics graphics, MeridianTheme.Bounds image, long now) {
        // 短动画在已确认动员后结束；未收到新提取时不会持续假装有真元流动。
        double elapsed = (now - releaseNanos) / NANOS_PER_MILLI;
        if (releaseNanos == 0 || elapsed >= RELEASE_MILLIS || EffectSettings.reducedMotion()
                || !view.state().getMobilizing() || view.state().getBreakthrough()) {
            return;
        }
        double progress = elapsed / RELEASE_MILLIS;
        double radius = (RELEASE_START + (RELEASE_END - RELEASE_START) * progress) * image.width();
        for (int particle = 0; particle < RELEASE_PARTICLES; particle++) {
            double angle = Math.PI / 2 + (particle / (double) (RELEASE_PARTICLES - 1) - CENTER) * RELEASE_ARC;
            int px = image.x() + image.width() / 2 + (int) Math.round(Math.cos(angle) * radius);
            int py = image.y() + image.height() / 2 + (int) Math.round(Math.sin(angle) * radius);
            graphics.fill(px - SPARK_RADIUS, py - SPARK_RADIUS, px + SPARK_RADIUS, py + SPARK_RADIUS, GOLD);
        }
    }

    private static Identifier texture(String name) {
        // 两层纹理随人体附属发布，可由资源包替换同尺寸图片。
        return Identifier.fromNamespaceAndPath("meridian_human_ui", "textures/gui/reserves/" + name + ".png");
    }

    private static void image(OwoUIGraphics graphics, Identifier texture, int left, int top, int size) {
        // 图像坐标与裁切使用同一逻辑像素，GUI 缩放不会改变脂肪覆盖范围。
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, left, top, 0, 0,
                size, size, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
    }

    private void caption(OwoUIGraphics graphics, String text, int top, String token) {
        // 简短读数跟随工作区主题，精确体积放在悬浮说明中。
        var font = Minecraft.getInstance().font;
        graphics.text(font, text, x + (width - font.width(text)) / 2, y + top,
                MeridianTheme.color(token), false /* shadow */);
    }

    @Override
    public boolean isInBoundingBox(double mouseX, double mouseY) {
        // 详细账本只在顶部百分比上悬浮展开，鼠标停在剖面中央不会遮住脂肪层。
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + LINE_HEIGHT;
    }
}
