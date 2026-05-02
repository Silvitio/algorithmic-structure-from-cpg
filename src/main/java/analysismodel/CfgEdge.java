package analysismodel;

import java.util.Objects;

public record CfgEdge(
        ProgramNode from,
        ProgramNode to,
        CfgEdgeKind kind
) {
    public CfgEdge {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
    }
}
