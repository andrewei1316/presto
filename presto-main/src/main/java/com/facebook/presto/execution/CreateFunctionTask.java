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
package com.facebook.presto.execution;

import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.security.AccessControl;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.function.QualifiedFunctionName;
import com.facebook.presto.spi.function.RoutineCharacteristics;
import com.facebook.presto.spi.function.SqlFunctionHandle;
import com.facebook.presto.spi.function.SqlInvokedFunction;
import com.facebook.presto.spi.function.SqlParameter;
import com.facebook.presto.spi.type.TypeSignature;
import com.facebook.presto.sql.analyzer.Analysis;
import com.facebook.presto.sql.analyzer.Analyzer;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.CreateFunction;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.util.concurrent.ListenableFuture;

import javax.inject.Inject;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.metadata.FunctionManager.qualifyFunctionName;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.spi.type.TypeSignature.parseTypeSignature;
import static com.facebook.presto.sql.SqlFormatter.formatSql;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public class CreateFunctionTask
        implements DataDefinitionTask<CreateFunction>
{
    private final SqlParser sqlParser;

    @Inject
    public CreateFunctionTask(SqlParser sqlParser)
    {
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
    }

    @Override
    public String getName()
    {
        return "CREATE FUNCTION";
    }

    @Override
    public String explain(CreateFunction statement, List<Expression> parameters)
    {
        return "CREATE FUNCTION " + statement.getFunctionName();
    }

    @Override
    public ListenableFuture<?> execute(CreateFunction statement, TransactionManager transactionManager, Metadata metadata, AccessControl accessControl, QueryStateMachine stateMachine, List<Expression> parameters)
    {
        Analyzer analyzer = new Analyzer(stateMachine.getSession(), metadata, sqlParser, accessControl, Optional.empty(), parameters, stateMachine.getWarningCollector());
        Analysis analysis = analyzer.analyze(statement);
        if (analysis.getFunctionHandles().values().stream()
                .anyMatch(SqlFunctionHandle.class::isInstance)) {
            throw new PrestoException(NOT_SUPPORTED, "Invoking a dynamically registered function in SQL function body is not supported");
        }

        metadata.getFunctionManager().createFunction(createSqlInvokedFunction(statement), statement.isReplace());
        return immediateFuture(null);
    }

    private SqlInvokedFunction createSqlInvokedFunction(CreateFunction statement)
    {
        QualifiedFunctionName functionName = qualifyFunctionName(statement.getFunctionName());
        List<SqlParameter> parameters = statement.getParameters().stream()
                .map(parameter -> new SqlParameter(parameter.getName().toString().toLowerCase(ENGLISH), parseTypeSignature(parameter.getType())))
                .collect(toImmutableList());
        TypeSignature returnType = parseTypeSignature(statement.getReturnType());
        String description = statement.getComment().orElse("");
        RoutineCharacteristics routineCharacteristics = RoutineCharacteristics.builder()
                .setLanguage(RoutineCharacteristics.Language.valueOf(statement.getCharacteristics().getLanguage().name()))
                .setDeterminism(RoutineCharacteristics.Determinism.valueOf(statement.getCharacteristics().getDeterminism().name()))
                .setNullCallClause(RoutineCharacteristics.NullCallClause.valueOf(statement.getCharacteristics().getNullCallClause().name()))
                .build();
        String body = formatSql(statement.getBody(), Optional.empty());

        return new SqlInvokedFunction(
                functionName,
                parameters,
                returnType,
                description,
                routineCharacteristics,
                body,
                Optional.empty());
    }
}
