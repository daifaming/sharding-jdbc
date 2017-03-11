package com.alibaba.druid.sql.parser;

import com.alibaba.druid.sql.context.InsertSQLContext;
import com.alibaba.druid.sql.context.ItemsToken;
import com.alibaba.druid.sql.context.TableContext;
import com.alibaba.druid.sql.expr.SQLExpr;
import com.alibaba.druid.sql.expr.SQLNumberExpr;
import com.alibaba.druid.sql.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.lexer.DataType;
import com.alibaba.druid.sql.lexer.DefaultKeyword;
import com.alibaba.druid.sql.lexer.Keyword;
import com.alibaba.druid.sql.lexer.Symbol;
import com.dangdang.ddframe.rdb.sharding.api.rule.ShardingRule;
import com.dangdang.ddframe.rdb.sharding.parser.result.router.Condition;
import com.dangdang.ddframe.rdb.sharding.parser.visitor.ParseContext;
import com.dangdang.ddframe.rdb.sharding.util.SQLUtil;
import com.google.common.collect.Sets;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Insert语句解析器.
 *
 * @author zhangliang
 */
@Getter(AccessLevel.PROTECTED)
public abstract class AbstractInsertParser {
    
    private final SQLExprParser exprParser;
    
    private final ShardingRule shardingRule;
    
    private final List<Object> parameters;
    
    private final InsertSQLContext sqlContext;
    
    public AbstractInsertParser(final ShardingRule shardingRule, final List<Object> parameters, final SQLExprParser exprParser) {
        this.exprParser = exprParser;
        this.shardingRule = shardingRule;
        this.parameters = parameters;
        sqlContext = new InsertSQLContext(exprParser.getLexer().getInput());
    }
    
    /**
     * 解析Insert语句.
     * 
     * @return 解析结果
     */
    public final InsertSQLContext parse() {
        exprParser.getLexer().nextToken();
        parseInto();
        Collection<Condition.Column> columns = parseColumns();
        if (exprParser.getLexer().equalToken(DefaultKeyword.SELECT, Symbol.LEFT_PAREN)) {
            throw new UnsupportedOperationException("Cannot support subquery");
        }
        if (getValuesKeywords().contains(exprParser.getLexer().getToken())) {
            parseValues(columns);
        } else if (getCustomizedInsertTokens().contains(exprParser.getLexer().getToken())) {
            parseCustomizedInsert();
        }
        return sqlContext;
    }
    
    protected Set<Keyword> getUnsupportedKeywords() {
        return Collections.emptySet();
    }
    
    private void parseInto() {
        exprParser.getLexer().skipIfEqual(DataType.HINT);
        if (getUnsupportedKeywords().contains(exprParser.getLexer().getToken())) {
            throw new ParserUnsupportedException(exprParser.getLexer().getToken());
        }
        exprParser.getLexer().skipUntil(DefaultKeyword.INTO);
        exprParser.getLexer().nextToken();
        exprParser.parseSingleTable(sqlContext);
        skipBetweenTableAndValues();
    }
    
    private void skipBetweenTableAndValues() {
        while (getSkippedTokensBetweenTableAndValues().contains(exprParser.getLexer().getToken())) {
            exprParser.getLexer().nextToken();
            if (exprParser.getLexer().equalToken(Symbol.LEFT_PAREN)) {
                exprParser.getLexer().skipParentheses();
            }
        }
    }
    
    protected Set<Keyword> getSkippedTokensBetweenTableAndValues() {
        return Collections.emptySet();
    }
    
    private Collection<Condition.Column> parseColumns() {
        Collection<Condition.Column> result = new LinkedList<>();
        Collection<String> autoIncrementColumns = shardingRule.getAutoIncrementColumns(sqlContext.getTables().get(0).getName());
        if (exprParser.getLexer().equalToken(Symbol.LEFT_PAREN)) {
            do {
                exprParser.getLexer().nextToken();
                result.add(getColumn(autoIncrementColumns));
                exprParser.getLexer().nextToken();
            } while (!exprParser.getLexer().equalToken(Symbol.RIGHT_PAREN) && !exprParser.getLexer().equalToken(DataType.EOF));
            ItemsToken itemsToken = new ItemsToken(exprParser.getLexer().getCurrentPosition() - exprParser.getLexer().getLiterals().length());
            for (String each : autoIncrementColumns) {
                itemsToken.getItems().add(each);
                result.add(new Condition.Column(each, sqlContext.getTables().get(0).getName(), true));
            }
            if (!itemsToken.getItems().isEmpty()) {
                sqlContext.getSqlTokens().add(itemsToken);
            }
            exprParser.getLexer().nextToken();
        }
        return result;
    }
    
    protected final Condition.Column getColumn(final Collection<String> autoIncrementColumns) {
        String columnName = SQLUtil.getExactlyValue(exprParser.getLexer().getLiterals());
        if (autoIncrementColumns.contains(columnName)) {
            autoIncrementColumns.remove(columnName);
        }
        return new Condition.Column(columnName, sqlContext.getTables().get(0).getName());
    }
    
    protected Set<Keyword> getValuesKeywords() {
        return Sets.<Keyword>newHashSet(DefaultKeyword.VALUES);
    }
    
    private void parseValues(final Collection<Condition.Column> columns) {
        ParseContext parseContext = getParseContext();
        boolean parsed = false;
        do {
            if (parsed) {
                throw new UnsupportedOperationException("Cannot support multiple insert");
            }
            exprParser.getLexer().nextToken();
            exprParser.getLexer().accept(Symbol.LEFT_PAREN);
            List<SQLExpr> sqlExprs = new LinkedList<>();
            do {
                sqlExprs.add(exprParser.parseExpr());
            } while (exprParser.getLexer().skipIfEqual(Symbol.COMMA));
            ItemsToken itemsToken = new ItemsToken(exprParser.getLexer().getCurrentPosition() - exprParser.getLexer().getLiterals().length());
            int count = 0;
            int parameterCount = 0;
            for (Condition.Column each : columns) {
                if (each.isAutoIncrement()) {
                    Number autoIncrementedValue = (Number) getShardingRule().findTableRule(sqlContext.getTables().get(0).getName()).generateId(each.getColumnName());
                    if (parameters.isEmpty()) {
                        itemsToken.getItems().add(autoIncrementedValue.toString());
                        sqlExprs.add(new SQLNumberExpr(autoIncrementedValue));
                    } else {
                        itemsToken.getItems().add("?");
                        parameters.add(autoIncrementedValue);
                        SQLVariantRefExpr variantRefExpr = new SQLVariantRefExpr("?");
                        variantRefExpr.setValue(autoIncrementedValue);
                        variantRefExpr.setIndex(parameters.size() - 1);
                        sqlExprs.add(variantRefExpr);
                    }
                    sqlContext.getGeneratedKeyContext().getColumns().add(each.getColumnName());
                    sqlContext.getGeneratedKeyContext().putValue(each.getColumnName(), autoIncrementedValue);
                } else if (sqlExprs.get(count) instanceof SQLVariantRefExpr) {
                    SQLVariantRefExpr variantRefExpr = ((SQLVariantRefExpr) sqlExprs.get(count));
                    variantRefExpr.setValue(parameters.get(parameterCount));
                    variantRefExpr.setIndex(parameterCount);
                    parameterCount++;
                }
                parseContext.addCondition(each.getColumnName(), each.getTableName(), Condition.BinaryOperator.EQUAL, sqlExprs.get(count));
                count++;
            }
            if (!itemsToken.getItems().isEmpty()) {
                sqlContext.getSqlTokens().add(itemsToken);
            }
            exprParser.getLexer().accept(Symbol.RIGHT_PAREN);
            parsed = true;
        }
        while (exprParser.getLexer().equalToken(Symbol.COMMA));
        sqlContext.getConditionContexts().add(parseContext.getCurrentConditionContext());
    }
    
    protected final ParseContext getParseContext() {
        ParseContext result = new ParseContext(1);
        result.setShardingRule(shardingRule);
        for (TableContext each : sqlContext.getTables()) {
            result.setCurrentTable(each.getName(), each.getAlias());
        }
        return result;
    }
    
    protected Set<Keyword> getCustomizedInsertTokens() {
        return Collections.emptySet();
    }
    
    protected void parseCustomizedInsert() {
    }
}
