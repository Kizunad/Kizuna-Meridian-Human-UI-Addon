package dev.kizuna.meridian.human.ui;

import dev.kizuna.inventoryui.client.component.ResponsiveLayout;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.ui.client.ActionRow;
import dev.kizuna.meridian.ui.client.MeridianTheme;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Positioning;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.parsing.UIModelLoader;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** 外壁储备的独立操作面板，不持有中焦模型或周天按键状态。 */
final class WallReservePanel {
    private static final int EDGE = 12;
    private static final int BUTTON_WIDTH = 132;
    private static final int BUTTON_HEIGHT = 28;
    private static final int FOOTER_SPACE = 64;
    private final FlowLayout root;
    private final WallReserveCanvas canvas = new WallReserveCanvas();
    private final ActionRow action;
    private BodySnapshot body;
    private WallReserveView view;

    WallReservePanel() {
        // XML 提供场景根，开始停止复用吐纳按钮的鼠标与键盘行为。
        var resource = Identifier.fromNamespaceAndPath("meridian_human_ui", "reserves");
        var content = UIModelLoader.get(resource).expandTemplate(FlowLayout.class, "reserves-panel", Map.of());
        action = new ActionRow(this::actionLabel, this::canAct, this::toggle);
        action.id("wall-reserve-mobilize");
        action.tooltip(Component.literal("将外壁真元释放到各脉内腔，内腔拥挤时保留储备。\n"
                + "关闭本窗口会停止使用外壁储备；正在突破时由中焦继续管理燃料。"));
        content.child(canvas);
        content.child(action);
        root = new ResponsiveLayout(content, this::arrange);
    }

    private void arrange(int width, int height) {
        // 最小尺寸仍保留完整剖面、读数和独立按钮命中区。
        place(canvas, new MeridianTheme.Bounds(EDGE, EDGE, width - EDGE * 2, height - FOOTER_SPACE));
        place(action, new MeridianTheme.Bounds((width - BUTTON_WIDTH) / 2,
                height - EDGE - BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT));
    }

    private static void place(UIComponent component, MeridianTheme.Bounds bounds) {
        // 控件绘制与工作区命中共用逻辑像素边界。
        component.positioning(Positioning.absolute(bounds.x(), bounds.y()));
        component.sizing(Sizing.fixed(bounds.width()), Sizing.fixed(bounds.height()));
    }

    FlowLayout root() {
        // 窗口 scope 持有同一棵组件树，更新资源不会重建按钮或焦点。
        return root;
    }

    void update(BodySnapshot snapshot) {
        // 一份快照只汇总一次，失去身体时清空画面与操作能力。
        if (body == snapshot) {
            return;
        }
        body = snapshot;
        if (snapshot == null) {
            view = null;
        } else {
            view = WallReserveView.from(snapshot);
        }
        canvas.update(view);
    }

    private String actionLabel() {
        // 运行反馈来自服务端，同一请求尚未确认时不伪造已开始状态。
        if (view != null && view.state().getMobilizing()) {
            return "停止使用";
        }
        return "使用";
    }

    private boolean canAct() {
        // 空壁不开始做功；已在动员或突破时仍允许明确停止。
        return view != null && !body.getVitality().getDead()
                && (view.amount() > 0 || view.state().getMobilizing());
    }

    private void toggle() {
        // 仅提交开关意图，内腔余量、脾耐力与精力均由权威规则决定。
        Operation operation = Operation.MOBILIZE_ON;
        if (view.state().getMobilizing()) {
            operation = Operation.MOBILIZE_OFF;
        }
        CultivationActions.send(body, operation);
    }

    void close() {
        // 关闭储备窗口不发 STOP，避免误停另一窗口的周天；突破燃料仍归周天管理。
        if (body != null && Minecraft.getInstance().getConnection() != null && !view.state().getBreakthrough()) {
            CultivationActions.send(body, Operation.MOBILIZE_OFF);
        }
    }
}
