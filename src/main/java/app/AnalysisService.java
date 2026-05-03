package app;

import analysis.ModelReturnInfluenceAnalyzer;
import analysis.ModelReturnInfluenceAnalyzer.ModelAnalysisResult;
import analysis.ModelStructuralSignificanceAnalyzer;
import analysismodel.Entity;
import analysismodel.FunctionModel;
import analysismodel.NodeKind;
import analysismodel.ProgramNode;
import cpg.DefsUsesExtractor;
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
    private static final String DECLARATIONS_QUERY = """
            MATCH (statement)-[declRel:DECLARATIONS]->(declaration:ValueDeclaration)
            WHERE elementId(statement) = $statementNodeId
            RETURN
              elementId(declaration) AS declarationNodeId,
              declaration.name AS declarationName,
              declaration.code AS declarationCode,
              declRel.index AS declarationIndex
            ORDER BY declarationIndex, declarationNodeId
            """;

    private final FunctionModelBuilder functionModelBuilder = new FunctionModelBuilder();
    private final ModelReturnInfluenceAnalyzer modelReturnInfluenceAnalyzer = new ModelReturnInfluenceAnalyzer();
    private final ModelStructuralSignificanceAnalyzer modelStructuralSignificanceAnalyzer =
            new ModelStructuralSignificanceAnalyzer();
    private final DefsUsesExtractor defsUsesExtractor = new DefsUsesExtractor();

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

                collectedCodes.addAll(resolveMarkedCodes(tx, model, semanticResult));
            }

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
        Set<Entity> participatingEntities = collectParticipatingEntities(model, result.significantModelNodeIds());
        DefsUsesExtractor.ExtractionState extractionState = defsUsesExtractor.newState();

        List<String> codes = new ArrayList<>();
        for (String nodeId : result.significantNodeIds()) {
            ProgramNode modelNode = model.findByCpgNodeId(nodeId).orElse(null);
            if (modelNode != null && modelNode.kind() == NodeKind.TRANSFER) {
                continue;
            }

            if (modelNode != null && modelNode.kind() == NodeKind.DECLARATION) {
                codes.addAll(resolveDeclarationCodes(tx, modelNode.cpgNodeId(), participatingEntities, extractionState));
                continue;
            }

            String text = resolveNodeText(tx, nodeId);
            if (text != null && !text.isBlank()) {
                codes.add(text.strip());
            }
        }

        return codes;
    }

    private List<String> resolveDeclarationCodes(
            TransactionContext tx,
            String statementNodeId,
            Set<Entity> participatingEntities,
            DefsUsesExtractor.ExtractionState extractionState
    ) {
        List<String> codes = new ArrayList<>();
        List<Record> declarationRecords = tx.run(
                DECLARATIONS_QUERY,
                Values.parameters("statementNodeId", statementNodeId)
        ).list();

        for (Record declarationRecord : declarationRecords) {
            String declarationNodeId = declarationRecord.get("declarationNodeId").asString();
            String declarationName = nullableString(declarationRecord, "declarationName");
            Set<Entity> declarationEntities =
                    defsUsesExtractor.declarationEntities(tx, declarationNodeId, declarationName, extractionState);
            if (!intersects(declarationEntities, participatingEntities)) {
                continue;
            }

            String declarationCode = nullableString(declarationRecord, "declarationCode");
            if (declarationCode != null && !declarationCode.isBlank()) {
                codes.add(declarationCode.strip());
            }
        }

        return codes;
    }

    private Set<Entity> collectParticipatingEntities(FunctionModel model, Set<String> significantModelNodeIds) {
        Set<Entity> entities = new LinkedHashSet<>();
        for (String nodeId : significantModelNodeIds) {
            model.findByCpgNodeId(nodeId).ifPresent(node -> {
                entities.addAll(node.defs());
                entities.addAll(node.uses());
            });
        }
        return entities;
    }

    private boolean intersects(Set<Entity> left, Set<Entity> right) {
        for (Entity entity : left) {
            if (right.contains(entity)) {
                return true;
            }
        }
        return false;
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
