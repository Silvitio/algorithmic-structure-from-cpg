package analysis;

import analysismodel.BranchArm;
import analysismodel.FunctionModel;
import analysismodel.IfStructure;
import analysismodel.LoopStructure;
import analysismodel.NodeKind;
import analysismodel.ProgramNode;
import analysismodel.Region;
import analysismodel.SwitchStructure;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ModelStructuralSignificanceAnalyzer {
    public Set<String> analyze(FunctionModel model, Set<String> significantModelNodeIds) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(significantModelNodeIds, "significantModelNodeIds must not be null");

        Set<String> structuralNodeIds = new LinkedHashSet<>();

        for (ProgramNode node : model.nodes()) {
            if (node.kind() == NodeKind.DECLARATION && significantModelNodeIds.contains(node.cpgNodeId())) {
                model.declarationStatementNodeId(node.cpgNodeId()).ifPresent(structuralNodeIds::add);
            }
        }

        for (Map.Entry<String, IfStructure> entry : model.ifStructures().entrySet()) {
            IfStructure structure = entry.getValue();
            boolean thenSignificant = regionContainsCarrier(structure.thenRegion(), significantModelNodeIds);
            boolean elseSignificant = regionContainsCarrier(structure.elseRegion(), significantModelNodeIds);
            if (thenSignificant || elseSignificant) {
                structuralNodeIds.add(entry.getKey());
                if (thenSignificant) {
                    addRegionBlock(structuralNodeIds, structure.thenRegion());
                }
                if (elseSignificant) {
                    addRegionBlock(structuralNodeIds, structure.elseRegion());
                }
            }
        }

        for (Map.Entry<String, LoopStructure> entry : model.loopStructures().entrySet()) {
            LoopStructure structure = entry.getValue();
            boolean bodySignificant = regionContainsCarrier(structure.bodyRegion(), significantModelNodeIds);
            boolean initSignificant = regionContainsCarrier(structure.initializerRegion(), significantModelNodeIds);
            boolean iterSignificant = regionContainsCarrier(structure.iterationRegion(), significantModelNodeIds);
            if (bodySignificant || initSignificant || iterSignificant) {
                structuralNodeIds.add(entry.getKey());
                if (bodySignificant) {
                    addRegionBlock(structuralNodeIds, structure.bodyRegion());
                }
                if (initSignificant) {
                    addRegionBlock(structuralNodeIds, structure.initializerRegion());
                }
                if (iterSignificant) {
                    addRegionBlock(structuralNodeIds, structure.iterationRegion());
                }
            }
        }

        for (Map.Entry<String, SwitchStructure> entry : model.switchStructures().entrySet()) {
            SwitchStructure structure = entry.getValue();
            boolean switchSignificant = false;
            for (BranchArm arm : structure.arms()) {
                if (regionContainsCarrier(arm.body(), significantModelNodeIds)) {
                    structuralNodeIds.add(arm.markerNodeId());
                    addRegionBlock(structuralNodeIds, arm.body());
                    switchSignificant = true;
                }
            }
            if (switchSignificant) {
                structuralNodeIds.add(entry.getKey());
                addRegionBlock(structuralNodeIds, structure.bodyRegion());
            }
        }

        if (regionContainsCarrier(model.bodyRegion(), significantModelNodeIds)) {
            structuralNodeIds.add(model.functionNodeId());
            addRegionBlock(structuralNodeIds, model.bodyRegion());
        }

        return structuralNodeIds;
    }

    private boolean regionContainsCarrier(Region region, Set<String> significantModelNodeIds) {
        for (String nodeId : region.nodeIds()) {
            if (significantModelNodeIds.contains(nodeId)) {
                return true;
            }
        }
        return false;
    }

    private void addRegionBlock(Set<String> structuralNodeIds, Region region) {
        if (region.blockNodeId() != null && !region.blockNodeId().isBlank()) {
            structuralNodeIds.add(region.blockNodeId());
        }
    }
}
