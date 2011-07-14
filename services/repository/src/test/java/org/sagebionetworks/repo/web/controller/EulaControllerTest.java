package org.sagebionetworks.repo.web.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.repo.model.NodeConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * Unit tests for the Location CRUD operations exposed by the LocationController
 * with JSON request and response encoding.
 * <p>
 * 
 * Note that test logic and assertions common to operations for all DAO-backed
 * entities can be found in the Helpers class. What follows are test cases that
 * make use of that generic test logic with some assertions specific to
 * locations.
 * 
 * @author deflaux
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class EulaControllerTest {

	/**
	 * Sample eula for use in unit tests, note that the parentId property is
	 * missing and necessary to be a valid location
	 */
	public static String SAMPLE_EULA = "{\"name\":\"TCGA Redistribution Use Agreement\", "
			+ "\"agreement\":\"The recipient acknowledges that the data herein is provided by TCGA and not SageBionetworks and must abide by ...\"}";

	@Autowired
	private Helpers helper;
	private JSONObject dataset;
	private JSONObject datasetLocation;
	private JSONObject layer;
	private JSONObject layerLocation;

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		helper.setUp();
		helper.useAdminUser();

		dataset = helper.testCreateJsonEntity(helper.getServletPrefix()
				+ "/dataset", DatasetControllerTest.SAMPLE_DATASET);
		datasetLocation = new JSONObject(LocationControllerTest.SAMPLE_LOCATION).put(
				NodeConstants.COL_PARENT_ID, dataset.getString("id"));
		datasetLocation = helper.testCreateJsonEntity("/location", datasetLocation.toString());
		helper.addPublicReadOnlyAclToEntity(dataset);

		layer = helper.testCreateJsonEntity(helper.getServletPrefix()
				+ "/layer", LayerControllerTest.getSampleLayer(dataset.getString("id")));
		layerLocation = new JSONObject(LocationControllerTest.SAMPLE_LOCATION).put(
				NodeConstants.COL_PARENT_ID, layer.getString("id"));
		layerLocation = helper.testCreateJsonEntity("/location", layerLocation.toString());
	}

	/**
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {
		helper.tearDown();
	}

	/*************************************************************************************************************************
	 * Happy case tests, most are covered by the DefaultController tests and do
	 * not need to be repeated here
	 */

	/**
	 * @throws Exception
	 */
	@Test
	public void testCreateEula() throws Exception {
		JSONObject eula = helper.testCreateJsonEntity(helper.getServletPrefix()
				+ "/eula", SAMPLE_EULA);
		assertEquals(
				"The recipient acknowledges that the data herein is provided by TCGA and not SageBionetworks and must abide by ...",
				eula.getString("agreement"));
		assertEquals("TCGA Redistribution Use Agreement", eula
				.getString("name"));
		assertExpectedEulaProperties(eula);

		// We can currently only handle agreements of length 3000 characters or
		// less
		JSONObject storedEula = helper.testGetJsonEntity(eula.getString("uri"));
		String longAgreement = new String(new char[10])
				.replace(
						"\0",
						"Lorem ipsum vis alia possit dolores an, id quo apeirian consequat. Te usu nihil facilis forensibus, graece populo deserunt vel an. Populo semper eu quo, ne ignota deleniti salutatus mea. Ullum petentium et duo, adhuc detracto vel ei. Disputando delicatissimi et eos, eam no labore mollis,");
		assertTrue(3000 > longAgreement.length());
		storedEula.put("agreement", longAgreement);
		JSONObject updatedEula = helper.testUpdateJsonEntity(storedEula);
		assertEquals(longAgreement, updatedEula.getString("agreement"));
	}

	/**
	 * @throws Exception
	 */
	@Test
	public void testCreateAgreement() throws Exception {
		JSONObject eula = helper.testCreateJsonEntity(helper.getServletPrefix()
				+ "/eula", SAMPLE_EULA);
		assertEquals(
				"The recipient acknowledges that the data herein is provided by TCGA and not SageBionetworks and must abide by ...",
				eula.getString("agreement"));
		assertEquals("TCGA Redistribution Use Agreement", eula
				.getString("name"));
		assertExpectedEulaProperties(eula);

		JSONObject agreement = helper.testCreateJsonEntity(helper
				.getServletPrefix()
				+ "/agreement", "{\"name\":\"agreement\", \"datasetId\":\""
				+ dataset.getString("id") + "\", \"eulaId\":\""
				+ eula.getString("id") + "\"}");
		assertExpectedAgreementProperties(agreement);

		String query = "select * from agreement where agreement.datasetId == \""
				+ dataset.getString("id") + "\" and agreement.eulaId == \""
				+ eula.getString("id")
				+ "\" and agreement.createdBy == \"admin\"";
		JSONObject queryResult = helper.testQuery(query);
		assertEquals(1, queryResult.getInt("totalNumberOfResults"));
		JSONArray results = queryResult.getJSONArray("results");
		assertEquals(1, results.length());
		JSONObject result = results.getJSONObject(0);
		assertEquals(agreement.getString("id"), result
				.getString("agreement.id"));
	}

	/**
	 * @throws Exception
	 */
	@Test
	public void testEnforceUseAgreement() throws Exception {
		// Make a use agreement
		JSONObject eula = helper.testCreateJsonEntity(helper.getServletPrefix()
				+ "/eula", SAMPLE_EULA);
		helper.addPublicReadOnlyAclToEntity(eula);
		// Add the use agreement restriction to the dataset
		dataset.put("eulaId", eula.getString("id"));
		JSONObject updatedDataset = helper.testUpdateJsonEntity(dataset);
		assertEquals(eula.getString("id"), updatedDataset.getString("eulaId"));
		
		// Change the user from the creator of the dataset to someone else
		helper.useTestUser();
		// The ACL on the dataset has public read so this works
		helper.testGetJsonEntity(dataset.getString("uri"));
		// The ACL on the eula has public read so this works
		helper.testGetJsonEntity(eula.getString("uri"));
		// But the user has not signed the agreement so these does not work
		helper.testGetJsonEntityShouldFail(dataset.getString("locations"), HttpStatus.FORBIDDEN);
		helper.testGetJsonEntityShouldFail(datasetLocation.getString("uri"), HttpStatus.FORBIDDEN);
		helper.testGetJsonEntityShouldFail(layer.getString("locations"), HttpStatus.FORBIDDEN);
		helper.testGetJsonEntityShouldFail(layerLocation.getString("uri"), HttpStatus.FORBIDDEN);
		helper.testQueryShouldFail("select * from layerlocation where parentId == \"" + dataset.getString("id") + "\"", HttpStatus.FORBIDDEN);
		helper.testQueryShouldFail("select * from layerlocation where parentId == \"" + layer.getString("id") + "\"", HttpStatus.FORBIDDEN);
		
		// Make agreement
		JSONObject agreement = helper.testCreateJsonEntity(helper
				.getServletPrefix()
				+ "/agreement", "{\"name\":\"agreement\", \"datasetId\":\""
				+ dataset.getString("id") + "\", \"eulaId\":\""
				+ eula.getString("id") + "\"}");
		assertExpectedAgreementProperties(agreement);
		
		// Now that the user has signed the agreement, these do work
		helper.testGetJsonEntities(dataset.getString("locations"));
		helper.testGetJsonEntity(datasetLocation.getString("uri"));
		helper.testGetJsonEntities(layer.getString("locations"));
		helper.testGetJsonEntity(layerLocation.getString("uri"));
		JSONObject datasetLocationQueryResult = helper.testQuery("select * from layerlocation where location.parentId == \"" + dataset.getString("id") + "\"");
		assertEquals(1, datasetLocationQueryResult.getInt("totalNumberOfResults"));
		JSONObject layerLocationQueryResult = helper.testQuery("select * from layerlocation where location.parentId == \"" + layer.getString("id") + "\"");
		assertEquals(1, layerLocationQueryResult.getInt("totalNumberOfResults"));
	}

	/*****************************************************************************************************
	 * Eula-specific helpers
	 */

	/**
	 * @param eula
	 * @throws Exception
	 */
	public static void assertExpectedEulaProperties(JSONObject eula)
			throws Exception {
		// Check required properties
		assertTrue(eula.has("name"));
		assertFalse("null".equals(eula.getString("name")));
		assertTrue(eula.has("agreement"));
		assertFalse("null".equals(eula.getString("agreement")));
	}

	/**
	 * @param agreement
	 * @throws Exception
	 */
	public static void assertExpectedAgreementProperties(JSONObject agreement)
			throws Exception {
		// Check required properties
		assertTrue(agreement.has("datasetId"));
		assertFalse("null".equals(agreement.getString("datasetId")));
		assertTrue(agreement.has("datasetVersionNumber"));
		assertFalse("null".equals(agreement.getString("datasetVersionNumber")));
		assertTrue(agreement.has("eulaId"));
		assertFalse("null".equals(agreement.getString("eulaId")));
		assertTrue(agreement.has("eulaVersionNumber"));
		assertFalse("null".equals(agreement.getString("eulaVersionNumber")));
		assertTrue(agreement.has("createdBy"));
		assertFalse("null".equals(agreement.getString("creationDate")));
		assertTrue(agreement.has("createdBy"));
		assertFalse("null".equals(agreement.getString("creationDate")));
	}
}
