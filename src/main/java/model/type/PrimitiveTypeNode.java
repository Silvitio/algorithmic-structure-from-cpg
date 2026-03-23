package model.type;

import model.TypeNode;

public record PrimitiveTypeNode(
        String name
) implements TypeNode {
}
