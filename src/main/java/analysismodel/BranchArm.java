package analysismodel;

import java.util.Objects;

public record BranchArm(
        String markerNodeId,
        Region body,
        boolean isDefault
) {
    public BranchArm {
        Objects.requireNonNull(markerNodeId, "markerNodeId must not be null");
        Objects.requireNonNull(body, "body must not be null");
    }
}
