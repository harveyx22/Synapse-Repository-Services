package org.sagebionetworks.table.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.table.query.model.ColumnReference;
import org.sagebionetworks.table.query.model.Element;
import org.sagebionetworks.table.query.model.InPredicate;
import org.sagebionetworks.table.query.model.InPredicateValue;
import org.sagebionetworks.table.query.model.Predicate;
import org.sagebionetworks.table.query.model.PredicateLeftHandSide;
import org.sagebionetworks.table.query.model.UnsignedLiteral;
import org.sagebionetworks.table.query.util.SqlElementUtils;

import com.google.common.collect.Lists;

public class InPredicateTest {

	@Test
	public void testInPredicateToSQL() throws ParseException {
		ColumnReference columnReferenceLHS = SqlElementUtils.createColumnReference("bar");
		Boolean not = null;
		InPredicateValue inPredicateValue = SqlElementUtils.createInPredicateValue("(1)");
		InPredicate element = new InPredicate(new PredicateLeftHandSide(columnReferenceLHS), not, inPredicateValue);
		assertEquals("bar IN ( 1 )", element.toString());
	}

	@Test
	public void testInPredicateToSQLNot() throws ParseException {
		ColumnReference columnReferenceLHS = SqlElementUtils.createColumnReference("bar");
		Boolean not = Boolean.TRUE;
		InPredicateValue inPredicateValue = SqlElementUtils.createInPredicateValue("(1, 2)");
		InPredicate element = new InPredicate(new PredicateLeftHandSide(columnReferenceLHS), not, inPredicateValue);
		assertEquals("bar NOT IN ( 1, 2 )", element.toString());
	}

	@Test
	public void testInPredicateToSQL_NotSetToFalse() throws ParseException {
		ColumnReference columnReferenceLHS = SqlElementUtils.createColumnReference("bar");
		Boolean not = Boolean.FALSE;
		InPredicateValue inPredicateValue = SqlElementUtils.createInPredicateValue("(1, 2)");
		InPredicate element = new InPredicate(new PredicateLeftHandSide(columnReferenceLHS), not, inPredicateValue);
		assertEquals("bar IN ( 1, 2 )", element.toString());
	}

	@Test
	public void testHasPredicate() throws ParseException {
		Predicate predicate = new TableQueryParser("foo in (1,'2',3)").predicate();
		InPredicate element = predicate.getFirstElementOfType(InPredicate.class);
		assertEquals("foo", element.getLeftHandSide().toSql());
		List<UnsignedLiteral> values = Lists.newArrayList(element.getRightHandSideValues());
		assertNotNull(values);
		assertEquals(3, values.size());
		assertEquals("1", values.get(0).toSqlWithoutQuotes());
		assertEquals("2", values.get(1).toSqlWithoutQuotes());
		assertEquals("3", values.get(2).toSqlWithoutQuotes());
	}

	@Test
	public void testGetChildren() throws ParseException {
		Predicate predicate = new TableQueryParser("foo in (1,'2',3)").predicate();
		InPredicate element = predicate.getFirstElementOfType(InPredicate.class);
		List<Element> children = element.getChildrenStream().collect(Collectors.toList());
		assertEquals(Arrays.asList(element.getLeftHandSide(), element.getInPredicateValue()), children);
	}
}
