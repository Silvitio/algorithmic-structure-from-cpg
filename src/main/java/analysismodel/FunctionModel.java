package analysismodel;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class FunctionModel {
    private final String functionNodeId;
    private final String functionName;
    private final ProgramNode entry;
    private final ProgramNode exit;
    private final List<ProgramNode> nodes;
    private final Map<String, ProgramNode> byCpgNodeId;

    public FunctionModel(
            String functionNodeId,
            String functionName,
            ProgramNode entry,
            ProgramNode exit,
            Collection<ProgramNode> nodes
    ) {
        this.functionNodeId = requireText(functionNodeId, "functionNodeId");
        this.functionName = requireText(functionName, "functionName");
        this.entry = Objects.requireNonNull(entry, "entry must not be null");
        this.exit = Objects.requireNonNull(exit, "exit must not be null");
        this.nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
        this.byCpgNodeId = indexNodes(this.nodes);
    }

    public String functionNodeId() {
        return functionNodeId;
    }

    public String functionName() {
        return functionName;
    }

    public ProgramNode entry() {
        return entry;
    }

    public ProgramNode exit() {
        return exit;
    }

    public List<ProgramNode> nodes() {
        return nodes;
    }

    public Optional<ProgramNode> findByCpgNodeId(String cpgNodeId) {
        return Optional.ofNullable(byCpgNodeId.get(cpgNodeId));
    }

    private static Map<String, ProgramNode> indexNodes(List<ProgramNode> nodes) {
        Map<String, ProgramNode> index = new LinkedHashMap<>();
        for (ProgramNode node : nodes) {
            ProgramNode previous = index.put(node.cpgNodeId(), node);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate ProgramNode cpgNodeId: " + node.cpgNodeId());
            }
        }
        return Map.copyOf(index);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
