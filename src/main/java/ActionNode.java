import java.util.Objects;

public final class ActionNode implements CodeNode {
    private final String cpgNodeId;
    private final String actionType;
    private final String code;
    private final int statementIndex;

    public ActionNode(String cpgNodeId, String actionType, String code, int statementIndex) {
        this.cpgNodeId = Objects.requireNonNull(cpgNodeId);
        this.actionType = Objects.requireNonNull(actionType);
        this.code = Objects.requireNonNull(code);
        this.statementIndex = statementIndex;
    }

    public String cpgNodeId() {
        return cpgNodeId;
    }

    public String actionType() {
        return actionType;
    }

    public String code() {
        return code;
    }

    public int statementIndex() {
        return statementIndex;
    }

    @Override
    public String toString() {
        return "ActionNode{" +
                "cpgNodeId='" + cpgNodeId + '\'' +
                ", actionType='" + actionType + '\'' +
                ", code='" + code + '\'' +
                ", statementIndex=" + statementIndex +
                '}';
    }
}
