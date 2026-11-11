# Ship and train overview indicators

Ship and train indicators follow the player overview thresholds: enter overview at camera distance 220, hide at 170 or closer, and retain the previous state in between to avoid flicker. Hiding an indicator closes its popup. Dimension/replay visibility still applies and the 3D models remain untouched.

Create carriage IDs identify trains, so windmills and ordinary contraptions never receive train indicators. Carriages of the same train share one indicator per dimension, anchored to the lowest available carriage index. Removal, unloaded geometry, leading-carriage changes and dimension transitions retain the remaining indicator. Train names are published through the existing optional SceneObject label field, without another scan or endpoint; old unlabeled feeds use “Train”. A distinct train SVG differentiates it from ship gears.

Initial validation: named-marker tests cover existing ships and safe names, zoom hysteresis/popup closure, train deduplication, anchor transfer, rename, dimension transitions and final removal. The complete generated-runtime/Java build and desktop/mobile browser checks follow the initial draft.

Stacked on the scene-transform draft to preserve its performance fixes in the built artifact.
