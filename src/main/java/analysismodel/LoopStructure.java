package analysismodel;

import java.util.Objects;

public record LoopStructure(
        String conditionNodeId,
        Region bodyRegion,
        Region initializerRegion,
        Region iterationRegion,
        boolean conditionAfterBody
) {
    public LoopStructure {
        Objects.requireNonNull(bodyRegion, "bodyRegion must not be null");
        Objects.requireNonNull(initializerRegion, "initializerRegion must not be null");
        Objects.requireNonNull(iterationRegion, "iterationRegion must not be null");
    }
}
