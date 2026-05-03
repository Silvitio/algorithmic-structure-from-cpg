package analysismodel;

import java.util.Objects;

public record IfStructure(
        String conditionNodeId,
        Region thenRegion,
        Region elseRegion
) {
    public IfStructure {
        Objects.requireNonNull(thenRegion, "thenRegion must not be null");
        Objects.requireNonNull(elseRegion, "elseRegion must not be null");
    }
}
