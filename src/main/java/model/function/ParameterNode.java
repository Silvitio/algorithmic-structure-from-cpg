package model.function;

import model.CodeNode;
import model.TypeNode;

public record ParameterNode(
        String sourceNodeId,
        String name,
        TypeNode type
) implements CodeNode {
}
