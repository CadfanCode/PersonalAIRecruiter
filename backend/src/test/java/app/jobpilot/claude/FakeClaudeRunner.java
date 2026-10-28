package app.jobpilot.claude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/** Returns fixture JSON per task name and never starts a process. Used by every test above the runner. */
public class FakeClaudeRunner implements ClaudeRunner {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Function<ClaudeTask<?>, String>> responses = new ConcurrentHashMap<>();
    private final List<ClaudeTask<?>> calls = Collections.synchronizedList(new ArrayList<>());

    public FakeClaudeRunner respond(String taskName, String json) {
        responses.put(taskName, t -> json);
        return this;
    }

    /** The answer can depend on the prompt, for example on which ad is being scored. */
    public FakeClaudeRunner respond(String taskName, Function<ClaudeTask<?>, String> answer) {
        responses.put(taskName, answer);
        return this;
    }

    public List<ClaudeTask<?>> calls() {
        return calls;
    }

    @Override
    public <T> T run(ClaudeTask<T> task) {
        calls.add(task);
        Function<ClaudeTask<?>, String> answer = responses.get(task.name());
        if (answer == null) {
            throw new ClaudeCallException("FakeClaudeRunner has no response for task " + task.name());
        }
        String json = answer.apply(task);
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
