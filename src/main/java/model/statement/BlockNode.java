package model.statement;

import model.StatementNode;

import java.util.List;

public record BlockNode(
        String sourceNodeId,
        List<StatementNode> statements
) implements StatementNode {
    public BlockNode {
        statements = statements == null ? List.of() : List.copyOf(statements);
    }
}
