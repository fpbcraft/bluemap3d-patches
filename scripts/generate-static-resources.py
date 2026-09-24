from pathlib import Path
import json

root = Path("addon-compat/src/main/resources/assets")

copycats = [
    "wrapped_copycat",
    "copycat_block", "copycat_beam", "copycat_board",
    "copycat_wooden_button", "copycat_stone_button",
    "copycat_byte", "copycat_byte_panel",
    "copycat_fence", "copycat_fence_gate", "copycat_ghost_block",
    "copycat_half_layer", "copycat_vertical_half_layer", "copycat_stacked_half_layer",
    "copycat_half_panel", "copycat_ladder", "copycat_layer",
    "copycat_wooden_pressure_plate", "copycat_stone_pressure_plate",
    "copycat_heavy_weighted_pressure_plate", "copycat_light_weighted_pressure_plate",
    "copycat_slab", "copycat_slice", "copycat_corner_slice",
    "copycat_stairs", "copycat_vertical_stairs",
    "copycat_trapdoor", "copycat_iron_trapdoor",
    "copycat_vertical_slice", "copycat_vertical_step", "copycat_wall",
    "copycat_slope", "copycat_vertical_slope", "copycat_slope_layer",
    "copycat_shaft", "copycat_cogwheel", "copycat_large_cogwheel",
    "copycat_fluid_pipe", "copycat_glass_fluid_pipe",
    "copycat_door", "copycat_iron_door",
    "copycat_pane", "copycat_sliding_door", "copycat_folding_door",
    "copycat_flat_pane",
]

connected = [
    "copycat_slab", "copycat_block", "copycat_beam", "copycat_vertical_step",
    "copycat_stairs", "wrapped_copycat_stairs",
    "copycat_fence", "wrapped_copycat_fence",
    "copycat_wall", "wrapped_copycat_wall",
    "copycat_fence_gate", "wrapped_copycat_fence_gate",
    "copycat_board",
]

def write_dispatch(namespace, names, renderer):
    blockstates = root / namespace / "blockstates"
    blockstates.mkdir(parents=True, exist_ok=True)
    payload = {
        "variants": {
            "": {
                "renderer": renderer,
                "model": "bluemap_copycats:block/placeholder",
            }
        }
    }
    for name in names:
        (blockstates / f"{name}.json").write_text(json.dumps(payload, indent=2) + "\n")

    properties = {
        f"{namespace}:{name}": {
            "occluding": False,
            "culling": False,
            "cullingIdentical": False,
        }
        for name in names
    }
    (root / namespace / "blockProperties.json").write_text(
        json.dumps(properties, indent=2) + "\n"
    )

write_dispatch("copycats", copycats, "bluemap_copycats:terrain")
write_dispatch("create_connected", connected, "bluemap_copycats:terrain")
write_dispatch(
    "bits_n_bobs",
    ["girder_strut", "weathered_girder_strut", "cable_girder_strut"],
    "bluemap_copycats:bits_n_bobs_girder",
)