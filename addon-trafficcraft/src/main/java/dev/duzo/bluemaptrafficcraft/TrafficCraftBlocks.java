package dev.duzo.bluemaptrafficcraft;

import java.util.ArrayList;
import java.util.List;

final class TrafficCraftBlocks {

    private TrafficCraftBlocks() {
    }

    static List<String> paintableIds() {
        List<String> ids = new ArrayList<>(1310);

        for (int i = 0; i < 323; i++) {
            ids.add("trafficcraft:asphalt_pattern_" + i);
            ids.add("trafficcraft:asphalt_slope_pattern_" + i);
            ids.add("trafficcraft:concrete_pattern_" + i);
            ids.add("trafficcraft:concrete_slope_pattern_" + i);
        }

        for (String path : List.of(
                "concrete_barrier",
                "street_sign",
                "house_number_sign",
                "traffic_light",
                "guardrail",
                "paint_bucket",
                "traffic_cone",
                "traffic_bollard",
                "traffic_barrel",
                "road_barrier_fence",
                "reflector")) {
            ids.add("trafficcraft:" + path);
        }

        return List.copyOf(ids);
    }
}
