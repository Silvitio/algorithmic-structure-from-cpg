import java.util.Objects;

public final class LoopNode implements CodeNode {
    private final String cpgNodeId;
    private final LoopType loopType;
    private final String code;
    private final String conditionCode;
    private final int statementIndex;
    private final SequenceNode initializer;
    private final SequenceNode body;
    private final SequenceNode iteration;

    public LoopNode(
            String cpgNodeId,
            LoopType loopType,
            String code,
            String conditionCode,
            int statementIndex,
            SequenceNode initializer,
            SequenceNode body,
            SequenceNode iteration
    ) {
        this.cpgNodeId = Objects.requireNonNull(cpgNodeId);
        this.loopType = Objects.requireNonNull(loopType);
        this.code = Objects.requireNonNull(code);
        this.conditionCode = conditionCode;
        this.statementIndex = statementIndex;
        this.initializer = initializer;
        this.body = Objects.requireNonNull(body);
        this.iteration = iteration;
    }

    public String cpgNodeId() {
        return cpgNodeId;
    }

    public LoopType loopType() {
        return loopType;
    }

    public String code() {
        return code;
    }

    public String conditionCode() {
        return conditionCode;
    }

    public int statementIndex() {
        return statementIndex;
    }

    public SequenceNode initializer() {
        return initializer;
    }

    public SequenceNode body() {
        return body;
    }

    public SequenceNode iteration() {
        return iteration;
    }
}
