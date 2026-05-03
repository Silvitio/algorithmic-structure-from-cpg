package analysis;

import analysismodel.Entity;
import analysismodel.EntityKind;
import analysismodel.FunctionModel;
import analysismodel.NodeKind;
import analysismodel.ProgramNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ModelReturnInfluenceAnalyzer {
    public Set<String> analyze(FunctionModel model) {
        return analyzeDetailed(model).significantNodeIds();
    }

    public ModelAnalysisResult analyzeDetailed(FunctionModel model) {
        Objects.requireNonNull(model, "model must not be null");

        Map<String, Set<Entity>> neededIn = new LinkedHashMap<>();
        Map<String, Set<Entity>> neededOut = new LinkedHashMap<>();
        Deque<ProgramNode> worklist = new ArrayDeque<>();

        for (ProgramNode node : model.nodes()) {
            neededIn.put(node.cpgNodeId(), new LinkedHashSet<>());
            neededOut.put(node.cpgNodeId(), new LinkedHashSet<>());
            worklist.addLast(node);
        }

        while (!worklist.isEmpty()) {
            ProgramNode node = worklist.removeFirst();

            Set<Entity> newNeededOut = unionSuccessorNeededIn(node, neededIn);
            Set<Entity> newNeededIn = transfer(node, newNeededOut);

            boolean outChanged = replaceIfChanged(neededOut, node.cpgNodeId(), newNeededOut);
            boolean inChanged = replaceIfChanged(neededIn, node.cpgNodeId(), newNeededIn);

            if (outChanged || inChanged) {
                for (ProgramNode predecessor : predecessors(node)) {
                    worklist.addLast(predecessor);
                }
            }
        }

        Set<String> significantNodeIds = new LinkedHashSet<>();
        for (ProgramNode node : model.nodes()) {
            if (isSemanticallySignificant(node, neededOut.get(node.cpgNodeId()))) {
                significantNodeIds.add(node.cpgNodeId());
            }
        }

        Set<Entity> participatingEntities = collectParticipatingEntities(model, significantNodeIds);
        for (ProgramNode node : model.nodes()) {
            if (node.kind() == NodeKind.DECLARATION && intersects(node.defs(), participatingEntities)) {
                significantNodeIds.add(node.cpgNodeId());
            }
        }

        return new ModelAnalysisResult(
                significantNodeIds,
                copyEntityMap(neededIn),
                copyEntityMap(neededOut)
        );
    }

    private Set<Entity> unionSuccessorNeededIn(ProgramNode node, Map<String, Set<Entity>> neededIn) {
        Set<Entity> result = new LinkedHashSet<>();
        node.outgoing().forEach(edge -> result.addAll(neededIn.get(edge.to().cpgNodeId())));
        return result;
    }

    private Set<Entity> transfer(ProgramNode node, Set<Entity> neededOut) {
        if (node.kind() == NodeKind.ENTRY || node.kind() == NodeKind.EXIT) {
            return new LinkedHashSet<>(neededOut);
        }

        if (isObservedSink(node)) {
            Set<Entity> neededIn = new LinkedHashSet<>(neededOut);
            neededIn.addAll(node.uses());
            return neededIn;
        }

        if (node.kind() == NodeKind.BRANCH || node.kind() == NodeKind.LOOP) {
            return new LinkedHashSet<>(neededOut);
        }

        if (intersects(node.defs(), neededOut)) {
            Set<Entity> neededIn = new LinkedHashSet<>(neededOut);
            neededIn.removeAll(node.defs());
            neededIn.addAll(node.uses());
            return neededIn;
        }

        return new LinkedHashSet<>(neededOut);
    }

    private boolean isSemanticallySignificant(ProgramNode node, Set<Entity> neededOut) {
        if (node.kind() == NodeKind.ENTRY || node.kind() == NodeKind.EXIT) {
            return false;
        }
        return isObservedSink(node) || intersects(node.defs(), neededOut);
    }

    private boolean isObservedSink(ProgramNode node) {
        if (definesReturnSlot(node)) {
            return true;
        }

        if (node.kind() != NodeKind.ACTION) {
            return false;
        }

        String code = node.code().stripLeading();
        return code.startsWith("printf(") || code.startsWith("scanf(");
    }

    private boolean definesReturnSlot(ProgramNode node) {
        if (node.kind() != NodeKind.TRANSFER) {
            return false;
        }

        for (Entity entity : node.defs()) {
            if (entity.kind() == EntityKind.RETURN_SLOT) {
                return true;
            }
        }
        return false;
    }

    private boolean intersects(Set<Entity> defs, Set<Entity> neededOut) {
        for (Entity entity : defs) {
            if (neededOut.contains(entity)) {
                return true;
            }
        }
        return false;
    }

    private Set<Entity> collectParticipatingEntities(FunctionModel model, Set<String> significantNodeIds) {
        Set<Entity> entities = new LinkedHashSet<>();
        for (String nodeId : significantNodeIds) {
            model.findByCpgNodeId(nodeId).ifPresent(node -> {
                entities.addAll(node.defs());
                entities.addAll(node.uses());
            });
        }
        return entities;
    }

    private boolean replaceIfChanged(
            Map<String, Set<Entity>> map,
            String nodeId,
            Set<Entity> newValue
    ) {
        Set<Entity> current = map.get(nodeId);
        if (current.equals(newValue)) {
            return false;
        }
        map.put(nodeId, new LinkedHashSet<>(newValue));
        return true;
    }

    private List<ProgramNode> predecessors(ProgramNode node) {
        List<ProgramNode> predecessors = new ArrayList<>();
        node.incoming().forEach(edge -> predecessors.add(edge.from()));
        return predecessors;
    }

    private Map<String, Set<Entity>> copyEntityMap(Map<String, Set<Entity>> source) {
        Map<String, Set<Entity>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
        return Map.copyOf(copy);
    }

    public record ModelAnalysisResult(
            Set<String> significantNodeIds,
            Map<String, Set<Entity>> neededIn,
            Map<String, Set<Entity>> neededOut
    ) {
    }
}
