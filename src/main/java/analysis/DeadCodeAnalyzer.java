package analysis;

import analysismodel.BranchArm;
import analysismodel.Entity;
import analysismodel.EntityKind;
import analysismodel.FunctionModel;
import analysismodel.NodeKind;
import analysismodel.ProgramNode;
import analysismodel.Region;
import analysismodel.SwitchStructure;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Values;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

public final class DeadCodeAnalyzer {
    private static final String MARK_DEAD_CODE_BY_IDS_QUERY = """
            UNWIND $nodeIds AS nodeId
            MATCH (n)
            WHERE elementId(n) = nodeId
            SET n:DEAD_CODE
            """;

    public Set<String> findDeadDefinitionNodeIds(FunctionModel model, Set<String> significantModelNodeIds) {
        Set<String> deadDefinitionNodeIds = new LinkedHashSet<>();

        for (ProgramNode node : model.nodes()) {
            if (!significantModelNodeIds.contains(node.cpgNodeId())) {
                continue;
            }
            if (!isDeadDefinitionCandidate(node)) {
                continue;
            }
            if (definesOnlyDeadValues(node, significantModelNodeIds)) {
                deadDefinitionNodeIds.add(node.cpgNodeId());
            }
        }

        return deadDefinitionNodeIds;
    }

    public void analyze(
            TransactionContext tx,
            FunctionModel model,
            Set<String> semanticNodeIds,
            Set<String> structuralNodeIds
    ) {
        Set<String> deadNodeIds = new LinkedHashSet<>();
        deadNodeIds.addAll(collectDeadSemanticNodeIds(model, semanticNodeIds));
        deadNodeIds.addAll(collectDeadStructuralNodeIds(model, structuralNodeIds));

        if (deadNodeIds.isEmpty()) {
            return;
        }

        tx.run(MARK_DEAD_CODE_BY_IDS_QUERY, Values.parameters("nodeIds", deadNodeIds)).consume();
    }

    private boolean definesOnlyDeadValues(ProgramNode defNode, Set<String> significantModelNodeIds) {
        Set<Entity> relevantDefs = new LinkedHashSet<>();
        for (Entity entity : defNode.defs()) {
            if (entity.kind() != EntityKind.RETURN_SLOT && entity.kind() != EntityKind.IO_SINK) {
                relevantDefs.add(entity);
            }
        }

        if (relevantDefs.isEmpty()) {
            return false;
        }

        for (Entity entity : relevantDefs) {
            if (hasUsefulUsePath(defNode, entity, significantModelNodeIds)) {
                return false;
            }
        }

        return true;
    }

    private boolean hasUsefulUsePath(ProgramNode defNode, Entity entity, Set<String> significantModelNodeIds) {
        Deque<ProgramNode> worklist = new ArrayDeque<>();
        Set<String> visitedNodeIds = new LinkedHashSet<>();

        defNode.outgoing().forEach(edge -> worklist.addLast(edge.to()));

        while (!worklist.isEmpty()) {
            ProgramNode node = worklist.removeFirst();
            if (!visitedNodeIds.add(node.cpgNodeId())) {
                continue;
            }

            if (significantModelNodeIds.contains(node.cpgNodeId()) && node.uses().contains(entity)) {
                return true;
            }

            if (node.defs().contains(entity)) {
                continue;
            }

            node.outgoing().forEach(edge -> worklist.addLast(edge.to()));
        }

        return false;
    }

    private Set<String> collectDeadSemanticNodeIds(
            FunctionModel model,
            Set<String> semanticNodeIds
    ) {
        Set<String> deadNodeIds = new LinkedHashSet<>();
        for (ProgramNode node : model.nodes()) {
            if (isSemanticDeadCandidate(node) && !semanticNodeIds.contains(node.cpgNodeId())) {
                deadNodeIds.add(node.cpgNodeId());
            }
        }
        return deadNodeIds;
    }

    private Set<String> collectDeadStructuralNodeIds(
            FunctionModel model,
            Set<String> structuralNodeIds
    ) {
        Set<String> candidates = new LinkedHashSet<>();

        for (ProgramNode node : model.nodes()) {
            if (node.kind() == NodeKind.DECLARATION) {
                model.declarationStatementNodeId(node.cpgNodeId()).ifPresent(candidates::add);
            }
        }

        candidates.addAll(model.ifStructures().keySet());
        candidates.addAll(model.loopStructures().keySet());
        candidates.addAll(model.switchStructures().keySet());
        candidates.add(model.functionNodeId());

        addRegionBlockCandidate(candidates, model.bodyRegion());
        model.ifStructures().values().forEach(structure -> {
            addRegionBlockCandidate(candidates, structure.thenRegion());
            addRegionBlockCandidate(candidates, structure.elseRegion());
        });
        model.loopStructures().values().forEach(structure -> {
            addRegionBlockCandidate(candidates, structure.initializerRegion());
            addRegionBlockCandidate(candidates, structure.bodyRegion());
            addRegionBlockCandidate(candidates, structure.iterationRegion());
        });
        for (SwitchStructure structure : model.switchStructures().values()) {
            addRegionBlockCandidate(candidates, structure.bodyRegion());
            for (BranchArm arm : structure.arms()) {
                candidates.add(arm.markerNodeId());
                addRegionBlockCandidate(candidates, arm.body());
            }
        }

        candidates.removeIf(structuralNodeIds::contains);
        return candidates;
    }

    private boolean isDeadDefinitionCandidate(ProgramNode node) {
        if (node.defs().isEmpty()) {
            return false;
        }

        String code = node.code().stripLeading();
        return switch (node.kind()) {
            case ACTION -> !code.startsWith("scanf(");
            case DECLARATION -> code.contains("=");
            case ENTRY, EXIT, BRANCH, LOOP, TRANSFER, BLOCK, LABEL -> false;
        };
    }

    private boolean isSemanticDeadCandidate(ProgramNode node) {
        return switch (node.kind()) {
            case DECLARATION, ACTION, TRANSFER -> true;
            case ENTRY, EXIT, BRANCH, LOOP, BLOCK, LABEL -> false;
        };
    }

    private void addRegionBlockCandidate(Set<String> candidates, Region region) {
        if (region.blockNodeId() != null && !region.blockNodeId().isBlank()) {
            candidates.add(region.blockNodeId());
        }
    }
}
