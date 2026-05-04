package app;

import analysis.DeadCodeAnalyzer;
import analysis.ModelReturnInfluenceAnalyzer;
import analysis.ModelReturnInfluenceAnalyzer.ModelAnalysisResult;
import analysis.ModelStructuralSignificanceAnalyzer;
import analysismodel.FunctionModel;
import analysismodel.NodeKind;
import analysismodel.ProgramNode;
import cpg.AlgoGraphBuilder;
import cpg.FunctionModelBuilder;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AnalysisService {
    public static final String URI = "bolt://localhost:17687";
    public static final String USER = "neo4j";
    public static final String PASSWORD = "strongPasswordHere";
    public static final String DATABASE = "neo4j";

    private static final String CLEAR_INFLUENCE_QUERY = """
            MATCH (n:INFLUENCES_RETURN)
            REMOVE n:INFLUENCES_RETURN
            """;
    private static final String CLEAR_STRUCTURAL_QUERY = """
            MATCH (n:STRUCTURALLY_SIGNIFICANT)
            REMOVE n:STRUCTURALLY_SIGNIFICANT
            """;
    private static final String CLEAR_DEAD_CODE_QUERY = """
            MATCH (n:DEAD_CODE)
            REMOVE n:DEAD_CODE
            """;
    private static final String MARK_INFLUENCE_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            SET node:INFLUENCES_RETURN
            """;
    private static final String MARK_STRUCTURAL_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            SET node:STRUCTURALLY_SIGNIFICANT
            """;
    private static final String NODE_TEXT_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            RETURN node.code AS code, node.name AS name, node.value AS value
            """;
    private final FunctionModelBuilder functionModelBuilder = new FunctionModelBuilder();
    private final ModelReturnInfluenceAnalyzer modelReturnInfluenceAnalyzer = new ModelReturnInfluenceAnalyzer();
    private final ModelStructuralSignificanceAnalyzer modelStructuralSignificanceAnalyzer =
            new ModelStructuralSignificanceAnalyzer();
    private final DeadCodeAnalyzer deadCodeAnalyzer = new DeadCodeAnalyzer();
    private final AlgoGraphBuilder algoGraphBuilder = new AlgoGraphBuilder();

    public List<String> collectMarkedCodes() {
        try (Driver driver = GraphDatabase.driver(URI, AuthTokens.basic(USER, PASSWORD));
             Session session = driver.session(SessionConfig.forDatabase(DATABASE))) {
            return collectMarkedCodes(session);
        }
    }

    public List<String> collectMarkedCodes(Session session) {
        List<String> markedCodes = session.executeWrite(tx -> {
            tx.run(CLEAR_INFLUENCE_QUERY).consume();
            tx.run(CLEAR_STRUCTURAL_QUERY).consume();
            tx.run(CLEAR_DEAD_CODE_QUERY).consume();

            List<FunctionModel> models = functionModelBuilder.buildAll(tx);
            List<String> collectedCodes = new ArrayList<>();

            for (FunctionModel model : models) {
                ModelAnalysisResult semanticResult = modelReturnInfluenceAnalyzer.analyzeDetailed(model);
                Set<String> structuralNodeIds = modelStructuralSignificanceAnalyzer.analyze(
                        model,
                        semanticResult.significantModelNodeIds()
                );

                markNodes(tx, semanticResult.significantNodeIds(), MARK_INFLUENCE_QUERY);
                markNodes(tx, structuralNodeIds, MARK_STRUCTURAL_QUERY);
                deadCodeAnalyzer.analyze(tx, model, semanticResult.significantNodeIds(), structuralNodeIds);

                collectedCodes.addAll(resolveMarkedCodes(tx, model, semanticResult));
            }

            algoGraphBuilder.rebuild(tx);

            return collectedCodes;
        });

        return markedCodes;
    }

    private void markNodes(TransactionContext tx, Set<String> nodeIds, String query) {
        for (String nodeId : nodeIds) {
            tx.run(query, Values.parameters("nodeId", nodeId)).consume();
        }
    }

    private List<String> resolveMarkedCodes(
            TransactionContext tx,
            FunctionModel model,
            ModelAnalysisResult result
    ) {
        List<String> codes = new ArrayList<>();
        for (String nodeId : result.significantNodeIds()) {
            ProgramNode modelNode = model.findByCpgNodeId(nodeId).orElse(null);
            if (modelNode != null
                    && modelNode.kind() == NodeKind.TRANSFER
                    && modelNode.defs().stream().anyMatch(entity -> entity.kind() == analysismodel.EntityKind.RETURN_SLOT)) {
                continue;
            }

            String text = resolveNodeText(tx, nodeId);
            if (text != null && !text.isBlank()) {
                codes.add(text.strip());
            }
        }

        return codes;
    }

    private String resolveNodeText(TransactionContext tx, String nodeId) {
        List<Record> records = tx.run(NODE_TEXT_QUERY, Values.parameters("nodeId", nodeId)).list();
        if (records.isEmpty()) {
            return null;
        }

        Record record = records.get(0);
        return firstNonBlank(
                nullableString(record, "code"),
                nullableString(record, "name"),
                nullableString(record, "value")
        );
    }

    private String nullableString(Record record, String key) {
        Value value = record.get(key);
        return value == null || value.isNull() ? null : value.asString();
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
