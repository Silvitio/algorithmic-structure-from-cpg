package app;

import cpg.AstTreeBuilder;
import model.ExpressionNode;
import model.StatementNode;
import model.TypeNode;
import model.expression.ArrayAccessExpressionNode;
import model.expression.BinaryExpressionNode;
import model.expression.ConstantExpressionNode;
import model.expression.FunctionCallExpressionNode;
import model.expression.IdentifierExpressionNode;
import model.expression.TernaryExpressionNode;
import model.expression.UnaryExpressionNode;
import model.function.FunctionNode;
import model.function.ParameterNode;
import model.statement.AssignmentNode;
import model.statement.BlockNode;
import model.statement.ExpressionStatementNode;
import model.statement.IfNode;
import model.statement.ReturnNode;
import model.statement.WhileNode;
import model.type.ArrayTypeNode;
import model.type.PointerTypeNode;
import model.type.PrimitiveTypeNode;
import normalizer.AstNormalizer;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

import java.util.List;

public class Main {
    public static void main(String[] args) {
        String uri = "bolt://localhost:17687";
        String user = "neo4j";
        String password = "strongPasswordHere";

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
             Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            AstTreeBuilder treeBuilder = new AstTreeBuilder();
            AstNormalizer astNormalizer = new AstNormalizer();
            List<FunctionNode> functions = treeBuilder.buildFunctions(session).stream()
                    .map(astNormalizer::normalize)
                    .toList();

            System.out.println("Found functions: " + functions.size());
            for (FunctionNode function : functions) {
                System.out.printf(
                        """
                        function=%s
                        sourceNodeId=%s
                        bodySourceNodeId=%s
                        returnType=%s
                        parameters=%d
                        bodyStatements=%d

                        """,
                        function.name(),
                        function.sourceNodeId(),
                        function.body().sourceNodeId(),
                        formatType(function.returnType()),
                        function.parameters().size(),
                        function.body().statements().size()
                );
                printParameters(function.parameters(), "  ");
                printBlock(function.body(), "  ");
                System.out.println();
            }
        }
    }

    private static void printParameters(List<ParameterNode> parameters, String indent) {
        for (ParameterNode parameter : parameters) {
            System.out.printf(
                    "%sParameter name=%s type=%s%n",
                    indent,
                    parameter.name(),
                    formatType(parameter.type())
            );
        }
    }

    private static void printBlock(BlockNode blockNode, String indent) {
        for (StatementNode statement : blockNode.statements()) {
            printStatement(statement, indent);
        }
    }

    private static void printStatement(StatementNode statement, String indent) {
        if (statement instanceof BlockNode blockNode) {
            System.out.printf("%sBlock statements=%d%n", indent, blockNode.statements().size());
            printBlock(blockNode, indent + "  ");
            return;
        }
        if (statement instanceof AssignmentNode assignmentNode) {
            System.out.printf(
                    "%sAssignment operator=%s target=%s value=%s%n",
                    indent,
                    assignmentNode.operator(),
                    formatExpression(assignmentNode.target()),
                    formatExpression(assignmentNode.value())
            );
            return;
        }
        if (statement instanceof ReturnNode returnNode) {
            System.out.printf("%sReturn %s%n", indent, formatExpression(returnNode.expression()));
            return;
        }
        if (statement instanceof ExpressionStatementNode expressionStatementNode) {
            System.out.printf("%sExpressionStatement %s%n", indent, formatExpression(expressionStatementNode.expression()));
            return;
        }
        if (statement instanceof IfNode ifNode) {
            System.out.printf("%sIf condition=%s%n", indent, formatExpression(ifNode.condition()));
            if (ifNode.thenBranch() != null) {
                System.out.printf("%s  Then%n", indent);
                printStatement(ifNode.thenBranch(), indent + "    ");
            }
            if (ifNode.elseBranch() != null) {
                System.out.printf("%s  Else%n", indent);
                printStatement(ifNode.elseBranch(), indent + "    ");
            }
            return;
        }
        if (statement instanceof WhileNode whileNode) {
            System.out.printf("%sWhile condition=%s%n", indent, formatExpression(whileNode.condition()));
            if (whileNode.body() != null) {
                printStatement(whileNode.body(), indent + "  ");
            }
        }
    }

    private static String formatExpression(ExpressionNode expression) {
        if (expression == null) {
            return "<null>";
        }
        if (expression instanceof IdentifierExpressionNode identifierExpressionNode) {
            return identifierExpressionNode.name();
        }
        if (expression instanceof ConstantExpressionNode constantExpressionNode) {
            return constantExpressionNode.value();
        }
        if (expression instanceof UnaryExpressionNode unaryExpressionNode) {
            return unaryExpressionNode.operator() + "(" + formatExpression(unaryExpressionNode.operand()) + ")";
        }
        if (expression instanceof BinaryExpressionNode binaryExpressionNode) {
            return "("
                    + formatExpression(binaryExpressionNode.left())
                    + " "
                    + binaryExpressionNode.operator()
                    + " "
                    + formatExpression(binaryExpressionNode.right())
                    + ")";
        }
        if (expression instanceof TernaryExpressionNode ternaryExpressionNode) {
            return "("
                    + formatExpression(ternaryExpressionNode.condition())
                    + " ? "
                    + formatExpression(ternaryExpressionNode.thenExpression())
                    + " : "
                    + formatExpression(ternaryExpressionNode.elseExpression())
                    + ")";
        }
        if (expression instanceof ArrayAccessExpressionNode arrayAccessExpressionNode) {
            return formatExpression(arrayAccessExpressionNode.array()) + "[" + formatExpression(arrayAccessExpressionNode.index()) + "]";
        }
        if (expression instanceof FunctionCallExpressionNode functionCallExpressionNode) {
            return formatExpression(functionCallExpressionNode.function()) + "("
                    + functionCallExpressionNode.arguments().stream().map(Main::formatExpression).toList()
                    + ")";
        }

        return expression.getClass().getSimpleName();
    }

    private static String formatType(TypeNode typeNode) {
        if (typeNode == null) {
            return "<null>";
        }
        if (typeNode instanceof PrimitiveTypeNode primitiveTypeNode) {
            return primitiveTypeNode.name();
        }
        if (typeNode instanceof PointerTypeNode pointerTypeNode) {
            return formatType(pointerTypeNode.targetType()) + "*";
        }
        if (typeNode instanceof ArrayTypeNode arrayTypeNode) {
            return formatType(arrayTypeNode.elementType()) + "[]";
        }

        return typeNode.getClass().getSimpleName();
    }
}
