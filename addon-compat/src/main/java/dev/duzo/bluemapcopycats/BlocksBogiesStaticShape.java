package dev.duzo.bluemapcopycats;

import java.util.regex.Pattern;

/** Parses all known 1.21.1 Blocks & Bogies block names without guessing styles. */
final class BlocksBogiesStaticShape {
    private static final Pattern ID = Pattern.compile(
            "^create_bb:(xl|l|s)_0(20|40|60|80|100|120)(?:_[a-z0-9_]+)?$");

    private BlocksBogiesStaticShape() {}

    static Spec parse(String id) {
        if (id == null) return null;
        var match = ID.matcher(id);
        if (!match.matches()) return null;
        String size=match.group(1);
        int axles=Integer.parseInt(match.group(2))/20;
        // B&B currently limits extra-large bogies to five axle pairs.
        if (axles < 1 || axles > 6 || ("xl".equals(size) && axles > 5)) {
            return null;
        }
        return new Spec(size, axles);
    }

    record Spec(String size, int axles) {
        boolean small() {return "s".equals(size);}
        boolean extraLarge() {return "xl".equals(size);}
    }
}
