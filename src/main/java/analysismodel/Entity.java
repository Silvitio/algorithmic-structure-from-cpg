package analysismodel;

import java.util.Objects;

public record Entity(EntityKind kind, String name) {
    public static final String RETURN_SLOT_NAME = "RETURN_SLOT";

    public Entity {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    public static Entity variable(String name) {
        return new Entity(EntityKind.VARIABLE, name);
    }

    public static Entity array(String name) {
        return new Entity(EntityKind.ARRAY, name);
    }

    public static Entity arraySummary(String name) {
        return new Entity(EntityKind.ARRAY_SUMMARY, name);
    }

    public static Entity returnSlot() {
        return new Entity(EntityKind.RETURN_SLOT, RETURN_SLOT_NAME);
    }

    public static Entity ioSink(String name) {
        return new Entity(EntityKind.IO_SINK, name);
    }
}
