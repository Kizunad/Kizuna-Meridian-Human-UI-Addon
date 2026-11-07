package dev.kizuna.meridian.human.ui;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.human.fabric.HumanChannels;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationInput.Operation;
import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** 同一连接中的周天和外壁窗口共用输入序号，资源仍由服务端结算。 */
final class CultivationActions {
    private static final long MILLIS_PER_TICK = 50;
    private static long sequence;

    private CultivationActions() {
        // 操作入口不保存身体余额或运行模式。
    }

    static CultivationState state(BodySnapshot body) {
        // 两个窗口解析同一附属进度，尚未修炼的身体使用协议初始值。
        var progress = body.getAddonProgressMap().get("human:cultivation");
        if (progress == null) {
            return CultivationState.getDefaultInstance();
        }
        try {
            return progress.unpack(CultivationState.class);
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalStateException(HumanUiErrors.INVALID_CULTIVATION, invalid);
        }
    }

    static void send(BodySnapshot body, Operation operation) {
        // 连续跨窗输入也递增，不能各自从同一个权威序号发出重复请求。
        sequence = Math.max(sequence, state(body).getLastSequence()) + 1;
        var request = CultivationInput.newBuilder()
                .setSequence(sequence)
                .setOperation(operation)
                .setKeyTick(body.getSettledAt().getElapsedMillis() / MILLIS_PER_TICK)
                .build();
        ClientPlayNetworking.send(HumanChannels.CULTIVATION.payload(request.toByteArray()));
    }

    static void disconnect() {
        // 新连接从自己的权威序号开始，不能继承上一台服务器的输入状态。
        sequence = 0;
    }
}
