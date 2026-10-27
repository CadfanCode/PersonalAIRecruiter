package se.caibirch.jobpilot.claude;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/** Validates JSON against the schema files in {@code resources/claude/schemas}, also for user-edited data. */
public final class JsonSchemas {

    private static final ConcurrentHashMap<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

    private JsonSchemas() {
    }

    /** Returns the validation messages; empty means valid. */
    public static List<String> validate(String schemaName, JsonNode json) {
        JsonSchema schema = CACHE.computeIfAbsent(schemaName, n -> JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012).getSchema(ClaudeResources.schema(n)));
        Set<ValidationMessage> problems = schema.validate(json);
        return problems.stream().map(ValidationMessage::getMessage).sorted().toList();
    }
}
