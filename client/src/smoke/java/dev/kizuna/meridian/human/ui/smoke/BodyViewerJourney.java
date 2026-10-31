package dev.kizuna.meridian.human.ui.smoke;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.meridian.human.client.HumanPresentation;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.*;
import dev.kizuna.meridian.ui.client.BodyInformation;
import dev.kizuna.meridian.ui.client.BodyScene;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import io.wispforest.owo.ui.core.UIComponent;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

import static dev.kizuna.meridian.human.ui.smoke.UiChecks.*;

/** 整合服内的“检视”回归：真实玩家身体的五层模型、点击选取、聚焦、信息、刷新、勾选组合与关窗清理。 */
final class BodyViewerJourney {
    private static final int CANVAS_BACKGROUND = 0x131F25;
    private enum Stage { OPEN, SURFACE, SKELETON, MUSCLES, REAR_MUSCLE, ORGANS, REFRESH, OVERVIEW, ALL_ORGANS,
        MERIDIANS, ALL_MERIDIANS, CHECKBOXES, ASSEMBLY, EYE, CLOSE, DONE }
    private Stage stage = Stage.OPEN;
    private int ticks;
    private WindowKey window;
    private float yaw;
    private float pitch;
    private long revision;

    boolean tick(Minecraft client, BodyUiModule module) {
        // 每阶段等待真实组件、相机或服务端推送就绪；不向客户端注入伪造身体。完成后调用方仍会逐 tick 询问，直接返回。
        if (stage == Stage.DONE) {
            return true;
        }
        ticks++;
        require(ticks < 1200, "检视回归阶段超时：" + stage);
        switch (stage) {
            case OPEN -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                InventoryUiClient.runtime().openWorkspace();
                window = InventoryUiClient.runtime().open("meridian_ui:body", null).key();
                next(Stage.SURFACE);
            }
            case SURFACE -> {
                if (ticks < 20 || module.panel() == null) {
                    break;
                }
                capture(client, "body-surface.png");
                requireModelPixels(client, module.panel().canvas(), CANVAS_BACKGROUND);
                module.panel().layer("skeleton");
                next(Stage.SKELETON);
            }
            case SKELETON -> {
                if (ticks == 15) {
                    capture(client, "body-skeleton.png");
                    // 骨架节点在构建期绑定为 bone_ 组织，按正式资源中的颅骨节点取实际目标，不写死身份。
                    String skull = HumanPresentation.create().getVisualsList().stream()
                            .filter(node -> node.getId().equals("skeleton.skull")).findFirst().orElseThrow().getTargetId();
                    clickBody(client, module, skull);
                }
                if (ticks == 35) {
                    capture(client, "body-skull-focused.png");
                    module.panel().layer("muscles");
                    next(Stage.MUSCLES);
                }
            }
            case MUSCLES -> {
                if (ticks == 15) {
                    capture(client, "body-muscles.png");
                    clickBody(client, module, "left_biceps_brachii");
                }
                if (ticks == 35) {
                    capture(client, "body-biceps-focused.png");
                    next(Stage.REAR_MUSCLE);
                }
            }
            case REAR_MUSCLE -> {
                // 聚焦背侧肌肉后相机应自动转到背面，点击与信息继续绑定同一组织。
                BodyVisual triceps = visual("left_triceps_brachii");
                module.panel().overview();
                module.panel().canvas().camera().settle();
                module.panel().select(triceps);
                module.panel().canvas().camera().settle();
                require(Math.abs(Math.abs(module.panel().canvas().camera().yaw()) - 180) <= .01, "背侧肌肉聚焦未转向背面");
                clickBody(client, module, "left_triceps_brachii");
                List<String> information = BodyInformation.lines(body(module), triceps);
                require(information.stream().anyMatch(line -> line.startsWith("基础力量："))
                        && information.stream().anyMatch(line -> line.startsWith("疲劳：")), "肌肉模型没有显示组织能力与疲劳");
                capture(client, "body-triceps-focused.png");
                module.panel().layer("organs");
                next(Stage.ORGANS);
            }
            case ORGANS -> {
                if (ticks == 15) {
                    capture(client, "body-organs.png");
                    clickBody(client, module, "heart");
                }
                if (ticks == 35) {
                    capture(client, "body-heart-focused.png");
                    require(!module.panel().canvas().camera().moving(), "聚焦未完成");
                    // 玩家转动视角后由服务端真实推送新版本；调试命令只用于触发一次正常身体事务。
                    module.panel().canvas().camera().drag(20, 10);
                    module.panel().canvas().camera().settle();
                    yaw = module.panel().canvas().camera().yaw();
                    pitch = module.panel().canvas().camera().pitch();
                    revision = body(module).getRevision();
                    var server = client.getSingleplayerServer();
                    var playerId = client.player.getUUID();
                    server.execute(() -> {
                        // 与管理员在游戏中执行同一命令树；命令按来源玩家找到身体并走权威事务。
                        var player = server.getPlayerList().getPlayer(playerId);
                        server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER), "meridian qi fill");
                    });
                    next(Stage.REFRESH);
                }
            }
            case REFRESH -> {
                if (body(module).getRevision() <= revision) {
                    require(ticks < 400, "二十秒内没有收到新的服务端身体快照");
                    break;
                }
                module.panel().canvas().camera().settle();
                require(module.panel().canvas().camera().yaw() == yaw && module.panel().canvas().camera().pitch() == pitch
                        && module.panel().selectedId().equals("heart"), "快照刷新覆盖了镜头或选中对象");
                // 经真实 Screen 中键回到全身，面板选择也一起清空。
                var canvas = module.panel().canvas();
                client.gui.screen().mouseClicked(mouse(canvas.x() + canvas.width() / 2.0, canvas.y() + canvas.height() / 2.0, 2), false);
                client.gui.screen().mouseReleased(mouse(canvas.x() + canvas.width() / 2.0, canvas.y() + canvas.height() / 2.0, 2));
                next(Stage.OVERVIEW);
            }
            case OVERVIEW -> {
                require(module.panel().selectedId().isEmpty(), "回到全身后仍保留了旧对象选择");
                next(Stage.ALL_ORGANS);
            }
            case ALL_ORGANS -> {
                // 逐个聚焦后用真实可见面点击，覆盖小器官及背侧器官的遮挡。
                for (BodyVisual organ : visible(module, BodyTargetKind.ORGAN)) {
                    module.panel().select(organ);
                    module.panel().canvas().camera().settle();
                    clickBody(client, module, organ.getTargetId());
                    List<String> information = BodyInformation.lines(body(module), organ);
                    require(information.contains("器官：" + organ.getDisplayName())
                            && information.stream().anyMatch(line -> line.startsWith("缓存："))
                            && information.stream().anyMatch(line -> line.startsWith("加工：")),
                            "器官选择没有显示对应状态：" + organ.getTargetId());
                }
                module.panel().layer("meridians");
                next(Stage.MERIDIANS);
            }
            case MERIDIANS -> {
                if (ticks == 15) {
                    capture(client, "body-meridians.png");
                    next(Stage.ALL_MERIDIANS);
                }
            }
            case ALL_MERIDIANS -> {
                // 当前境界可见的每条经脉都要能点中，并显示内腔与开拓状态。
                List<BodyVisual> meridians = visible(module, BodyTargetKind.MERIDIAN);
                require(!meridians.isEmpty(), "经脉层没有可见经脉");
                for (BodyVisual meridian : meridians) {
                    module.panel().select(meridian);
                    module.panel().canvas().camera().settle();
                    clickBody(client, module, meridian.getTargetId());
                    List<String> information = BodyInformation.lines(body(module), meridian);
                    require(information.contains("经脉：" + meridian.getDisplayName())
                            && information.stream().anyMatch(line -> line.startsWith("内腔："))
                            && information.stream().anyMatch(line -> line.startsWith("开拓：")),
                            "经脉模型没有显示对应状态：" + meridian.getTargetId());
                }
                System.out.println("BODY_VIEWER_MERIDIANS " + meridians.size());
                next(Stage.CHECKBOXES);
            }
            case CHECKBOXES -> {
                checkboxes(client, module);
                module.panel().overview();
                next(Stage.ASSEMBLY);
            }
            case ASSEMBLY -> {
                if (ticks < 15) {
                    break;
                }
                requireModelPixels(client, module.panel().canvas(), CANVAS_BACKGROUND);
                capture(client, "body-assembly.png");
                module.panel().setLayerVisible("muscles", false);
                module.panel().setLayerVisible("meridians", false);
                module.panel().select(visual("left_eye"));
                clickBody(client, module, "left_eye");
                next(Stage.EYE);
            }
            case EYE -> {
                if (ticks == 20) {
                    capture(client, "body-eye-assembled.png");
                    InventoryUiClient.runtime().workspace().windows().close(window);
                    next(Stage.CLOSE);
                }
            }
            case CLOSE -> {
                require(module.panel() == null, "检视窗口关闭后未清理组件");
                // 两处模型像素回读都判定完才算通过。
                if (!framesRead()) {
                    break;
                }
                System.out.println("BODY_VIEWER_JOURNEY_OK layers/click/focus/organs/meridians/refresh/checkboxes/cleanup");
                next(Stage.DONE);
            }
            default -> { /* 完成后由调用方进入下一段旅程。 */ }
        }
        return stage == Stage.DONE;
    }

    private void checkboxes(Minecraft client, BodyUiModule module) {
        // 通过左侧真实勾选控件叠加各层；隐藏所在层会清除选择，全部关闭后不能再选中模型。
        var panel = module.panel();
        panel.layer("skeleton");
        for (String layer : List.of("muscles", "organs", "meridians")) {
            UIComponent checkbox = panel.root().childById(UIComponent.class, "layer-toggle-" + layer);
            client.gui.screen().mouseClicked(mouse(checkbox.x() + 12, checkbox.y() + 13, 0), false);
            client.gui.screen().mouseReleased(mouse(checkbox.x() + 12, checkbox.y() + 13, 0));
        }
        require(panel.visibleLayers().equals(Set.of("skeleton", "muscles", "organs", "meridians")), "勾选没有保留多层组合");
        panel.select(visual("left_eye"));
        panel.setLayerVisible("organs", false);
        require(panel.selectedId().isEmpty(), "隐藏器官后仍保留眼球选择");
        for (String layer : List.copyOf(panel.visibleLayers())) {
            panel.setLayerVisible(layer, false);
        }
        require(panel.canvas().pick(10, 10) == null, "全层关闭后仍能选中模型");
        for (String layer : List.of("skeleton", "muscles", "organs", "meridians")) {
            panel.setLayerVisible(layer, true);
        }
    }

    private static List<BodyVisual> visible(BodyUiModule module, BodyTargetKind kind) {
        // 只验收当前境界下真实存在的对象，与玩家可见集合一致。
        BodySnapshot body = body(module);
        return HumanPresentation.create().getVisualsList().stream()
                .filter(node -> node.getTargetKind() == kind)
                .filter(node -> BodyScene.exists(body, node) && BodyScene.realmAllows(body, node))
                .toList();
    }

    private static BodyVisual visual(String targetId) {
        // 节点身份来自 Human 正式展示资源，测试不另建人体坐标。
        return HumanPresentation.create().getVisualsList().stream()
                .filter(node -> node.getTargetId().equals(targetId)).findFirst().orElseThrow();
    }

    private static BodySnapshot body(BodyUiModule module) {
        // 读取已通过连接和版本校验的同一份客户端投影。
        return module.snapshot();
    }

    private void next(Stage stage) {
        // 阶段日志便于超时时定位具体检查点。
        this.stage = stage;
        ticks = 0;
        System.out.println("BODY_VIEWER_STAGE " + stage);
    }
}
