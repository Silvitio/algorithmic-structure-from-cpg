package analysismodel;

import java.util.List;
import java.util.Objects;

public record SwitchStructure(
        String selectorNodeId,
        List<BranchArm> arms
) {
    public SwitchStructure {
        Objects.requireNonNull(arms, "arms must not be null");
        arms = List.copyOf(arms);
    }
}
