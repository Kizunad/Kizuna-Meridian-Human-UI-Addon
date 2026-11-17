package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.fabric.MeridianChannels;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput;
import dev.kizuna.meridian.human.server.HumanCultivation;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.InspectGathering;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 冒烟的短时限输入按整合服回执生成，客户端卡帧不能延后松键或续租。 */
public final class HumanUiSmokeInputs {
    private static final long MILLIS_PER_TICK = 50;
    private static final int BREATH_RENEW_TICKS = 5;
    private static final int START_LIMIT_TICKS = 200;
    private static final double PUSH_RELEASE_THRUST = 1;
    private enum Mode { BREATHING, THRUST }
    private record Target(MinecraftServer server, UUID player, Mode mode) {}
    private volatile Target target;
    private volatile RuntimeException failure;
    private volatile long releasedPushes;
    private int ticks;
    private String breathingSession;

    public HumanUiSmokeInputs() {
        // 读 Human 已结算的本 tick 状态，下一 tick 推进前把明确输入交给正式接收器。
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
    }

    public void keepBreathing(MinecraftServer server, UUID player) {
        // 开始仍由真实按钮发起；这里只续已有会话，不能把中断伪装成重新开始。
        target = new Target(server, player, Mode.BREATHING);
    }

    public void controlThrust(MinecraftServer server, UUID player) {
        // 起推仍走实际按键，松键读取当前服务端状态，避免迟到的客户端快照把推力推过头。
        target = new Target(server, player, Mode.THRUST);
    }

    public long releasedPushes() {
        // 客户端收到松键回执后才释放本地按键状态，不能在起推请求尚未到达时提前松开。
        return releasedPushes;
    }

    public void stop() {
        // 后续关窗和停止输入完全交还实际窗口，回调不能续上已关闭的练习。
        check();
        Target current = target;
        if (current != null && current.mode() == Mode.THRUST) {
            require(releasedPushes > 0, "手动周天未确认真实推力起止输入");
        }
        target = null;
    }

    public void check() {
        // 服务端记录第一处失败，交给客户端冒烟抛出，不能丢在服务器日志里继续验收。
        RuntimeException recorded = failure;
        if (recorded != null) {
            throw recorded;
        }
    }

    private void tick(MinecraftServer server) {
        // 只处理显式绑定的整合服玩家；保存重启后的其他服务器不继承输入。
        Target current = target;
        if (current == null || current.server() != server || failure != null) {
            return;
        }
        try {
            UUID playerId = current.player();
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            require(player != null, "冒烟输入对应的玩家已离线");
            HumanPlayerBody body = HumanTechniqueHost.state().find(playerId);
            require(body != null, "冒烟输入对应的权威身体不存在");
            ticks++;
            switch (current.mode()) {
                case BREATHING -> renewBreathing(player, body);
                case THRUST -> controlThrust(player, body);
            }
        } catch (RuntimeException invalid) {
            failure = invalid;
            target = null;
        }
    }

    private void renewBreathing(ServerPlayer player, HumanPlayerBody body) {
        // 与正常心跳相同频率，序号来自当前权威会话；服务端追赶 tick 时也逐边界续期。
        if (ticks % BREATH_RENEW_TICKS != 1) {
            return;
        }
        long millis = player.level().getServer().overworld().getGameTime() * MILLIS_PER_TICK;
        var inspect = gathering(body)
                .setInspect(InspectGathering.getDefaultInstance())
                .build();
        var response = body.gather(millis, inspect);
        require(response.hasView(), "吐纳权威查询失败：" + response);
        var breath = response.getView().getBreathing();
        boolean active = breath.getActive();
        if (breathingSession == null && !active) {
            require(ticks < START_LIMIT_TICKS, "实际按钮未能开始吐纳：" + breath.getStatus());
            return;
        }
        require(active && !breath.getAutomatic(), "改变分辨率中断了手动吐纳：" + breath.getStatus());
        String sessionId = breath.getSessionId();
        if (breathingSession == null) {
            breathingSession = sessionId;
            System.out.println("RESPONSIVE_SERVER_SESSION " + breathingSession);
        }
        require(sessionId.equals(breathingSession), "改变分辨率重建了权威吐纳会话");
        var control = BreathControl.newBuilder()
                .setAction(BreathAction.HOLD)
                .setSequence(breath.getSequence() + 1)
                .build();
        var request = gathering(body)
                .setBreath(control)
                .build();
        player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
                MeridianChannels.GATHERING_REQUEST.payload(request.toByteArray())));
    }

    private static GatheringRequest.Builder gathering(HumanPlayerBody body) {
        // 只构造当前身体的正常聚气协议请求，不修改资源、租期或服务端时间。
        return GatheringRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyId(body.snapshot().id().value());
    }

    private void controlThrust(ServerPlayer player, HumanPlayerBody body) {
        // 每次推进都保留渗漏断言，后续冲关反馈不能覆盖已经发生的损伤。
        var state = HumanCultivation.state(body.snapshot());
        String reason = state.getReason();
        require(!reason.contains("渗漏"), "真实推力发生非预期损伤：" + reason);
        require(state.getRunning() && !state.getAutomatic(), "手动周天意外停止：" + reason);
        double thrust = state.getThrust();
        if (!state.getPushing()) {
            return;
        }
        if (thrust < PUSH_RELEASE_THRUST) {
            return;
        }
        var input = CultivationInput.newBuilder()
                .setSequence(state.getLastSequence() + 1)
                .setOperation(CultivationInput.Operation.PUSH_OFF)
                .setKeyTick(player.level().getServer().overworld().getGameTime())
                .build();
        player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
                HumanChannels.CULTIVATION.payload(input.toByteArray())));
        var receipt = HumanCultivation.state(body.snapshot());
        require(receipt.getLastSequence() == input.getSequence() && !receipt.getPushing(), "服务端未确认推力松键输入");
        releasedPushes++;
    }

    private static void require(boolean condition, String message) {
        // 任一权威状态不符都终止，不通过重开、回填或清除失败来继续。
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
