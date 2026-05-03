package app;

import analysis.ModelReturnInfluenceAnalyzer;
import analysis.ModelReturnInfluenceAnalyzer.ModelAnalysisResult;
import analysis.ReturnInfluenceAnalyzer;
import analysis.ReturnInfluenceAnalyzer.AnalysisSummary;
import analysis.ReturnInfluenceAnalyzer.FunctionInfluence;
import analysismodel.Entity;
import analysismodel.FunctionModel;
import analysismodel.NodeKind;
import analysismodel.ProgramNode;
import cpg.FunctionModelBuilder;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ModelInfluenceComparisonDebugService {
    private static final String NODE_TEXT_QUERY = """
            MATCH (node)
            WHERE elementId(node) IN $nodeIds
            RETURN
              elementId(node) AS nodeId,
              node.code AS code,
              node.name AS name,
              node.value AS value
            """;

    private final ReturnInfluenceAnalyzer legacyAnalyzer = new ReturnInfluenceAnalyzer();
    private final FunctionModelBuilder functionModelBuilder = new FunctionModelBuilder();
    private final ModelReturnInfluenceAnalyzer modelAnalyzer = new ModelReturnInfluenceAnalyzer();

    public List<String> collectDebugLines() {
        try (Driver driver = GraphDatabase.driver(
                AnalysisService.URI,
                AuthTokens.basic(AnalysisService.USER, AnalysisService.PASSWORD));
             Session session = driver.session(SessionConfig.forDatabase(AnalysisService.DATABASE))) {
            return collectDebugLines(session);
        }
    }

    public List<String> collectDebugLines(Session session) {
        AnalysisSummary legacySummary = legacyAnalyzer.analyze(session);
        List<FunctionModel> models = functionModelBuilder.buildAll(session);
        Map<String, FunctionInfluence> legacyByFunction = indexLegacySummary(legacySummary);

        List<String> lines = new ArrayList<>();
        for (FunctionModel model : models) {
            lines.add("Function: " + model.functionName());

            FunctionInfluence legacy = legacyByFunction.get(model.functionNodeId());
            Set<String> legacyCodes = legacy == null ? Set.of() : new LinkedHashSet<>(legacy.markedCodes());

            ModelAnalysisResult result = modelAnalyzer.analyzeDetailed(model);
            Set<String> modelCodes = resolveMarkedCodes(session, model, result.significantNodeIds());

            lines.add("Legacy codes: " + legacyCodes);
            lines.add("Model codes: " + modelCodes);
            lines.add("Only legacy: " + difference(legacyCodes, modelCodes));
            lines.add("Only model: " + difference(modelCodes, legacyCodes));

            for (ProgramNode node : model.nodes()) {
                lines.add(formatNode(model, node, result));
            }
            lines.add("");
        }

        return lines;
    }

    private Map<String, FunctionInfluence> indexLegacySummary(AnalysisSummary summary) {
        Map<String, FunctionInfluence> index = new LinkedHashMap<>();
        for (FunctionInfluence functionInfluence : summary.functions()) {
            index.put(functionInfluence.functionNodeId(), functionInfluence);
        }
        return index;
    }

    private Set<String> resolveMarkedCodes(
            Session session,
            FunctionModel model,
            Set<String> significantNodeIds
    ) {
        return session.executeRead(tx -> resolveMarkedCodes(tx, model, significantNodeIds));
    }

    private Set<String> resolveMarkedCodes(
            TransactionContext tx,
            FunctionModel model,
            Set<String> significantNodeIds
    ) {
        if (significantNodeIds.isEmpty()) {
            return Set.of();
        }

        Map<String, String> resolvedTexts = new LinkedHashMap<>();
        List<Record> records = tx.run(
                NODE_TEXT_QUERY,
                Values.parameters("nodeIds", List.copyOf(significantNodeIds))
        ).list();

        for (Record record : records) {
            String nodeId = record.get("nodeId").asString();
            String text = firstNonBlank(
                    nullableString(record, "code"),
                    nullableString(record, "name"),
                    nullableString(record, "value")
            );
            if (text != null && !text.isBlank()) {
                resolvedTexts.put(nodeId, text.strip());
            }
        }

        Set<String> codes = new LinkedHashSet<>();
        for (String nodeId : significantNodeIds) {
            ProgramNode modelNode = model.findByCpgNodeId(nodeId).orElse(null);
            if (modelNode != null && modelNode.kind() == NodeKind.TRANSFER) {
                continue;
            }
            String text = resolvedTexts.get(nodeId);
            if (text != null && !text.isBlank()) {
                codes.add(text);
            }
        }
        return codes;
    }

    private Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private String formatNode(FunctionModel model, ProgramNode node, ModelAnalysisResult result) {
        boolean significant = result.significantModelNodeIds().contains(node.cpgNodeId());
        return "[" + node.kind() + "]"
                + " line=" + (node.startLine() == null ? "?" : node.startLine())
                + " significant=" + significant
                + " code=" + sanitize(node.code())
                + " | defs=" + formatEntities(node.defs())
                + " | uses=" + formatEntities(node.uses())
                + " | out=" + formatEntities(result.neededOut().getOrDefault(node.cpgNodeId(), Set.of()))
                + " | in=" + formatEntities(result.neededIn().getOrDefault(node.cpgNodeId(), Set.of()));
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

    private String sanitize(String code) {
        if (code == null || code.isBlank()) {
            return "<no-code>";
        }
        String singleLine = code.replace(System.lineSeparator(), " ").replace('\n', ' ').trim();
        if (singleLine.length() <= 100) {
            return singleLine;
        }
        return singleLine.substring(0, 97) + "...";
    }

    private String nullableString(Record record, String key) {
        if (record.get(key).isNull()) {
            return null;
        }
        return record.get(key).asString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
