package org.sagebionetworks.snapshot.workers;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.AsynchronousJobWorkerHelper;
import org.sagebionetworks.StackConfigurationSingleton;
import org.sagebionetworks.audit.dao.ObjectRecordDAO;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.AccessControlListDAO;
import org.sagebionetworks.repo.model.AccessRequirement;
import org.sagebionetworks.repo.model.AccessRequirementDAO;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.Node;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.RestrictableObjectDescriptor;
import org.sagebionetworks.repo.model.RestrictableObjectType;
import org.sagebionetworks.repo.model.TermsOfUseAccessRequirement;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.audit.NodeRecord;
import org.sagebionetworks.repo.model.audit.ObjectRecord;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.util.AccessControlListUtil;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.sagebionetworks.snapshot.workers.writers.NodeObjectRecordWriter;
import org.sagebionetworks.workers.util.aws.message.QueueCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = {"classpath:test-context.xml"})
public class NodeObjectSnapshotWorkerIntegrationTest {

	private static final String QUEUE_NAME = "OBJECT";

	@Autowired
	private NodeDAO nodeDao;
	@Autowired
	private ObjectRecordDAO objectRecordDAO;
	@Autowired
	private QueueCleaner queueCleaner;
	@Autowired
	private AccessControlListDAO accessControlListDAO;
	@Autowired
	private AccessRequirementDAO accessRequirementDAO;
	@Autowired
	private AsynchronousJobWorkerHelper asyncHelper;

	private List<String> toDelete = new ArrayList<String>();
	private Long creatorUserGroupId;
	private Long altUserGroupId;
	private String type;
	UserInfo adminUser;

	@BeforeEach
	public void before() {
		creatorUserGroupId = BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
		altUserGroupId = BOOTSTRAP_PRINCIPAL.AUTHENTICATED_USERS_GROUP.getPrincipalId();
		adminUser = new UserInfo(true, creatorUserGroupId);
		assertNotNull(nodeDao);
		assertNotNull(objectRecordDAO);
		assertNotNull(queueCleaner);
		toDelete = new ArrayList<String>();

		type = NodeRecord.class.getSimpleName().toLowerCase();
		queueCleaner.purgeQueue(StackConfigurationSingleton.singleton().getQueueName(QUEUE_NAME));
		
		// Clear the data for this test instance.
		objectRecordDAO.deleteAllStackInstanceBatches(type);
	}
	
	@AfterEach
	public void after() {
		if(toDelete != null && nodeDao != null){
			for(String id:  toDelete){
				// Delete each
				try{
					nodeDao.delete(id);
				}catch (NotFoundException e) {
					// happens if the object no longer exists.
				}
			}
		}
	}

	@Test
	public void testObjectSnapshotsWithReadOnlyMode() throws Exception {
		asyncHelper.runInReadOnlyMode(()->{
			Set<String> keys = ObjectSnapshotWorkerIntegrationTestUtils.listAllKeys(objectRecordDAO, type);

			Node toCreate = createNew("node name", creatorUserGroupId, altUserGroupId);
			toCreate = nodeDao.createNewNode(toCreate);
			toDelete.add(toCreate.getId());
			assertNotNull(toCreate.getId());
			// This node should exist
			assertTrue(nodeDao.doesNodeExist(KeyFactory.stringToKey(toCreate.getId())));
			// add an acl.
			AccessControlList acl = AccessControlListUtil.createACLToGrantEntityAdminAccess(toCreate.getId(), adminUser, new Date());
			accessControlListDAO.create(acl, ObjectType.ENTITY);

			// fetch it
			Node node = nodeDao.getNode(toCreate.getId());
			String benefactorId = nodeDao.getBenefactor(toCreate.getId());
			String projectId = nodeDao.getProjectId(toCreate.getId()).orElseThrow();
			NodeRecord record = NodeObjectRecordWriter.buildNodeRecord(node, benefactorId, projectId);
			
			record.setIsPublic(false);
			record.setIsRestricted(false);
			record.setIsControlled(false);
			record.setEffectiveArs(Collections.emptyList());
			
			ObjectRecord expectedRecord = new ObjectRecord();
			expectedRecord.setJsonClassName(record.getClass().getSimpleName().toLowerCase());
			expectedRecord.setJsonString(EntityFactory.createJSONStringForEntity(record));

			assertTrue(ObjectSnapshotWorkerIntegrationTestUtils.waitForObjects(keys, Arrays.asList(expectedRecord), objectRecordDAO, type));
			return 0;
		});
	}

	@Test
	public void testAR() throws Exception {
		Set<String> keys = ObjectSnapshotWorkerIntegrationTestUtils.listAllKeys(objectRecordDAO, type);

		Node toCreate = createNew("node name", creatorUserGroupId, altUserGroupId);
		toCreate = nodeDao.createNewNode(toCreate);
		toDelete.add(toCreate.getId());
		assertNotNull(toCreate.getId());
		// This node should exist
		assertTrue(nodeDao.doesNodeExist(KeyFactory.stringToKey(toCreate.getId())));
		// add an acl.
		AccessControlList acl = AccessControlListUtil.createACLToGrantEntityAdminAccess(toCreate.getId(), adminUser, new Date());
		accessControlListDAO.create(acl, ObjectType.ENTITY);
		// add an AR.
		AccessRequirement ar = new TermsOfUseAccessRequirement();
		ar.setAccessType(ACCESS_TYPE.DOWNLOAD);
		RestrictableObjectDescriptor rod = new RestrictableObjectDescriptor();
		rod.setId(toCreate.getId());
		rod.setType(RestrictableObjectType.ENTITY);
		List<RestrictableObjectDescriptor> rods = Arrays.asList(rod);
		ar.setSubjectIds(rods);
		ar.setCreatedOn(new Date());
		ar.setCreatedBy(String.valueOf(creatorUserGroupId));
		ar.setModifiedOn(new Date());
		ar.setModifiedBy(String.valueOf(creatorUserGroupId));
		accessRequirementDAO.create(ar);

		// fetch it
		Node node = nodeDao.getNode(toCreate.getId());
		String benefactorId = nodeDao.getBenefactor(toCreate.getId());
		String projectId = nodeDao.getProjectId(toCreate.getId()).orElseThrow();
		NodeRecord record = NodeObjectRecordWriter.buildNodeRecord(node, benefactorId, projectId);
		
		record.setIsPublic(false);
		record.setIsRestricted(true);
		record.setIsControlled(false);
		record.setEffectiveArs(List.of(ar.getId()));
		
		ObjectRecord expectedRecord = new ObjectRecord();
		expectedRecord.setJsonClassName(record.getClass().getSimpleName().toLowerCase());
		expectedRecord.setJsonString(EntityFactory.createJSONStringForEntity(record));

		assertTrue(ObjectSnapshotWorkerIntegrationTestUtils.waitForObjects(keys, Arrays.asList(expectedRecord), objectRecordDAO, type));
	}

	private static Node createNew(String name, Long creatorUserGroupId, Long modifierGroupId){
		Node node = new Node();
		node.setName(name);
		node.setCreatedByPrincipalId(creatorUserGroupId);
		node.setModifiedByPrincipalId(modifierGroupId);
		node.setCreatedOn(new Date(System.currentTimeMillis()));
		node.setModifiedOn(node.getCreatedOn());
		node.setNodeType(EntityType.project);
		return node;
	}

}
