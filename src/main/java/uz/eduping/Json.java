package uz.eduping;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private Json() {}
    public static JsonNode read(String value) { return MAPPER.readTree(value); }
    public static String write(Object value) { return MAPPER.writeValueAsString(value); }
}
