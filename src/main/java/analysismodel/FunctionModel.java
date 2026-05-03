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
    private final Map<String, IfStructure> ifStructures;
    private final Map<String, LoopStructure> loopStructures;
    private final Map<String, SwitchStructure> switchStructures;

    public FunctionModel(
            String functionNodeId,
            String functionName,
            ProgramNode entry,
            ProgramNode exit,
            Collection<ProgramNode> nodes,
            Map<String, IfStructure> ifStructures,
            Map<String, LoopStructure> loopStructures,
            Map<String, SwitchStructure> switchStructures
    ) {
        this.functionNodeId = requireText(functionNodeId, "functionNodeId");
        this.functionName = requireText(functionName, "functionName");
        this.entry = Objects.requireNonNull(entry, "entry must not be null");
        this.exit = Objects.requireNonNull(exit, "exit must not be null");
        this.nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
        this.byCpgNodeId = indexNodes(this.nodes);
        this.ifStructures = Map.copyOf(Objects.requireNonNull(ifStructures, "ifStructures must not be null"));
        this.loopStructures = Map.copyOf(Objects.requireNonNull(loopStructures, "loopStructures must not be null"));
        this.switchStructures = Map.copyOf(Objects.requireNonNull(switchStructures, "switchStructures must not be null"));
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

    public Map<String, IfStructure> ifStructures() {
        return ifStructures;
    }

    public Map<String, LoopStructure> loopStructures() {
        return loopStructures;
    }

    public Map<String, SwitchStructure> switchStructures() {
        return switchStructures;
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
