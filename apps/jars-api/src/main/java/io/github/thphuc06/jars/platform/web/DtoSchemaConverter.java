package io.github.thphuc06.jars.platform.web;

import io.github.thphuc06.jars.shared.VndAmount;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Tells swagger-core two facts about our DTOs that it cannot see by itself:
 *
 * <ul>
 *   <li>JSpecify {@code @Nullable} is a TYPE_USE annotation and swagger-core only reads declaration annotations, so
 *       {@code @Nullable UUID toJarId} would be documented as never null. Here it becomes {@code type: [string, null]}
 *       (or {@code oneOf: [$ref, null]} for a nested object).
 *   <li>{@code @VndAmount BigDecimal} is money, and money travels as a decimal STRING (api-contract §1): a JS number
 *       is exact only up to 2^53, fewer digits than {@code @VndAmount} allows.
 * </ul>
 *
 * Fields are found by their JSON name, which is the field name in every DTO of this code base.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
final class DtoSchemaConverter implements ModelConverter {

    static final String VND_AMOUNT_PATTERN = "^[0-9]{1,17}(\\.0+)?$";

    private static final String OWN_PACKAGE = "io.github.thphuc06.jars.";

    @Override
    public @Nullable Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        if (!chain.hasNext()) {
            return null;
        }
        Schema schema = chain.next().resolve(type, context, chain);
        Class<?> raw = Json.mapper().constructType(type.getType()).getRawClass();
        if (schema == null || !raw.getName().startsWith(OWN_PACKAGE)) {
            return schema;
        }
        // a nested type comes back as {$ref: ...}; its properties live in the model registered under that name
        Schema model = schema.get$ref() == null
                ? schema
                : context.getDefinedModels()
                        .get(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1));
        if (model != null && model.getProperties() != null) {
            model.getProperties().replaceAll((name, property) -> adjust(raw, (String) name, (Schema) property));
        }
        return schema;
    }

    private static Schema adjust(Class<?> owner, String name, Schema property) {
        Field field = findField(owner, name);
        if (field == null) {
            return property;
        }
        Schema result = field.isAnnotationPresent(VndAmount.class) ? vndAmount(property) : property;
        return field.getAnnotatedType().isAnnotationPresent(Nullable.class) ? nullable(result) : result;
    }

    private static Schema vndAmount(Schema property) {
        return new Schema()
                .types(new LinkedHashSet<>(List.of("string")))
                .pattern(VND_AMOUNT_PATTERN)
                .example("85000")
                .description("Positive whole number of VND as a decimal string; \"85000.00\" is accepted too")
                .writeOnly(property.getWriteOnly());
    }

    private static Schema nullable(Schema property) {
        if (property.get$ref() != null) {
            return new Schema().oneOf(List.of(new Schema().$ref(property.get$ref()), nullType()));
        }
        if (property.getOneOf() != null) {
            return property; // already wrapped (the same model can be resolved twice)
        }
        Set<String> types = new LinkedHashSet<>(property.getTypes() == null ? Set.of() : property.getTypes());
        types.add("null");
        property.setTypes(types);
        if (property.getEnum() != null && !property.getEnum().contains(null)) {
            List values = new ArrayList<>(property.getEnum());
            values.add(null); // JSON Schema: a value must also be one of the enum values, so null has to be listed
            property.setEnum(values);
        }
        return property;
    }

    private static Schema nullType() {
        return new Schema().types(new LinkedHashSet<>(List.of("null")));
    }

    private static @Nullable Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // look in the superclass
            }
        }
        return null;
    }
}
