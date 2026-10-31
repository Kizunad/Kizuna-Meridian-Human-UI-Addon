package dev.kizuna.meridian.human.ui.smoke;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.ui.HumanMeridianRoutes;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.ui.api.MeridianRouteProvider;
import dev.kizuna.meridian.ui.client.BodyCanvas;
import dev.kizuna.meridian.ui.client.BodyUiModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import java.nio.file.Files;
import java.util.List;
import java.util.function.BiConsumer;

/** 真实客户端回归：正式扩展发现、人体资源、内观窗口和 GPU 光迹。 */
public final class HumanOpeningSmoke implements ClientModInitializer {
    private BodyUiModule module;
    private BodySnapshot initial;
    private int age;
    private int ticks;
    private int upperMeridianPixel;
    private int upperTrailPixel = Integer.MAX_VALUE;
    private int brightestFrame;
    private final ProgressionJourney journey = new ProgressionJourney();
    private boolean journeyComplete;

    @Override
    public void onInitializeClient() {
        // 验收只在 smoke 源集中运行，失败退出游戏并保留 Gradle 堆栈。
        if (Boolean.getBoolean("meridian.revivalSmoke")) {
            new RevivalJourney().register();
            return;
        }
        if (Boolean.getBoolean("meridian.openingEffectsSmoke")) {
            new OpeningEffectsJourney().register();
            return;
        }
        // 验收按新玩家配置开始：删除上次运行留下的吐纳指引已读偏好，首次开始必须先显示指引。
        try {
            Files.deleteIfExists(FabricLoader.getInstance().getConfigDir().resolve("meridian-breathing-tutorial.txt"));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("无法重置吐纳指引偏好", failure);
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (Exception failure) {
                throw new IllegalStateException("人体内观 smoke 失败", failure);
            }
        });
    }

    private void tick(Minecraft client) throws Exception {
        // 等资源和工作台注册完成后装配只读样例，不依赖外部服务器或玩家存档。
        if (++age > 48_000) {
            throw new IllegalStateException("人体内观 smoke 超时");
        }
        if (client.gui.screen() instanceof AccessibilityOnboardingScreen) {
            client.gui.setScreen(new TitleScreen());
        }
        if (module == null && client.gui.overlay() == null) {
            List<MeridianRouteProvider> providers = FabricLoader.getInstance()
                    .getEntrypoints(MeridianRouteProvider.ENTRYPOINT, MeridianRouteProvider.class);
            if (providers.stream().filter(HumanMeridianRoutes.class::isInstance).count() != 1) {
                throw new IllegalStateException("正式 Human-UI 光迹扩展未加载或重复加载");
            }
            module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                    .filter(BodyUiModule.class::isInstance).map(BodyUiModule.class::cast).findFirst().orElseThrow();
        }
        if (module == null) {
            return;
        }
        if (!journeyComplete) {
            journeyComplete = journey.tick(client, module);
            return;
        }
        if (initial == null) {
            var time = TimeCursor.newBuilder().setDomain("smoke:human_ui").setEpoch("preview")
                    .setElapsedMillis(0).build();
            initial = BodySnapshot.parseFrom(HumanBirth.candidate(HumanContent.bundle().getTemplates(0), "preview", time));
            client.options.guiScale().set(2);
            client.resizeGui();
            module.present(initial, Identifier.fromNamespaceAndPath("meridian_human", "meridian/adult.pb"));
            InventoryUiClient.runtime().openWorkspace();
            InventoryUiClient.runtime().open("meridian_ui:meridians", null);
        }
        if ((ticks == 179 || ticks == 185) && !UiChecks.framesRead()) {
            // 下一步要比较此前提交的像素回读；回读未完成时不推进时间轴。
            return;
        }
        ticks++;
        if (ticks == 20) {
            pixels(client, false, (sample, image) -> upperMeridianPixel = sample[0]);
            capture(client, "middle-jiao-before.png");

            // 在真实拓扑上构造展示夹具，固定六秒在途便于逐帧检查交汇；不向服务器提交。
            var moving = initial.toBuilder().setRevision(1);
            for (String id : List.of("left_large_intestine_meridian.to.right_stomach_meridian",
                    "right_large_intestine_meridian.to.left_stomach_meridian",
                    "left_spleen_meridian.to.left_lung_meridian", "right_spleen_meridian.to.right_lung_meridian")) {
                initial.getStructure().getConnectionsList().stream().filter(edge -> edge.getId().equals(id))
                        .findFirst().orElseThrow();
                moving.addTransits(QiTransit.newBuilder().setId(id).setConnectionId(id)
                        .setStatus(TransitStatus.MOVING).setArrivalMillis(6000)
                        .setCargo(QiInventory.newBuilder().addEntries(Qi.newBuilder()
                                .setDensity(.01).setAttribute("meridian:none").setAmount(.1))));
            }
            module.update(moving.build());
        }
        if (ticks > 20 && ticks < 175 && ticks % 3 == 0) {
            // 人中高于游炁八脉的原模型；只有扩展正确进入绘制才能在该处看到明亮光尾。
            pixels(client, true, (bright, image) -> {
                if (bright[1] > brightestFrame) {
                    brightestFrame = bright[1];
                }
                if (bright[0] < upperTrailPixel) {
                    upperTrailPixel = bright[0];
                    UiChecks.save(client, image, "middle-jiao-crossing.png");
                }
            });
        }
        if (ticks == 180) {
            // 交汇必须明显高于肩部经脉，截图保留最接近人中的一帧，而非刚离开原网格的瞬间。
            double minimumRise = module.meridianPanel().canvas().height() * client.getWindow().getGuiScale() * .06;
            if (upperTrailPixel >= upperMeridianPixel - minimumRise || brightestFrame < 4) {
                throw new IllegalStateException("GPU 未显示人中交叉光迹：" + upperMeridianPixel + "/" + upperTrailPixel);
            }
            // 快照移除在途后应立即撤下光迹，改用同一身体继续保持镜头与窗口。
            module.update(initial.toBuilder().setRevision(2).build());
        }
        if (ticks == 185) {
            pixels(client, true, (bright, image) -> {
                if (bright[1] != 0) {
                    throw new IllegalStateException("在途移除后残留光迹");
                }
                UiChecks.save(client, image, "middle-jiao-stopped.png");
            });
        }
        if (ticks == 186) {
            Files.writeString(client.gameDirectory.toPath().resolve("human-ui-smoke-ok.txt"),
                    "Human-UI entrypoint; Human model; opening canvas; moving philtrum light; transit removal");
            System.out.println("HUMAN_UI_SMOKE_OK");
            client.stop();
        }
    }

    private void pixels(Minecraft client, boolean brightOnly, BiConsumer<int[], NativeImage> result) {
        // 只检查三维画布内部，排除顶栏文字；低亮度模型和亮光尾使用不同阈值。
        // 画布范围在提交回读时取定，与回读到的那一帧一致；最高光点与计数在回读完成后连同该帧交给调用方。
        BodyCanvas canvas = module.meridianPanel().canvas();
        double scale = client.getWindow().getGuiScale();
        int top = (int) (canvas.y() * scale) + 4;
        double bottom = (canvas.y() + canvas.height() - 110) * scale - 4;
        int left = (int) (canvas.x() * scale) + 4;
        double right = (canvas.x() + canvas.width()) * scale - 4;
        UiChecks.readFrame(client, image -> {
            int upper = Integer.MAX_VALUE;
            int count = 0;
            for (int y = top; y < bottom; y++) {
                for (int x = left; x < right; x++) {
                    int color = image.getPixel(x, y);
                    int red = ARGB.red(color);
                    int green = ARGB.green(color);
                    int blue = ARGB.blue(color);
                    int threshold = brightOnly ? 170 : 30;
                    if (red > threshold && red > green && green > blue + 8) {
                        upper = Math.min(upper, y);
                        count++;
                    }
                }
            }
            if (!brightOnly && count < 30) {
                throw new IllegalStateException("人体经脉模型未绘制");
            }
            result.accept(new int[]{upper, count}, image);
        });
    }

    private void capture(Minecraft client, String name) {
        // 截图保存在忽略的独立运行目录，供复核实际画面。
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, message -> {});
    }
}
