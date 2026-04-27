package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelRequest;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.ToolRegistry;
import org.example.agent.tool.skill.SkillDocument;
import org.example.agent.tool.skill.SkillManifest;
import org.example.agent.tool.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineSkillTest {

    private static ModelResponse endTurn() {
        return new ModelResponse(List.of(new ContentBlock.Text("done")), StopReason.END_TURN, 0, 0);
    }

    private static QueryParams params(String systemPrompt) {
        return new QueryParams(List.of(Message.user("hi")), systemPrompt, null, null, null);
    }

    @Test
    void skill_directory_is_prepended_to_system_prompt() {
        var doc = new SkillDocument(new SkillManifest("foo", "Foo skill"), "foo body");
        var skillReg = new SkillRegistry(Map.of("foo", doc));
        var captured = new ArrayList<ModelRequest>();
        var engine = new QueryEngine(req -> { captured.add(req); return endTurn(); },
                new ToolRegistry(), skillReg);

        engine.run(params("base prompt"));

        assertEquals(1, captured.size());
        var sp = captured.get(0).systemPrompt();
        assertTrue(sp.contains("Skills available:"), "should contain skill section");
        assertTrue(sp.contains("foo: Foo skill"), "should list skill");
        assertTrue(sp.contains("base prompt"), "should retain base prompt");
    }

    @Test
    void null_system_prompt_produces_skill_section_only() {
        var doc = new SkillDocument(new SkillManifest("bar", "Bar skill"), "bar body");
        var skillReg = new SkillRegistry(Map.of("bar", doc));
        var captured = new ArrayList<ModelRequest>();
        var engine = new QueryEngine(req -> { captured.add(req); return endTurn(); },
                new ToolRegistry(), skillReg);

        engine.run(params(null));

        var sp = captured.get(0).systemPrompt();
        assertNotNull(sp);
        assertTrue(sp.contains("bar: Bar skill"));
        assertFalse(sp.contains("null"), "must not contain literal 'null'");
    }

    @Test
    void empty_registry_leaves_system_prompt_unchanged() {
        var captured = new ArrayList<ModelRequest>();
        var engine = new QueryEngine(req -> { captured.add(req); return endTurn(); },
                new ToolRegistry(), SkillRegistry.empty());

        engine.run(params("original prompt"));

        var sp = captured.get(0).systemPrompt();
        assertTrue(sp.contains("original prompt"), "base prompt must be present");
        assertTrue(sp.contains("=== Dynamic Context ==="), "dynamic section must be present");
    }

    @Test
    void no_skill_registry_leaves_system_prompt_unchanged() {
        var captured = new ArrayList<ModelRequest>();
        var engine = new QueryEngine(req -> { captured.add(req); return endTurn(); },
                new ToolRegistry());

        engine.run(params("original prompt"));

        var sp = captured.get(0).systemPrompt();
        assertTrue(sp.contains("original prompt"), "base prompt must be present");
        assertTrue(sp.contains("=== Dynamic Context ==="), "dynamic section must be present");
    }
}
