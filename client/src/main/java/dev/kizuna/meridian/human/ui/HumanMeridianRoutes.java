package dev.kizuna.meridian.human.ui;

import dev.kizuna.meridian.human.client.HumanPresentation;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyStructure;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Connection;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TemplateReference;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyPresentation;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyVisual;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.BodyTargetKind;
import dev.kizuna.meridian.protocol.pb.PresentationMessages.ModelPoint;
import dev.kizuna.meridian.ui.api.MeridianRouteProvider;
import java.util.List;

/** 人体中焦周天的模型路线；直接使用 Human 经脉 ID，不修改权威连接。 */
public final class HumanMeridianRoutes implements MeridianRouteProvider {
    private static final TemplateReference TEMPLATE = HumanPresentation.create().getTemplate();

    // 手阳明大肠经“交人中，左之右，右之左”；两条跨侧路线共经此点。
    private static final List<Point> LEFT_CROSSING = List.of(
            new Point(.31f, 1.40f, .06f), new Point(.18f, 1.48f, .10f),
            new Point(.10f, 1.58f, .18f), new Point(.04f, 1.66f, .24f),
            new Point(0, 1.67f, .25f), new Point(-.04f, 1.66f, .24f),
            new Point(-.10f, 1.58f, .18f), new Point(-.13f, 1.39f, .12f),
            new Point(-.14f, 1.12f, .10f));

    // 肺经“起于中焦”：由同侧腹部回流至胸部，左右镜像使用同一模型比例。
    private static final List<Point> LEFT_RETURN = List.of(
            new Point(.13f, 1.10f, .02f), new Point(.09f, 1.20f, .04f),
            new Point(.12f, 1.32f, .06f), new Point(.22f, 1.42f, .04f));

    private static final List<Route> ROUTES = List.of(
            new Route("left_large_intestine_meridian", "right_stomach_meridian", LEFT_CROSSING),
            new Route("right_large_intestine_meridian", "left_stomach_meridian", mirror(LEFT_CROSSING)),
            new Route("left_spleen_meridian", "left_lung_meridian", LEFT_RETURN),
            new Route("right_spleen_meridian", "right_lung_meridian", mirror(LEFT_RETURN)));

    @Override
    public List<Point> waypoints(BodyPresentation model, BodyStructure structure, Connection connection) {
        // 本包负责人体全部路线；模板和权威连接必须匹配，错误接线不能静默换路。
        if (!model.getTemplate().equals(TEMPLATE) || !structure.getConnectionsList().contains(connection)) {
            throw new IllegalArgumentException(HumanUiErrors.INVALID_CONNECTION + connection.getId());
        }
        for (Route route : ROUTES) {
            String connectionId = route.from() + ".to." + route.to();
            if (connection.getId().equals(connectionId)) {
                if (!connection.getFrom().equals(route.from() + ".out")
                        || !connection.getTo().equals(route.to() + ".in")) {
                    throw new IllegalArgumentException(HumanUiErrors.INVALID_CONNECTION + connection.getId());
                }
                return route.points();
            }
        }

        // 普通人体连接也由本包明确提供衔接点，UI 只负责沿两端模型网格延展光迹。
        BodyVisual source = owner(model, structure, connection.getFrom());
        BodyVisual target = owner(model, structure, connection.getTo());
        return List.of(center(source), center(target));
    }

    private static BodyVisual owner(BodyPresentation model, BodyStructure structure, String portId) {
        // 先按权威端口找账户，再关联 Human 模型；不能从经脉名称推测空间位置。
        String meridianId = structure.getMeridiansList().stream()
                .filter(meridian -> meridian.getPortsList().stream().anyMatch(port -> port.getId().equals(portId)))
                .map(meridian -> meridian.getId()).findFirst().orElse(null);
        BodyTargetKind kind = BodyTargetKind.MERIDIAN;
        String targetId = meridianId;
        if (targetId == null) {
            kind = BodyTargetKind.ORGAN;
            targetId = structure.getOrgansList().stream()
                    .filter(organ -> organ.getPortsList().stream().anyMatch(port -> port.getId().equals(portId)))
                    .map(organ -> organ.getId()).findFirst().orElseThrow(
                            () -> new IllegalArgumentException(HumanUiErrors.MISSING_ENDPOINT + portId));
        }

        BodyTargetKind targetKind = kind;
        String ownerId = targetId;
        return model.getVisualsList().stream()
                .filter(visual -> visual.getTargetKind() == targetKind && visual.getTargetId().equals(ownerId)
                        && visual.getVerticesCount() > 0)
                .findFirst().orElseThrow(() -> new IllegalArgumentException(HumanUiErrors.MISSING_ENDPOINT + portId));
    }

    private static Point center(BodyVisual visual) {
        // 人体普通连接以绑定网格中心作为衔接点，不另存第二份模型坐标。
        float x = 0;
        float y = 0;
        float z = 0;
        for (ModelPoint vertex : visual.getVerticesList()) {
            x += vertex.getX();
            y += vertex.getY();
            z += vertex.getZ();
        }
        return new Point(x / visual.getVerticesCount(), y / visual.getVerticesCount(), z / visual.getVerticesCount());
    }

    private static List<Point> mirror(List<Point> points) {
        // Human 的左右模型以 X=0 对称；镜像保持 from → to 的流向与人中交汇位置。
        return points.stream().map(point -> new Point(-point.x(), point.y(), point.z())).toList();
    }

    private record Route(String from, String to, List<Point> points) {}
}
