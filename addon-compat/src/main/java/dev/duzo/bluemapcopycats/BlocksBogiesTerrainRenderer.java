package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModel;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.LightData;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stationary-only server-side fallback for Blocks &amp; Bogies.
 *
 * Their 1.21.1 JSON blockstates contain only the top mount; frames and wheels
 * are OBJ partials drawn by a client-only BogeyBlockEntityRenderer. BlueMap
 * does not load that renderer or NeoForge's OBJ model loader. Render a
 * recognisable frame, axle rods and axle-accurate wheel sets using actual
 * Blocks &amp; Bogies textures, without affecting moving train meshes.
 */
public final class BlocksBogiesTerrainRenderer implements BlockRenderer {
    private static final Set<String> TRACED = ConcurrentHashMap.newKeySet();
    private final ResourcePack pack;
    private final TextureGallery gallery;
    private TileModelView view;
    private LightData light;

    public BlocksBogiesTerrainRenderer(
            ResourcePack pack, TextureGallery gallery, RenderSettings settings) {
        this.pack = pack;
        this.gallery = gallery;
    }

    @Override
    public void render(BlockNeighborhood block, Variant ignored,
            TileModelView tile, Color blockColor) {
        var spec = BlocksBogiesStaticShape.parse(block.getBlockState().getFormatted());
        if (spec == null) return;
        this.view = tile;
        this.light = block.getLightData();
        int first = tile.getTileModel().size();

        // Prefer the actual static OBJ geometry shipped inside the installed
        // Blocks & Bogies JAR, including the intended frame and wheel profile.
        // Only fall back to a procedural approximation if those files are absent.
        if (renderOriginalObj(block, spec)) {
            tile.initialize(first);
            if ("x".equals(block.getBlockState().getProperties().get("axis"))) {
                tile.translate(-.5f, 0f, -.5f)
                        .rotate(90,0,1,0)
                        .translate(.5f, 0f, .5f);
            }
            blockColor.set(1f,1f,1f,1f,true);
            if (TRACED.add(block.getBlockState().getFormatted())) {
                Logger.global.logInfo("STATIC BOGIE block="
                        + block.getBlockState().getFormatted()
                        + " geometry=mod-obj"
                        + " triangles=" + (tile.getTileModel().size()-first));
            }
            return;
        }

        int frame = texture(spec.small() ? "create_bb:block/bogie/small_frame"
                : "create_bb:block/bogie/frame2",
                "create_bb:block/bogie/frame", "create:block/bogey/top");
        int wheel = texture(spec.extraLarge() ? "create_bb:block/bogie/32x32"
                : "create_bb:block/bogie/wheel",
                "create_bb:block/bogie/wheel", "create:block/bogey/wheel");
        int metal = texture("create_bb:block/bogie/extras",
                "create:block/axis", "minecraft:block/iron_block");

        double extent = Math.max(0.6, (spec.axles()-1)*0.41+0.5);
        double radius = spec.small() ? .29 : spec.extraLarge() ? .52 : .405;
        // z is longitudinal before orienting to the block's axis. Frames and
        // wheels can extend beyond one block, like their original OBJ partials.
        cube(-.56, .60, -extent, .56, .83, extent, frame);
        cube(-.68, .35, -extent, -.48, .65, extent, frame);
        cube(.48, .35, -extent, .68, .65, extent, frame);
        cube(-.62, .76, -Math.min(.42, extent), .62, 1.0,
                Math.min(.42, extent), frame);
        for(int i=0;i<spec.axles();i++){
            double z=(i-(spec.axles()-1)/2.0)*.82;
            cube(-.74,.43,z-.09,.74,.54,z+.09,metal);
            wheel(-.70, .49, z, radius, .13, wheel);
            wheel(.70, .49, z, radius, .13, wheel);
        }

        tile.initialize(first);
        if ("x".equals(block.getBlockState().getProperties().get("axis"))) {
            // TileModelView rotates around (0,0,0), not the block midpoint.
            // Preserve the bogey's center when turning its wheels across the track.
            tile.translate(-.5f, 0f, -.5f)
                    .rotate(90,0,1,0)
                    .translate(.5f, 0f, .5f);
        }
        blockColor.set(1f,1f,1f,1f,true);
        if (TRACED.add(block.getBlockState().getFormatted())) {
            Logger.global.logInfo("STATIC BOGIE block="
                    + block.getBlockState().getFormatted()
                    + " axles=" + spec.axles() + " size=" + spec.size()
                    + " geometry=frame-and-wheels"
                    + " triangles=" + (tile.getTileModel().size()-first));
        }
    }

    private boolean renderOriginalObj(
            BlockNeighborhood block, BlocksBogiesStaticShape.Spec spec) {
        String raw = block.getBlockState().getFormatted();
        String suffix = raw.substring(raw.indexOf(':')+1);
        String modelRoot;
        if (spec.small()) {
            String prefix = suffix.endsWith("_trailing") ? "t"
                    : suffix.endsWith("_offset") ? "s" : "s";
            String n = Integer.toString(spec.axles()*2);
            modelRoot = "bogie/small/" + prefix + n
                    + (suffix.endsWith("_offset") ? "e" : "");
        } else {
            String n = Integer.toString(spec.axles()*2);
            modelRoot = "bogie/" + (spec.extraLarge() ? "extra_large/xl" : "large/l")
                    + n + "p";
        }
        var frame = BlocksBogiesObjMesh.load(modelRoot + "/frame");
        if (frame.isEmpty()) return false;

        // Load every mesh before emitting anything, so a missing wheel asset
        // does not leave a half-finished OBJ mixed with fallback cylinders.
        String size = spec.small() ? "small" : spec.extraLarge() ? "extra_large" : "large";
        var wheels = BlocksBogiesObjMesh.load("bogie/" + size + "/shared/wheels");
        if (wheels.isEmpty()) return false;

        emitObj(frame, 0, 0, 0);

        for (int i=0; i<spec.axles();i++) {
            float z=(float)(i-(spec.axles()-1)/2.0);
            // Corresponds to CachedBuffers.partial(...wheels).translate(0,.75,j)
            // in Blocks & Bogies' actual client renderers.
            emitObj(wheels, 0, .75f, z);
        }

        for (String rod : new String[]{"left_c_rod", "right_c_rod", "belts"}) {
            var partial = BlocksBogiesObjMesh.load(modelRoot + "/" + rod);
            if (!partial.isEmpty()) emitObj(partial, 0, 0, 0);
        }
        return true;
    }

    private void emitObj(
            java.util.List<BlocksBogiesObjMesh.Triangle> quads,
            float dx,float dy,float dz) {
        java.util.Map<String,Integer> materials = new java.util.HashMap<>();
        for(var quad:quads) {
            String material=quad.material();
            if ("none".equals(material)) continue;
            int index=materials.computeIfAbsent(material,
                    name -> textureFromMaterial(name));
            var a=quad.a();var b=quad.b();var c=quad.c();
            triangle(
                    new Vec(a.xyz()[0]+dx,a.xyz()[1]+dy,a.xyz()[2]+dz),
                    new Vec(b.xyz()[0]+dx,b.xyz()[1]+dy,b.xyz()[2]+dz),
                    new Vec(c.xyz()[0]+dx,c.xyz()[1]+dy,c.xyz()[2]+dz),
                    index,
                    a.uv()[0],a.uv()[1],b.uv()[0],b.uv()[1],c.uv()[0],c.uv()[1]);
        }
    }

    private int textureFromMaterial(String material) {
        return switch(material) {
            case "create_rods" -> texture("create_bb:block/bogie/create_rods");
            case "belts" -> texture("create:block/bogey/belt");
            case "wheels", "wheel_small" -> texture("create_bb:block/bogie/wheel");
            case "wheels_single" -> texture("create_bb:block/bogie/single");
            case "wheels_xl" -> texture("create_bb:block/bogie/32x32");
            case "wheels_xl_single" -> texture("create_bb:block/bogie/32x32_single");
            case "frame", "frame2", "frame4", "small_frame", "piston",
                    "extras", "support", "single_axle_body", "broad_piston",
                    "trailing_bogey_base", "small_trailing_bogey_outer",
                    "xl_textures" -> texture("create_bb:block/bogie/" + material);
            default -> texture("create_bb:block/bogie/" + material,
                    "minecraft:block/iron_block");
        };
    }

    private int texture(String... ids) {
        for(String id:ids) {
            var path=new ResourcePath<Texture>(id);
            if(pack.getTextures().containsKey(path)){
                int index=gallery.get(path);
                if(index>0) return index;
            }
        }
        return gallery.get(new ResourcePath<Texture>("minecraft:block/iron_block"));
    }

    private void cube(double x0,double y0,double z0,double x1,double y1,double z1,int texture) {
        Vec a=new Vec(x0,y0,z0),b=new Vec(x1,y0,z0);
        Vec c=new Vec(x1,y1,z0),d=new Vec(x0,y1,z0);
        Vec e=new Vec(x0,y0,z1),f=new Vec(x1,y0,z1);
        Vec g=new Vec(x1,y1,z1),h=new Vec(x0,y1,z1);
        face(a,d,c,b,texture);face(f,g,h,e,texture);
        face(e,h,d,a,texture);face(b,c,g,f,texture);
        face(d,h,g,c,texture);face(e,a,b,f,texture);
    }

    private void wheel(double x,double y,double z,double radius,double thickness,int texture) {
        int sides=12;
        double x0=x-thickness/2, x1=x+thickness/2;
        Vec centerLeft=new Vec(x0,y,z),centerRight=new Vec(x1,y,z);
        for(int i=0;i<sides;i++){
            double a=2*Math.PI*i/sides, b=2*Math.PI*(i+1)/sides;
            Vec l0=new Vec(x0,y+radius*Math.cos(a),z+radius*Math.sin(a));
            Vec l1=new Vec(x0,y+radius*Math.cos(b),z+radius*Math.sin(b));
            Vec r0=new Vec(x1,l0.y,l0.z),r1=new Vec(x1,l1.y,l1.z);
            triangle(centerLeft,l1,l0,texture, .5f,.5f,
                    .5f+(float)Math.sin(b)*.48f,.5f+(float)Math.cos(b)*.48f,
                    .5f+(float)Math.sin(a)*.48f,.5f+(float)Math.cos(a)*.48f);
            triangle(centerRight,r0,r1,texture,.5f,.5f,
                    .5f+(float)Math.sin(a)*.48f,.5f+(float)Math.cos(a)*.48f,
                    .5f+(float)Math.sin(b)*.48f,.5f+(float)Math.cos(b)*.48f);
            face(l0,l1,r1,r0,texture);
        }
    }

    private void face(Vec a,Vec b,Vec c,Vec d,int texture) {
        triangle(a,b,c,texture,0,1,1,1,1,0);
        triangle(a,c,d,texture,0,1,1,0,0,0);
    }

    private void triangle(Vec a,Vec b,Vec c,int texture,
            float u0,float v0,float u1,float v1,float u2,float v2) {
        view.initialize();
        view.add(1);
        TileModel model=view.getTileModel();
        int n=view.getStart();
        model.setPositions(n,(float)(a.x+.5),(float)a.y,(float)(a.z+.5),
                (float)(b.x+.5),(float)b.y,(float)(b.z+.5),
                (float)(c.x+.5),(float)c.y,(float)(c.z+.5));
        model.setUvs(n,u0,v0,u1,v1,u2,v2);
        model.setMaterialIndex(n,texture);
        model.setColor(n,1,1,1);
        model.setSunlight(n,light.getSkyLight());
        model.setBlocklight(n,light.getBlockLight());
        model.setAOs(n,1,1,1);
    }

    private record Vec(double x,double y,double z) {}
}
