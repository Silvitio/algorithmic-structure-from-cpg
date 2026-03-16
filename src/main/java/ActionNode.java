import java.util.Objects;

public final class ActionNode implements CodeNode {
    private final String cpgNodeId;
    private final ActionType actionType;
    private final String code;
    private final int statementIndex;
    private final Integer declarationIndex;

    public ActionNode(String cpgNodeId, ActionType actionType, String code, int statementIndex, Integer declarationIndex) {
        this.cpgNodeId = Objects.requireNonNull(cpgNodeId);
        this.actionType = Objects.requireNonNull(actionType);
        this.code = Objects.requireNonNull(code);
        this.statementIndex = statementIndex;
        this.declarationIndex = declarationIndex;
    }

    public String cpgNodeId() {
        return cpgNodeId;
    }

    public ActionType actionType() {
        return actionType;
    }

    public String code() {
        return code;
    }

    public int statementIndex() {
        return statementIndex;
    }

    public Integer declarationIndex() {
        return declarationIndex;
    }

    @Override
    public String toString() {
        return "ActionNode{" +
                "cpgNodeId='" + cpgNodeId + '\'' +
                ", actionType='" + actionType + '\'' +
                ", code='" + code + '\'' +
                ", statementIndex=" + statementIndex +
                ", declarationIndex=" + declarationIndex +
                '}';
    }
}
