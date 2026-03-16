import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SequenceNode implements CodeNode {
    private final List<CodeNode> children = new ArrayList<>();

    public void addChild(CodeNode child) {
        children.add(Objects.requireNonNull(child));
    }

    public List<CodeNode> children() {
        return List.copyOf(children);
    }

    public int size() {
        return children.size();
    }
}
