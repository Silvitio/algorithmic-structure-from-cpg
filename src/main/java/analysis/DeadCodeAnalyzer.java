package analysis;

import analysismodel.BranchArm;
import analysismodel.FunctionModel;
import analysismodel.NodeKind;
import analysismodel.ProgramNode;
import analysismodel.Region;
import analysismodel.SwitchStructure;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Values;

import java.util.LinkedHashSet;
import java.util.Set;

public final class DeadCodeAnalyzer {
    private static final String MARK_DEAD_CODE_BY_IDS_QUERY = """
            UNWIND $nodeIds AS nodeId
            MATCH (n)
            WHERE elementId(n) = nodeId
            SET n:DEAD_CODE
            """;

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
