package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.meridian.ui.client.BodyCanvas;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import io.wispforest.owo.ui.core.Component;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 整合服 smoke 共用的界面断言；全部基于挂载后的真实组件边界、帧缓冲和 Screen 输入链。 */
final class UiChecks {
    private UiChecks() {
        // 只放跨旅程复用的断言，具体流程仍留在各自旅程中按顺序阅读。
    }

    static void requireInside(Component parent, Component child) {
        // 以挂载后的真实边界验收，覆盖 XML 百分比、窗口装饰与 GUI 缩放的组合结果。
        if (child.width() <= 0 || child.height() <= 0 || child.x() < parent.x() || child.y() < parent.y()
                || child.x() + child.width() > parent.x() + parent.width()
                || child.y() + child.height() > parent.y() + parent.height()) {
            throw new IllegalStateException("组件超出内容区：" + child.id());
        }
    }

    static void requireSeparate(Component first, Component second) {
        // 各区域可以共边，但不能相互覆盖文字或阻挡鼠标操作。
        if (first.x() < second.x() + second.width() && first.x() + first.width() > second.x()
                && first.y() < second.y() + second.height() && first.y() + first.height() > second.y()) {
            throw new IllegalStateException("组件相互覆盖：" + first.id() + " / " + second.id());
        }
    }

    static void click(MinecraftClient client, Component component, int button) {
        // 点击控件中心并经过真实 Screen 按下与释放，保护遮挡和命中问题。
        double x = component.x() + component.width() / 2.0;
        double y = component.y() + component.height() / 2.0;
        client.currentScreen.mouseClicked(x, y, button);
        client.currentScreen.mouseReleased(x, y, button);
    }

    static void clickBody(MinecraftClient client, BodyUiModule module, String targetId) {
        // 从画布中心向外寻找目标的可见面，再经真实 Screen 链点击，确认选择事件接线。
        BodyCanvas canvas = module.panel().canvas();
        List<int[]> probes = new ArrayList<>();
        // 小器官在紧凑窗口里可能只有几个逻辑像素，采样步长不能跨过可点击三角面。
        int step = canvas.height() < 140 ? 1 : 3;
        for (int y = 10; y < canvas.height() - 10; y += step) {
            for (int x = 10; x < canvas.width() - 10; x += step) {
                probes.add(new int[]{x, y});
            }
        }
        probes.sort(Comparator.comparingDouble(point -> Math.pow(point[0] - canvas.width() / 2.0, 2)
                + Math.pow(point[1] - canvas.height() / 2.0, 2)));
        for (int[] point : probes) {
            var hit = canvas.pick(point[0], point[1]);
            if (hit == null || !hit.getTargetId().equals(targetId)) {
                continue;
            }
            client.currentScreen.mouseClicked(canvas.x() + point[0], canvas.y() + point[1], 0);
            client.currentScreen.mouseReleased(canvas.x() + point[0], canvas.y() + point[1], 0);
            require(module.panel().selectedId().equals(targetId), "点击没有选择相应身体对象：" + targetId);
            return;
        }
        throw new IllegalStateException("当前层中没有可点击的对象：" + targetId);
    }

    static void requireModelPixels(MinecraftClient client, Component canvas, int background) {
        // CPU 命中测试发现不了 GPU 矩阵裁剪错误，需确认画布里确实画出了与底色不同的模型像素。
        double scale = client.getWindow().getScaleFactor();
        int left = (int) ((canvas.x() + 8) * scale);
        int top = (int) ((canvas.y() + 8) * scale);
        int right = (int) ((canvas.x() + canvas.width() - 8) * scale);
        int bottom = (int) ((canvas.y() + canvas.height() - 8) * scale);
        int modelPixels = 0;
        try (NativeImage image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
            for (int y = top; y < Math.min(bottom, image.getHeight()); y += 2) {
                for (int x = left; x < Math.min(right, image.getWidth()); x += 2) {
                    int color = image.getColor(x, y);
                    int difference = Math.abs((color & 255) - (background >> 16 & 255))
                            + Math.abs((color >> 8 & 255) - (background >> 8 & 255))
                            + Math.abs((color >> 16 & 255) - (background & 255));
                    if (difference > 30) {
                        modelPixels++;
                    }
                }
            }
        }
        require(modelPixels >= (right - left) * (bottom - top) / 4000, "模型可以被选中但没有绘制到画布");
    }

    static void capture(MinecraftClient client, String name) {
        // 截图保存在运行目录，供复核真实画面。
        ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), ignored -> {});
    }

    static void require(boolean condition, String message) {
        // 验收失败立即终止，保留阶段日志，不能继续产出成功标记。
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
