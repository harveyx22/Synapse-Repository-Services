package org.sagebionetworks.table.cluster;

import static org.sagebionetworks.repo.model.table.TableConstants.ROW_ETAG;
import static org.sagebionetworks.repo.model.table.TableConstants.ROW_ID;
import static org.sagebionetworks.repo.model.table.TableConstants.ROW_VERSION;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnMultiValueFunctionQueryFilter;
import org.sagebionetworks.repo.model.table.ColumnSingleValueFilterOperator;
import org.sagebionetworks.repo.model.table.ColumnSingleValueQueryFilter;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.FacetType;
import org.sagebionetworks.repo.model.table.JsonSubColumnModel;
import org.sagebionetworks.repo.model.table.QueryFilter;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.repo.model.table.TableConstants;
import org.sagebionetworks.repo.model.table.TextMatchesQueryFilter;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.sagebionetworks.table.cluster.SQLUtils.TableIndexType;
import org.sagebionetworks.table.cluster.columntranslation.ColumnTranslationReference;
import org.sagebionetworks.table.cluster.columntranslation.SchemaColumnTranslationReference;
import org.sagebionetworks.table.cluster.description.BenefactorDescription;
import org.sagebionetworks.table.cluster.description.ColumnToAdd;
import org.sagebionetworks.table.cluster.description.IndexDescription;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.table.query.ParseException;
import org.sagebionetworks.table.query.TableQueryParser;
import org.sagebionetworks.table.query.model.ActualIdentifier;
import org.sagebionetworks.table.query.model.ArrayFunctionSpecification;
import org.sagebionetworks.table.query.model.ArrayFunctionType;
import org.sagebionetworks.table.query.model.ArrayHasLikePredicate;
import org.sagebionetworks.table.query.model.ArrayHasPredicate;
import org.sagebionetworks.table.query.model.BacktickDelimitedIdentifier;
import org.sagebionetworks.table.query.model.BooleanFactor;
import org.sagebionetworks.table.query.model.BooleanFunctionPredicate;
import org.sagebionetworks.table.query.model.BooleanPredicate;
import org.sagebionetworks.table.query.model.BooleanPrimary;
import org.sagebionetworks.table.query.model.BooleanTerm;
import org.sagebionetworks.table.query.model.BooleanTest;
import org.sagebionetworks.table.query.model.CastSpecification;
import org.sagebionetworks.table.query.model.CastTarget;
import org.sagebionetworks.table.query.model.ColumnList;
import org.sagebionetworks.table.query.model.ColumnName;
import org.sagebionetworks.table.query.model.ColumnNameReference;
import org.sagebionetworks.table.query.model.ColumnReference;
import org.sagebionetworks.table.query.model.CorrelationName;
import org.sagebionetworks.table.query.model.CorrelationSpecification;
import org.sagebionetworks.table.query.model.CurrentUserFunction;
import org.sagebionetworks.table.query.model.DefiningClause;
import org.sagebionetworks.table.query.model.DelimitedIdentifier;
import org.sagebionetworks.table.query.model.DerivedColumn;
import org.sagebionetworks.table.query.model.Element;
import org.sagebionetworks.table.query.model.ExactNumericLiteral;
import org.sagebionetworks.table.query.model.Factor;
import org.sagebionetworks.table.query.model.FromClause;
import org.sagebionetworks.table.query.model.FunctionReturnType;
import org.sagebionetworks.table.query.model.GroupingColumnReference;
import org.sagebionetworks.table.query.model.HasFunctionReturnType;
import org.sagebionetworks.table.query.model.HasPredicate;
import org.sagebionetworks.table.query.model.HasReplaceableChildren;
import org.sagebionetworks.table.query.model.HasSearchCondition;
import org.sagebionetworks.table.query.model.HasSqlContext;
import org.sagebionetworks.table.query.model.Identifier;
import org.sagebionetworks.table.query.model.InValueList;
import org.sagebionetworks.table.query.model.IntervalLiteral;
import org.sagebionetworks.table.query.model.JoinCondition;
import org.sagebionetworks.table.query.model.JoinType;
import org.sagebionetworks.table.query.model.JsonTable;
import org.sagebionetworks.table.query.model.JsonTableColumn;
import org.sagebionetworks.table.query.model.MySqlFunction;
import org.sagebionetworks.table.query.model.MySqlFunctionName;
import org.sagebionetworks.table.query.model.NonJoinQueryExpression;
import org.sagebionetworks.table.query.model.NullPredicate;
import org.sagebionetworks.table.query.model.NumericPrimary;
import org.sagebionetworks.table.query.model.NumericValueExpression;
import org.sagebionetworks.table.query.model.NumericValueFunction;
import org.sagebionetworks.table.query.model.OuterJoinType;
import org.sagebionetworks.table.query.model.Pagination;
import org.sagebionetworks.table.query.model.Predicate;
import org.sagebionetworks.table.query.model.PredicateLeftHandSide;
import org.sagebionetworks.table.query.model.QualifiedJoin;
import org.sagebionetworks.table.query.model.QueryExpression;
import org.sagebionetworks.table.query.model.QuerySpecification;
import org.sagebionetworks.table.query.model.RegularIdentifier;
import org.sagebionetworks.table.query.model.SearchCondition;
import org.sagebionetworks.table.query.model.SelectList;
import org.sagebionetworks.table.query.model.SqlContext;
import org.sagebionetworks.table.query.model.StringOverride;
import org.sagebionetworks.table.query.model.TableExpression;
import org.sagebionetworks.table.query.model.TableName;
import org.sagebionetworks.table.query.model.TableNameCorrelation;
import org.sagebionetworks.table.query.model.TableReference;
import org.sagebionetworks.table.query.model.Term;
import org.sagebionetworks.table.query.model.TextMatchesMySQLPredicate;
import org.sagebionetworks.table.query.model.TextMatchesPredicate;
import org.sagebionetworks.table.query.model.TruthSpecification;
import org.sagebionetworks.table.query.model.TruthValue;
import org.sagebionetworks.table.query.model.UnsignedLiteral;
import org.sagebionetworks.table.query.model.UnsignedNumericLiteral;
import org.sagebionetworks.table.query.model.ValueExpression;
import org.sagebionetworks.table.query.model.ValueExpressionPrimary;
import org.sagebionetworks.table.query.model.WhereClause;
import org.sagebionetworks.table.query.model.WithListElement;
import org.sagebionetworks.table.query.util.ColumnTypeListMappings;
import org.sagebionetworks.table.query.util.SqlElementUtils;
import org.sagebionetworks.util.ValidateArgument;

import com.google.common.collect.Lists;

/**
 * Helper methods to translate user generated queries
 * to queries that run against the actual database.
 *
 */
public class SQLTranslatorUtils {

	private static final String DEFINING_WHERE_CAN_ONLY_BE_USED_WITH_A_CTE = "DEFINING_WHERE can only be used with a common table expression with a single inner query";
	private static final String COLON = ":";
	public static final String BIND_PREFIX = "b";


	/**
	 * Get the list of column IDs that are referenced in the select clause.
	 * 
	 * @param selectList
	 * @param columnTranslationReferenceLookup
	 * @param isAggregate
	 * @return
	 */
	public static List<SelectColumn> getSelectColumns(SelectList selectList, TableAndColumnMapper lookup, boolean isAggregate) {
		ValidateArgument.required(lookup, "ColumnLookup");
		ValidateArgument.required(selectList, "selectList");
		if (selectList.getAsterisk() != null) {
			throw new IllegalStateException("The columns should have been expanded before getting here");
		} 
		List<SelectColumn> selects = Lists.newArrayListWithCapacity(selectList.getColumns().size());
		boolean isAtLeastOneColumnIdNull = false;
		for (DerivedColumn dc : selectList.getColumns()) {
			SelectColumn model = getSelectColumns(dc, lookup);
			selects.add(model);
			if(model.getId() == null){
				isAtLeastOneColumnIdNull = true;
			}
		}
		
		// All columnIds should be null if one column has a null ID or this is an aggregate query.
		if(isAtLeastOneColumnIdNull || isAggregate){
			// clear all columnIds.
			for(SelectColumn select: selects){
				select.setId(null);
			}
		}
		return selects;
	}
	
	/**
	 * Given a DerivedColumn extract all data about both the SelectColumn and ColumnModel.
	 * 
	 * @param derivedColumn
	 * @param columnTranslationReferenceLookup
	 * @return
	 */
	public static SelectColumn getSelectColumns(DerivedColumn derivedColumn, TableAndColumnMapper lookup){
		
		CastTarget castTarget = derivedColumn.getFirstElementOfType(CastTarget.class);
		if(castTarget != null) {
			if(castTarget.getType() != null) {
				return new SelectColumn().setColumnType(castTarget.getType() ).setName(derivedColumn.getDisplayName());
			}
			if(castTarget.getColumnId() != null) {
				ColumnModel cm = lookup.getColumnModel(castTarget.getColumnId().toSql());
				String name = derivedColumn.getAsClause() != null? derivedColumn.getDisplayName(): cm.getName();
				return new SelectColumn().setColumnType(cm.getColumnType()).setName(name).setId(cm.getId());
			}
		}
		
		// Extract data about this column.
		String displayName = derivedColumn.getDisplayName();
		// lookup the column referenced by this select.
		ColumnNameReference referencedColumn = derivedColumn.getReferencedColumn();
		// Select defines the selection
		SelectColumn selectColumn = new SelectColumn();
		selectColumn.setName(displayName);
		
		ColumnTranslationReference translationReference = null;
		if(referencedColumn != null){
			ColumnReference columnReference = derivedColumn.getFirstElementOfType(ColumnReference.class);
			// Does the reference match an actual column name?
			translationReference = lookup.lookupColumnReference(columnReference).orElse(null);
		}
		// Lookup the base type starting only with the column referenced.
		ColumnType columnType = getBaseColulmnType(referencedColumn);
		if(translationReference != null){
			// If we have a column model the base type is defined by it.
			columnType = translationReference.getColumnType();

			// We only set the id on the select column when the display name match the column name.
			if(translationReference.getUserQueryColumnName().equals(displayName)
					//must be a column defined in the schema, not a metadata column
					&& translationReference instanceof SchemaColumnTranslationReference){
				selectColumn.setId(((SchemaColumnTranslationReference) translationReference).getId());
			}
		}
		FunctionReturnType functionReturnType = null;
		// if this is a function it will have a return type
		HasFunctionReturnType hasReturnType = derivedColumn.getFirstElementOfType(HasFunctionReturnType.class);
		if(hasReturnType != null){
			functionReturnType = hasReturnType.getFunctionReturnType();
			if(functionReturnType != null) {
				columnType = functionReturnType.getColumnType(columnType);
			}
		}
		selectColumn.setColumnType(columnType);
		validateSelectColumn(selectColumn, functionReturnType, translationReference, referencedColumn);
		// done
		return selectColumn;
	}
	
	public static void validateSelectColumn(SelectColumn selectColumn, FunctionReturnType functionReturnType,
											ColumnTranslationReference columnTranslationReference, ColumnNameReference referencedColumn) {
		ValidateArgument.requirement(columnTranslationReference != null
				|| functionReturnType != null
				|| (referencedColumn instanceof UnsignedLiteral),
				"Unknown column "+selectColumn.getName());
	}

	/**
	 * Given a referenced column, attempt to determine the type of the column using only
	 * the SQL.
	 * @param referencedColumn
	 * @return
	 */
	public static ColumnType getBaseColulmnType(ColumnNameReference referencedColumn){
		if(referencedColumn == null){
			return null;
		}
		// Get the upper case column name without quotes.
		if(!referencedColumn.hasQuotesRecursive()){
			return ColumnType.DOUBLE;
		}
		return ColumnType.STRING;
	}
	
	/**
	 * Is the given ColumnType numeric?
	 * @param columnType
	 * @return
	 */
	public static boolean isNumericType(ColumnType columnType){
		ValidateArgument.required(columnType, "columnType");
		switch(columnType){
		case INTEGER:
		case DOUBLE:
		case DATE:
		case FILEHANDLEID:
		case USERID:
		case SUBMISSIONID:
		case EVALUATIONID:
			return true;
		default:
			return false;
		}
	}
	
	
	/**
	 * 
	 * @param selectList
	 * @param tableIds
	 * @param columnsToAddToSelect
	 */
	public static void addMetadataColumnsToSelect(SelectList selectList, Set<IdAndVersion> tableIds, List<ColumnToAdd> columnsToAddToSelect) {
		List<String> toAdd = columnsToAddToSelect.stream()
				.map(a -> tableIds.contains(a.getIdAndVersion()) ? a.getSql() : "-1")
				.collect(Collectors.toList());
		SQLTranslatorUtils.addMetadataColumnsToSelect(selectList, toAdd);
	}
	
	/**
	 * Add system columns (ROW_ID, ROW_VERSION, & ROW_ETAG) to the passed select list.
	 * 
	 * @param selectList
	 * @return
	 */
	public static void addMetadataColumnsToSelect(SelectList selectList, List<String> columnsToAdd){
		for(String columnToAdd: columnsToAdd) {
			selectList.addDerivedColumn(SqlElementUtils.createNonQuotedDerivedColumn(columnToAdd));
		}
		selectList.recursiveSetParent();
	}
	
	/**
	 * Create ColumnTypeInfo[] for a given list of SelectColumns.
	 * 
	 * @param columns
	 * @return
	 */
	public static ColumnTypeInfo[] getColumnTypeInfoArray(List<SelectColumn> columns){
		ColumnTypeInfo[] infoArray = new  ColumnTypeInfo[columns.size()];
		for(int i=0; i<columns.size(); i++){
			SelectColumn column = columns.get(i);
			infoArray[i] = ColumnTypeInfo.getInfoForType(column.getColumnType());
		}
		return infoArray;
	}
	
	/**
	 * Read a Row from a ResultSet that was produced with the given query.
	 * @param rs
	 * @param includesRowIdAndVersion Is ROW_ID and ROW_VERSION included in the result set?
	 * @param includeEtag Is the read row an EntityRow?
	 * @return
	 * @throws SQLException
	 */
	public static Row readRow(ResultSet rs, boolean includesRowIdAndVersion, boolean includeEtag, ColumnTypeInfo[] colunTypes) throws SQLException{
		Row row = new Row();
		List<String> values = new LinkedList<String>();
		row.setValues(values);
		if(includesRowIdAndVersion){
			row.setRowId(rs.getLong(ROW_ID));
			row.setVersionNumber(rs.getLong(ROW_VERSION));
			if(includeEtag){
				row.setEtag(rs.getString(ROW_ETAG));
			}
		}
		// Read the select columns.
		for(int i=0; i < colunTypes.length; i++){
			ColumnTypeInfo type = colunTypes[i];
			String value = rs.getString(i+1);
			value = TableModelUtils.translateRowValueFromQuery(value, type);
			values.add(value);
		}
		return row;
	}
	
	

	/**
	 * Translate this query into a form that can be executed against the actual table index.
	 * 
	 * Translate user generated queries to queries that can
	 * run against the actual database.
	 * 
	 * @param transformedModel
	 * @param parameters
	 * @param columnTranslationReferenceLookup
	 */
	public static void translateModel(QuerySpecification transformedModel,
			Map<String, Object> parameters, Long userId, TableAndColumnMapper mapper) {
		
		translateCast(transformedModel, mapper);

		translateSynapseFunctions(transformedModel, userId);

		// translate all column references.
		translateAllColumnReferences(transformedModel, mapper);
		
		translateCastToDouble(transformedModel);
		
		TableExpression tableExpression = transformedModel.getTableExpression();
		if(tableExpression == null){
			// nothing else to do.
			return;
		}

		translateAllTableNameCorrelation(tableExpression.getFromClause(), mapper);

		// Translate all predicates
		Iterable<HasPredicate> hasPredicates = tableExpression.createIterable(HasPredicate.class);
		for (HasPredicate predicate : hasPredicates) {
			translate(predicate, parameters, mapper);
		}

		for (BooleanPrimary booleanPrimary : tableExpression.createIterable(BooleanPrimary.class)) {
			replaceBooleanFunction(booleanPrimary, mapper);
			replaceArrayHasPredicate(booleanPrimary, mapper);
			replaceTextMatchesPredicate(booleanPrimary);
		}

		// translate Pagination
		Pagination pagination = tableExpression.getPagination();
		if(pagination != null){
			translate(pagination, parameters);
		}

		//handle array functions which requires appending a join on another table
		try {
			translateArrayFunctions(transformedModel, mapper);
		} catch (ParseException e) {
			throw new IllegalArgumentException(e);
		}

		/*
		 * All remaining DelimitedIdentifier should be treated as a column
		 *  reference and therefore should be enclosed in backticks.
		 */
		translateUnresolvedDelimitedIdentifiers(transformedModel);
	}
	
	public static void translateCast(QuerySpecification model,TableAndColumnMapper mapper) {
		Iterable<CastSpecification> casts = model.createIterable(CastSpecification.class);
		for(CastSpecification cast: casts) {
			translateCastSpecification(cast, mapper);
		}
	}
	

	public static void translateCastSpecification(CastSpecification cast, TableAndColumnMapper mapper) {
		ValidateArgument.required(cast, "CastSpecification");
		ValidateArgument.required(mapper, "TableAndColumnMapper");
		
		CastTarget target = cast.getCastTarget();
		
		if (target.getType() == null && target.getColumnId() == null) {
			throw new IllegalArgumentException("Either ColumnType or ColumnId is required");
		}
		
		ColumnType type = target.getType() != null ? target.getType()
				: mapper.getColumnModel(target.getColumnId().toSql()).getColumnType();
		
		ColumnTypeInfo columnTypeInfo = ColumnTypeInfo.getInfoForType(type);
		
		target.replaceElement(new CastTarget(columnTypeInfo.getType(), columnTypeInfo.getMySqlType().getMySqlCastType().name()));
	}

	/**
	 * Translate all ColumnReference found in the given root element.
	 * 
	 * @param root
	 * @param mapper
	 */
	static void translateAllColumnReferences(Element root, TableAndColumnMapper mapper) {
		ValidateArgument.required(root, "root");
		ValidateArgument.required(mapper, "TableAndColumnMapper");
		for(ColumnReference hasReference: root.createIterable(ColumnReference.class)){
			translateColumnReference(hasReference, mapper).ifPresent(replacement -> hasReference.replaceElement(replacement));
		}
	}
	
	/**
	 * When there is a cast to a double within a {@link WithListElement} and
	 * {@link SelectList}, we need to add a 'NULL' immediacy after it in the select
	 * list. The NULL provides values for a virtual _DBL_C#_ column, which can be
	 * used for double expansion in the root select statement.
	 * 
	 * @param model
	 */
	static void translateCastToDouble(QuerySpecification model) {
		if (model.isInContext(WithListElement.class)) {
			SelectList oldSelect = model.getSelectList();
			List<DerivedColumn> oldList = oldSelect.getColumns();
			if(oldList != null) {
				List<DerivedColumn> newList = new ArrayList<>(oldList.size());
				oldList.forEach(dc -> {
					newList.add(dc);
					dc.stream(CastTarget.class).findFirst().ifPresent(ct -> {
						if (ColumnType.DOUBLE.equals(ct.getType())) {
							newList.add(createNullDerivedColumn());
						}
					});
				});
				model.replaceSelectList(new SelectList(newList), model.getSetQuantifier());
			}
		}
	}
	
	static DerivedColumn createNullDerivedColumn() {
		try {
			return new TableQueryParser("NULL").derivedColumn();
		} catch (ParseException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * from one of the tables. The resulting LHS will be the translated table alias
	 * and the RHS will be the translated column name. Optional.empty() returned if
	 * no match was found.
	 * 
	 * @param columnReference
	 * @return
	 */
	static Optional<ColumnReference> translateColumnReference(ColumnReference columnReference, TableAndColumnMapper mapper) {
		Optional<ColumnReferenceMatch> optional = mapper.lookupColumnReferenceMatch(columnReference);
		if (!optional.isPresent()) {
			return Optional.empty();
		}
		ColumnReferenceMatch match = optional.get();

		ColumnType type = match.getColumnTranslationReference().getColumnType();
		if (isDoubleExpansion(columnReference, type)) {
			return Optional.of(
					createDoubleExpansion(mapper.getNumberOfTables(),
							match.getTableInfo().getTranslatedTableAlias(),
							match.getColumnTranslationReference().getTranslatedColumnName()));
		}
		if (isBothDoubleColumns(columnReference, type)) {
			return Optional.of(
					createBothDoubleColumns(mapper.getNumberOfTables(),
							match.getTableInfo().getTranslatedTableAlias(),
							match.getColumnTranslationReference().getTranslatedColumnName()));
		}
		// All other cases
		return simpleTranslateColumn(mapper, match);
	}

	/**
	 * Is this a case where both double columns should be expanded into a cast statement?
	 * @param columnReference
	 * @param type
	 * @return
	 */
	static boolean isDoubleExpansion(ColumnReference columnReference, ColumnType type) {
		if(!ColumnType.DOUBLE.equals(type)) {
			return false;
		}
		if(!columnReference.isInContext(SelectList.class)) {
			return false;
		}
		if(columnReference.isInContext(WithListElement.class)) {
			return false;
		}
		if(columnReference.isInContext(HasFunctionReturnType.class)) {
			return false;
		}
		return SqlContext.query.equals(columnReference.getContext(HasSqlContext.class).get().getSqlContext());
	}
	
	/**
	 * Is this a case where both double columns should be selected?
	 * 
	 * @param columnReference
	 * @param type
	 * @return
	 */
	static boolean isBothDoubleColumns(ColumnReference columnReference, ColumnType type) {
		if(!ColumnType.DOUBLE.equals(type)) {
			return false;
		}
		if(!columnReference.isInContext(SelectList.class) && !columnReference.isInContext(GroupingColumnReference.class)) {
			return false;
		}
		if(!columnReference.isInContext(WithListElement.class)) {
			return false;
		}
		if(columnReference.isInContext(HasFunctionReturnType.class)) {
			return false;
		}
		return SqlContext.query.equals(columnReference.getContext(HasSqlContext.class).get().getSqlContext());
	}


	static Optional<ColumnReference> simpleTranslateColumn(TableAndColumnMapper mapper, ColumnReferenceMatch match) {
		StringBuilder builder = new StringBuilder();
		if (mapper.getNumberOfTables() > 1) {
			builder.append(match.getTableInfo().getTranslatedTableAlias());
			builder.append(".");
		}
		builder.append(match.getColumnTranslationReference().getTranslatedColumnName());
		try {
			return Optional.of(new TableQueryParser(builder.toString()).columnReference());
		} catch (ParseException e) {
			throw new IllegalStateException(e);
		}
	}
	

	/**
	 * Generates a select statement for a double column that uses both of the double's columns.
	 * For example: 'CASE WHEN alias._DBL_C#_ IS NULL THEN alias._C#_ ELSE alias._DBL_C#_ END'
	 * @param tableCount
	 * @param translatedTableAlias
	 * @param columnId value of #.
	 * @return
	 */
	static ColumnReference createDoubleExpansion(final int tableCount, final String translatedTableAlias,
			final String columnId) {
		return createColumReferenceFromTemplate("CASE WHEN %1$s_DBL%2$s IS NULL THEN %1$s%2$s ELSE %1$s_DBL%2$s END",
				tableCount, translatedTableAlias, columnId);
	}
	
	/**
	 * Generates a simple select statement for both of a double's columns.  For example: 'alias._C#_, alias._DBL_C#_'
	 * @param tableCount
	 * @param translatedTableAlias
	 * @param columnId
	 * @return
	 */
	static ColumnReference createBothDoubleColumns(final int tableCount, final String translatedTableAlias,
			final String columnId) {
		return createColumReferenceFromTemplate("%1$s%2$s, %1$s_DBL%2$s", tableCount, translatedTableAlias,
				columnId);
	}
	
	static ColumnReference createColumReferenceFromTemplate(String template, int tableCount,
			String translatedTableAlias, final String columnId) {
		String tableAlias = (tableCount > 1) ? translatedTableAlias + "." : "";
		String sql = String.format(template, tableAlias, columnId);
		return new ColumnReference(new ColumnName(new Identifier(new ActualIdentifier(new RegularIdentifier(sql)))),
				null);
	}

	private static void replaceTextMatchesPredicate(BooleanPrimary booleanPrimary) {
		if (booleanPrimary.getPredicate() == null) {
			return;
		}
		
		TextMatchesPredicate predicate = booleanPrimary.getPredicate().getFirstElementOfType(TextMatchesPredicate.class);
		
		if (predicate == null) {
			return;
		}
		
		booleanPrimary.getPredicate().replaceChildren(new TextMatchesMySQLPredicate(predicate));
	}
	
	/**
	 * Translate all TableNameCorrelation found in the passed root element.
	 * @param root
	 * @param mapper
	 */
	static void translateAllTableNameCorrelation(Element root, TableAndColumnMapper mapper) {
		// translate all of the table names
		for(TableNameCorrelation tableNameCorrelation: root.createIterable(TableNameCorrelation.class)) {
			translateTableName(tableNameCorrelation, mapper).ifPresent(replacement -> tableNameCorrelation.replaceElement(replacement));
		}
	}

	/**
	 * Translates FROM clause and returns the original Synapse IdAndVersion that was translated
	 * @param fromClause
	 * @return
	 */
	static Optional<TableNameCorrelation> translateTableName(TableNameCorrelation tableNameCorrelation, TableAndColumnMapper mapper) {
		Optional<TableInfo> optional = mapper.lookupTableNameCorrelation(tableNameCorrelation);
		if(optional.isPresent()) {
			try {
				TableInfo info = optional.get();
				StringBuilder builder = new StringBuilder(info.getTranslatedTableName());
				if(mapper.getNumberOfTables() > 1) {
					builder.append(" ").append(info.getTranslatedTableAlias());
				}
				return Optional.of(new TableQueryParser(builder.toString()).tableNameCorrelation());
			} catch (ParseException e) {
				throw new IllegalStateException(e);
			}
		}
		return Optional.empty();
	}

	static void translateArrayFunctions(QuerySpecification transformedModel, TableAndColumnMapper mapper) throws ParseException {
		// UNNEST(columnName) for the same columnName may appear in multiple places (select clause ,group by, order by, etc.)
		// but should only join the unnested index table for that column once
		Set<String> uniqueUnnestedRefs = new HashSet<>();
		List<ColumnReferenceMatch> unnestedColumns = new ArrayList<>();

		// iterate over all ValueExpressionPrimary since its may hold a ArrayFunctionSpecification.
		// Its held element then may need to be replaced with a different child element.
		for(ValueExpressionPrimary valueExpressionPrimary : transformedModel.createIterable(ValueExpressionPrimary.class)){
			//ignore valueExpressionPrimary that don't use an ArrayFunctionSpecification
			if(!(valueExpressionPrimary.getChild() instanceof ArrayFunctionSpecification)){
				continue;
			}
			ArrayFunctionSpecification arrayFunctionSpecification = (ArrayFunctionSpecification) valueExpressionPrimary.getChild();

			//handle UNNEST() functions
			if (arrayFunctionSpecification.getListFunctionType() == ArrayFunctionType.UNNEST) {
				ColumnReference referencedColumn = arrayFunctionSpecification.getColumnReference();

				ColumnReferenceMatch columnReferenceMatch = lookupAndRequireListColumn(mapper, referencedColumn, "UNNEST()");
				SchemaColumnTranslationReference columnTranslationReference = (SchemaColumnTranslationReference) columnReferenceMatch.getColumnTranslationReference();
				
				IdAndVersion tableIdRef = columnReferenceMatch.getTableInfo().getTableIdAndVersion();
				
				// The reference to the unnested column reference in the secondary index
				String unnestedColumnRef = 
					SQLUtils.getTableNameForMultiValueColumnIndex(tableIdRef, columnTranslationReference.getId()) 
						+ "." +
					SQLUtils.getUnnestedColumnNameForId(columnTranslationReference.getId());
				
				// add the column reference to the unique columns set
				if (uniqueUnnestedRefs.add(unnestedColumnRef)) {
					// If not present already we need to join on the secondary index to fetch the data
					unnestedColumns.add(columnReferenceMatch);
				}

				//replace "UNNEST(_C123_)" with column "_C123__UNNEST", uses the full table name of the multivalue column
				valueExpressionPrimary.replaceChildren(SqlElementUtils.createColumnReference(unnestedColumnRef));
			}
		}

		appendUnnestJoinsToFromClause(mapper, transformedModel.getTableExpression().getFromClause(), unnestedColumns);
	}

	static void appendUnnestJoinsToFromClause(TableAndColumnMapper mapper, FromClause fromClause, List<ColumnReferenceMatch> unnestedColumns) throws ParseException {
		TableReference currentTableReference = fromClause.getTableReference();
		
		//chain additional tables to join via right-recursion
		for (ColumnReferenceMatch columnMatch : unnestedColumns) {
			IdAndVersion referencedTable = columnMatch.getTableInfo().getTableIdAndVersion();
			SchemaColumnTranslationReference columnReference = (SchemaColumnTranslationReference) columnMatch.getColumnTranslationReference();
			
			// When we have multiple tables (join) we reference them by a generated alias
			String mainTableName = mapper.getNumberOfTables() > 1 ? columnMatch.getTableInfo().getTranslatedTableAlias() : columnMatch.getTableInfo().getTranslatedTableName();
			String joinTableName = SQLUtils.getTableNameForMultiValueColumnIndex(referencedTable, columnReference.getId());
			
			ColumnReference joinColumnReference = SqlElementUtils.createColumnReference(mainTableName + "." + columnMatch.getColumnTranslationReference().getTranslatedColumnName());
			
			String unnestColumnName = SQLUtils.getUnnestedColumnNameForId(columnReference.getId());
			ColumnTypeInfo unnestColumnTypeInfo = ColumnTypeInfo.getInfoForType(ColumnTypeListMappings.nonListType(columnReference.getColumnType()));
			
			String unnestColumnType = unnestColumnTypeInfo.getMySqlType().toString();
			
			if (unnestColumnTypeInfo.getMySqlType().hasSize && columnReference.getMaximumSize() != null) {
				unnestColumnType += "(" + columnReference.getMaximumSize() + ")";
			}
			
			List<JsonTableColumn> jsonColumns = List.of(new JsonTableColumn(unnestColumnName, unnestColumnType));
			CorrelationSpecification jsonTableCorrelation = new CorrelationSpecification(true, new CorrelationName(new RegularIdentifier(joinTableName)));
			
			TableReference jsonTableRef = new TableReference(new JsonTable(joinColumnReference, jsonColumns, jsonTableCorrelation));
			
			JoinCondition joinOnTrue = new JoinCondition(new TruthSpecification(TruthValue.TRUE));
			
			currentTableReference = new TableReference(new QualifiedJoin(currentTableReference, new JoinType(OuterJoinType.LEFT), jsonTableRef, joinOnTrue));
		}
		
		fromClause.setTableReference(currentTableReference);
	}

	/**
	 * Any DelimitedIdentifier remaining in the query after translation should be
	 * treated as a column reference, which for MySQL, means the value must be
	 * within backticks. Therefore, this function will translate any
	 * DoubleQuoteDelimitedIdentifier into a BacktickDelimitedIdentifier. any
	 * 
	 * @param element
	 */
	public static void translateUnresolvedDelimitedIdentifiers(Element element) {
		Iterable<DelimitedIdentifier> delimitedIdentifierIt = element.createIterable(DelimitedIdentifier.class);
		for(DelimitedIdentifier identifier: delimitedIdentifierIt) {
			String value = identifier.toSqlWithoutQuotes();
			identifier.replaceChildren(new BacktickDelimitedIdentifier(value));
		}
	}

	/**
	 * Translate pagination.
	 * 
	 * Translate user generated queries to queries that can
	 * run against the actual database.
	 * 
	 * @param pagination
	 * @param parameters
	 */
	public static void translate(Pagination pagination,
			Map<String, Object> parameters) {
		ValidateArgument.required(pagination, "pagination");
		ValidateArgument.required(parameters, "parameters");
		// limit
		if(pagination.getLimit() != null){
			String key = BIND_PREFIX+parameters.size();
			Long value = pagination.getLimitLong();
			parameters.put(key, value);
			pagination.setLimit(COLON+key);
		}
		// offset
		if(pagination.getOffset() != null){
			String key = BIND_PREFIX+parameters.size();
			Long value = pagination.getOffsetLong();
			parameters.put(key, value);
			pagination.setOffset(COLON+key);
		}
	}

	/**
	 * Translate a predicate.
	 * 
	 * Translate user generated queries to queries that can
	 * run against the actual database.
	 * 
	 * @param predicate
	 * @param parameters
	 * @param columnTranslationReferenceLookup
	 */
	public static void translate(HasPredicate predicate,
			Map<String, Object> parameters,
			TableAndColumnMapper mapper) {
		ValidateArgument.required(predicate, "predicate");
		ValidateArgument.required(parameters, "parameters");
		
		ColumnType columnType = getColumnType(mapper, predicate);
		
		predicate.getRightHandSideColumn().ifPresent((rhs)->{
			// The right=hand-side is a ColumnReference so validate that it exists.
			getColumnType(mapper, rhs);
		});
		
		// handle the right-hand-side values
		Iterable<UnsignedLiteral> rightHandSide = predicate.getRightHandSideValues();
		
		if(rightHandSide != null){
			//for the ArrayHasPredicate, we want its corresponding non-list type to be used
			if(predicate instanceof ArrayHasPredicate && ColumnTypeListMappings.isList(columnType)){
				columnType = ColumnTypeListMappings.nonListType(columnType);
			} 
			
			for(UnsignedLiteral element: rightHandSide){
				translateRightHandeSide(element, columnType, parameters);
			}
		}
	}
	
	static ColumnType getColumnType(TableAndColumnMapper mapper, HasPredicate predicate) {
		
		Element leftHandSideElement = predicate.getLeftHandSide().getChild();
				
		if (leftHandSideElement instanceof ColumnReference) {
			return getColumnType(mapper, (ColumnReference) leftHandSideElement);	
		} 
		
		if (leftHandSideElement instanceof MySqlFunction) {
			// A MySQLFunction always have a constant return column type that does not depend on the parameters
			return ((MySqlFunction) leftHandSideElement).getFunctionReturnType().getColumnType(null);
		} 
		
		if (leftHandSideElement instanceof CastSpecification) {
			// At this point all the cast specifications have been translated so the type must be there
			return ((CastSpecification) leftHandSideElement).getCastTarget().getType();
		}
		
		throw new IllegalArgumentException("Unsupported left hand side of predicate '" + predicate.toSql() + "': expected a column reference, a mysql function or a cast specification.");
		
	}

	static ColumnType getColumnType(TableAndColumnMapper mapper, ColumnReference columnReference) {
		ValidateArgument.required(mapper, "TableAndColumnMapper");
		ValidateArgument.required(columnReference, "columnReference");
		// We first check if the left hand side column is implicit (Not exposed to the user, for example TextMatchesPredicate)
		// In such cases the ColumnTranslationReferenceLookup cannot be used since the user cannot query the column directly
		// and instead we use the column type from the reference directly
		ColumnType columnType = columnReference.getImplicitColumnType();
		
		if (columnType == null) {
			// lookup the column name from the left-hand-side
			String columnName = columnReference.toSqlWithoutQuotes();
			
			columnType = mapper.lookupColumnReference(columnReference)
					.orElseThrow(() ->  new IllegalArgumentException("Column does not exist: " + columnName))
					.getColumnType();
		}
		
		return columnType;
	}

	/**
	 * Translate instances of Synapse functions not supported by SQL
	 *
	 * @param transformedModel
	 * @param userId
	 */
	public static void translateSynapseFunctions(QuerySpecification transformedModel, Long userId){
		// Insert userId if needed
		Iterable<CurrentUserFunction> userFunctions = transformedModel.createIterable(CurrentUserFunction.class);
		for(CurrentUserFunction userFunction: userFunctions){
			userFunction.replaceElement(new UnsignedLiteral(new UnsignedNumericLiteral(new ExactNumericLiteral(userId))));
		}
	}

	/**
	 * Translate the right-hand-side of a predicate.
	 * 
	 * Translate user generated queries to queries that can
	 * run against the actual database.
	 * 
	 * @param element
	 * @param type
	 * @param parameters
	 */
	public static void translateRightHandeSide(HasReplaceableChildren element,
			ColumnType type, Map<String, Object> parameters) {
		ValidateArgument.required(element, "element");
		ValidateArgument.required(type, "type");
		ValidateArgument.required(parameters, "parameters");
		if(element.getFirstElementOfType(IntervalLiteral.class) != null){
			// intervals should not be replaced.
			return;
		}
		// Fix for PLFM-6819. 
		if(element.isInContext(MySqlFunction.class)) {
			// mysql parameters should not be replaced.
			return;
		}
		
		String key = BIND_PREFIX+parameters.size();
		String value = element.toSqlWithoutQuotes();
		Object valueObject = null;
		try{
			valueObject = SQLUtils.parseValueForDB(type, value);
		}catch (IllegalArgumentException e){
			// thrown for number format exception.
			valueObject = value;
		}

		parameters.put(key, valueObject);
		element.replaceChildren(new StringOverride(COLON+key));
	}

	/**
	 * Replace any BooleanFunctionPredicate with a search condition.
	 * For example: 'isInfinity(DOUBLETYPE)' will be replaced with (_DBL_C777_ IS NOT NULL AND _DBL_C777_ IN ('-Infinity', 'Infinity'))'
	 * @param booleanPrimary
	 * @param columnTranslationReferenceLookup
	 */
	public static void replaceBooleanFunction(BooleanPrimary booleanPrimary, TableAndColumnMapper mapper){
		if(booleanPrimary.getPredicate() != null){
			BooleanFunctionPredicate bfp = booleanPrimary.getPredicate().getFirstElementOfType(BooleanFunctionPredicate.class);
			if(bfp != null){
				ColumnReference columnReference = bfp.getColumnReference();
				ColumnTranslationReference columnTranslationReference = mapper.lookupColumnReference(columnReference)
						.orElseThrow(() -> new IllegalArgumentException("Function: "+bfp.getBooleanFunction()+" has unknown reference: "+columnReference.toSqlWithoutQuotes()));

				if( !(columnTranslationReference instanceof SchemaColumnTranslationReference) ){
					throw new IllegalArgumentException("(double boolean-functions can only be used on columns defined in the schema");
				}
				if(columnTranslationReference.getColumnType() != ColumnType.DOUBLE){
					throw new IllegalArgumentException("Function: "+bfp.getBooleanFunction()+" can only be used with a column of type DOUBLE.");
				}

				StringBuilder builder = new StringBuilder();
				// Is this a boolean function
				switch(bfp.getBooleanFunction()){
				case ISINFINITY:
					SQLUtils.appendIsInfinity(((SchemaColumnTranslationReference) columnTranslationReference).getId(), builder);
					break;
				case ISNAN:
					SQLUtils.appendIsNan(((SchemaColumnTranslationReference) columnTranslationReference).getId(), builder);
					break;
				default:
					throw new IllegalArgumentException("Unknown boolean function: "+bfp.getBooleanFunction());
				}

				try {
					BooleanPrimary newPrimary = new TableQueryParser(builder.toString()).booleanPrimary();
					booleanPrimary.replaceSearchCondition(newPrimary.getSearchCondition());
				} catch (ParseException e) {
					throw new IllegalArgumentException(e);
				}
			}
		}
	}

	public static void replaceArrayHasPredicate(BooleanPrimary booleanPrimary, TableAndColumnMapper mapper){
		if(booleanPrimary.getPredicate() == null) {
			return; // "HAS" should always be under a Predicate
		}
		
		ArrayHasPredicate arrayHasPredicate = booleanPrimary.getPredicate().getFirstElementOfType(ArrayHasPredicate.class);
		
		if (arrayHasPredicate == null) {
			return; // no ArrayHasPredicate to replace
		}
		
		mapper.getSingleTableId().orElseThrow(TableConstants.JOIN_NOT_SUPPORTED_IN_THIS_CONTEXT);

		PredicateLeftHandSide leftHandSide = arrayHasPredicate.getLeftHandSide();
		
		if (!(leftHandSide.getChild() instanceof ColumnReference)) {
			throw new IllegalArgumentException("The HAS keyword only works for list column references");
		}
		
		ColumnReference columnRefernece = (ColumnReference) leftHandSide.getChild();

		lookupAndRequireListColumn(mapper, columnRefernece, "The " + arrayHasPredicate.getKeyWord() + " keyword");
		
		SearchCondition replacementSearchCondition;
		
		try {
			if (arrayHasPredicate instanceof ArrayHasLikePredicate) {
				replacementSearchCondition = createArrayHasLikeJsonSearchSearchCondition((ArrayHasLikePredicate) arrayHasPredicate, columnRefernece);
			} else {
				replacementSearchCondition = createArrayHasSearchCondition(arrayHasPredicate, columnRefernece);
			}
		} catch (ParseException e) {
			throw new IllegalArgumentException(e);
		}
		
		booleanPrimary.replaceSearchCondition(replacementSearchCondition);
	}
	
	// Translate the expression foo HAS_LIKE ('a%', 'b%') -> JSON_SEARCH(foo, 'one', 'a%', NULL, '$[*]') IS NOT NULL OR JSON_SEARCH(foo, 'one', 'b%', NULL, '$[*]') IS NOT NULL
	private static SearchCondition createArrayHasLikeJsonSearchSearchCondition(ArrayHasLikePredicate predicate, ColumnReference columnReference) throws ParseException {
		
		List<ValueExpression> values = predicate.getFirstElementOfType(InValueList.class).getValueExpressions();
		
		List<BooleanPrimary> booleanPrimaries = new ArrayList<>(values.size());
		
		ValueExpression escapeCharacter = predicate.getEscapeCharacter() == null ? 
			// The parser does not parse NULL as a VALUE expression
			new ValueExpression(new StringOverride("NULL")) :
			// The parser does not parse bind variables (e.g. :b1)
			new ValueExpression(new StringOverride(predicate.getEscapeCharacter().toSql()));
		
		Boolean notNull = Boolean.TRUE.equals(predicate.getNot()) ? null : true;
				
		for (ValueExpression value : values) {
			MySqlFunction jsonSearchFunction = new MySqlFunction(MySqlFunctionName.JSON_SEARCH);
			
			jsonSearchFunction.startParentheses();
			
			// The first parameter is the column reference
			jsonSearchFunction.addParameter(new ValueExpression(new NumericValueExpression(new Term(new Factor(null, new NumericPrimary(new ValueExpressionPrimary(columnReference)))))));
			
			// Next we have the special value 'one' (stop at the first match)
			jsonSearchFunction.addParameter(SqlElementUtils.createValueExpression("'one'"));
			
			// The third parameter is the actual bind variable (search string)
			// Note that the JSON_SEARCH function is case sensitive (unlike the LIKE predicate) and we need to specify collation of the argument to be case insensitive
			jsonSearchFunction.addParameter(new ValueExpression(new StringOverride(value.toSql() + " COLLATE 'utf8mb4_0900_ai_ci'")));
			
			// Next we provide the escape character
			jsonSearchFunction.addParameter(escapeCharacter);
			
			// Finally the path we are searching in (any element of the array)
			jsonSearchFunction.addParameter(SqlElementUtils.createValueExpression("'$[*]'"));
						
			booleanPrimaries.add(new BooleanPrimary(new Predicate(new NullPredicate(new PredicateLeftHandSide(jsonSearchFunction), notNull))));
			
		}
		
		List<BooleanTerm> orTerms;
		
		if (notNull == null) {
			// JSON_SEARCH(foo, 'a') IS NULL AND JSON_SEARCH(foo, 'b') IS NULL 
			orTerms = List.of(
				new BooleanTerm(booleanPrimaries.stream().map(
					booleanPrimary -> new BooleanFactor(null, new BooleanTest(booleanPrimary, null, null, null))
				).collect(Collectors.toList()))
			);
		} else {
			// JSON_SEARCH(foo, 'a') IS NOT NULL OR JSON_SEARCH(foo, 'b') IS NOT NULL 
			orTerms = booleanPrimaries.stream().map( booleanPrimary -> 
				new BooleanTerm(List.of(new BooleanFactor(null, new BooleanTest(booleanPrimary, null, null, null))))
			).collect(Collectors.toList());
		}
		
		return new SearchCondition(orTerms);
	}
	
	// Translate the expression foo HAS (1, 2, 3) -> JSON_OVERLAPS(foo, JSON_ARRAY(1, 2, 3)) IS TRUE
	private static SearchCondition createArrayHasSearchCondition(ArrayHasPredicate predicate, ColumnReference columnReference) {
		
		// First build the JSON_ARRAY expression with the input values
		MySqlFunction jsonArrayFunction = new MySqlFunction(MySqlFunctionName.JSON_ARRAY);
		
		jsonArrayFunction.startParentheses();
		
		predicate.getFirstElementOfType(InValueList.class).getValueExpressions().forEach(jsonArrayFunction::addParameter);
		
		MySqlFunction jsonOverlapFunction = new MySqlFunction(MySqlFunctionName.JSON_OVERLAPS);
		
		jsonOverlapFunction.startParentheses();
		
		// The first argument of the JSON_OVERLAPS is the column reference
		jsonOverlapFunction.addParameter(new ValueExpression(new NumericValueExpression(new Term(new Factor(null, new NumericPrimary(new ValueExpressionPrimary(columnReference)))))));
		
		// The second argument of the JSON_OVERLAPS is the result of a JSON_ARRAY with the input values
		jsonOverlapFunction.addParameter(new ValueExpression(new NumericValueExpression(new Term(new Factor(null, new NumericPrimary(new NumericValueFunction(jsonArrayFunction)))))));
		
		TruthValue truthValue = Boolean.TRUE.equals(predicate.getNot()) ? TruthValue.FALSE : TruthValue.TRUE;
		
		Predicate isPredicate = new Predicate(new BooleanPredicate(new PredicateLeftHandSide(jsonOverlapFunction), null, truthValue));
		
		return new SearchCondition(List.of(new BooleanTerm(List.of(new BooleanFactor(null, new BooleanTest(new BooleanPrimary(isPredicate), null, null, null))))));
	}

	/**
	 * Wraps string table name inside a TableReference
	 * @param tableName
	 * @return
	 */
	private static TableReference tableReferenceForName(String tableName){
		return new TableReference(new TableNameCorrelation(new TableName(new RegularIdentifier(tableName)), null));
	}

	/**
	 *
	 * @param columnTranslationReferenceLookup lookup table for ColumnTranslationReferences
	 * @param columnName column name for which to
	 * @param errorMessageFunctionName name of the function that requires a list column type
	 * @throws IllegalArgumentException if the column is not defined in the schema or does not have a _LIST ColumnType
	 * @return ColumnReferenceMatch associated with the columnName, the {@link ColumnReferenceMatch#getColumnTranslationReference()} is guaranteed to be a SchemaColumnTranslationReference
	 */
	private static ColumnReferenceMatch lookupAndRequireListColumn(TableAndColumnMapper mapper, ColumnReference columnRefrence, String errorMessageFunctionName){
		ColumnReferenceMatch columnRefMatch = mapper.lookupColumnReferenceMatch(columnRefrence)
				.orElseThrow(() ->  new IllegalArgumentException("Unknown column reference: " + columnRefrence.toSqlWithoutQuotes()));
				
		if( !(columnRefMatch.getColumnTranslationReference() instanceof SchemaColumnTranslationReference) ){
			throw new IllegalArgumentException(errorMessageFunctionName + " may only be used on columns defined in the schema");
		}
		
		SchemaColumnTranslationReference schemaColumnTranslationReference = (SchemaColumnTranslationReference) columnRefMatch.getColumnTranslationReference();

		if( !ColumnTypeListMappings.isList(schemaColumnTranslationReference.getColumnType()) ){
			throw new IllegalArgumentException(errorMessageFunctionName + " only works for columns that hold list values");
		}
		
		return columnRefMatch;
	}
	
	public static void addFiltersToTableExpression(List<QueryFilter> additionalFilters, TableExpression expression) {
		
	}

	public static void translateQueryFilters(TableExpression tableExpression, List<QueryFilter> additionalFilters) {
		ValidateArgument.required(tableExpression, "tableExpression");
		ValidateArgument.requiredNotEmpty(additionalFilters, "additionalFilters");

		StringBuilder where = new StringBuilder();
		StringBuilder defining = new StringBuilder();

		for (QueryFilter filter : additionalFilters) {
			StringBuilder builder = Boolean.TRUE.equals(filter.getIsDefiningCondition()) ? defining : where;
			if (builder.length() > 0) {
				builder.append(" AND ");
			}
			translateQueryFilters(builder, filter);
		}

		createSearchCondition(where).ifPresent(searchCondition -> {
			tableExpression.replaceWhere(new WhereClause(
					mergeSearchConditions(tableExpression.getWhereClause(), searchCondition)));
		});
		createSearchCondition(defining).ifPresent(searchCondition -> {
			tableExpression.replaceDefiningClause(
					new DefiningClause(mergeSearchConditions(tableExpression.getDefiningClause(), searchCondition)));
		});
	}
	
	public static Optional<SearchCondition> createSearchCondition(StringBuilder builder){
		if(builder.length() < 1) {
			return Optional.empty();
		}
		try {
			return Optional.of(new TableQueryParser(builder.toString()).searchCondition());
		} catch (ParseException e) {
			throw new RuntimeException(e);
		}
	}

	static void translateQueryFilters(StringBuilder builder, QueryFilter filter){
		if(filter instanceof ColumnSingleValueQueryFilter){
			translateSingleValueFilters(builder, (ColumnSingleValueQueryFilter) filter);
		} else if (filter instanceof ColumnMultiValueFunctionQueryFilter) {
			translateMultiValueFunctionFilters(builder, (ColumnMultiValueFunctionQueryFilter) filter);
		} else if (filter instanceof TextMatchesQueryFilter) {
			translateTextMatchesQueryFilter(builder, (TextMatchesQueryFilter) filter);
		} else {
			throw new IllegalArgumentException("Unknown QueryFilter type");
		}
	}

	static void translateTextMatchesQueryFilter(StringBuilder builder, TextMatchesQueryFilter filter) {
		ValidateArgument.requiredNotBlank(filter.getSearchExpression(), "TextMatchesQueryFilter.searchExpression");
		builder.append("(");
		builder.append(TextMatchesPredicate.KEYWORD).append("(");
		appendSingleQuotedValueToStringBuilder(builder, filter.getSearchExpression());
		builder.append(")");
		builder.append(")");
	}

	static void translateSingleValueFilters(StringBuilder builder, ColumnSingleValueQueryFilter filter){
		ValidateArgument.requiredNotEmpty(filter.getColumnName(), "ColumnSingleValueQueryFilter.columnName");
		ValidateArgument.requiredNotEmpty(filter.getValues(), "ColumnSingleValueQueryFilter.values");
		ValidateArgument.required(filter.getOperator(), "ColumnSingleValueQueryFilter.operator");
		appendFilter(builder, filter);
	}

	static void appendFilter(StringBuilder builder, ColumnSingleValueQueryFilter filter){
		builder.append("(");

		boolean firstVal = true;
		String columnName = filter.getColumnName();
		
		// For the IN operator each value is part of the IN clause
		if (ColumnSingleValueFilterOperator.IN.equals(filter.getOperator())) {
			builder.append("\"")
				.append(columnName)
				.append("\" IN (");			
			for (String likeValue: filter.getValues()){
				if(!firstVal){
					builder.append(",");
				}				
				appendSingleQuotedValueToStringBuilder(builder, likeValue);	
				firstVal = false;
			}
			builder.append(")");
		} else {
			for (String likeValue: filter.getValues()){
				if(!firstVal){
					builder.append(" OR ");
				}
				builder.append("\"")
					.append(columnName)
					.append("\"");
				switch (filter.getOperator()) {
					case LIKE:
						builder.append(" LIKE ");
						break;
					case EQUAL:
						builder.append(" = ");
						break;
					default:
						throw new IllegalArgumentException("Unexpected operator: " + filter.getOperator());
				}
				appendSingleQuotedValueToStringBuilder(builder, likeValue);	
				firstVal = false;
			}
		}
		builder.append(")");
	}

	static void translateMultiValueFunctionFilters(StringBuilder builder, ColumnMultiValueFunctionQueryFilter filter) {
		ValidateArgument.requiredNotEmpty(filter.getColumnName(), "ColumnMultiValueFunctionQueryFilter.columnName");
		ValidateArgument.requiredNotEmpty(filter.getValues(), "ColumnMultiValueFunctionQueryFilter.values");
		ValidateArgument.required(filter.getFunction(), "ColumnMultiValueFunctionQueryFilter.function");
		switch (filter.getFunction()) {
		case HAS:
		case HAS_LIKE:
			appendHasFilter(builder, filter);
			break;
		default:
			throw new IllegalArgumentException("Unexpected function: " + filter.getFunction());
		}
	}
	
	static void appendHasFilter(StringBuilder builder, ColumnMultiValueFunctionQueryFilter filter){
		builder.append("(");
		builder.append("\"").append(filter.getColumnName()).append("\" ");
		builder.append(filter.getFunction().name()).append(" (");
		boolean firstVal = true;
		for (String likeValue : filter.getValues()) {
			if (!firstVal) {
				builder.append(", ");
			}
			appendSingleQuotedValueToStringBuilder(builder, likeValue);
			firstVal = false;
		}
		builder.append("))");
	}

	/**
	 * Appends a value to the string builder
	 * and places single quotes (') around it if the column type is String
	 */
	static void appendSingleQuotedValueToStringBuilder(StringBuilder builder, String value){
		builder.append("'");
		builder.append(value.replaceAll("'", "''"));
		builder.append("'");
	}

	/**
	 * Create the schema of a select. Tables and views can only have a single select
	 * statement, so their select schema is simply the schema of that select. A
	 * materialized view that includes one or more UIONs will have one schema for
	 * each select statement. For such cases, the schema of the materialized view
	 * will be created from the first select statement. However, for columns with a
	 * maximum size and/or maximum list length, the resulting size/length must be
	 * the maximum of all columns from the same column index.
	 * 
	 * @param selectSchemas The schemas of each select statement. Tables, Views, and
	 *                      materialized views without UNIONs should only provide
	 *                      one schema.
	 * @return Will always return the first schema of the provided list, with
	 *         ColumnModels modified as needed.
	 */
	public static List<ColumnModel> createSchemaOfSelect(final List<List<ColumnModel>> selectSchemas) {
		if (selectSchemas.size() < 2 || !hasSameSize(selectSchemas)) {
			return selectSchemas.get(0);
		}
		List<ColumnModel> firstSchema = selectSchemas.get(0);
		List<ColumnModel> newSchema = new ArrayList<>(firstSchema.size());
		for (int colunIndex = 0; colunIndex < firstSchema.size(); colunIndex++) {
			try {
				ColumnModel clone = EntityFactory.createEntityFromJSONString(
						EntityFactory.createJSONStringForEntity(firstSchema.get(colunIndex)), ColumnModel.class);
				newSchema.add(clone);
				for (int schemaIndex = 1; schemaIndex < selectSchemas.size(); schemaIndex++) {
					ColumnModel compareToColumn = selectSchemas.get(schemaIndex).get(colunIndex);
					clone.setMaximumSize(maxWithNulls(clone.getMaximumSize(), compareToColumn.getMaximumSize()));
					clone.setMaximumListLength(maxWithNulls(clone.getMaximumListLength(), compareToColumn.getMaximumListLength()));
				}
			} catch (JSONObjectAdapterException e) {
				throw new RuntimeException(e);
			}
		}
		return newSchema;
	}
	
	/**
	 * Do all of the given lists have the same size?
	 * @param listOfLists
	 * @return true if each list has the same size.
	 */
	public static <T> boolean hasSameSize(final List<List<T>> listOfLists) {
		if(listOfLists.isEmpty()) {
			return true;
		}
		int rootSize = listOfLists.get(0).size();
		return listOfLists.stream().skip(1).allMatch(l -> l.size() == rootSize);
	}
	
	/**
	 * Get the max long with null checking.
	 * @param first
	 * @param second
	 * @return
	 */
	public static Long maxWithNulls(Long first, Long second) {
		if(first == null) {
			return second;
		}
		if(second == null) {
			return first;
		}
		return Math.max(first, second);
	}

	
	/**
	 * Get a ColumnModel representation of each column from the SQL's select
	 * statement. Note: When a result ColumnModel does not match any of the columns
	 * from the source table/view, the {@link ColumnModel#getId()} will be null.
	 * 
	 * @param selectList
	 * @param tableAndColumnMapper
	 * @return
	 */
	public static List<ColumnModel> getSchemaOfSelect(SelectList selectList,
			TableAndColumnMapper tableAndColumnMapper) {
		return selectList.getColumns().stream().map(d -> getSchemaOfDerivedColumn(d, tableAndColumnMapper))
				.collect(Collectors.toList());
	}
	
	/**
	 * Get a ColumnModel representation of each column from the SQL's select
	 * statement. Note: The {@link ColumnModel#getId()} will always be null.
	 * @param derivedColumn
	 * @param tableAndColumnMapper
	 * @return
	 */
	public static ColumnModel getSchemaOfDerivedColumn(DerivedColumn derivedColumn,
			TableAndColumnMapper tableAndColumnMapper) {
		// the SelectColumn provides a starting name and type.
		SelectColumn selectColumn = getSelectColumns(derivedColumn, tableAndColumnMapper);
		Long maximumSize = null;
		Long maxListLength = null;
		List<String> enumValues = null;
		// The data type is correctly inferred by the #getSelectColumns call
		ColumnType columnType = selectColumn.getColumnType();
		String defaultValue = null;
		FacetType facetType = null;
		List<JsonSubColumnModel> jsonSubColumns = null;
		if(selectColumn.getId() != null) {
			ColumnModel cm = tableAndColumnMapper.getColumnModel(selectColumn.getId());
			maximumSize = cm.getMaximumSize();
			maxListLength = cm.getMaximumListLength();
			defaultValue = cm.getDefaultValue();
			facetType = cm.getFacetType();
			enumValues = cm.getEnumValues();
			jsonSubColumns = cm.getJsonSubColumns();
		}else {
			for (ColumnReference cr : derivedColumn.createIterable(ColumnReference.class)) {
				ColumnTranslationReference ctr = tableAndColumnMapper.lookupColumnReference(cr).orElse(null);
				if (ctr != null) {
					maximumSize = addLongsWithNull(maximumSize, ctr.getMaximumSize());
					maxListLength = addLongsWithNull(maxListLength, ctr.getMaximumListLength());
					defaultValue = ctr.getDefaultValues();
					facetType = ctr.getFacetType();
					jsonSubColumns = ctr.getJsonSubColumns();
				}
			}
		}


		ColumnModel result = new ColumnModel();
		result.setColumnType(columnType);
		result.setMaximumSize(maximumSize);
		result.setMaximumListLength(maxListLength);
		result.setName(selectColumn.getName());
		result.setFacetType(facetType);
		result.setDefaultValue(defaultValue);
		result.setEnumValues(enumValues);
		result.setJsonSubColumns(jsonSubColumns);
		result.setId(null);
		return result;
	}
	
	
	/**
	 * Addition for Longs that can be null.
	 * 
	 * @param currentValue
	 * @param newValue
	 * @return
	 */
	public static Long addLongsWithNull(Long currentValue, Long newValue) {
		if(currentValue == null) {
			return newValue;
		}
		if(newValue == null) {
			return currentValue;
		}
		return currentValue + newValue;
	}

	/**
	 * Create the SQL used to build a materialized view from a defining SQL query.
	 * @param outputSQL The translated SQL
	 * @param indexDescription
	 * @return
	 */
	public static String createMaterializedViewInsertSql(List<ColumnModel> schemaOfSelect, String outputSQL, IndexDescription indexDescription) {
		String tableName = SQLUtils.getTableNameForId(indexDescription.getIdAndVersion(), TableIndexType.INDEX);
		StringJoiner joiner = new StringJoiner(",");
		// start with the columns from the select
		for(ColumnModel cm: schemaOfSelect) {
			joiner.add(SQLUtils.getColumnNameForId(cm.getId()));
		}
		// add benefactor columns as needed
		for(BenefactorDescription benDesc: indexDescription.getBenefactors()) {
			joiner.add(benDesc.getBenefactorColumnName());
		}
		return String.format("INSERT INTO %s (%s) %s", tableName, joiner.toString(), outputSQL);
	}

	/**
	 * Translate the {@link Identifier} and {@link ColumnList} within the provided {@link WithListElement}.
	 * @param wle
	 * @param schemaProvider
	 */
	public static void translateWithListElement(WithListElement wle, SchemaProvider schemaProvider) {
		try {
			IdAndVersion idAndVersion = IdAndVersion.parse(wle.getIdentifier().toSql());
			List<ColumnModel> schema = schemaProvider.getTableSchema(idAndVersion);
			String t = SQLUtils.getTableNameForId(idAndVersion, TableIndexType.INDEX);
			wle.getIdentifier().replaceElement(new TableQueryParser(t).identifier());
			StringJoiner joiner = new StringJoiner(",");
			for(ColumnModel cm: schema) {
				joiner.add( new SchemaColumnTranslationReference(cm).getTranslatedColumnName());
				if(ColumnType.DOUBLE.equals(cm.getColumnType())) {
					joiner.add(String.format("_DBL_C%s_", cm.getId()));
				}
			}
			ColumnList cl = new TableQueryParser(String.format("(%s)", joiner.toString())).columnList();
			wle.setColumnList(cl);
		} catch (ParseException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * For a CTE that includes a defining_where in the root
	 * (NonJoinQueryExpression), the defining_where SearchCondition must be merged
	 * with the search condition of the inner query's where clause.
	 * 
	 * @param element
	 */
	public static void translateDefiningClause(QueryExpression element) {
		NonJoinQueryExpression root = element.getNonJoinQueryExpression();
		TableExpression rootTableExpression = root.getFirstElementOfType(TableExpression.class);
		DefiningClause definingClause = rootTableExpression.getDefiningClause();
		if (definingClause != null) {
			if(element.getWithListElements().isEmpty()) {
				throw new IllegalArgumentException(
						DEFINING_WHERE_CAN_ONLY_BE_USED_WITH_A_CTE);
			}
			element.getWithListElements().ifPresent(list -> {
				if (list.size() != 1) {
					throw new IllegalArgumentException(
							DEFINING_WHERE_CAN_ONLY_BE_USED_WITH_A_CTE);
				}
				rootTableExpression.replaceDefiningClause(null);
				TableExpression innerTableExpression = list.get(0).getFirstElementOfType(TableExpression.class);
				SearchCondition newSearchCondition = mergeSearchConditions(innerTableExpression.getWhereClause(),
								definingClause.getSearchCondition());
				innerTableExpression.replaceWhere(new WhereClause(newSearchCondition));
			});
		}
	}
	
	/**
	 * Merge the two SearchConditions into a single SearchCondition with 'AND' between each.
	 * @param original If null, toMerge will be returned unmodified.
	 * @param toMerge
	 * @return
	 */
	public static SearchCondition mergeSearchConditions(HasSearchCondition original, SearchCondition toMerge) {
		if(original == null) {
			return toMerge;
		}
		return mergeSearchConditions(original.getSearchCondition(), toMerge);
	}
	
	/**
	 * Merge the two SearchConditions into a single SearchCondition with 'AND' between each.
	 * @param original
	 * @param toMerge
	 * @return
	 */
	public static SearchCondition mergeSearchConditions(SearchCondition original, SearchCondition toMerge) {
		return new SearchCondition(Collections.singletonList(new BooleanTerm(
				List.of(wrapSearchConditionInBooleanFactor(original), wrapSearchConditionInBooleanFactor(toMerge)))));
	}
	
	public static BooleanFactor wrapSearchConditionInBooleanFactor(SearchCondition condition) {
		return new BooleanFactor(null, new BooleanTest(new BooleanPrimary(condition), null, null, null));
	}
}
