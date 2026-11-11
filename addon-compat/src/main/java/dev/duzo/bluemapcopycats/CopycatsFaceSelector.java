package dev.duzo.bluemapcopycats;

import java.util.List;
import java.util.Map;

/** Pure face-selection policy shared by Copycats appearance resolution tests and runtime. */
final class CopycatsFaceSelector {

    private CopycatsFaceSelector() {
    }

    static <K, T> T selectPreferredFace(
            List<? extends Map<K, T>> faceSets,
            K preferredFace,
            Iterable<K> fallbackOrder) {
        for (Map<K, T> faces : faceSets) {
            T face = faces.get(preferredFace);
            if (face != null) return face;
        }

        for (Map<K, T> faces : faceSets) {
            for (K direction : fallbackOrder) {
                T face = faces.get(direction);
                if (face != null) return face;
            }
        }

        return null;
    }
}
