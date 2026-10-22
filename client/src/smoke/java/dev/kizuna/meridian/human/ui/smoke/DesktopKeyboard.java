package dev.kizuna.meridian.human.ui.smoke;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Minecraft 将自身 AWT 固定为 headless，桌面按键由独立测试进程发送。 */
public final class DesktopKeyboard {
    private static Process keyboard;

    public static void space(boolean pressed) throws Exception {
        // 子进程只加载此测试类，继承 Xvfb 或 Windows 桌面，不更改游戏或系统设置。
        if (!pressed) {
            keyboard.getOutputStream().write('\n');
            keyboard.getOutputStream().flush();
            if (!keyboard.waitFor(10, TimeUnit.SECONDS) || keyboard.exitValue() != 0) {
                keyboard.destroyForcibly();
                throw new IllegalStateException("桌面按键释放失败");
            }
            keyboard = null;
            return;
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path classes = Path.of(DesktopKeyboard.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        keyboard = new ProcessBuilder(java.toString(), "-Djava.awt.headless=false", "-cp", classes.toString(),
                DesktopKeyboard.class.getName()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String ready = new java.io.BufferedReader(new java.io.InputStreamReader(keyboard.getInputStream())).readLine();
        if (!"PRESSED".equals(ready)) {
            throw new IllegalStateException("桌面按键发送失败");
        }
    }

    public static void main(String[] args) throws Exception {
        // 真正改变桌面键盘状态，正式界面仍通过 GLFW 查询空格并推进完整播放。
        Robot robot = new Robot();
        robot.keyPress(KeyEvent.VK_SPACE);
        System.out.println("PRESSED");
        System.out.flush();
        // 保持桌面连接直到父进程释放按键，避免 X11 在子进程退出时自动清除输入状态。
        System.in.read();
        robot.keyRelease(KeyEvent.VK_SPACE);
    }
}
