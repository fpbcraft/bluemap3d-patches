package dev.duzo.bluemapctm;

import de.bluecolored.bluemap.core.util.Direction;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class CreateConnectedTexturesTest {

    @Test
    void createDecoSheetMetalUsesVerticalCt() {
        assertEquals(
                "vertical",
                CreateConnectedTextures.typeForSheet(
                        "createdeco:block/palettes/sheet_metal/zinc_sheet_metal_connected"));

        CreateConnectedTextures.Spec spec = CreateConnectedTextures.find(
                "createdeco:block/palettes/sheet_metal/zinc_sheet_metal",
                "createdeco:zinc_sheet_metal",
                Map.of("axis", "y"),
                Direction.NORTH);
        assertEquals("vertical", spec.type());
        assertEquals(
                "createdeco:block/palettes/sheet_metal/zinc_sheet_metal_connected",
                spec.sheetTexture());
    }

    @Test
    void unambiguousEightByEightAddonSheetsAreDiscoveredGenerically() {
        BufferedImage base = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        BufferedImage connected = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        assertEquals(
                "omnidirectional",
                ConnectedTextureResourceExtension.inferCreateType(
                        "aeronautics:block/levitite", base, connected));
    }

    @Test
    void ambiguousTwoByTwoSheetsRequireKnownFamily() {
        BufferedImage base = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        BufferedImage connected = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        assertEquals(
                "vertical",
                ConnectedTextureResourceExtension.inferCreateType(
                        "createdeco:block/palettes/sheet_metal/zinc_sheet_metal",
                        base,
                        connected));
        assertNull(
                ConnectedTextureResourceExtension.inferCreateType(
                        "example:block/mystery", base, connected));
    }

    @Test
    void vaultSheetsUseCreateDirectMediumAndLargeNames() {
        assertEquals(
                "rectangle",
                CreateConnectedTextures.typeForSheet(
                        "create:block/vault/vault_top_medium"));
        assertEquals(
                "rectangle",
                CreateConnectedTextures.typeForSheet(
                        "create:block/vault/vault_side_large"));

        // Vault is the deliberate exception to Create's usual *_connected target naming.
        assertNull(CreateConnectedTextures.typeForSheet(
                "create:block/vault/vault_top_medium_connected"));
    }

    @Test
    void standardCreateSheetsStillUseConnectedSuffix() {
        assertEquals(
                "omnidirectional",
                CreateConnectedTextures.typeForSheet(
                        "create:block/andesite_casing_connected"));
        assertEquals(
                "horizontal_kryppers",
                CreateConnectedTextures.typeForSheet(
                        "create:block/palettes/stone_types/layered/tuff_cut_layered_connected"));
    }
}
