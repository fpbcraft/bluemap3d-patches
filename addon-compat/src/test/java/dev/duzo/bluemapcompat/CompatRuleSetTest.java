package dev.duzo.bluemapcompat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CompatRuleSetTest {

    @Test
    void globMatchingCapturesStarsButNotQuestionMarks() {
        var glob = new SharedCompatRules.Glob("example:?_*_branch");

        assertTrue(glob.matches("example:x_oak_branch"));
        assertEquals(List.of("oak"), glob.captures("example:x_oak_branch"));
        assertNull(glob.captures("example:oak_branch"));
        assertEquals(1, glob.captureCount());
    }

    @Test
    void ruleMatchingHonorsPropertiesAndExclusions() {
        var match = new SharedCompatRules.Match();
        match.blocks = List.of("example:*_fence");
        match.exclude = List.of("example:debug_*");
        match.properties = Map.of("waterlogged", "fals?");

        var rule = new SharedCompatRules.Rule();
        rule.id = "fences";
        rule.match = match;
        rule.compile();

        assertTrue(rule.matches("example:oak_fence", Map.of("waterlogged", "false")));
        assertFalse(rule.matches("example:oak_fence", Map.of("waterlogged", "true")));
        assertFalse(rule.matches("example:debug_fence", Map.of("waterlogged", "false")));
    }

    @Test
    void aliasTemplatesUseWildcardAndIdTokens() {
        var model = new SharedCompatRules.Model();
        model.type = "alias";
        model.sourceBlock = "replacement:${1}_${path0}";

        assertEquals(
                "replacement:oak_trees",
                model.resolveSourceBlock(
                        "example:trees/oak_branch",
                        List.of("oak")));
    }

    @Test
    void resourceAliasDefaultsTargetToMatchedBlockModel() {
        var model = new SharedCompatRules.Model();
        model.type = "resource_alias";
        model.sourceModel = "example:block/${1}_log";

        assertEquals(
                "trees:block/smart/oak_branch",
                model.resolveTargetModel("trees:smart/oak_branch", List.of("oak")));
        assertEquals(
                "example:block/oak_log",
                model.resolveSourceModel("trees:smart/oak_branch", List.of("oak")));
    }

    @Test
    void compileRejectsTemplatesThatReferenceMissingCaptures() {
        var match = new SharedCompatRules.Match();
        match.blocks = List.of("example:*_fence");

        var model = new SharedCompatRules.Model();
        model.type = "alias";
        model.sourceBlock = "example:${2}_fence";

        var rule = new SharedCompatRules.Rule();
        rule.id = "bad-capture";
        rule.match = match;
        rule.model = model;

        var error = assertThrows(IllegalArgumentException.class, rule::compile);
        assertTrue(error.getMessage().contains("capture ${2}"));
    }
}
