package analysismodel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ProgramNode {
    private final String cpgNodeId;
    private final NodeKind kind;
    private final String code;
    private final Integer startLine;
    private final Set<Entity> defs;
    private final Set<Entity> uses;
    private final List<CfgEdge> outgoing;
    private final List<CfgEdge> incoming;

    public ProgramNode(
            String cpgNodeId,
            NodeKind kind,
            String code,
            Integer startLine,
            Collection<Entity> defs,
            Collection<Entity> uses
    ) {
        this.cpgNodeId = requireText(cpgNodeId, "cpgNodeId");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.code = code == null ? "" : code;
        this.startLine = startLine;
        this.defs = new LinkedHashSet<>(Objects.requireNonNull(defs, "defs must not be null"));
        this.uses = new LinkedHashSet<>(Objects.requireNonNull(uses, "uses must not be null"));
        this.outgoing = new ArrayList<>();
        this.incoming = new ArrayList<>();
    }

    public String cpgNodeId() {
        return cpgNodeId;
    }

    public NodeKind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public Integer startLine() {
        return startLine;
    }

    public Set<Entity> defs() {
        return Collections.unmodifiableSet(defs);
    }

    public Set<Entity> uses() {
        return Collections.unmodifiableSet(uses);
    }

    public List<CfgEdge> outgoing() {
        return Collections.unmodifiableList(outgoing);
    }

    public List<CfgEdge> incoming() {
        return Collections.unmodifiableList(incoming);
    }

    public void addOutgoingEdge(CfgEdge edge) {
        outgoing.add(Objects.requireNonNull(edge, "edge must not be null"));
    }

    public void addIncomingEdge(CfgEdge edge) {
        incoming.add(Objects.requireNonNull(edge, "edge must not be null"));
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
