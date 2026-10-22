package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.human.client.HumanPresentation;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Connection;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyPresentation;
import dev.kizuna.meridian.ui.api.MeridianRouteProvider.Point;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.*;

/** 整体回归：人体模型、权威连接与独立客户端路线必须一致。 */
public class HumanMeridianRoutesTest {
    @Test
    public void openingFollowsMeridianDirectionAndBindsMeditationBones() {
        // 八脉路线取真实模型，手阴足阳向下，手阳足阴向上；每点必须有可混合挂骨。
        var provider = new HumanOpeningEffects();
        var model = HumanPresentation.create();
        for (String side : List.of("left", "right")) {
            for (String type : List.of("lung", "large_intestine", "stomach", "spleen")) {
                String id = side + "_" + type + "_meridian";
                var visual = model.getVisualsList().stream().filter(item -> item.getTargetId().equals(id)).findFirst().orElseThrow();
                var path = provider.route(visual);
                assertTrue(id, path.size() >= 2);
                float rise = path.get(path.size() - 1).y() - path.get(0).y();
                boolean ascending = type.equals("large_intestine") || type.equals("spleen");
                assertTrue(id + " direction " + rise, ascending ? rise > .2 : rise < -.2);
                for (var point : path) {
                    assertFalse(point.bone().isBlank());
                    assertFalse(point.adjacentBone().isBlank());
                    assertTrue(point.blend() >= 0 && point.blend() <= 1);
                    assertTrue(Float.isFinite(point.x()) && Float.isFinite(point.y()) && Float.isFinite(point.z()));
                }
            }
        }
    }

    @Test
    public void routesMatchHumanTopologyAndCrossAtPhiltrum() {
        // 读取交付资源与真实内容，不复制另一份人体模型或拓扑。
        BodyPresentation model = HumanPresentation.create();
        var structure = HumanContent.bundle().getTemplates(0).getStructure();
        var provider = new HumanMeridianRoutes();
        Map<String, List<Point>> routes = structure.getConnectionsList().stream()
                .collect(Collectors.toMap(Connection::getId, connection -> provider.waypoints(model, structure, connection)));
        assertEquals(structure.getConnectionsCount(), routes.size());

        List<Point> left = routes.get("left_large_intestine_meridian.to.right_stomach_meridian");
        List<Point> right = routes.get("right_large_intestine_meridian.to.left_stomach_meridian");
        assertNotNull(left);
        assertNotNull(right);
        assertEquals(left.size(), right.size());
        for (int index = 0; index < left.size(); index++) {
            assertEquals(-left.get(index).x(), right.get(index).x(), 0);
            assertEquals(left.get(index).y(), right.get(index).y(), 0);
            assertEquals(left.get(index).z(), right.get(index).z(), 0);
        }
        assertTrue(left.stream().anyMatch(point -> point.x() == 0 && point.y() == 1.67f && point.z() == .25f));
        assertTrue(right.stream().anyMatch(point -> point.x() == 0 && point.y() == 1.67f && point.z() == .25f));
        for (String side : List.of("left", "right")) {
            List<Point> returning = routes.get(side + "_spleen_meridian.to." + side + "_lung_meridian");
            assertNotNull(returning);
            assertTrue(returning.get(0).y() < returning.get(returning.size() - 1).y());
        }

        // 所有坐标必须适合 Human 的真实模型包围盒，且交付结果不可被调用方改写。
        for (List<Point> points : routes.values()) {
            assertTrue(points.size() >= 2 && points.size() <= 256);
            assertThrows(UnsupportedOperationException.class, () -> points.add(new Point(0, 0, 0)));
            for (Point point : points) {
                assertTrue(Float.isFinite(point.x()) && Float.isFinite(point.y()) && Float.isFinite(point.z()));
                assertTrue(point.x() >= model.getMinimum().getX() && point.x() <= model.getMaximum().getX());
                assertTrue(point.y() >= model.getMinimum().getY() && point.y() <= model.getMaximum().getY());
                assertTrue(point.z() >= model.getMinimum().getZ() && point.z() <= model.getMaximum().getZ());
            }
        }
    }

    @Test
    public void invalidBindingsFailAndOldConnectionsKeepTheirActualEndpoints() {
        // 相同名称也不代表适用：模板、实际连接和端口必须同时匹配。
        BodyPresentation model = HumanPresentation.create();
        var structure = HumanContent.bundle().getTemplates(0).getStructure();
        var crossing = structure.getConnectionsList().stream().filter(connection -> connection.getId()
                .equals("left_large_intestine_meridian.to.right_stomach_meridian")).findFirst().orElseThrow();
        var provider = new HumanMeridianRoutes();
        assertThrows(IllegalArgumentException.class, () -> provider.waypoints(model.toBuilder()
                .setTemplate(model.getTemplate().toBuilder().setId("other:creature")).build(), structure, crossing));
        assertThrows(IllegalArgumentException.class,
                () -> provider.waypoints(model, structure.toBuilder().clearConnections().build(), crossing));

        Connection old = crossing.toBuilder().setId("left_large_intestine_meridian.to.left_stomach_meridian")
                .setTo("left_stomach_meridian.in").build();
        // 旧存档的同侧连接仍按自身端点展示，客户端坐标不能代替权威拓扑迁移。
        assertEquals(2, provider.waypoints(model, structure.toBuilder().addConnections(old).build(), old).size());
        Connection altered = crossing.toBuilder().setTo("left_stomach_meridian.in").build();
        assertThrows(IllegalArgumentException.class, () -> provider.waypoints(model,
                structure.toBuilder().clearConnections().addConnections(altered).build(), altered));
        Connection unknown = old.toBuilder().setFrom("missing.out").build();
        assertThrows(IllegalArgumentException.class, () -> provider.waypoints(model,
                structure.toBuilder().clearConnections().addConnections(unknown).build(), unknown));
    }
}
