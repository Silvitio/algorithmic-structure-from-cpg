package analysismodel;

import java.util.List;
import java.util.Objects;

public record Region(String blockNodeId, List<String> nodeIds) {
    public Region {
        Objects.requireNonNull(nodeIds, "nodeIds must not be null");
        nodeIds = List.copyOf(nodeIds);
    }

    public static Region empty() {
        return new Region(null, List.of());
    }
}
