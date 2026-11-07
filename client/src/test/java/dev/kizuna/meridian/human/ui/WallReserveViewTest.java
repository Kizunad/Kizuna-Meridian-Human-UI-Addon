package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyStructure;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 混合密度和圆环面积决定可见储量，不能以真元量或图片高度代替占用比例。 */
public class WallReserveViewTest {
    private static final double EPSILON = 0.00001;
    private static final double CAPACITY = 100;
    private static final double DENSE_AMOUNT = 2;
    private static final double DENSE_DENSITY = 0.1;
    private static final double LIGHT_AMOUNT = 0.3;
    private static final double LIGHT_DENSITY = 0.01;
    private static final double HALF = 0.5;
    private static final double QUARTER = 0.25;
    private static final int SAMPLE_SIZE = 800;
    private static final double RASTER_TOLERANCE = 0.003;

    @Test
    public void countsMixedDensityVolumeAndEmptyCapacity() {
        // 同样的真元量可能占不同体积：20 + 30 体积应当填满一半外壁。
        var inventory = QiInventory.newBuilder()
                .addEntries(qi(DENSE_AMOUNT, DENSE_DENSITY))
                .addEntries(qi(LIGHT_AMOUNT, LIGHT_DENSITY))
                .build();
        var meridian = MeridianDefinition.newBuilder().setWallVolume(CAPACITY).build();
        var structure = BodyStructure.newBuilder().addMeridians(meridian).build();
        var state = MeridianState.newBuilder().setWallQi(inventory).build();
        var body = BodySnapshot.newBuilder()
                .setStructure(structure)
                .addMeridianStates(state)
                .build();
        WallReserveView view = WallReserveView.from(body);
        assertEquals(DENSE_AMOUNT + LIGHT_AMOUNT, view.amount(), EPSILON);
        assertEquals(HALF, view.fraction(), EPSILON);
        assertEquals(0, WallReserveView.from(BodySnapshot.getDefaultInstance()).fraction(), EPSILON);
    }

    @Test
    public void visibleFatAreaMatchesCapacityIncludingExtremes() {
        // 用独立像素面积计数验证积分裁切，覆盖空壁、半壁和满壁边界。
        for (double fraction : new double[]{0, QUARTER, HALF, 1 - QUARTER, 1}) {
            double top = WallReserveFill.top(fraction);
            int area = 0;
            int filled = 0;
            for (int row = 0; row < SAMPLE_SIZE; row++) {
                for (int column = 0; column < SAMPLE_SIZE; column++) {
                    double y = (row + HALF) / SAMPLE_SIZE;
                    double x = (column + HALF) / SAMPLE_SIZE;
                    double radius = Math.hypot(x - HALF, y - HALF);
                    if (radius < WallReserveFill.INNER_RADIUS || radius > WallReserveFill.OUTER_RADIUS) {
                        continue;
                    }
                    area++;
                    if (y >= top) {
                        filled++;
                    }
                }
            }
            assertEquals(fraction, filled / (double) area, RASTER_TOLERANCE);
            assertTrue(top >= 0 && top <= 1);
        }
    }

    private static Qi qi(double amount, double density) {
        // 夹具只提供汇总所需的物理量，客户端不创建权威身体。
        return Qi.newBuilder()
                .setAmount(amount)
                .setDensity(density)
                .build();
    }
}
