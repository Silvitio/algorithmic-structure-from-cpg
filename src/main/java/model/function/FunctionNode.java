package model.function;

import model.CodeNode;
import model.TypeNode;
import model.statement.BlockNode;

import java.util.List;

public record FunctionNode(
        String sourceNodeId,
        String name,
        TypeNode returnType,
        List<ParameterNode> parameters,
        BlockNode body
) implements CodeNode {
    public FunctionNode {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
