package dev.kizuna.meridian.human.ui;

import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowDefinition;
import dev.kizuna.meridian.progression.client.ClientProgression;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import java.util.Set;
import io.wispforest.owo.ui.core.ParentUIComponent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/** 独立外壁窗口；使用通用 UI 已验证的快照，不重复注册身体网络接收器。 */
public final class WallReserveModule implements ClientModule {
    static final String FEATURE = "meridian_human_ui:reserves";
    private static final String WINDOW = "meridian_human_ui:reserves_window";
    private static final int MIN_WIDTH = 340;
    private static final int MIN_HEIGHT = 340;
    private static final int WIDTH = 460;
    private static final int HEIGHT = 440;
    private BodyUiModule bodies;
    private WallReservePanel panel;

    @Override
    public FrameworkCatalog.Module definition() {
        // 储备可以从修炼菜单直接打开，无需先建立周天会话。
        var window = new UiWindowDefinition(WINDOW, FEATURE, MIN_WIDTH, MIN_HEIGHT, WIDTH, HEIGHT,
                Set.of(UiWindowDefinition.Capability.WINDOW));
        return FrameworkCatalog.Module.of(FEATURE, Set.of("meridian_ui:body"),
                new FrameworkCatalog.Window(window),
                new FrameworkCatalog.Feature(FEATURE, "外壁储备", "修炼", "meridian_ui:body_icon", WINDOW));
    }

    @Override
    public void register(ClientBindings bindings) {
        // 复用必装身体入口；窗口只在快照引用改变时更新汇总。
        bodies = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(BodyUiModule.class::isInstance)
                .map(BodyUiModule.class::cast)
                .findFirst().orElseThrow();
        bindings.featureAccess(FEATURE, WallReserveModule::canOpen);
        bindings.window(WINDOW, (runtime, window) -> {
            ClientProgression.requireCultivation();
            if (!canOpen()) {
                throw new IllegalStateException("需先阅读中焦残卷");
            }
            var opened = new WallReservePanel();
            panel = opened;
            opened.update(bodies.snapshot());
            window.scope().addCleanup(() -> {
                opened.close();
                if (panel == opened) {
                    panel = null;
                }
            });
            return opened.root();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (panel != null) {
                panel.update(bodies.snapshot());
            }
        });
    }

    private static boolean canOpen() {
        // 菜单权限与中焦一致，服务端仍会独立验证操作资格。
        return ClientProgression.canCultivate() && ClientProgression.canUseMiddleJiao();
    }

    public ParentUIComponent root() {
        // 宿主可以检查当前已挂载的窗口，关闭后不暴露失效组件。
        if (panel == null) {
            return null;
        }
        return panel.root();
    }

    @Override
    public void disconnected(ClientRuntime runtime) {
        // 工作区随后销毁窗口；先撤下身体，避免关闭回调向已断开的连接发送停止请求。
        if (panel != null) {
            panel.update(null);
        }
        panel = null;
        CultivationActions.disconnect();
    }
}
