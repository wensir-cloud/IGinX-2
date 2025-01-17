package cn.edu.tsinghua.iginx.sql.expression;

public class UnaryExpression implements Expression {

    private final Operator operator;
    private final Expression expression;
    private String alias;

    public UnaryExpression(Operator operator, Expression expression) {
        this(operator, expression, "");
    }

    public UnaryExpression(Operator operator, Expression expression, String alias) {
        this.operator = operator;
        this.expression = expression;
        this.alias = alias;
    }

    public Operator getOperator() {
        return operator;
    }

    public Expression getExpression() {
        return expression;
    }

    @Override
    public String getColumnName() {
        return Operator.operatorToString(operator) + " " + expression.getColumnName();
    }

    @Override
    public ExpressionType getType() {
        return ExpressionType.Unary;
    }

    @Override
    public boolean hasAlias() {
        return alias != null && !alias.equals("");
    }

    @Override
    public String getAlias() {
        return alias;
    }
}
