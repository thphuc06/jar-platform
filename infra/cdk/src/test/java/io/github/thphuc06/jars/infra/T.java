package io.github.thphuc06.jars.infra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

/** Tiny helpers that keep the nested CloudFormation assertions readable. */
final class T {

    private T() {}

    /** Map from alternating keys and values, in order. */
    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** {@code Match.objectLike} of alternating keys and values. */
    static Object like(Object... keysAndValues) {
        return Match.objectLike(map(keysAndValues));
    }

    /** {@code Properties} of every resource of a type, as plain maps. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> properties(Template template, String type) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> resource : template.findResources(type).values()) {
            Object properties = resource.get("Properties");
            result.add(properties == null ? Map.of() : (Map<String, Object>) properties);
        }
        return result;
    }

    /** Resource logical id to the full resource definition. */
    static Map<String, Map<String, Object>> resources(Template template, String type) {
        return template.findResources(type);
    }

    /** The values of one property over all resources of a type. */
    static List<Object> values(Template template, String type, String property) {
        return properties(template, type).stream().map(p -> p.get(property)).collect(Collectors.toList());
    }
}
