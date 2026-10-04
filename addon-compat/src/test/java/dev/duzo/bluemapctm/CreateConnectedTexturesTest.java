package dev.duzo.bluemapctm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class CreateConnectedTexturesTest {

    @Test
    void createDecoConnectedSheetsExposeTheirActualCreateCtTypes() {
        assertEquals(
                "vertical",
                CreateConnectedTextures.typeForSheet(
                        "createdeco:block/palettes/sheet_metal/zinc_sheet_metal_connected"));
        assertEquals(
                "omnidirectional",
                CreateConnectedTextures.typeForSheet(
                        "createdeco:block/palettes/catwalks/zinc_catwalk_connected"));
        assertEquals(
                "vertical",
                CreateConnectedTextures.typeForSheet(
                        "createdeco:block/palettes/windows/zinc_window_connected"));
        assertEquals(
                "rectangle",
                CreateConnectedTextures.typeForSheet(
                        "createdeco:block/palettes/shipping_containers/blue/vault_side_medium"));
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
