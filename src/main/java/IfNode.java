import java.util.Objects;

public final class IfNode implements CodeNode {
    private final String cpgNodeId;
    private final String code;
    private final String conditionCode;
    private final int statementIndex;
    private final SequenceNode thenBranch;
    private final CodeNode elseBranch;

    public IfNode(
            String cpgNodeId,
            String code,
            String conditionCode,
            int statementIndex,
            SequenceNode thenBranch,
            CodeNode elseBranch
    ) {
        this.cpgNodeId = Objects.requireNonNull(cpgNodeId);
        this.code = Objects.requireNonNull(code);
        this.conditionCode = conditionCode;
        this.statementIndex = statementIndex;
        this.thenBranch = Objects.requireNonNull(thenBranch);
        this.elseBranch = elseBranch;
    }

    public String cpgNodeId() {
        return cpgNodeId;
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

    public SequenceNode thenBranch() {
        return thenBranch;
    }

    public CodeNode elseBranch() {
        return elseBranch;
    }
}
