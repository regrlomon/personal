package org.example.agent.tool.skill;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoadSkillToolTest {

    private static SkillRegistry registryWith(String name, String description, String body) {
        return new SkillRegistry(Map.of(name,
                new SkillDocument(new SkillManifest(name, description), body)));
    }

    @Test
    void returns_skill_body_wrapped_in_xml_tag() {
        var tool = new LoadSkillTool(registryWith("foo", "Foo skill", "foo body content"));
        var result = tool.execute(Map.of("name", "foo"), null);
        assertTrue(result.ok());
        assertTrue(result.content().contains("<skill name=\"foo\">"), "should have opening tag");
        assertTrue(result.content().contains("foo body content"), "should include body");
        assertTrue(result.content().contains("</skill>"), "should have closing tag");
    }

    @Test
    void returns_error_for_unknown_skill() {
        var tool = new LoadSkillTool(SkillRegistry.empty());
        var result = tool.execute(Map.of("name", "unknown"), null);
        assertFalse(result.ok());
        assertTrue(result.isError());
        assertTrue(result.content().contains("unknown"));
    }

    @Test
    void returns_error_for_blank_name() {
        var tool = new LoadSkillTool(SkillRegistry.empty());
        var result = tool.execute(Map.of("name", "  "), null);
        assertFalse(result.ok());
    }

    @Test
    void returns_error_when_name_key_missing() {
        var tool = new LoadSkillTool(SkillRegistry.empty());
        var result = tool.execute(Map.of(), null);
        assertFalse(result.ok());
    }

    @Test
    void definition_name_is_load_skill() {
        assertEquals("load_skill", new LoadSkillTool(SkillRegistry.empty()).definition().name());
    }
}
