package app;

import analysis.ModelReturnInfluenceAnalyzer;
import analysis.ModelReturnInfluenceAnalyzer.ModelAnalysisResult;
import analysis.ModelStructuralSignificanceAnalyzer;
import analysismodel.FunctionModel;
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
import java.util.Set;

public final class ModelAnalysisDebugService {
    private static final String NODE_TEXT_QUERY = """
            MATCH (node)
            WHERE elementId(node) IN $nodeIds
            RETURN elementId(node) AS nodeId, node.code AS code, node.name AS name, node.value AS value
            """;

    private final FunctionModelBuilder functionModelBuilder = new FunctionModelBuilder();
    private final ModelReturnInfluenceAnalyzer modelInfluenceAnalyzer = new ModelReturnInfluenceAnalyzer();
    private final ModelStructuralSignificanceAnalyzer modelStructuralAnalyzer = new ModelStructuralSignificanceAnalyzer();

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
            ModelAnalysisResult semanticResult = modelInfluenceAnalyzer.analyzeDetailed(model);
            Set<String> structuralNodeIds = modelStructuralAnalyzer.analyze(model, semanticResult.significantModelNodeIds());

            Set<String> semanticCodes = session.executeRead(tx -> resolveNodeTexts(tx, semanticResult.significantNodeIds()));
            Set<String> structuralCodes = session.executeRead(tx -> resolveNodeTexts(tx, structuralNodeIds));

            lines.add("Function: " + model.functionName());
            lines.add("Semantic codes: " + semanticCodes);
            lines.add("Structural codes: " + structuralCodes);
            lines.add("");
        }

        return lines;
    }

    private Set<String> resolveNodeTexts(TransactionContext tx, Set<String> nodeIds) {
        if (nodeIds.isEmpty()) {
            return Set.of();
        }

        Map<String, String> resolvedTexts = new LinkedHashMap<>();
        List<Record> records = tx.run(
                NODE_TEXT_QUERY,
                Values.parameters("nodeIds", List.copyOf(nodeIds))
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
        for (String nodeId : nodeIds) {
            String text = resolvedTexts.get(nodeId);
            if (text != null && !text.isBlank()) {
                codes.add(text);
            }
        }
        return codes;
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
