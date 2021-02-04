/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.connector.CatalogName;
import io.trino.connector.MockConnectorColumnHandle;
import io.trino.connector.MockConnectorFactory;
import io.trino.connector.MockConnectorTableHandle;
import io.trino.cost.PlanNodeStatsEstimate;
import io.trino.metadata.TableHandle;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.JoinApplicationResult;
import io.trino.spi.connector.JoinCondition;
import io.trino.spi.connector.JoinType;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.expression.Variable;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.NullableValue;
import io.trino.spi.predicate.TupleDomain;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.iterative.rule.test.RuleAssert;
import io.trino.sql.planner.iterative.rule.test.RuleTester;
import io.trino.sql.planner.plan.JoinNode;
import io.trino.sql.planner.plan.PlanNodeId;
import io.trino.sql.planner.plan.TableScanNode;
import io.trino.sql.tree.ArithmeticBinaryExpression;
import io.trino.sql.tree.ComparisonExpression;
import io.trino.sql.tree.GenericLiteral;
import org.assertj.core.api.Assertions;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Predicate;

import static com.google.common.base.Predicates.equalTo;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.spi.predicate.Domain.onlyNull;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.sql.planner.assertions.PlanMatchPattern.anyTree;
import static io.trino.sql.planner.assertions.PlanMatchPattern.tableScan;
import static io.trino.sql.planner.iterative.rule.test.RuleTester.defaultRuleTester;
import static io.trino.sql.planner.plan.JoinNode.Type.FULL;
import static io.trino.sql.planner.plan.JoinNode.Type.INNER;
import static io.trino.sql.planner.plan.JoinNode.Type.LEFT;
import static io.trino.sql.planner.plan.JoinNode.Type.RIGHT;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestPushJoinIntoTableScan
{
    private static final String MOCK_CATALOG = "mock_catalog";
    private static final String SCHEMA = "test_schema";
    private static final String TABLE_A = "test_table_a";
    private static final String TABLE_B = "test_table_b";
    private static final SchemaTableName TABLE_A_SCHEMA_TABLE_NAME = new SchemaTableName(SCHEMA, TABLE_A);
    private static final SchemaTableName TABLE_B_SCHEMA_TABLE_NAME = new SchemaTableName(SCHEMA, TABLE_B);

    private static final TableHandle TABLE_A_HANDLE = createTableHandle(new MockConnectorTableHandle(new SchemaTableName(SCHEMA, TABLE_A)));
    private static final TableHandle TABLE_B_HANDLE = createTableHandle(new MockConnectorTableHandle(new SchemaTableName(SCHEMA, TABLE_B)));

    private static final Session MOCK_SESSION = testSessionBuilder()
            .setCatalog(MOCK_CATALOG)
            .setSchema(SCHEMA)
            .setSystemProperty("join_pushdown", "EAGER")
            .build();

    private static final String COLUMN_A1 = "columna1";
    public static final Variable COLUMN_A1_VARIABLE = new Variable(COLUMN_A1, BIGINT);
    private static final ColumnHandle COLUMN_A1_HANDLE = new MockConnectorColumnHandle(COLUMN_A1, BIGINT);
    private static final String COLUMN_A2 = "columna2";
    private static final ColumnHandle COLUMN_A2_HANDLE = new MockConnectorColumnHandle(COLUMN_A2, BIGINT);
    private static final String COLUMN_B1 = "columnb1";
    public static final Variable COLUMN_B1_VARIABLE = new Variable(COLUMN_B1, BIGINT);
    private static final ColumnHandle COLUMN_B1_HANDLE = new MockConnectorColumnHandle(COLUMN_B1, BIGINT);

    private static final Map<String, ColumnHandle> TABLE_A_ASSIGNMENTS = ImmutableMap.of(
            COLUMN_A1, COLUMN_A1_HANDLE,
            COLUMN_A2, COLUMN_A2_HANDLE);

    private static final Map<String, ColumnHandle> TABLE_B_ASSIGNMENTS = ImmutableMap.of(
            COLUMN_B1, COLUMN_B1_HANDLE);

    private static final List<ColumnMetadata> TABLE_A_COLUMN_METADATA = TABLE_A_ASSIGNMENTS.entrySet().stream()
            .map(entry -> new ColumnMetadata(entry.getKey(), ((MockConnectorColumnHandle) entry.getValue()).getType()))
            .collect(toImmutableList());

    private static final List<ColumnMetadata> TABLE_B_COLUMN_METADATA = TABLE_B_ASSIGNMENTS.entrySet().stream()
            .map(entry -> new ColumnMetadata(entry.getKey(), ((MockConnectorColumnHandle) entry.getValue()).getType()))
            .collect(toImmutableList());

    public static final SchemaTableName JOIN_PUSHDOWN_SCHEMA_TABLE_NAME = new SchemaTableName(SCHEMA, "TABLE_A_JOINED_WITH_B");

    public static final ColumnHandle JOIN_COLUMN_A1_HANDLE = new MockConnectorColumnHandle("join_" + COLUMN_A1, BIGINT);
    public static final ColumnHandle JOIN_COLUMN_A2_HANDLE = new MockConnectorColumnHandle("join_" + COLUMN_A2, BIGINT);
    public static final ColumnHandle JOIN_COLUMN_B1_HANDLE = new MockConnectorColumnHandle("join_" + COLUMN_B1, BIGINT);

    public static final MockConnectorTableHandle JOIN_CONNECTOR_TABLE_HANDLE = new MockConnectorTableHandle(
            JOIN_PUSHDOWN_SCHEMA_TABLE_NAME, TupleDomain.none(), Optional.of(ImmutableList.of(JOIN_COLUMN_A1_HANDLE, JOIN_COLUMN_A2_HANDLE, JOIN_COLUMN_B1_HANDLE)));

    public static final Map<ColumnHandle, ColumnHandle> JOIN_TABLE_A_COLUMN_MAPPING = ImmutableMap.of(
            COLUMN_A1_HANDLE, JOIN_COLUMN_A1_HANDLE,
            COLUMN_A2_HANDLE, JOIN_COLUMN_A2_HANDLE);
    public static final Map<ColumnHandle, ColumnHandle> JOIN_TABLE_B_COLUMN_MAPPING = ImmutableMap.of(
            COLUMN_B1_HANDLE, JOIN_COLUMN_B1_HANDLE);

    public static final List<ColumnMetadata> JOIN_TABLE_COLUMN_METADATA = JOIN_TABLE_A_COLUMN_MAPPING.entrySet().stream()
            .map(entry -> new ColumnMetadata(((MockConnectorColumnHandle) entry.getValue()).getName(), ((MockConnectorColumnHandle) entry.getValue()).getType()))
            .collect(toImmutableList());

    @Test(dataProvider = "testPushJoinIntoTableScanParams")
    public void testPushJoinIntoTableScan(JoinNode.Type joinType, Optional<ComparisonExpression.Operator> filterComparisonOperator)
    {
        try (RuleTester ruleTester = defaultRuleTester()) {
            MockConnectorFactory connectorFactory = createMockConnectorFactory((session, applyJoinType, left, right, joinConditions, leftAssignments, rightAssignments) -> {
                assertThat(((MockConnectorTableHandle) left).getTableName()).isEqualTo(TABLE_A_SCHEMA_TABLE_NAME);
                assertThat(((MockConnectorTableHandle) right).getTableName()).isEqualTo(TABLE_B_SCHEMA_TABLE_NAME);
                Assertions.assertThat(applyJoinType).isEqualTo(toSpiJoinType(joinType));
                JoinCondition.Operator expectedOperator = filterComparisonOperator.map(this::getConditionOperator).orElse(JoinCondition.Operator.EQUAL);
                Assertions.assertThat(joinConditions).containsExactly(new JoinCondition(expectedOperator, COLUMN_A1_VARIABLE, COLUMN_B1_VARIABLE));

                return Optional.of(new JoinApplicationResult<>(
                        JOIN_CONNECTOR_TABLE_HANDLE,
                        JOIN_TABLE_A_COLUMN_MAPPING,
                        JOIN_TABLE_B_COLUMN_MAPPING));
            });

            ruleTester.getQueryRunner().createCatalog(MOCK_CATALOG, connectorFactory, ImmutableMap.of());

            ruleTester.assertThat(new PushJoinIntoTableScan(ruleTester.getMetadata()))
                    .on(p -> {
                        Symbol columnA1Symbol = p.symbol(COLUMN_A1);
                        Symbol columnA2Symbol = p.symbol(COLUMN_A2);
                        Symbol columnB1Symbol = p.symbol(COLUMN_B1);
                        TableScanNode left = p.tableScan(
                                TABLE_A_HANDLE,
                                ImmutableList.of(columnA1Symbol, columnA2Symbol),
                                ImmutableMap.of(
                                        columnA1Symbol, COLUMN_A1_HANDLE,
                                        columnA2Symbol, COLUMN_A2_HANDLE));
                        TableScanNode right = p.tableScan(
                                TABLE_B_HANDLE,
                                ImmutableList.of(columnB1Symbol),
                                ImmutableMap.of(columnB1Symbol, COLUMN_B1_HANDLE));

                        if (filterComparisonOperator.isEmpty()) {
                            return p.join(
                                    joinType,
                                    left,
                                    right,
                                    new JoinNode.EquiJoinClause(columnA1Symbol, columnB1Symbol));
                        }
                        return p.join(
                                joinType,
                                left,
                                right,
                                new ComparisonExpression(filterComparisonOperator.get(), columnA1Symbol.toSymbolReference(), columnB1Symbol.toSymbolReference()));
                    })
                    .withSession(MOCK_SESSION)
                    .matches(
                            tableScan(JOIN_PUSHDOWN_SCHEMA_TABLE_NAME.getTableName()));
        }
    }

    @DataProvider
    private static Object[][] testPushJoinIntoTableScanParams()
    {
        return new Object[][] {
                {INNER, Optional.empty()},
                {INNER, Optional.of(ComparisonExpression.Operator.EQUAL)},
                {INNER, Optional.of(ComparisonExpression.Operator.LESS_THAN)},
                {INNER, Optional.of(ComparisonExpression.Operator.LESS_THAN_OR_EQUAL)},
                {INNER, Optional.of(ComparisonExpression.Operator.GREATER_THAN)},
                {INNER, Optional.of(ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL)},
                {INNER, Optional.of(ComparisonExpression.Operator.NOT_EQUAL)},
                {INNER, Optional.of(ComparisonExpression.Operator.IS_DISTINCT_FROM)},

                {JoinNode.Type.LEFT, Optional.empty()},
                {JoinNode.Type.LEFT, Optional.of(ComparisonExpression.Operator.EQUAL)},
                {JoinNode.Type.LEFT, Optional.of(ComparisonExpression.Operator.LESS_THAN)},
                {JoinNode.Type.LEFT, Optional.of(ComparisonExpression.Operator.LESS_THAN_OR_EQUAL)},
                {JoinNode.Type.LEFT, Optional.of(ComparisonExpression.Operator.GREATER_THAN)},
                {JoinNode.Type.LEFT, Optional.of(ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL)},
                {JoinNode.Type.LEFT, Optional.of(ComparisonExpression.Operator.NOT_EQUAL)},
                {JoinNode.Type.LEFT, Optional.of(ComparisonExpression.Operator.IS_DISTINCT_FROM)},

                {JoinNode.Type.RIGHT, Optional.empty()},
                {JoinNode.Type.RIGHT, Optional.of(ComparisonExpression.Operator.EQUAL)},
                {JoinNode.Type.RIGHT, Optional.of(ComparisonExpression.Operator.LESS_THAN)},
                {JoinNode.Type.RIGHT, Optional.of(ComparisonExpression.Operator.LESS_THAN_OR_EQUAL)},
                {JoinNode.Type.RIGHT, Optional.of(ComparisonExpression.Operator.GREATER_THAN)},
                {JoinNode.Type.RIGHT, Optional.of(ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL)},
                {JoinNode.Type.RIGHT, Optional.of(ComparisonExpression.Operator.NOT_EQUAL)},
                {JoinNode.Type.RIGHT, Optional.of(ComparisonExpression.Operator.IS_DISTINCT_FROM)},

                {JoinNode.Type.FULL, Optional.empty()},
                {JoinNode.Type.FULL, Optional.of(ComparisonExpression.Operator.EQUAL)},
                {JoinNode.Type.FULL, Optional.of(ComparisonExpression.Operator.LESS_THAN)},
                {JoinNode.Type.FULL, Optional.of(ComparisonExpression.Operator.LESS_THAN_OR_EQUAL)},
                {JoinNode.Type.FULL, Optional.of(ComparisonExpression.Operator.GREATER_THAN)},
                {JoinNode.Type.FULL, Optional.of(ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL)},
                {JoinNode.Type.FULL, Optional.of(ComparisonExpression.Operator.NOT_EQUAL)},
                {JoinNode.Type.FULL, Optional.of(ComparisonExpression.Operator.IS_DISTINCT_FROM)},
        };
    }

    @Test
    public void testPushJoinIntoTableScanDoesNotTriggerWithUnsupportedFilter()
    {
        try (RuleTester ruleTester = defaultRuleTester()) {
            MockConnectorFactory connectorFactory = createMockConnectorFactory(
                    (session, applyJoinType, left, right, joinConditions, leftAssignments, rightAssignments) -> {
                        throw new IllegalStateException("applyJoin should not be called!");
                    });

            ruleTester.getQueryRunner().createCatalog(MOCK_CATALOG, connectorFactory, ImmutableMap.of());

            ruleTester.assertThat(new PushJoinIntoTableScan(ruleTester.getMetadata()))
                    .on(p -> {
                        Symbol columnA1Symbol = p.symbol(COLUMN_A1);
                        Symbol columnA2Symbol = p.symbol(COLUMN_A2);
                        Symbol columnB1Symbol = p.symbol(COLUMN_B1);
                        TableScanNode left = p.tableScan(
                                TABLE_A_HANDLE,
                                ImmutableList.of(columnA1Symbol, columnA2Symbol),
                                ImmutableMap.of(
                                        columnA1Symbol, COLUMN_A1_HANDLE,
                                        columnA2Symbol, COLUMN_A2_HANDLE));
                        TableScanNode right = p.tableScan(
                                TABLE_B_HANDLE,
                                ImmutableList.of(columnB1Symbol),
                                ImmutableMap.of(columnB1Symbol, COLUMN_B1_HANDLE));

                        return p.join(
                                INNER,
                                left,
                                right,
                                new ComparisonExpression(
                                        ComparisonExpression.Operator.GREATER_THAN,
                                        new ArithmeticBinaryExpression(ArithmeticBinaryExpression.Operator.MULTIPLY, new GenericLiteral("BIGINT", "44"), columnA1Symbol.toSymbolReference()),
                                        columnB1Symbol.toSymbolReference()));
                    })
                    .withSession(MOCK_SESSION)
                    .doesNotFire();
        }
    }

    @Test
    public void testPushJoinIntoTableScanDoesNotFireForDifferentCatalogs()
    {
        try (RuleTester ruleTester = defaultRuleTester()) {
            MockConnectorFactory connectorFactory = createMockConnectorFactory(
                    (session, applyJoinType, left, right, joinConditions, leftAssignments, rightAssignments) -> {
                        throw new IllegalStateException("applyJoin should not be called!");
                    });

            ruleTester.getQueryRunner().createCatalog(MOCK_CATALOG, connectorFactory, ImmutableMap.of());
            ruleTester.getQueryRunner().createCatalog("another_catalog", "mock", ImmutableMap.of());
            TableHandle tableBHandleAnotherCatalog = createTableHandle(new MockConnectorTableHandle(new SchemaTableName(SCHEMA, TABLE_B)), "another_catalog");

            ruleTester.assertThat(new PushJoinIntoTableScan(ruleTester.getMetadata()))
                    .on(p -> {
                        Symbol columnA1Symbol = p.symbol(COLUMN_A1);
                        Symbol columnA2Symbol = p.symbol(COLUMN_A2);
                        Symbol columnB1Symbol = p.symbol(COLUMN_B1);
                        TableScanNode left = p.tableScan(
                                TABLE_A_HANDLE,
                                ImmutableList.of(columnA1Symbol, columnA2Symbol),
                                ImmutableMap.of(
                                        columnA1Symbol, COLUMN_A1_HANDLE,
                                        columnA2Symbol, COLUMN_A2_HANDLE));
                        TableScanNode right = p.tableScan(
                                tableBHandleAnotherCatalog,
                                ImmutableList.of(columnB1Symbol),
                                ImmutableMap.of(columnB1Symbol, COLUMN_B1_HANDLE));

                        return p.join(
                                INNER,
                                left,
                                right,
                                new JoinNode.EquiJoinClause(columnA1Symbol, columnB1Symbol));
                    })
                    .withSession(MOCK_SESSION)
                    .doesNotFire();
        }
    }

    @Test
    public void testPushJoinIntoTableScanDoesNotFireWhenDisabled()
    {
        Session joinPushDownDisabledSession = Session.builder(MOCK_SESSION)
                .setSystemProperty("join_pushdown", "DISABLED")
                .build();

        try (RuleTester ruleTester = defaultRuleTester()) {
            MockConnectorFactory connectorFactory = createMockConnectorFactory(
                    (session, applyJoinType, left, right, joinConditions, leftAssignments, rightAssignments) -> {
                        throw new IllegalStateException("applyJoin should not be called!");
                    });

            ruleTester.getQueryRunner().createCatalog(MOCK_CATALOG, connectorFactory, ImmutableMap.of());

            ruleTester.assertThat(new PushJoinIntoTableScan(ruleTester.getMetadata()))
                    .on(p -> {
                        Symbol columnA1Symbol = p.symbol(COLUMN_A1);
                        Symbol columnA2Symbol = p.symbol(COLUMN_A2);
                        Symbol columnB1Symbol = p.symbol(COLUMN_B1);
                        TableScanNode left = p.tableScan(
                                TABLE_A_HANDLE,
                                ImmutableList.of(columnA1Symbol, columnA2Symbol),
                                ImmutableMap.of(
                                        columnA1Symbol, COLUMN_A1_HANDLE,
                                        columnA2Symbol, COLUMN_A2_HANDLE));
                        TableScanNode right = p.tableScan(
                                TABLE_B_HANDLE,
                                ImmutableList.of(columnB1Symbol),
                                ImmutableMap.of(columnB1Symbol, COLUMN_B1_HANDLE));

                        return p.join(
                                INNER,
                                left,
                                right,
                                new JoinNode.EquiJoinClause(columnA1Symbol, columnB1Symbol));
                    })
                    .withSession(joinPushDownDisabledSession)
                    .doesNotFire();
        }
    }

    @Test
    public void testPushJoinIntoTableScanDoesNotFireWhenAllPushdownsDisabled()
    {
        Session allPushdownsDisabledSession = Session.builder(MOCK_SESSION)
                .setSystemProperty("allow_pushdown_into_connectors", "false")
                .build();

        try (RuleTester ruleTester = defaultRuleTester()) {
            MockConnectorFactory connectorFactory = createMockConnectorFactory(
                    (session, applyJoinType, left, right, joinConditions, leftAssignments, rightAssignments) -> {
                        throw new IllegalStateException("applyJoin should not be called!");
                    });

            ruleTester.getQueryRunner().createCatalog(MOCK_CATALOG, connectorFactory, ImmutableMap.of());

            ruleTester.assertThat(new PushJoinIntoTableScan(ruleTester.getMetadata()))
                    .on(p -> {
                        Symbol columnA1Symbol = p.symbol(COLUMN_A1);
                        Symbol columnA2Symbol = p.symbol(COLUMN_A2);
                        Symbol columnB1Symbol = p.symbol(COLUMN_B1);
                        TableScanNode left = p.tableScan(
                                TABLE_A_HANDLE,
                                ImmutableList.of(columnA1Symbol, columnA2Symbol),
                                ImmutableMap.of(
                                        columnA1Symbol, COLUMN_A1_HANDLE,
                                        columnA2Symbol, COLUMN_A2_HANDLE));
                        TableScanNode right = p.tableScan(
                                TABLE_B_HANDLE,
                                ImmutableList.of(columnB1Symbol),
                                ImmutableMap.of(columnB1Symbol, COLUMN_B1_HANDLE));

                        return p.join(
                                INNER,
                                left,
                                right,
                                new JoinNode.EquiJoinClause(columnA1Symbol, columnB1Symbol));
                    })
                    .withSession(allPushdownsDisabledSession)
                    .doesNotFire();
        }
    }

    @Test(dataProvider = "testPushJoinIntoTableScanPreservesEnforcedConstraintParams")
    public void testPushJoinIntoTableScanPreservesEnforcedConstraint(JoinNode.Type joinType, TupleDomain<ColumnHandle> leftConstraint, TupleDomain<ColumnHandle> rightConstraint, TupleDomain<Predicate<ColumnHandle>> expectedConstraint)
    {
        try (RuleTester ruleTester = defaultRuleTester()) {
            MockConnectorFactory connectorFactory = createMockConnectorFactory((session, applyJoinType, left, right, joinConditions, leftAssignments, rightAssignments) -> Optional.of(new JoinApplicationResult<>(
                    JOIN_CONNECTOR_TABLE_HANDLE,
                    JOIN_TABLE_A_COLUMN_MAPPING,
                    JOIN_TABLE_B_COLUMN_MAPPING)));

            ruleTester.getQueryRunner().createCatalog(MOCK_CATALOG, connectorFactory, ImmutableMap.of());

            ruleTester.assertThat(new PushJoinIntoTableScan(ruleTester.getMetadata()))
                    .on(p -> {
                        Symbol columnA1Symbol = p.symbol(COLUMN_A1);
                        Symbol columnA2Symbol = p.symbol(COLUMN_A2);
                        Symbol columnB1Symbol = p.symbol(COLUMN_B1);

                        TableScanNode left = p.tableScan(
                                TABLE_A_HANDLE,
                                ImmutableList.of(columnA1Symbol, columnA2Symbol),
                                ImmutableMap.of(
                                        columnA1Symbol, COLUMN_A1_HANDLE,
                                        columnA2Symbol, COLUMN_A2_HANDLE),
                                leftConstraint);
                        TableScanNode right = p.tableScan(
                                TABLE_B_HANDLE,
                                ImmutableList.of(columnB1Symbol),
                                ImmutableMap.of(columnB1Symbol, COLUMN_B1_HANDLE),
                                rightConstraint);

                        return p.join(
                                joinType,
                                left,
                                right,
                                new JoinNode.EquiJoinClause(columnA1Symbol, columnB1Symbol));
                    })
                    .withSession(MOCK_SESSION)
                    .matches(
                            tableScan(
                                    tableHandle -> JOIN_PUSHDOWN_SCHEMA_TABLE_NAME.equals(((MockConnectorTableHandle) tableHandle).getTableName()),
                                    expectedConstraint,
                                    ImmutableMap.of()));
        }
    }

    @DataProvider
    public static Object[][] testPushJoinIntoTableScanPreservesEnforcedConstraintParams()
    {
        Domain columnA1Domain = Domain.multipleValues(BIGINT, List.of(3L));
        Domain columnA2Domain = Domain.multipleValues(BIGINT, List.of(10L, 20L));
        Domain columnB1Domain = Domain.multipleValues(BIGINT, List.of(30L, 40L));
        return new Object[][] {
                {
                        INNER,
                        TupleDomain.withColumnDomains(Map.of(
                                COLUMN_A1_HANDLE, columnA1Domain,
                                COLUMN_A2_HANDLE, columnA2Domain)),
                        TupleDomain.withColumnDomains(Map.of(
                                COLUMN_B1_HANDLE, columnB1Domain)),
                        TupleDomain.withColumnDomains(Map.of(
                                equalTo(JOIN_COLUMN_A1_HANDLE), columnA1Domain,
                                equalTo(JOIN_COLUMN_A2_HANDLE), columnA2Domain,
                                equalTo(JOIN_COLUMN_B1_HANDLE), columnB1Domain))
                },
                {
                        RIGHT,
                        TupleDomain.withColumnDomains(Map.of(
                                COLUMN_A1_HANDLE, columnA1Domain,
                                COLUMN_A2_HANDLE, columnA2Domain)),
                        TupleDomain.withColumnDomains(Map.of(
                                COLUMN_B1_HANDLE, columnB1Domain)),
                        TupleDomain.withColumnDomains(Map.of(
                                equalTo(JOIN_COLUMN_A1_HANDLE), columnA1Domain.union(onlyNull(BIGINT)),
                                equalTo(JOIN_COLUMN_A2_HANDLE), columnA2Domain.union(onlyNull(BIGINT)),
                                equalTo(JOIN_COLUMN_B1_HANDLE), columnB1Domain))
                },
                {
                        LEFT,
                        TupleDomain.withColumnDomains(Map.of(
                                COLUMN_A1_HANDLE, columnA1Domain,
                                COLUMN_A2_HANDLE, columnA2Domain)),
                        TupleDomain.withColumnDomains(Map.of(
                                COLUMN_B1_HANDLE, columnB1Domain)),
                        TupleDomain.withColumnDomains(Map.of(
                                equalTo(JOIN_COLUMN_A1_HANDLE), columnA1Domain,
                                equalTo(JOIN_COLUMN_A2_HANDLE), columnA2Domain,
                                equalTo(JOIN_COLUMN_B1_HANDLE), columnB1Domain.union(onlyNull(BIGINT))))
                },
                {
                        FULL,
                        TupleDomain.withColumnDomains(Map.of(
                                COLUMN_A1_HANDLE, columnA1Domain,
                                COLUMN_A2_HANDLE, columnA2Domain)),
                        TupleDomain.withColumnDomains(Map.of(
                                COLUMN_B1_HANDLE, columnB1Domain)),
                        TupleDomain.withColumnDomains(Map.of(
                                equalTo(JOIN_COLUMN_A1_HANDLE), columnA1Domain.union(onlyNull(BIGINT)),
                                equalTo(JOIN_COLUMN_A2_HANDLE), columnA2Domain.union(onlyNull(BIGINT)),
                                equalTo(JOIN_COLUMN_B1_HANDLE), columnB1Domain.union(onlyNull(BIGINT))))
                }
        };
    }

    @Test
    public void testPushJoinIntoTableDoesNotFireForCrossJoin()
    {
        try (RuleTester ruleTester = defaultRuleTester()) {
            MockConnectorFactory connectorFactory = createMockConnectorFactory(
                    (session, applyJoinType, left, right, joinConditions, leftAssignments, rightAssignments) -> {
                        throw new IllegalStateException("applyJoin should not be called!");
                    });

            ruleTester.getQueryRunner().createCatalog(MOCK_CATALOG, connectorFactory, ImmutableMap.of());

            ruleTester.assertThat(new PushJoinIntoTableScan(ruleTester.getMetadata()))
                    .on(p -> {
                        Symbol columnA1Symbol = p.symbol(COLUMN_A1);
                        Symbol columnA2Symbol = p.symbol(COLUMN_A2);
                        Symbol columnB1Symbol = p.symbol(COLUMN_B1);

                        TableScanNode left = p.tableScan(
                                TABLE_A_HANDLE,
                                ImmutableList.of(columnA1Symbol, columnA2Symbol),
                                ImmutableMap.of(
                                        columnA1Symbol, COLUMN_A1_HANDLE,
                                        columnA2Symbol, COLUMN_A2_HANDLE));
                        TableScanNode right = p.tableScan(
                                TABLE_B_HANDLE,
                                ImmutableList.of(columnB1Symbol),
                                ImmutableMap.of(columnB1Symbol, COLUMN_B1_HANDLE));

                        // cross-join - no criteria
                        return p.join(
                                INNER,
                                left,
                                right);
                    })
                    .withSession(MOCK_SESSION)
                    .doesNotFire();
        }
    }

    @Test
    public void testPushJoinIntoTableRequiresFullColumnHandleMappingInResult()
    {
        try (RuleTester ruleTester = defaultRuleTester()) {
            MockConnectorFactory connectorFactory = createMockConnectorFactory((session, applyJoinType, left, right, joinConditions, leftAssignments, rightAssignments) -> Optional.of(new JoinApplicationResult<>(
                    JOIN_CONNECTOR_TABLE_HANDLE,
                    ImmutableMap.of(COLUMN_A1_HANDLE, JOIN_COLUMN_A1_HANDLE, COLUMN_A2_HANDLE, JOIN_COLUMN_A2_HANDLE),
                    // mapping for COLUMN_B1_HANDLE is missing
                    ImmutableMap.of())));

            ruleTester.getQueryRunner().createCatalog(MOCK_CATALOG, connectorFactory, ImmutableMap.of());

            assertThatThrownBy(() -> {
                ruleTester.assertThat(new PushJoinIntoTableScan(ruleTester.getMetadata()))
                        .on(p -> {
                            Symbol columnA1Symbol = p.symbol(COLUMN_A1);
                            Symbol columnA2Symbol = p.symbol(COLUMN_A2);
                            Symbol columnB1Symbol = p.symbol(COLUMN_B1);

                            TupleDomain<ColumnHandle> leftContraint =
                                    TupleDomain.fromFixedValues(ImmutableMap.of(COLUMN_A2_HANDLE, NullableValue.of(BIGINT, 44L)));
                            TupleDomain<ColumnHandle> rightConstraint =
                                    TupleDomain.fromFixedValues(ImmutableMap.of(COLUMN_B1_HANDLE, NullableValue.of(BIGINT, 45L)));

                            TableScanNode left = p.tableScan(
                                    TABLE_A_HANDLE,
                                    ImmutableList.of(columnA1Symbol, columnA2Symbol),
                                    ImmutableMap.of(
                                            columnA1Symbol, COLUMN_A1_HANDLE,
                                            columnA2Symbol, COLUMN_A2_HANDLE),
                                    leftContraint);
                            TableScanNode right = p.tableScan(
                                    TABLE_B_HANDLE,
                                    ImmutableList.of(columnB1Symbol),
                                    ImmutableMap.of(columnB1Symbol, COLUMN_B1_HANDLE),
                                    rightConstraint);

                            return p.join(
                                    INNER,
                                    left,
                                    right,
                                    new JoinNode.EquiJoinClause(columnA1Symbol, columnB1Symbol));
                        })
                        .withSession(MOCK_SESSION)
                        .matches(anyTree());
            })
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Column handle mappings do not match old column handles");
        }
    }

    @Test(dataProvider = "testAutomaticJoinPushDownParams")
    public void testAutomaticJoinPushDown(OptionalDouble leftRows, OptionalDouble righRows, OptionalDouble joinRows, boolean pushdownExpected)
    {
        Session pushdownAutomaticSession = Session.builder(MOCK_SESSION)
                .setSystemProperty("join_pushdown", "AUTOMATIC")
                .build();

        try (RuleTester ruleTester = defaultRuleTester()) {
            MockConnectorFactory connectorFactory = createMockConnectorFactory((session, applyJoinType, left, right, joinConditions, leftAssignments, rightAssignments) -> {
                assertThat(((MockConnectorTableHandle) left).getTableName()).isEqualTo(TABLE_A_SCHEMA_TABLE_NAME);
                assertThat(((MockConnectorTableHandle) right).getTableName()).isEqualTo(TABLE_B_SCHEMA_TABLE_NAME);
                Assertions.assertThat(applyJoinType).isEqualTo(toSpiJoinType(JoinNode.Type.INNER));
                Assertions.assertThat(joinConditions).containsExactly(new JoinCondition(JoinCondition.Operator.EQUAL, COLUMN_A1_VARIABLE, COLUMN_B1_VARIABLE));

                return Optional.of(new JoinApplicationResult<>(
                        JOIN_CONNECTOR_TABLE_HANDLE,
                        JOIN_TABLE_A_COLUMN_MAPPING,
                        JOIN_TABLE_B_COLUMN_MAPPING));
            });

            ruleTester.getQueryRunner().createCatalog(MOCK_CATALOG, connectorFactory, ImmutableMap.of());

            RuleAssert ruleAssert = ruleTester.assertThat(new PushJoinIntoTableScan(ruleTester.getMetadata()))
                    .overrideStats("left", new PlanNodeStatsEstimate(leftRows.orElse(Double.NaN), ImmutableMap.of()))
                    .overrideStats("right", new PlanNodeStatsEstimate(righRows.orElse(Double.NaN), ImmutableMap.of()))
                    .overrideStats("join", new PlanNodeStatsEstimate(joinRows.orElse(Double.NaN), ImmutableMap.of()))
                    .on(p -> {
                        Symbol columnA1Symbol = p.symbol(COLUMN_A1);
                        Symbol columnA2Symbol = p.symbol(COLUMN_A2);
                        Symbol columnB1Symbol = p.symbol(COLUMN_B1);
                        TableScanNode left = new TableScanNode(
                                new PlanNodeId("left"),
                                TABLE_A_HANDLE,
                                ImmutableList.of(columnA1Symbol, columnA2Symbol),
                                ImmutableMap.of(
                                        columnA1Symbol, COLUMN_A1_HANDLE,
                                        columnA2Symbol, COLUMN_A2_HANDLE),
                                TupleDomain.all(),
                                false);

                        TableScanNode right = new TableScanNode(
                                new PlanNodeId("right"),
                                TABLE_B_HANDLE,
                                ImmutableList.of(columnB1Symbol),
                                ImmutableMap.of(columnB1Symbol, COLUMN_B1_HANDLE),
                                TupleDomain.all(),
                                false);

                        return join(new PlanNodeId("join"), JoinNode.Type.INNER, left, right, new JoinNode.EquiJoinClause(columnA1Symbol, columnB1Symbol));
                    })
                    .withSession(pushdownAutomaticSession);

            if (pushdownExpected) {
                ruleAssert.matches(tableScan(JOIN_PUSHDOWN_SCHEMA_TABLE_NAME.getTableName()));
            }
            else {
                ruleAssert.doesNotFire();
            }
        }
    }

    @DataProvider
    public static Object[][] testAutomaticJoinPushDownParams()
    {
        return new Object[][] {
                {OptionalDouble.of(100), OptionalDouble.of(200), OptionalDouble.of(133), true},
                {OptionalDouble.of(100), OptionalDouble.of(200), OptionalDouble.of(134), false}, // just above output size boundary
                {OptionalDouble.empty(), OptionalDouble.of(200), OptionalDouble.of(250), false},
                {OptionalDouble.of(100), OptionalDouble.empty(), OptionalDouble.of(250), false},
                {OptionalDouble.of(100), OptionalDouble.of(200), OptionalDouble.empty(), false},
                {OptionalDouble.of(100), OptionalDouble.of(200), OptionalDouble.of(301), false}
        };
    }

    @Test(dataProvider = "testJoinPushdownStatsIrrelevantIfPushdownForcedParams")
    public void testJoinPushdownStatsIrrelevantIfPushdownForced(OptionalDouble leftRows, OptionalDouble righRows, OptionalDouble joinRows)
    {
        try (RuleTester ruleTester = defaultRuleTester()) {
            MockConnectorFactory connectorFactory = createMockConnectorFactory((session, applyJoinType, left, right, joinConditions, leftAssignments, rightAssignments) -> {
                assertThat(((MockConnectorTableHandle) left).getTableName()).isEqualTo(TABLE_A_SCHEMA_TABLE_NAME);
                assertThat(((MockConnectorTableHandle) right).getTableName()).isEqualTo(TABLE_B_SCHEMA_TABLE_NAME);
                Assertions.assertThat(applyJoinType).isEqualTo(toSpiJoinType(JoinNode.Type.INNER));
                Assertions.assertThat(joinConditions).containsExactly(new JoinCondition(JoinCondition.Operator.EQUAL, COLUMN_A1_VARIABLE, COLUMN_B1_VARIABLE));

                return Optional.of(new JoinApplicationResult<>(
                        JOIN_CONNECTOR_TABLE_HANDLE,
                        JOIN_TABLE_A_COLUMN_MAPPING,
                        JOIN_TABLE_B_COLUMN_MAPPING));
            });

            ruleTester.getQueryRunner().createCatalog(MOCK_CATALOG, connectorFactory, ImmutableMap.of());

            ruleTester.assertThat(new PushJoinIntoTableScan(ruleTester.getMetadata()))
                    .overrideStats("left", new PlanNodeStatsEstimate(leftRows.orElse(Double.NaN), ImmutableMap.of()))
                    .overrideStats("right", new PlanNodeStatsEstimate(righRows.orElse(Double.NaN), ImmutableMap.of()))
                    .overrideStats("join", new PlanNodeStatsEstimate(joinRows.orElse(Double.NaN), ImmutableMap.of()))
                    .on(p -> {
                        Symbol columnA1Symbol = p.symbol(COLUMN_A1);
                        Symbol columnA2Symbol = p.symbol(COLUMN_A2);
                        Symbol columnB1Symbol = p.symbol(COLUMN_B1);
                        TableScanNode left = new TableScanNode(
                                new PlanNodeId("left"),
                                TABLE_A_HANDLE,
                                ImmutableList.of(columnA1Symbol, columnA2Symbol),
                                ImmutableMap.of(
                                        columnA1Symbol, COLUMN_A1_HANDLE,
                                        columnA2Symbol, COLUMN_A2_HANDLE),
                                TupleDomain.all(),
                                false);

                        TableScanNode right = new TableScanNode(
                                new PlanNodeId("right"),
                                TABLE_B_HANDLE,
                                ImmutableList.of(columnB1Symbol),
                                ImmutableMap.of(columnB1Symbol, COLUMN_B1_HANDLE),
                                TupleDomain.all(),
                                false);

                        return join(new PlanNodeId("join"), JoinNode.Type.INNER, left, right, new JoinNode.EquiJoinClause(columnA1Symbol, columnB1Symbol));
                    })
                    .withSession(MOCK_SESSION)
                    .matches(tableScan(JOIN_PUSHDOWN_SCHEMA_TABLE_NAME.getTableName()));
        }
    }

    @DataProvider
    public static Object[][] testJoinPushdownStatsIrrelevantIfPushdownForcedParams()
    {
        return new Object[][] {
                {OptionalDouble.of(100), OptionalDouble.of(200), OptionalDouble.of(133)},
                {OptionalDouble.of(100), OptionalDouble.of(200), OptionalDouble.of(134)},
                {OptionalDouble.empty(), OptionalDouble.of(200), OptionalDouble.of(250)},
                {OptionalDouble.of(100), OptionalDouble.empty(), OptionalDouble.of(250)},
                {OptionalDouble.of(100), OptionalDouble.of(200), OptionalDouble.empty()},
                {OptionalDouble.of(100), OptionalDouble.of(200), OptionalDouble.of(301)}
        };
    }

    private JoinNode join(PlanNodeId planNodeId, JoinNode.Type joinType, TableScanNode left, TableScanNode right, JoinNode.EquiJoinClause... criteria)
    {
        return new JoinNode(
                planNodeId,
                joinType,
                left,
                right,
                ImmutableList.copyOf(criteria),
                left.getOutputSymbols(),
                right.getOutputSymbols(),
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of(),
                Optional.empty());
    }

    private static TableHandle createTableHandle(ConnectorTableHandle tableHandle)
    {
        return createTableHandle(tableHandle, MOCK_CATALOG);
    }

    private static TableHandle createTableHandle(ConnectorTableHandle tableHandle, String catalog)
    {
        return new TableHandle(
                new CatalogName(catalog),
                tableHandle,
                new ConnectorTransactionHandle() {},
                Optional.empty());
    }

    private MockConnectorFactory createMockConnectorFactory(MockConnectorFactory.ApplyJoin applyJoin)
    {
        return MockConnectorFactory.builder()
                .withListSchemaNames(connectorSession -> ImmutableList.of(SCHEMA))
                .withListTables((connectorSession, schema) -> SCHEMA.equals(schema) ? ImmutableList.of(TABLE_A_SCHEMA_TABLE_NAME, TABLE_B_SCHEMA_TABLE_NAME) : ImmutableList.of())
                .withApplyJoin(applyJoin)
                .withGetColumns(schemaTableName -> {
                    if (schemaTableName.equals(TABLE_A_SCHEMA_TABLE_NAME)) {
                        return TABLE_A_COLUMN_METADATA;
                    }
                    if (schemaTableName.equals(TABLE_B_SCHEMA_TABLE_NAME)) {
                        return TABLE_B_COLUMN_METADATA;
                    }
                    if (schemaTableName.equals(JOIN_PUSHDOWN_SCHEMA_TABLE_NAME)) {
                        return JOIN_TABLE_COLUMN_METADATA;
                    }
                    throw new RuntimeException("Unknown table: " + schemaTableName);
                })
                .build();
    }

    private JoinType toSpiJoinType(JoinNode.Type joinType)
    {
        switch (joinType) {
            case INNER:
                return JoinType.INNER;
            case LEFT:
                return JoinType.LEFT_OUTER;
            case RIGHT:
                return JoinType.RIGHT_OUTER;
            case FULL:
                return JoinType.FULL_OUTER;
            default:
                throw new IllegalArgumentException("Unknown join type: " + joinType);
        }
    }

    private JoinCondition.Operator getConditionOperator(ComparisonExpression.Operator operator)
    {
        switch (operator) {
            case EQUAL:
                return JoinCondition.Operator.EQUAL;
            case NOT_EQUAL:
                return JoinCondition.Operator.NOT_EQUAL;
            case LESS_THAN:
                return JoinCondition.Operator.LESS_THAN;
            case LESS_THAN_OR_EQUAL:
                return JoinCondition.Operator.LESS_THAN_OR_EQUAL;
            case GREATER_THAN:
                return JoinCondition.Operator.GREATER_THAN;
            case GREATER_THAN_OR_EQUAL:
                return JoinCondition.Operator.GREATER_THAN_OR_EQUAL;
            case IS_DISTINCT_FROM:
                return JoinCondition.Operator.IS_DISTINCT_FROM;
            default:
                throw new IllegalArgumentException("Unknown operator: " + operator);
        }
    }
}
