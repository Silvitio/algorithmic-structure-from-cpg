package normalizer;

import model.ExpressionNode;
import model.StatementNode;
import model.expression.ArrayAccessExpressionNode;
import model.expression.BinaryExpressionNode;
import model.expression.ConstantExpressionNode;
import model.expression.FunctionCallExpressionNode;
import model.expression.IdentifierExpressionNode;
import model.expression.TernaryExpressionNode;
import model.expression.UnaryExpressionNode;
import model.function.FunctionNode;
import model.statement.AssignmentNode;
import model.statement.BlockNode;
import model.statement.ExpressionStatementNode;
import model.statement.IfNode;
import model.statement.ReturnNode;
import model.statement.WhileNode;

import java.util.ArrayList;
import java.util.List;

public final class AstNormalizer {
    public FunctionNode normalize(FunctionNode functionNode) {
        return new FunctionNode(
                functionNode.sourceNodeId(),
                functionNode.name(),
                functionNode.returnType(),
                functionNode.parameters(),
                normalizeBlock(functionNode.body())
        );
    }

    private BlockNode normalizeBlock(BlockNode blockNode) {
        List<StatementNode> normalizedStatements = new ArrayList<>();
        for (StatementNode statement : blockNode.statements()) {
            StatementNode normalizedStatement = normalizeStatement(statement);
            if (normalizedStatement != null) {
                if (isRedundantConsecutiveAssignment(normalizedStatements, normalizedStatement)) {
                    continue;
                }
                normalizedStatements.add(normalizedStatement);
            }
        }

        return new BlockNode(blockNode.sourceNodeId(), normalizedStatements);
    }

    private StatementNode normalizeStatement(StatementNode statement) {
        if (statement instanceof AssignmentNode assignmentNode) {
            return isSelfAssignment(assignmentNode) ? null : assignmentNode;
        }
        if (statement instanceof BlockNode blockNode) {
            return normalizeBlock(blockNode);
        }
        if (statement instanceof IfNode ifNode) {
            StatementNode normalizedThenBranch = normalizeOptionalStatement(ifNode.thenBranch());
            StatementNode normalizedElseBranch = normalizeOptionalStatement(ifNode.elseBranch());

            return new IfNode(
                    ifNode.sourceNodeId(),
                    ifNode.condition(),
                    normalizedThenBranch,
                    normalizedElseBranch
            );
        }
        if (statement instanceof WhileNode whileNode) {
            return new WhileNode(
                    whileNode.sourceNodeId(),
                    whileNode.condition(),
                    normalizeOptionalStatement(whileNode.body())
            );
        }
        if (statement instanceof ReturnNode || statement instanceof ExpressionStatementNode) {
            return statement;
        }

        return statement;
    }

    private StatementNode normalizeOptionalStatement(StatementNode statement) {
        if (statement == null) {
            return null;
        }

        return normalizeStatement(statement);
    }

    private boolean isSelfAssignment(AssignmentNode assignmentNode) {
        return "=".equals(assignmentNode.operator())
                && areEquivalent(assignmentNode.target(), assignmentNode.value());
    }

    private boolean isRedundantConsecutiveAssignment(
            List<StatementNode> normalizedStatements,
            StatementNode normalizedStatement
    ) {
        if (normalizedStatements.isEmpty()) {
            return false;
        }
        if (!(normalizedStatement instanceof AssignmentNode currentAssignment)) {
            return false;
        }
        StatementNode previousStatement = normalizedStatements.get(normalizedStatements.size() - 1);
        if (!(previousStatement instanceof AssignmentNode previousAssignment)) {
            return false;
        }

        return "=".equals(currentAssignment.operator())
                && "=".equals(previousAssignment.operator())
                && areEquivalent(previousAssignment.target(), currentAssignment.target())
                && areEquivalent(previousAssignment.value(), currentAssignment.value());
    }

    private boolean areEquivalent(ExpressionNode left, ExpressionNode right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (!left.getClass().equals(right.getClass())) {
            return false;
        }
        if (left instanceof IdentifierExpressionNode leftIdentifier
                && right instanceof IdentifierExpressionNode rightIdentifier) {
            return leftIdentifier.name().equals(rightIdentifier.name());
        }
        if (left instanceof ConstantExpressionNode leftConstant
                && right instanceof ConstantExpressionNode rightConstant) {
            return leftConstant.value().equals(rightConstant.value());
        }
        if (left instanceof UnaryExpressionNode leftUnary
                && right instanceof UnaryExpressionNode rightUnary) {
            return leftUnary.operator().equals(rightUnary.operator())
                    && areEquivalent(leftUnary.operand(), rightUnary.operand());
        }
        if (left instanceof BinaryExpressionNode leftBinary
                && right instanceof BinaryExpressionNode rightBinary) {
            return leftBinary.operator().equals(rightBinary.operator())
                    && areEquivalent(leftBinary.left(), rightBinary.left())
                    && areEquivalent(leftBinary.right(), rightBinary.right());
        }
        if (left instanceof TernaryExpressionNode leftTernary
                && right instanceof TernaryExpressionNode rightTernary) {
            return areEquivalent(leftTernary.condition(), rightTernary.condition())
                    && areEquivalent(leftTernary.thenExpression(), rightTernary.thenExpression())
                    && areEquivalent(leftTernary.elseExpression(), rightTernary.elseExpression());
        }
        if (left instanceof ArrayAccessExpressionNode leftArray
                && right instanceof ArrayAccessExpressionNode rightArray) {
            return areEquivalent(leftArray.array(), rightArray.array())
                    && areEquivalent(leftArray.index(), rightArray.index());
        }
        if (left instanceof FunctionCallExpressionNode leftCall
                && right instanceof FunctionCallExpressionNode rightCall) {
            if (!areEquivalent(leftCall.function(), rightCall.function())) {
                return false;
            }
            if (leftCall.arguments().size() != rightCall.arguments().size()) {
                return false;
            }
            for (int index = 0; index < leftCall.arguments().size(); index++) {
                if (!areEquivalent(leftCall.arguments().get(index), rightCall.arguments().get(index))) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }
}
