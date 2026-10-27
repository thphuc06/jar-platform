package io.github.thphuc06.jars.platform.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * Rules that hold for EVERY operation, applied to the generated document instead of being repeated as annotations on
 * each controller method. OpenApiContractTest checks them against the running application.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
final class ApiContractRules implements OpenApiCustomizer {

    static final String BEARER = "bearerAuth";
    static final String READ_SCOPE = "jars-api/read";
    static final String WRITE_SCOPE = "jars-api/write";

    /** Must match the permitAll() rules of SecurityConfig; OpenApiContractTest calls every operation to prove it. */
    static final Set<String> PUBLIC_OPERATIONS = Set.of("GET /api/v1/health");

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Override
    public void customise(OpenAPI api) {
        api.getPaths()
                .forEach((path, item) ->
                        item.readOperationsMap().forEach((method, operation) -> applyTo(path, method, operation)));
        requireEveryResponseProperty(api);
    }

    private static void applyTo(String path, HttpMethod method, Operation operation) {
        if (!PUBLIC_OPERATIONS.contains(method + " " + path)) {
            // same rule as SecurityConfig: reading needs jars-api/read, every other method jars-api/write
            operation.addSecurityItem(
                    new SecurityRequirement().addList(BEARER, method == HttpMethod.GET ? READ_SCOPE : WRITE_SCOPE));
            operation.getResponses().addApiResponse("401", ref("Unauthorized"));
            operation.getResponses().addApiResponse("403", ref("Forbidden"));
        }
        operation.getResponses().addApiResponse("default", ref("Problem"));

        ApiResponse created = operation.getResponses().get("201");
        if (created != null) {
            created.addHeaderObject(
                    "Location",
                    new Header()
                            .description("URL of the created resource")
                            .schema(new Schema().types(new LinkedHashSet<>(List.of("string"))))
                            .required(true));
        }
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                if (IDEMPOTENCY_KEY.equals(parameter.getName())) {
                    parameter.description("New UUID per user action, kept when the same action is sent again: a "
                            + "repeated request returns the stored result instead of moving the money twice");
                    if (created != null) {
                        created.addHeaderObject(
                                "Idempotent-Replayed",
                                new Header()
                                        .description("\"true\" when this is the stored result of an earlier request "
                                                + "with the same key; absent otherwise")
                                        .schema(new Schema()
                                                .types(new LinkedHashSet<>(List.of("string")))
                                                ._enum(List.of("true"))));
                    }
                }
            }
        }
    }

    /**
     * Jackson writes every property of a response record, null or not, so all of them are REQUIRED (present) and
     * {@code @Nullable} ones are nullable. Without this a TypeScript client would see {@code total?: string} and
     * have to check for undefined on every field. Request bodies keep what Bean Validation enforces
     * ({@code @NotNull} → required), so a schema may never serve as both.
     */
    private static void requireEveryResponseProperty(OpenAPI api) {
        Map<String, Schema> schemas = api.getComponents().getSchemas();
        Set<String> responseModels = new TreeSet<>();
        Set<String> requestModels = new TreeSet<>();
        api.getPaths()
                .values()
                .forEach(item -> item.readOperations().forEach(operation -> {
                    operation.getResponses().forEach((status, response) -> {
                        if (status.startsWith("2")) {
                            collect(response.getContent(), schemas, responseModels);
                        }
                    });
                    if (operation.getRequestBody() != null) {
                        collect(operation.getRequestBody().getContent(), schemas, requestModels);
                    }
                }));
        Set<String> both = new TreeSet<>(responseModels);
        both.retainAll(requestModels);
        if (!both.isEmpty()) {
            throw new IllegalStateException("schemas used as request AND response body: " + both
                    + "; give the request its own record so 'required' can mean the right thing on each side");
        }
        for (String name : responseModels) {
            Schema model = schemas.get(name);
            if (model.getProperties() != null) {
                model.setRequired(new ArrayList<>(
                        new TreeSet<String>(model.getProperties().keySet())));
            }
        }
    }

    private static void collect(Content content, Map<String, Schema> schemas, Set<String> found) {
        if (content != null) {
            content.values().forEach(media -> collect(media.getSchema(), schemas, found));
        }
    }

    private static void collect(Schema schema, Map<String, Schema> schemas, Set<String> found) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null) {
            String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            if (found.add(name)) {
                collect(schemas.get(name), schemas, found);
            }
            return;
        }
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(p -> collect((Schema) p, schemas, found));
        }
        collect(schema.getItems(), schemas, found);
        if (schema.getOneOf() != null) {
            schema.getOneOf().forEach(s -> collect((Schema) s, schemas, found));
        }
    }

    private static ApiResponse ref(String name) {
        return new ApiResponse().$ref("#/components/responses/" + name);
    }
}
