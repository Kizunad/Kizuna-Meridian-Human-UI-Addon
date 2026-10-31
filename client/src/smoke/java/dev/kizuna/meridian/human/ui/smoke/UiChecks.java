package dev.kizuna.meridian.human.ui.smoke;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kizuna.meridian.ui.client.BodyCanvas;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import io.wispforest.owo.ui.core.UIComponent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.ARGB;

/** 整合服 smoke 共用的界面断言；全部基于挂载后的真实组件边界、帧缓冲和 Screen 输入链。 */
final class UiChecks {
    private static int pendingFrames;
    private static RuntimeException frameFailure;

    private UiChecks() {
        // 只放跨旅程复用的断言，具体流程仍留在各自旅程中按顺序阅读。
    }

    static void requireInside(UIComponent parent, UIComponent child) {
        // 以挂载后的真实边界验收，覆盖 XML 百分比、窗口装饰与 GUI 缩放的组合结果。
        if (child.width() <= 0 || child.height() <= 0 || child.x() < parent.x() || child.y() < parent.y()
                || child.x() + child.width() > parent.x() + parent.width()
                || child.y() + child.height() > parent.y() + parent.height()) {
            throw new IllegalStateException("组件超出内容区：" + child.id());
        }
    }

    static void requireSeparate(UIComponent first, UIComponent second) {
        // 各区域可以共边，但不能相互覆盖文字或阻挡鼠标操作。
        if (first.x() < second.x() + second.width() && first.x() + first.width() > second.x()
                && first.y() < second.y() + second.height() && first.y() + first.height() > second.y()) {
            throw new IllegalStateException("组件相互覆盖：" + first.id() + " / " + second.id());
        }
    }

    static void click(Minecraft client, UIComponent component, int button) {
        // 点击控件中心并经过真实 Screen 按下与释放，保护遮挡和命中问题。
        double x = component.x() + component.width() / 2.0;
        double y = component.y() + component.height() / 2.0;
        client.gui.screen().mouseClicked(mouse(x, y, button), false);
        client.gui.screen().mouseReleased(mouse(x, y, button));
    }

    static MouseButtonEvent mouse(double x, double y, int button) {
        // 26.2 的 Screen 与 owo 输入以事件记录传入坐标和按键；验收输入不带修饰键。
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
    }

    static void clickBody(Minecraft client, BodyUiModule module, String targetId) {
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
            client.gui.screen().mouseClicked(mouse(canvas.x() + point[0], canvas.y() + point[1], 0), false);
            client.gui.screen().mouseReleased(mouse(canvas.x() + point[0], canvas.y() + point[1], 0));
            require(module.panel().selectedId().equals(targetId), "点击没有选择相应身体对象：" + targetId);
            return;
        }
        throw new IllegalStateException("当前层中没有可点击的对象：" + targetId);
    }

    static void requireModelPixels(Minecraft client, UIComponent canvas, int background) {
        // CPU 命中测试发现不了 GPU 矩阵裁剪错误，需确认画布里确实画出了与底色不同的模型像素。
        double scale = client.getWindow().getGuiScale();
        int left = (int) ((canvas.x() + 8) * scale);
        int top = (int) ((canvas.y() + 8) * scale);
        int right = (int) ((canvas.x() + canvas.width() - 8) * scale);
        int bottom = (int) ((canvas.y() + canvas.height() - 8) * scale);
        readFrame(client, image -> {
            int modelPixels = 0;
            for (int y = top; y < Math.min(bottom, image.getHeight()); y += 2) {
                for (int x = left; x < Math.min(right, image.getWidth()); x += 2) {
                    int color = image.getPixel(x, y);
                    int difference = Math.abs(ARGB.red(color) - (background >> 16 & 255))
                            + Math.abs(ARGB.green(color) - (background >> 8 & 255))
                            + Math.abs(ARGB.blue(color) - (background & 255));
                    if (difference > 30) {
                        modelPixels++;
                    }
                }
            }
            require(modelPixels >= (right - left) * (bottom - top) / 4000, "模型可以被选中但没有绘制到画布");
        });
    }

    static void readFrame(Minecraft client, Consumer<NativeImage> check) {
        // 26.2 的帧缓冲只能异步回读：复制命令在提交时取到已画好的上一帧，与 1.20.1 在同一 tick 同步读取的是同一帧。
        // 判定在回读完成后的回调里进行，失败先记下第一处，由 framesRead 在 tick 中抛出，照旧中止 smoke。
        pendingFrames++;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                check.accept(image);
            } catch (RuntimeException failure) {
                if (frameFailure == null) {
                    frameFailure = failure;
                }
            } finally {
                pendingFrames--;
            }
        });
    }

    static boolean framesRead() {
        // 依赖像素结果的下一步先等全部回读完成；已有判定失败时立即抛出。
        if (frameFailure != null) {
            throw frameFailure;
        }
        return pendingFrames == 0;
    }

    static void save(Minecraft client, NativeImage image, String name) {
        // 把参与判定的那一帧原样存档，与 Screenshot.grab 写到同一截图目录。
        Path directory = client.gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR);
        try {
            Files.createDirectories(directory);
            image.writeToFile(directory.resolve(name));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static void capture(Minecraft client, String name) {
        // 截图保存在运行目录，供复核真实画面。
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
    }

    static int runCommand(ServerPlayer player, String command) {
        // 以管理员权限走正式命令树。26.2 执行命令不再返回结果，经来源回调取得：
        // 顶层执行在返回前完成回调，解析失败不回调，结果保持 0。
        int[] result = {0};
        var source = player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER)
                .withCallback((success, value) -> result[0] = success ? value : 0);
        player.level().getServer().getCommands().performPrefixedCommand(source, command);
        return result[0];
    }

    static void require(boolean condition, String message) {
        // 验收失败立即终止，保留阶段日志，不能继续产出成功标记。
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
