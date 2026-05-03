package analysismodel;

import java.util.List;
import java.util.Objects;

public record SwitchStructure(
        String selectorNodeId,
        Region bodyRegion,
        List<BranchArm> arms
) {
    public SwitchStructure {
        Objects.requireNonNull(bodyRegion, "bodyRegion must not be null");
        Objects.requireNonNull(arms, "arms must not be null");
        arms = List.copyOf(arms);
    }
}
