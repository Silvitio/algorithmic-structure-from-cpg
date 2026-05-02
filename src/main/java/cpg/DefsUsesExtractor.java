package cpg;

import analysismodel.Entity;
import org.neo4j.driver.Record;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DefsUsesExtractor {
    private static final String NODE_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            RETURN
              elementId(node) AS nodeId,
              labels(node) AS labels,
              node.name AS name,
              node.code AS code,
              node.value AS value,
              node.operatorCode AS operatorCode
            """;
    private static final String AST_CHILDREN_QUERY = """
            MATCH (node)-[astRel:AST]->(child)
            WHERE elementId(node) = $nodeId
            RETURN
              astRel.index AS childIndex,
              elementId(child) AS childNodeId
            ORDER BY childIndex, childNodeId
            """;
    private static final String SUBSCRIPT_BASE_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            OPTIONAL MATCH (node)-[:BASE]->(base)
            WITH node, base
            OPTIONAL MATCH (node)-[:ARRAY_EXPRESSION]->(arrayExpr)
            RETURN coalesce(elementId(base), elementId(arrayExpr)) AS baseNodeId
            """;

    public ExtractionState newState() {
        return new ExtractionState();
    }

    public Set<Entity> collectReadEntities(TransactionContext tx, String nodeId, ExtractionState state) {
        return collectReadEntities(
                tx,
                nodeId,
                state.nodeCache(),
                state.astChildrenCache(),
                state.subscriptBaseCache(),
                new LinkedHashSet<>()
        );
    }

    public Set<Entity> collectWrittenEntities(TransactionContext tx, String nodeId, ExtractionState state) {
        return collectWrittenEntities(
                tx,
                nodeId,
                state.nodeCache(),
                state.astChildrenCache(),
                state.subscriptBaseCache(),
                new LinkedHashSet<>()
        );
    }

    public Set<Entity> collectScanfAddressingEntities(TransactionContext tx, String nodeId, ExtractionState state) {
        return collectScanfAddressingEntities(
                tx,
                nodeId,
                state.nodeCache(),
                state.astChildrenCache(),
                state.subscriptBaseCache(),
                new LinkedHashSet<>()
        );
    }

    public Set<Entity> collectWriteTargetUses(TransactionContext tx, String nodeId, ExtractionState state) {
        return collectWriteTargetUses(
                tx,
                nodeId,
                state.nodeCache(),
                state.astChildrenCache(),
                state.subscriptBaseCache(),
                new LinkedHashSet<>()
        );
    }

    public Set<Entity> collectArraySummaryEntities(TransactionContext tx, String nodeId, ExtractionState state) {
        return collectArraySummaryEntities(
                tx,
                nodeId,
                state.nodeCache(),
                state.astChildrenCache(),
                state.subscriptBaseCache(),
                new LinkedHashSet<>()
        );
    }

    public Set<Entity> declarationEntities(
            TransactionContext tx,
            String declarationNodeId,
            String declarationName,
            ExtractionState state
    ) {
        if (declarationName == null || declarationName.isBlank()) {
            return Set.of();
        }

        Set<Entity> entities = new LinkedHashSet<>();
        entities.add(Entity.variable(declarationName));
        if (isArrayDeclaration(tx, declarationNodeId, state)) {
            entities.add(Entity.arraySummary(arraySummaryEntityName(declarationName)));
        }
        return entities;
    }

    public String arraySummaryEntityName(String entityName) {
        if (entityName == null || entityName.isBlank()) {
            return entityName;
        }
        return entityName.endsWith("[*]") ? entityName : entityName + "[*]";
    }

    private Set<Entity> collectReadEntities(
            TransactionContext tx,
            String nodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, String> subscriptBaseCache,
            Set<String> visiting
    ) {
        Set<Entity> entities = new LinkedHashSet<>();
        if (nodeId == null || !visiting.add(nodeId)) {
            return entities;
        }

        try {
            NodeInfo nodeInfo = loadNodeInfo(tx, nodeId, nodeCache);
            if (nodeInfo == null) {
                return entities;
            }

            if (nodeInfo.labels().contains("Reference")) {
                String entity = firstNonBlank(nodeInfo.name(), nodeInfo.code());
                if (entity != null && !entity.isBlank()) {
                    entities.add(Entity.variable(entity));
                }
                return entities;
            }

            if (isSubscriptExpression(nodeInfo.labels())) {
                String baseNodeId = loadSubscriptBase(tx, nodeId, subscriptBaseCache);
                if (baseNodeId != null) {
                    entities.addAll(collectReadEntities(
                            tx,
                            baseNodeId,
                            nodeCache,
                            astChildrenCache,
                            subscriptBaseCache,
                            visiting
                    ));
                }
                entities.addAll(collectArraySummaryEntities(
                        tx,
                        nodeId,
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        new LinkedHashSet<>()
                ));
                for (String childNodeId : loadAstChildren(tx, nodeId, astChildrenCache)) {
                    entities.addAll(collectReadEntities(
                            tx,
                            childNodeId,
                            nodeCache,
                            astChildrenCache,
                            subscriptBaseCache,
                            visiting
                    ));
                }
                return entities;
            }

            for (String childNodeId : loadAstChildren(tx, nodeId, astChildrenCache)) {
                entities.addAll(collectReadEntities(
                        tx,
                        childNodeId,
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        visiting
                ));
            }

            return entities;
        } finally {
            visiting.remove(nodeId);
        }
    }

    private Set<Entity> collectWrittenEntities(
            TransactionContext tx,
            String nodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, String> subscriptBaseCache,
            Set<String> visiting
    ) {
        Set<Entity> entities = new LinkedHashSet<>();
        if (nodeId == null || !visiting.add(nodeId)) {
            return entities;
        }

        try {
            NodeInfo nodeInfo = loadNodeInfo(tx, nodeId, nodeCache);
            if (nodeInfo == null) {
                return entities;
            }

            if (nodeInfo.labels().contains("Reference")) {
                String entity = firstNonBlank(nodeInfo.name(), nodeInfo.code());
                if (entity != null && !entity.isBlank()) {
                    entities.add(Entity.variable(entity));
                }
                return entities;
            }

            if (isSubscriptExpression(nodeInfo.labels())) {
                entities.addAll(collectArraySummaryEntities(
                        tx,
                        nodeId,
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        new LinkedHashSet<>()
                ));
                return entities;
            }

            for (String childNodeId : loadAstChildren(tx, nodeId, astChildrenCache)) {
                entities.addAll(collectWrittenEntities(
                        tx,
                        childNodeId,
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        visiting
                ));
            }

            return entities;
        } finally {
            visiting.remove(nodeId);
        }
    }

    private Set<Entity> collectArraySummaryEntities(
            TransactionContext tx,
            String nodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, String> subscriptBaseCache,
            Set<String> visiting
    ) {
        Set<Entity> entities = new LinkedHashSet<>();
        if (nodeId == null || !visiting.add(nodeId)) {
            return entities;
        }

        try {
            NodeInfo nodeInfo = loadNodeInfo(tx, nodeId, nodeCache);
            if (nodeInfo == null) {
                return entities;
            }

            if (nodeInfo.labels().contains("Reference")) {
                String entity = firstNonBlank(nodeInfo.name(), nodeInfo.code());
                if (entity != null && !entity.isBlank()) {
                    entities.add(Entity.arraySummary(arraySummaryEntityName(entity)));
                }
                return entities;
            }

            if (isSubscriptExpression(nodeInfo.labels())) {
                String baseNodeId = loadSubscriptBase(tx, nodeId, subscriptBaseCache);
                if (baseNodeId != null) {
                    entities.addAll(collectArraySummaryEntities(
                            tx,
                            baseNodeId,
                            nodeCache,
                            astChildrenCache,
                            subscriptBaseCache,
                            visiting
                    ));
                }
                return entities;
            }

            for (String childNodeId : loadAstChildren(tx, nodeId, astChildrenCache)) {
                entities.addAll(collectArraySummaryEntities(
                        tx,
                        childNodeId,
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        visiting
                ));
            }

            return entities;
        } finally {
            visiting.remove(nodeId);
        }
    }

    private Set<Entity> collectScanfAddressingEntities(
            TransactionContext tx,
            String nodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, String> subscriptBaseCache,
            Set<String> visiting
    ) {
        Set<Entity> entities = new LinkedHashSet<>();
        if (nodeId == null || !visiting.add(nodeId)) {
            return entities;
        }

        try {
            NodeInfo nodeInfo = loadNodeInfo(tx, nodeId, nodeCache);
            if (nodeInfo == null) {
                return entities;
            }

            if (nodeInfo.labels().contains("Reference")) {
                return entities;
            }

            if ((nodeInfo.labels().contains("UnaryOperator") || nodeInfo.labels().contains("UnaryOp"))
                    && "&".equals(normalizedOperator(nodeInfo.operatorCode()))) {
                List<String> children = loadAstChildren(tx, nodeId, astChildrenCache);
                if (!children.isEmpty()) {
                    return collectScanfAddressingEntities(
                            tx,
                            children.get(0),
                            nodeCache,
                            astChildrenCache,
                            subscriptBaseCache,
                            visiting
                    );
                }
                return entities;
            }

            if (isSubscriptExpression(nodeInfo.labels())) {
                List<String> children = loadAstChildren(tx, nodeId, astChildrenCache);
                if (!children.isEmpty()) {
                    entities.addAll(collectScanfAddressingEntities(
                            tx,
                            children.get(0),
                            nodeCache,
                            astChildrenCache,
                            subscriptBaseCache,
                            visiting
                    ));
                }
                if (children.size() >= 2) {
                    entities.addAll(collectReadEntities(
                            tx,
                            children.get(1),
                            nodeCache,
                            astChildrenCache,
                            subscriptBaseCache,
                            new LinkedHashSet<>()
                    ));
                }
                return entities;
            }

            for (String childNodeId : loadAstChildren(tx, nodeId, astChildrenCache)) {
                entities.addAll(collectScanfAddressingEntities(
                        tx,
                        childNodeId,
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        visiting
                ));
            }

            return entities;
        } finally {
            visiting.remove(nodeId);
        }
    }

    private Set<Entity> collectWriteTargetUses(
            TransactionContext tx,
            String nodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, String> subscriptBaseCache,
            Set<String> visiting
    ) {
        Set<Entity> entities = new LinkedHashSet<>();
        if (nodeId == null || !visiting.add(nodeId)) {
            return entities;
        }

        try {
            NodeInfo nodeInfo = loadNodeInfo(tx, nodeId, nodeCache);
            if (nodeInfo == null) {
                return entities;
            }

            if (nodeInfo.labels().contains("Reference")) {
                return entities;
            }

            if (isSubscriptExpression(nodeInfo.labels())) {
                List<String> children = loadAstChildren(tx, nodeId, astChildrenCache);
                if (children.size() >= 2) {
                    entities.addAll(collectReadEntities(
                            tx,
                            children.get(1),
                            nodeCache,
                            astChildrenCache,
                            subscriptBaseCache,
                            new LinkedHashSet<>()
                    ));
                }
                if (!children.isEmpty()) {
                    entities.addAll(collectWriteTargetUses(
                            tx,
                            children.get(0),
                            nodeCache,
                            astChildrenCache,
                            subscriptBaseCache,
                            visiting
                    ));
                }
                return entities;
            }

            for (String childNodeId : loadAstChildren(tx, nodeId, astChildrenCache)) {
                entities.addAll(collectWriteTargetUses(
                        tx,
                        childNodeId,
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        visiting
                ));
            }

            return entities;
        } finally {
            visiting.remove(nodeId);
        }
    }

    private NodeInfo loadNodeInfo(
            TransactionContext tx,
            String nodeId,
            Map<String, NodeInfo> nodeCache
    ) {
        if (nodeId == null) {
            return null;
        }
        if (nodeCache.containsKey(nodeId)) {
            return nodeCache.get(nodeId);
        }

        List<Record> records = tx.run(NODE_QUERY, Values.parameters("nodeId", nodeId)).list();
        if (records.isEmpty()) {
            nodeCache.put(nodeId, null);
            return null;
        }

        Record record = records.get(0);
        NodeInfo nodeInfo = new NodeInfo(
                nodeId,
                record.get("labels").asList(Value::asString),
                getNullableString(record, "name"),
                getNullableString(record, "code"),
                getNullableString(record, "value"),
                getNullableString(record, "operatorCode")
        );
        nodeCache.put(nodeId, nodeInfo);
        return nodeInfo;
    }

    private List<String> loadAstChildren(
            TransactionContext tx,
            String nodeId,
            Map<String, List<String>> astChildrenCache
    ) {
        if (nodeId == null) {
            return List.of();
        }
        if (astChildrenCache.containsKey(nodeId)) {
            return astChildrenCache.get(nodeId);
        }

        List<String> children = tx.run(AST_CHILDREN_QUERY, Values.parameters("nodeId", nodeId)).list(record ->
                record.get("childNodeId").asString()
        );
        astChildrenCache.put(nodeId, children);
        return children;
    }

    private String loadSubscriptBase(
            TransactionContext tx,
            String nodeId,
            Map<String, String> subscriptBaseCache
    ) {
        if (nodeId == null) {
            return null;
        }
        if (subscriptBaseCache.containsKey(nodeId)) {
            return subscriptBaseCache.get(nodeId);
        }

        Record record = tx.run(SUBSCRIPT_BASE_QUERY, Values.parameters("nodeId", nodeId)).single();
        String baseNodeId = getNullableString(record, "baseNodeId");
        subscriptBaseCache.put(nodeId, baseNodeId);
        return baseNodeId;
    }

    private boolean isSubscriptExpression(List<String> labels) {
        return labels.contains("SubscriptExpression")
                || labels.contains("ArraySubscriptExpression")
                || labels.contains("ArraySubscriptionExpression");
    }

    private boolean isArrayDeclaration(
            TransactionContext tx,
            String declarationNodeId,
            ExtractionState state
    ) {
        if (declarationNodeId == null) {
            return false;
        }

        NodeInfo nodeInfo = loadNodeInfo(tx, declarationNodeId, state.nodeCache());
        if (nodeInfo == null) {
            return false;
        }

        String code = firstNonBlank(nodeInfo.code(), nodeInfo.name());
        return code != null && code.contains("[") && code.contains("]");
    }

    private String normalizedOperator(String operatorCode) {
        return operatorCode == null || operatorCode.isBlank() ? "=" : operatorCode;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String getNullableString(Record record, String key) {
        Value value = record.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    public record NodeInfo(
            String nodeId,
            List<String> labels,
            String name,
            String code,
            String value,
            String operatorCode
    ) {
    }

    public static final class ExtractionState {
        private final Map<String, NodeInfo> nodeCache = new HashMap<>();
        private final Map<String, List<String>> astChildrenCache = new HashMap<>();
        private final Map<String, String> subscriptBaseCache = new HashMap<>();

        public Map<String, NodeInfo> nodeCache() {
            return nodeCache;
        }

        public Map<String, List<String>> astChildrenCache() {
            return astChildrenCache;
        }

        public Map<String, String> subscriptBaseCache() {
            return subscriptBaseCache;
        }
    }
}
