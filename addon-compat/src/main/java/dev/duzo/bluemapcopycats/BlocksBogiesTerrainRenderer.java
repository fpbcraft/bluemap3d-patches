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
 * Stationary-only server-side fallback for Blocks & Bogies.
 *
 * Their 1.21.1 JSON blockstates contain only the top mount; frames and wheels
 * are OBJ partials drawn by a client-only BogeyBlockEntityRenderer. BlueMap
 * does not load that renderer or NeoForge's OBJ model loader. Render a
 * recognisable frame, axle rods and axle-accurate wheel sets using actual
 * Blocks & Bogies textures, without affecting moving train meshes.
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
