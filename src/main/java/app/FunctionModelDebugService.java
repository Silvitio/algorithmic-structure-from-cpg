package app;

import analysismodel.Entity;
import analysismodel.CfgEdge;
import analysismodel.FunctionModel;
import analysismodel.IfStructure;
import analysismodel.LoopStructure;
import analysismodel.ProgramNode;
import analysismodel.Region;
import analysismodel.SwitchStructure;
import analysismodel.BranchArm;
import cpg.FunctionModelBuilder;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class FunctionModelDebugService {
    private final FunctionModelBuilder functionModelBuilder = new FunctionModelBuilder();

    public List<String> collectDebugLines() {
        try (Driver driver = GraphDatabase.driver(
                AnalysisService.URI,
                AuthTokens.basic(AnalysisService.USER, AnalysisService.PASSWORD));
             Session session = driver.session(SessionConfig.forDatabase(AnalysisService.DATABASE))) {
            return collectDebugLines(session);
        }
    }

    public List<String> collectDebugLines(Session session) {
        List<FunctionModel> models = functionModelBuilder.buildAll(session);
        List<String> lines = new ArrayList<>();

        for (FunctionModel model : models) {
            lines.add("Function: " + model.functionName());
            lines.add("BodyRegion: " + formatRegion(model, model.bodyRegion()));
            for (ProgramNode node : model.nodes()) {
                lines.add(formatNode(node));
                if (!node.outgoing().isEmpty()) {
                    lines.add("  CFG -> " + formatEdges(model, node.outgoing()));
                }
            }
            appendStructures(lines, model);
            lines.add("");
        }

        return lines;
    }

    private String formatNode(ProgramNode node) {
        return "[" + node.kind() + "]"
                + " line=" + (node.startLine() == null ? "?" : node.startLine())
                + " id=" + node.cpgNodeId()
                + " code=" + node.code()
                + " | defs=" + formatEntities(node.defs())
                + " | uses=" + formatEntities(node.uses());
    }

    private String formatEntities(Set<Entity> entities) {
        if (entities.isEmpty()) {
            return "[]";
        }

        List<String> names = new ArrayList<>();
        for (Entity entity : entities) {
            names.add(entity.name());
        }
        return names.toString();
    }

    private String formatEdges(FunctionModel model, List<CfgEdge> edges) {
        List<String> descriptions = new ArrayList<>();
        for (CfgEdge edge : edges) {
            descriptions.add(edge.kind() + ":" + describeNode(model, edge.to().cpgNodeId()));
        }
        return descriptions.toString();
    }

    private void appendStructures(List<String> lines, FunctionModel model) {
        if (!model.ifStructures().isEmpty()) {
            lines.add("IfStructures:");
            for (var entry : model.ifStructures().entrySet()) {
                lines.add(formatIfStructure(model, entry.getKey(), entry.getValue()));
            }
        }

        if (!model.loopStructures().isEmpty()) {
            lines.add("LoopStructures:");
            for (var entry : model.loopStructures().entrySet()) {
                lines.add(formatLoopStructure(model, entry.getKey(), entry.getValue()));
            }
        }

        if (!model.switchStructures().isEmpty()) {
            lines.add("SwitchStructures:");
            for (var entry : model.switchStructures().entrySet()) {
                lines.add(formatSwitchStructure(model, entry.getKey(), entry.getValue()));
            }
        }
    }

    private String formatIfStructure(FunctionModel model, String ownerNodeId, IfStructure structure) {
        return "IF owner=" + describeNode(model, ownerNodeId)
                + " conditionId=" + formatRawId(structure.conditionNodeId())
                + " then=" + formatRegion(model, structure.thenRegion())
                + " else=" + formatRegion(model, structure.elseRegion());
    }

    private String formatLoopStructure(FunctionModel model, String ownerNodeId, LoopStructure structure) {
        return "LOOP owner=" + describeNode(model, ownerNodeId)
                + " postCondition=" + structure.conditionAfterBody()
                + " conditionId=" + formatRawId(structure.conditionNodeId())
                + " init=" + formatRegion(model, structure.initializerRegion())
                + " body=" + formatRegion(model, structure.bodyRegion())
                + " iter=" + formatRegion(model, structure.iterationRegion());
    }

    private String formatSwitchStructure(FunctionModel model, String ownerNodeId, SwitchStructure structure) {
        List<String> armDescriptions = new ArrayList<>();
        for (BranchArm arm : structure.arms()) {
            armDescriptions.add((arm.isDefault() ? "default" : "case")
                    + "(markerId=" + arm.markerNodeId() + ", body=" + formatRegion(model, arm.body()) + ")");
        }

        return "SWITCH owner=" + describeNode(model, ownerNodeId)
                + " selectorId=" + formatRawId(structure.selectorNodeId())
                + " arms=" + armDescriptions;
    }

    private String formatRegion(FunctionModel model, Region region) {
        if (region.nodeIds().isEmpty()) {
            return "[]";
        }

        List<String> descriptions = new ArrayList<>();
        for (String nodeId : region.nodeIds()) {
            descriptions.add(describeNode(model, nodeId));
        }
        return descriptions.toString();
    }

    private String describeNode(FunctionModel model, String nodeId) {
        if (nodeId == null) {
            return "<null>";
        }

        return model.findByCpgNodeId(nodeId)
                .map(node -> "line=" + (node.startLine() == null ? "?" : node.startLine())
                        + ":" + node.kind()
                        + ":" + sanitizeCode(node.code()))
                .orElse(nodeId);
    }

    private String formatRawId(String nodeId) {
        return nodeId == null ? "<null>" : nodeId;
    }

    private String sanitizeCode(String code) {
        if (code == null || code.isBlank()) {
            return "<no-code>";
        }

        String singleLine = code.replace(System.lineSeparator(), " ").replace('\n', ' ').trim();
        if (singleLine.length() <= 80) {
            return singleLine;
        }
        return singleLine.substring(0, 77) + "...";
    }
}
