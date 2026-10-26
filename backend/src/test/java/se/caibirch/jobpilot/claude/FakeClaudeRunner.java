package se.caibirch.jobpilot.claude;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/** Returns fixture JSON per task name and never starts a process. Used by every test above the runner. */
public class FakeClaudeRunner implements ClaudeRunner {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> responses = new HashMap<>();
    private final List<ClaudeTask<?>> calls = new ArrayList<>();

    public FakeClaudeRunner respond(String taskName, String json) {
        responses.put(taskName, json);
        return this;
    }

    public List<ClaudeTask<?>> calls() {
        return calls;
    }

    @Override
    public <T> T run(ClaudeTask<T> task) {
        calls.add(task);
        String json = responses.get(task.name());
        if (json == null) {
            throw new ClaudeCallException("FakeClaudeRunner has no response for task " + task.name());
        }
        try {
            JsonNode node = mapper.readTree(json);
            Set<ValidationMessage> problems = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(task.schemaJson()).validate(node);
            if (!problems.isEmpty()) {
                throw new ClaudeCallException("Fixture for " + task.name() + " violates its schema: " + problems);
            }
            return mapper.treeToValue(node, task.resultType());
        } catch (ClaudeCallException e) {
            throw e;
        } catch (Exception e) {
            throw new ClaudeCallException("Bad fixture for " + task.name(), null, e);
        }
    }
}
