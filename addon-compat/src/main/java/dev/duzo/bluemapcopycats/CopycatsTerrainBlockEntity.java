package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity;
import de.bluecolored.bluenbt.NBTName;
import de.bluecolored.bluemap.core.world.BlockEntity;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * BlueMap-side DTO for Copycats+/Create: Connected copycat block entities.
 *
 * <p>BlueNBT decodes nested compounds into Map-like objects when retained as Object, which
 * lets this compatibility layer support both Create's single Material compound and
 * Copycats+' material_data map without linking to either mod's implementation classes.
 */
public final class CopycatsTerrainBlockEntity extends MCABlockEntity {

    @NBTName("Material")
    private Object material;

    @NBTName("material_data")
    private Object materialData;

    public CopycatsTerrainBlockEntity() {
    }

    /**
     * BlueMap's chunk deserializer may have already selected CreateEntityAddon's
     * copycat DTO before our own type registration is visible. Adapt it rather than
     * dropping the entire block: both contain the same underlying Material compound.
     * Other unrelated block entities are never adapted.
     */
    public static CopycatsTerrainBlockEntity from(BlockEntity raw) {
        if (raw instanceof CopycatsTerrainBlockEntity own) return own;
        if (raw == null || !raw.getClass().getName().equals(
                "eu.cronmoth.createentityaddon.rendering.copycats.entitymodel.CopycatBlockEntity")) {
            return null;
        }
        try {
            Object material = raw.getClass().getMethod("getMaterial").invoke(raw);
            if (material == null) return null;
            Method getName = material.getClass().getMethod("getName");
            Object name = getName.invoke(material);
            if (!(name instanceof String id) || id.isBlank()) return null;
            Object properties = material.getClass().getMethod("getProperties").invoke(material);

            CopycatsTerrainBlockEntity adapted = new CopycatsTerrainBlockEntity();
            adapted.material = Map.of(
                    "Name", id,
                    "Properties", properties instanceof Map<?, ?> ? properties : Map.of());
            // The foreign DTO does not retain Copycats+ material_data. This salvages
            // single-material blocks, while the full decoder remains authoritative
            // for multi-material shapes.
            return adapted;
        } catch (ReflectiveOperationException | RuntimeException unsupported) {
            return null;
        }
    }

    public Object material() {
        return material;
    }

    public Object materialData() {
        return materialData;
    }
}
