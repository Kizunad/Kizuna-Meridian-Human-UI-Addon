package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.human.pb.CultivationMessages.CultivationState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Connection;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiTransit;
import dev.kizuna.meridian.ui.api.MeridianRouteProvider.Point;
import dev.kizuna.meridian.ui.client.BodyCanvas;
import dev.kizuna.meridian.ui.client.BodyScene;
import dev.kizuna.meridian.ui.client.EffectSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 同一身体快照中的周天展示数据；时钟插值只服务关口提示。 */
final class CultivationView {
    static final int GATE_COUNT = 4;
    static final int GREEN = 0xFF54EF82;
    static final int RED = 0xFFFF5858;
    static final int GOLD = 0xFFFFE48A;
    private static final int PUSH_GOLD = 0xFFFFF4C9;
    private static final double NORMAL_THRUST = 1;
    static final double THRUST_MAX = 2;
    static final double THRUST_WARNING = 1.2;
    static final double INITIAL_DENSITY = 0.01;
    static final double BREAKTHROUGH_SECONDS = 60;
    static final float TARGET_RADIUS = 14;
    private static final float MODEL_RING_MAX = 42;
    private static final float STATIC_RING_RADIUS = 20;
    private static final float TARGET_THICKNESS = 2;
    private static final float READY_THICKNESS = 4;
    private static final float INDICATOR_THICKNESS = 3;
    private static final double LATE_CONTRACTION = 0.5;
    private static final double MAX_PREDICTION_MILLIS = 250;
    private static final double NANOS_PER_MILLI = 1_000_000;
    private static final int PERCENT = 100;

    enum Gate { IDLE, TRAVELLING, INHALE, EARLY, READY, LATE, HIT, MISS, CLEARED }
    record Batch(double amount, double density, double progress) { }

    private final BodyScene scene;
    private final HumanMeridianRoutes routes = new HumanMeridianRoutes();
    private BodySnapshot body;
    private CultivationState state = CultivationState.getDefaultInstance();
    private Batch batch = new Batch(0, INITIAL_DENSITY, 0);
    private List<Connection> gates = List.of();
    private List<Point> gatePoints = List.of();
    private long receivedNanos;

    CultivationView(BodyScene scene) {
        // 人体坐标继续由现有路线接线提供。
        this.scene = scene;
    }

    void update(BodySnapshot snapshot) {
        // 原始状态与汇总在快照到达时一起更新，绘制不逐帧扫描身体账户。
        body = snapshot;
        state = CultivationActions.state(snapshot);
        QiTransit transit = snapshot.getTransitsList().stream()
                .filter(item -> item.getId().equals("human-middle-jiao"))
                .findFirst().orElse(null);
        batch = batch(transit);
        updateGates(transit);
        receivedNanos = System.nanoTime();
    }

    private void updateGates(QiTransit transit) {
        // 关口位置按真实闭环顺序缓存，四关标记不会凭显示进度假定成功。
        if (transit == null) {
            gates = List.of();
            gatePoints = List.of();
            return;
        }
        gates = transit.getCircuit().getConnectionIdsList().stream()
                .filter(id -> id.contains("large_intestine_meridian.to.") || id.contains("spleen_meridian.to."))
                .map(id -> body.getStructure().getConnectionsList().stream()
                        .filter(item -> item.getId().equals(id)).findFirst().orElseThrow())
                .toList();
        gatePoints = gates.stream().map(gate -> {
            var points = routes.waypoints(scene.presentation(), body.getStructure(), gate);
            return points.get(points.size() / 2);
        }).toList();
    }

    private static Batch batch(QiTransit transit) {
        // 周天只汇总在途批次，外壁账户由独立储备窗口读取。
        double amount = 0;
        double density = INITIAL_DENSITY;
        double progress = 0;
        if (transit != null) {
            QiInventory cargo = transit.getCargo();
            amount = cargo.getEntriesList().stream().mapToDouble(Qi::getAmount).sum();
            double volume = cargo.getEntriesList().stream().mapToDouble(qi -> qi.getAmount() / qi.getDensity()).sum();
            if (volume > 0) {
                density = amount / volume;
            }
            var circuit = transit.getCircuit();
            if (circuit.getDurationMillis() > 0) {
                progress = circuit.getWorkMillis() / circuit.getDurationMillis();
            }
        }
        return new Batch(amount, density, progress);
    }

    CultivationState state() {
        // 所有控件读同一份服务端周天状态。
        return state;
    }

    BodySnapshot body() {
        // 只用于输入携带最近所见身体 tick。
        return body;
    }

    Batch batch() {
        // 汇总只随身体版本变化，动画不能改写这些数值。
        return batch;
    }

    double clock() {
        // 不越过一个推送周期，服务端暂停时关口提示也会停止前进。
        double elapsed = (System.nanoTime() - receivedNanos) / NANOS_PER_MILLI;
        return body.getSettledAt().getElapsedMillis() + Math.min(MAX_PREDICTION_MILLIS, elapsed);
    }

    Gate gate() {
        // 命中与失败先读取服务端反馈；可按窗口仅作预测提示。
        if (!state.getRunning() || state.getAutomatic()) {
            return Gate.IDLE;
        }
        double now = clock();
        if (state.getFeedbackUntil() > now) {
            return state.getGateSuccess() ? Gate.HIT : Gate.MISS;
        }
        if (state.getGateIndex() >= GATE_COUNT) {
            return Gate.CLEARED;
        }
        if (state.getGateRetryUntil() == 0) {
            return Gate.TRAVELLING;
        }
        long cycle = state.getBreathCycleMillis();
        if (cycle > 0 && now % cycle < state.getExhaleStartMillis()) {
            return Gate.INHALE;
        }
        double offset = now - state.getGateRetryUntil();
        if (Math.abs(offset) <= state.getGateWindowMillis()) {
            return Gate.READY;
        }
        return offset < 0 ? Gate.EARLY : Gate.LATE;
    }

    double indicatorRadius(double maximum) {
        // 动态圈在权威目标时刻合拢；减少动态时保留可按颜色和静态轮廓。
        if (EffectSettings.reducedMotion()) {
            return STATIC_RING_RADIUS;
        }
        double remaining = state.getGateRetryUntil() - clock();
        if (remaining >= 0) {
            double retry = Math.max(1, state.getGateRetryMillis());
            return TARGET_RADIUS + Math.min(1, remaining / retry) * (maximum - TARGET_RADIUS);
        }
        double window = Math.max(1, state.getGateWindowMillis());
        return TARGET_RADIUS * (1 - LATE_CONTRACTION * Math.min(1, -remaining / window));
    }

    String gateName() {
        // 名称和顺序来自当前连接；尚无在途时不伪造下一关位置。
        int index = state.getGateIndex();
        if (index >= gates.size()) {
            return "关口";
        }
        String name = "中焦";
        if (gates.get(index).getId().contains("large_intestine_meridian.to.")) {
            name = "人中";
        }
        return name + " " + (index + 1) + "/" + gates.size();
    }

    String instruction() {
        // 操作时机用短句与几何共同表达，避免只依赖红绿颜色。
        return switch (gate()) {
            case IDLE -> "开始后运转周天";
            case TRAVELLING -> "真元在途";
            case INHALE -> "吸气 · 等待";
            case EARLY -> "等待合圈";
            case READY -> "空格  冲关";
            case LATE -> "等待下一拍";
            case HIT -> "已通过";
            case MISS -> "冲关未中";
            case CLEARED -> "本圈收束";
        };
    }

    String automaticNote() {
        // 自动代价常显，暂停、恢复和效率均使用服务端已经下发的参数。
        if (state.getSchemaVersion() == 0) {
            return "耗脑耐力，不耗精力\n耐力不足暂停\n效率低于手动";
        }
        return String.format(Locale.ROOT, "低于 %.0f%% 暂停\n恢复至 %.0f%% 继续\n效率 %.0f%% · 不耗精力",
                state.getAutomaticMinimumStaminaRatio() * PERCENT,
                state.getAutomaticResumeStaminaRatio() * PERCENT,
                state.getAutomaticEfficiency() * PERCENT);
    }

    void effects(BodyCanvas canvas) {
        // 圈的位置来自人体模型，转动和缩放时仍贴着当前关口。
        var markers = new ArrayList<BodyCanvas.Marker>();
        int index = state.getGateIndex();
        if (index < gatePoints.size() && !state.getAutomatic()) {
            Point point = gatePoints.get(index);
            Gate gate = gate();
            int color = GOLD;
            float thickness = TARGET_THICKNESS;
            if (gate == Gate.READY || gate == Gate.HIT) {
                color = GREEN;
                thickness = READY_THICKNESS;
            }
            markers.add(new BodyCanvas.Marker(point.x(), point.y(), point.z(), TARGET_RADIUS, thickness, color));
            if (gate == Gate.READY || gate == Gate.EARLY || gate == Gate.LATE) {
                int indicator = gate == Gate.READY ? GREEN : RED;
                markers.add(new BodyCanvas.Marker(point.x(), point.y(), point.z(),
                        (float) indicatorRadius(MODEL_RING_MAX), INDICATOR_THICKNESS, indicator));
            }
        }
        int color = GOLD;
        if (state.getThrust() > NORMAL_THRUST) {
            color = PUSH_GOLD;
        }
        if (state.getThrust() > THRUST_WARNING) {
            color = RED;
        }
        canvas.effects(markers, color);
    }
}
