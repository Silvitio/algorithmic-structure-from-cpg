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
    private final Region bodyRegion;
    private final Map<String, IfStructure> ifStructures;
    private final Map<String, LoopStructure> loopStructures;
    private final Map<String, SwitchStructure> switchStructures;
    private final Map<String, String> declarationStatementByValueDeclarationId;

    public FunctionModel(
            String functionNodeId,
            String functionName,
            ProgramNode entry,
            ProgramNode exit,
            Collection<ProgramNode> nodes,
            Region bodyRegion,
            Map<String, IfStructure> ifStructures,
            Map<String, LoopStructure> loopStructures,
            Map<String, SwitchStructure> switchStructures,
            Map<String, String> declarationStatementByValueDeclarationId
    ) {
        this.functionNodeId = requireText(functionNodeId, "functionNodeId");
        this.functionName = requireText(functionName, "functionName");
        this.entry = Objects.requireNonNull(entry, "entry must not be null");
        this.exit = Objects.requireNonNull(exit, "exit must not be null");
        this.nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
        this.byCpgNodeId = indexNodes(this.nodes);
        this.bodyRegion = Objects.requireNonNull(bodyRegion, "bodyRegion must not be null");
        this.ifStructures = Map.copyOf(Objects.requireNonNull(ifStructures, "ifStructures must not be null"));
        this.loopStructures = Map.copyOf(Objects.requireNonNull(loopStructures, "loopStructures must not be null"));
        this.switchStructures = Map.copyOf(Objects.requireNonNull(switchStructures, "switchStructures must not be null"));
        this.declarationStatementByValueDeclarationId = Map.copyOf(Objects.requireNonNull(
                declarationStatementByValueDeclarationId,
                "declarationStatementByValueDeclarationId must not be null"
        ));
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

    public Region bodyRegion() {
        return bodyRegion;
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

    public Optional<String> declarationStatementNodeId(String valueDeclarationNodeId) {
        return Optional.ofNullable(declarationStatementByValueDeclarationId.get(valueDeclarationNodeId));
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
