package dev.duzo.bluemapcompat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompatRuleSetTest {

    @Test
    void globMatchingPreservesCapturesPropertiesAndExclusions() {
        var rule = new CompatRuleSet.Rule();
        rule.id = "leaves";
        rule.match = new CompatRuleSet.Match();
        rule.match.blocks = List.of("quark:*_leaves");
        rule.match.exclude = List.of("quark:ancient_*");
        rule.match.properties = Map.of("persistent", "false");
        rule.compile();

        assertTrue(rule.matches("quark:orange_leaves", Map.of("persistent", "false")));
        assertEquals(
                List.of("orange"),
                rule.captures("quark:orange_leaves", Map.of("persistent", "false")));
        assertFalse(rule.matches("quark:orange_leaves", Map.of("persistent", "true")));
        assertFalse(rule.matches("quark:ancient_oak_leaves", Map.of("persistent", "false")));
    }

    @Test
    void aliasTemplatesResolveWildcardCaptures() {
        var rule = new CompatRuleSet.Rule();
        rule.id = "wall-alias";
        rule.scope = List.of("terrain");
        rule.match = new CompatRuleSet.Match();
        rule.match.blocks = List.of("example:*_wall");
        rule.model = new CompatRuleSet.Model();
        rule.model.type = "alias";
        rule.model.sourceBlock = "minecraft:${1}_wall";
        rule.compile();

        var captures = rule.captures("example:diorite_wall", Map.of());
        assertEquals(List.of("diorite"), captures);
        assertEquals(
                "minecraft:diorite_wall",
                rule.model.resolveSourceBlock("example:diorite_wall", captures));
        assertTrue(rule.appliesTo("terrain"));
        assertFalse(rule.appliesTo("moving"));
    }

    @Test
    void invalidTemplateCaptureFailsAtCompileTime() {
        var rule = new CompatRuleSet.Rule();
        rule.id = "invalid";
        rule.match = new CompatRuleSet.Match();
        rule.match.blocks = List.of("example:*");
        rule.model = new CompatRuleSet.Model();
        rule.model.type = "alias";
        rule.model.sourceBlock = "minecraft:${2}";

        var error = assertThrows(IllegalArgumentException.class, rule::compile);
        assertTrue(error.getMessage().contains("capture ${2}"));
    }

    @Test
    void templateExpansionKeepsNamespacePathAndCapturesStable() {
        assertEquals(
                "copy/block:block/a/b/leaf",
                CompatRuleSet.expandTemplate(
                        "${namespace}/${path0}:${path}/${1}",
                        "copy:block/a/b",
                        List.of("leaf")));
    }
}
