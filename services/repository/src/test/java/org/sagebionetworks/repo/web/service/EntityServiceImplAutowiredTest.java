package org.sagebionetworks.repo.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.sagebionetworks.common.util.progress.ProgressCallback;
import org.sagebionetworks.common.util.progress.SynchronizedProgressCallback;
import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.table.ColumnModelManager;
import org.sagebionetworks.repo.manager.table.TableEntityManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.EntityRef;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.NameConflictException;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.file.FileHandleDao;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.semaphore.LockContext;
import org.sagebionetworks.repo.model.semaphore.LockContext.ContextType;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.Dataset;
import org.sagebionetworks.repo.model.table.MaterializedView;
import org.sagebionetworks.repo.model.table.SnapshotRequest;
import org.sagebionetworks.repo.model.table.SnapshotResponse;
import org.sagebionetworks.repo.model.table.TableConstants;
import org.sagebionetworks.repo.model.table.TableEntity;
import org.sagebionetworks.repo.model.table.VirtualTable;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.TemporarilyUnavailableException;
import org.sagebionetworks.repo.web.service.metadata.EventType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.google.common.collect.Lists;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class EntityServiceImplAutowiredTest  {

	@Autowired
	private EntityService entityService;
	
	@Autowired
	private UserManager userManager;
	
	@Autowired
	private FileHandleDao fileHandleDao;
	
	@Autowired
	private ColumnModelManager columnModelManager;
	
	@Autowired 
	private TableEntityManager tableEntityManager;

	@Autowired
	private TableManagerSupport tableManagerSupport;
	
	@Autowired
	private IdGenerator idGenerator;
	
	private Project project;
	private List<String> toDelete;
	private HttpServletRequest mockRequest;
	private Long adminUserId;
	private UserInfo adminUserInfo;
	
	private S3FileHandle fileHandle1;
	private S3FileHandle fileHandle2;
	private S3FileHandle fileHandle3;
	
	private ColumnModel column;
	
	@BeforeEach
	public void before() throws Exception {
		toDelete = new LinkedList<String>();
		// Map test objects to their urls
		// Make sure we have a valid user.
		adminUserId = BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
		adminUserInfo = userManager.getUserInfo(adminUserId);
		UserInfo.validateUserInfo(adminUserInfo);
		
		mockRequest = Mockito.mock(HttpServletRequest.class);
		when(mockRequest.getServletPath()).thenReturn("/repo/v1");
		// Create a project
		project = new Project();
		project = entityService.createEntity(adminUserId, project, null);
		toDelete.add(project.getId());
		
		// Create some file handles
		fileHandle1 = new S3FileHandle();
		fileHandle1.setBucketName("bucket");
		fileHandle1.setKey("key");
		fileHandle1.setCreatedBy(adminUserInfo.getId().toString());
		fileHandle1.setCreatedOn(new Date());
		fileHandle1.setContentSize(123l);
		fileHandle1.setConcreteType("text/plain");
		fileHandle1.setEtag("etag");
		fileHandle1.setFileName("foo.bar");
		fileHandle1.setContentMd5("md5");
		fileHandle1.setId(idGenerator.generateNewId(IdType.FILE_IDS).toString());
		fileHandle1.setEtag(UUID.randomUUID().toString());
		
		fileHandle2 = new S3FileHandle();
		fileHandle2.setBucketName("bucket");
		fileHandle2.setKey("key2");
		fileHandle2.setCreatedBy(adminUserInfo.getId().toString());
		fileHandle2.setCreatedOn(new Date());
		fileHandle2.setContentSize(123l);
		fileHandle2.setConcreteType("text/plain");
		fileHandle2.setEtag("etag");
		fileHandle2.setFileName("two.txt");
		fileHandle2.setContentMd5("md52");
		fileHandle2.setId(idGenerator.generateNewId(IdType.FILE_IDS).toString());
		fileHandle2.setEtag(UUID.randomUUID().toString());
		
		fileHandle3 = new S3FileHandle();
		fileHandle3.setBucketName("bucket");
		fileHandle3.setKey("key3");
		fileHandle3.setCreatedBy(adminUserInfo.getId().toString());
		fileHandle3.setCreatedOn(new Date());
		fileHandle3.setContentSize(123l);
		fileHandle3.setConcreteType("text/plain");
		fileHandle3.setEtag("etag");
		fileHandle3.setFileName("three.txt");
		fileHandle3.setContentMd5(fileHandle1.getContentMd5());
		fileHandle3.setId(idGenerator.generateNewId(IdType.FILE_IDS).toString());
		fileHandle3.setEtag(UUID.randomUUID().toString());

		fileHandleDao.createBatch(Arrays.asList(fileHandle1, fileHandle2, fileHandle3));
		
		fileHandle1 = (S3FileHandle) fileHandleDao.get(fileHandle1.getId());
		fileHandle2 = (S3FileHandle) fileHandleDao.get(fileHandle2.getId());
		fileHandle3 = (S3FileHandle) fileHandleDao.get(fileHandle3.getId());
		
		column = new ColumnModel();
		column.setColumnType(ColumnType.INTEGER);
		column.setName("anInteger");
		column = columnModelManager.createColumnModel(adminUserInfo, column);
	}
	@AfterEach
	public void after(){
		if(toDelete != null){
			for(String id: toDelete){
				try {
					entityService.deleteEntity(adminUserId, id);
				} catch (Exception e) {	}
			}
		}
		fileHandleDao.truncateTable();
	}
	
	/**
	 * PLFM-1754 "Disallow FileEntity with Null FileHandle"
	 * @throws Exception
	 */
	@Test
	public void testPLFM_1754CreateNullFileHandleId() throws Exception {
		FileEntity file = new FileEntity();
		file.setParentId(project.getId());
		
		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			entityService.createEntity(adminUserId, file, null);
		}).getMessage();
		
		assertEquals("FileEntity.dataFileHandleId cannot be null", errorMessage);
	}
	
	/**
	 * PLFM-1754 "Disallow FileEntity with Null FileHandle"
	 * @throws Exception
	 */
	@Test
	public void testPLFM_1754HappyCase() throws Exception {
		FileEntity file = new FileEntity();
		file.setParentId(project.getId());
		file.setDataFileHandleId(fileHandle1.getId());
		file = entityService.createEntity(adminUserId, file, null);
		assertNotNull(file);
		// Make sure we can update it 
		file.setDataFileHandleId(fileHandle2.getId());
		file = entityService.updateEntity(adminUserId, file, false, null);
	}
	
	/**
	 * PLFM-1754 "Disallow FileEntity with Null FileHandle"
	 * @throws Exception
	 */
	@Test
	public void testPLFM_1754UpdateNull() throws Exception {
		FileEntity file = new FileEntity();
		file.setParentId(project.getId());
		file.setDataFileHandleId(fileHandle1.getId());
		file = entityService.createEntity(adminUserId, file, null);
		assertNotNull(file);
		// Now try to set it to null
		file.setDataFileHandleId(null);
		
		final FileEntity finalFile = file;
		
		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			entityService.updateEntity(adminUserId, finalFile, false, null);
		}).getMessage();
		
		assertEquals("FileEntity.dataFileHandleId cannot be null", errorMessage);
	}
	
	/**
	 * PLFM-1744 "Any change to a FileEntity's 'dataFileHandleId' should trigger a new version."
	 * ...as long as the MD5 does not match (See PLFM-6429)
	 * 
	 * @throws NotFoundException 
	 * @throws UnauthorizedException 
	 * @throws InvalidModelException 
	 * @throws DatastoreException 
	 */
	@Test
	public void testPLFM_1744() throws Exception {
		FileEntity file = new FileEntity();
		file.setParentId(project.getId());
		file.setDataFileHandleId(fileHandle1.getId());
		file = entityService.createEntity(adminUserId, file, null);
		assertNotNull(file);
		assertEquals(new Long(1), file.getVersionNumber(), "Should start off as version one");
		// Make sure we can update it 
		file.setDataFileHandleId(fileHandle2.getId());
		file = entityService.updateEntity(adminUserId, file, false, null);
		// This should trigger a version change.
		assertEquals(new Long(2), file.getVersionNumber(), "Changing the dataFileHandleId of a FileEntity should have created a new version");
		// Now make sure if we change the name but the file
		file.setName("newName");
		file = entityService.updateEntity(adminUserId, file, false, null);
		assertEquals(new Long(2), file.getVersionNumber(), "A new version should not have been created when a name changed");
	}
	
	// See PLFM-6429
	@Test
	public void testUpdateEntityFileHandleNoAutoVersionWhenMD5Matches() throws Exception {
		FileEntity file = new FileEntity();
		file.setParentId(project.getId());
		file.setDataFileHandleId(fileHandle1.getId());
		file = entityService.createEntity(adminUserId, file, null);
		assertNotNull(file);
		assertEquals(1L, file.getVersionNumber(), "Should start off as version one");
		// Make sure we can update it 
		file.setDataFileHandleId(fileHandle3.getId());
		file = entityService.updateEntity(adminUserId, file, false, null);
		// This should NOT trigger a version change.
		assertEquals(1L, file.getVersionNumber());
	}

	@Test
	public void testProjectAlias() {
		String alias1 = "alias" + RandomUtils.nextInt();
		String alias2 = "alias" + RandomUtils.nextInt();
		String alias3 = "alias" + RandomUtils.nextInt();
		// create alias1
		Project project1 = new Project();
		project1.setName("project" + UUID.randomUUID());
		project1.setAlias(alias1);
		project1 = entityService.createEntity(adminUserId, project1, null);
		toDelete.add(project1.getId());
		assertEquals(alias1, ((Project) entityService.getEntity(adminUserId, project1.getId())).getAlias());
		// create alias2
		Project project2 = new Project();
		project2.setName("project" + UUID.randomUUID());
		project2.setAlias(alias2);
		project2 = entityService.createEntity(adminUserId, project2, null);
		toDelete.add(project2.getId());
		assertEquals(alias2, ((Project) entityService.getEntity(adminUserId, project2.getId())).getAlias());
		// fail on create alias1
		Project projectFailCreate = new Project();
		projectFailCreate.setName("project" + UUID.randomUUID());
		projectFailCreate.setAlias(alias1);
		try {
			entityService.createEntity(adminUserId, projectFailCreate, null);
			fail("duplicate entry should have been rejected");
		} catch (NameConflictException e) {
			// expected
		}
		// update to null
		project2.setAlias(null);
		project2 = entityService.updateEntity(adminUserId, project2, false, null);
		assertNull(((Project) entityService.getEntity(adminUserId, project2.getId())).getAlias());
		// fail on update to alias1
		try {
			project2.setAlias(alias1);
			entityService.updateEntity(adminUserId, project2, false, null);
			fail("duplicate entry should have been rejected");
		} catch (NameConflictException e) {
			// expected
		}
		project2.setAlias(alias3);
		project2 = entityService.updateEntity(adminUserId, project2, false, null);
		assertEquals(alias3, ((Project) entityService.getEntity(adminUserId, project2.getId())).getAlias());
		// create alias2 again
		Project project2Again = new Project();
		project2Again.setName("project" + UUID.randomUUID());
		project2Again.setAlias(alias2);
		project2Again = entityService.createEntity(adminUserId, project2Again, null);
		toDelete.add(project2Again.getId());
		assertEquals(alias2, ((Project) entityService.getEntity(adminUserId, project2Again.getId())).getAlias());
	}
	
	@Test
	public void testTableEntityCreateGet(){
		List<String> columnIds = Lists.newArrayList(column.getId());
		TableEntity table = new TableEntity();
		table.setParentId(project.getId());
		table.setName("SampleTable");
		table.setColumnIds(columnIds);
		
		table = entityService.createEntity(adminUserId, table, null);
		assertEquals(columnIds, table.getColumnIds());
		// default label and comment should be added.
		assertEquals(TableConstants.IN_PROGRESS, table.getVersionLabel());
		assertEquals(TableConstants.IN_PROGRESS, table.getVersionComment());
		
		table = entityService.getEntity(adminUserId, table.getId(), TableEntity.class);
		assertEquals(columnIds, table.getColumnIds());
		assertEquals(TableConstants.IN_PROGRESS, table.getVersionLabel());
		assertEquals(TableConstants.IN_PROGRESS, table.getVersionComment());
	}
	
	@Test
	public void testTableEntityCreateWithLableAndComment(){
		List<String> columnIds = Lists.newArrayList(column.getId());
		TableEntity table = new TableEntity();
		table.setParentId(project.getId());
		table.setName("SampleTable");
		table.setColumnIds(columnIds);
		String label = "a label";
		String comment = "a comment";
		table.setVersionLabel(label);
		table.setVersionComment(comment);
		
		table = entityService.createEntity(adminUserId, table, null);
		assertEquals(columnIds, table.getColumnIds());
		// default label and comment should be added.
		assertEquals(label, table.getVersionLabel());
		assertEquals(comment, table.getVersionComment());
		
		table = entityService.getEntity(adminUserId, table.getId(), TableEntity.class);
		assertEquals(columnIds, table.getColumnIds());
		assertEquals(label, table.getVersionLabel());
		assertEquals(comment, table.getVersionComment());
	}
	
	@Test
	public void testCreateTableEntityVersion() {
		List<String> columnIds = Lists.newArrayList(column.getId());
		TableEntity table = new TableEntity();
		table.setParentId(project.getId());
		table.setName("SampleTable");
		table.setColumnIds(columnIds);
		
		table = entityService.createEntity(adminUserId, table, null);
		// the first version of a table should not have a transaction linked
		Optional<Long> optional =tableEntityManager.getTransactionForVersion(table.getId(), table.getVersionNumber());
		assertNotNull(optional);
		assertFalse(optional.isPresent());
	}
	
	@Test
	public void testTableUpdateNewVersion() {
		List<String> columnIds = Lists.newArrayList(column.getId());
		TableEntity table = new TableEntity();
		table.setParentId(project.getId());
		table.setName("SampleTable");
		table.setColumnIds(columnIds);
		
		table = entityService.createEntity(adminUserId, table, null);
		String activityId = null;
		boolean newVersion = true;
		table.setVersionLabel(null);
		// Call under test
		entityService.updateEntity(adminUserId, table, newVersion, activityId);
	}
	
	@Test
	public void testTableUpdateNoNewVersion() {
		List<String> columnIds = Lists.newArrayList(column.getId());
		TableEntity table = new TableEntity();
		table.setParentId(project.getId());
		table.setName("SampleTable");
		table.setColumnIds(columnIds);
		
		table = entityService.createEntity(adminUserId, table, null);
		long firstVersion = table.getVersionNumber();
		String activityId = null;
		boolean newVersion = false;
		// Create a new version of the entity
		table = entityService.updateEntity(adminUserId, table, newVersion, activityId);
		assertTrue(firstVersion == table.getVersionNumber());
		// update without a version change should not result in the binding of a transaction.
		Optional<Long> optional =tableEntityManager.getTransactionForVersion(table.getId(), table.getVersionNumber());
		assertNotNull(optional);
		assertFalse(optional.isPresent());
	}
	
	/**
	 * Test for PLFM-5685
	 */
	@Test
	public void testGetEntityVersionTableSchema() {
		
		ColumnModel one = new ColumnModel();
		one.setColumnType(ColumnType.INTEGER);
		one.setName("one");
		one = columnModelManager.createColumnModel(adminUserInfo, one);
		
		ColumnModel two = new ColumnModel();
		two.setColumnType(ColumnType.INTEGER);
		two.setName("two");
		two = columnModelManager.createColumnModel(adminUserInfo, two);
		
		List<String> firstSchema = Lists.newArrayList(one.getId());
		List<String> secondSchema = Lists.newArrayList(one.getId(), two.getId());
		TableEntity table = new TableEntity();
		table.setParentId(project.getId());
		table.setName("PLFM-5685");
		table.setColumnIds(firstSchema);
		
		table = entityService.createEntity(adminUserId, table, null);
		
		// call under test
		table = entityService.getEntityForVersion(adminUserId, table.getId(), 1L, TableEntity.class);
		assertEquals(firstSchema, table.getColumnIds());
		
		// create a snapshot to to create a new version.
		SnapshotRequest snapshotRequest = new SnapshotRequest();
		SnapshotResponse snapshotResponse = tableEntityManager.createTableSnapshot(adminUserInfo, table.getId(), snapshotRequest);
		snapshotResponse.getSnapshotVersionNumber();
		
		// call under test
		table = entityService.getEntityForVersion(adminUserId, table.getId(), 1L, TableEntity.class);
		assertEquals(firstSchema, table.getColumnIds());
		// call under test
		table = entityService.getEntityForVersion(adminUserId, table.getId(), 2L, TableEntity.class);
		assertEquals(firstSchema, table.getColumnIds());
		// call under test
		table = entityService.getEntity(adminUserInfo, table.getId(), TableEntity.class, EventType.GET);
		assertEquals(firstSchema, table.getColumnIds());
		
		// change the schema of version two.
		boolean newVersion = false;
		String activityId = null;
		table.setColumnIds(secondSchema);
		table = entityService.updateEntity(adminUserId, table, newVersion, activityId);
		
		// call under test
		table = entityService.getEntityForVersion(adminUserId, table.getId(), 1L, TableEntity.class);
		assertEquals(firstSchema, table.getColumnIds());
		// call under test
		table = entityService.getEntityForVersion(adminUserId, table.getId(), 2L, TableEntity.class);
		assertEquals(secondSchema, table.getColumnIds());
		// call under test
		table = entityService.getEntity(adminUserInfo, table.getId(), TableEntity.class, EventType.GET);
		assertEquals(secondSchema, table.getColumnIds());
	}
	
	@Test
	public void testDatasetCRUD() {
		boolean newVersion = false;
		String activityId = null;
		Dataset dataset = new Dataset();
		dataset.setName("first-dataset");
		dataset.setParentId(project.getId());
		// call under test (create and get)
		dataset = entityService.createEntity(adminUserId, dataset, activityId);
		assertNotNull(dataset.getId());
		final String datsetId = dataset.getId();
		
		dataset.setName("new-dataset-name");
		// call under test (update)
		dataset = entityService.updateEntity(adminUserId, dataset, newVersion, activityId);
		
		// call under test (delete)
		entityService.deleteEntity(adminUserId, datsetId);
		
		assertThrows(NotFoundException.class, ()->{
			entityService.getEntity(adminUserId, datsetId);
		});
		
	}
	
	@Test
	public void testDatasetScope() {
		FileEntity file = new FileEntity();
		file.setParentId(project.getId());
		file.setDataFileHandleId(fileHandle1.getId());
		file = entityService.createEntity(adminUserId, file, null);
		
		ColumnModel one = new ColumnModel();
		one.setColumnType(ColumnType.INTEGER);
		one.setName("one");
		one = columnModelManager.createColumnModel(adminUserInfo, one);
		List<String> schema = Arrays.asList(one.getId());
		
		List<EntityRef> scope = Arrays.asList(new EntityRef().setEntityId(file.getId()).setVersionNumber(file.getVersionNumber()));

		boolean newVersion = false;
		String activityId = null;
		Dataset dataset = new Dataset();
		dataset.setName("first-dataset");
		dataset.setParentId(project.getId());

		dataset.setItems(scope);
		dataset.setColumnIds(schema);
		// call under test (create and get)
		dataset = entityService.createEntity(adminUserId, dataset, activityId);
		assertNotNull(dataset.getId());
		final String datsetId = dataset.getId();
		assertEquals(scope, dataset.getItems());
		assertEquals(schema, dataset.getColumnIds());
		
		// update
		dataset.setItems(null);
		dataset = entityService.updateEntity(adminUserId, dataset, newVersion, activityId);
		assertNull(dataset.getItems());
	}
	
	@Test
	public void testDatasetScopeOverLimit() {
		ColumnModel one = new ColumnModel();
		one.setColumnType(ColumnType.INTEGER);
		one.setName("one");
		one = columnModelManager.createColumnModel(adminUserInfo, one);
		List<String> schema = Arrays.asList(one.getId());
		
		List<EntityRef> scope = new ArrayList<>(TableConstants.MAX_CONTAINERS_PER_VIEW+1);
		for(int i=0; i<TableConstants.MAX_CONTAINERS_PER_VIEW+1; i++) {
			scope.add(new EntityRef().setEntityId("syn"+(i+100_100_000)).setVersionNumber((long)i));
		}

		String activityId = null;
		Dataset dataset = new Dataset();
		dataset.setName("first-dataset");
		dataset.setParentId(project.getId());

		dataset.setItems(scope);
		dataset.setColumnIds(schema);
		String message = assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			entityService.createEntity(adminUserId, dataset, activityId);
		}).getMessage();
		assertEquals(String.format(TableConstants.MAXIMUM_OF_ITEMS_IN_A_DATASET_EXCEEDED, TableConstants.MAX_CONTAINERS_PER_VIEW), message);
	}
	
	@Test
	public void testMaterializedViewCRUD() {
		// create a schema for the referenced tabled.
		ColumnModel foo = new ColumnModel();
		foo.setColumnType(ColumnType.INTEGER);
		foo.setName("foo");
		foo = columnModelManager.createColumnModel(adminUserInfo, foo);
		
		TableEntity table = new TableEntity();
		table.setParentId(project.getId());
		table.setName(UUID.randomUUID().toString());
		table.setColumnIds(Arrays.asList(foo.getId()));
		table = entityService.createEntity(adminUserId, table, null);
		
		boolean newVersion = false;
		String activityId = null;
		
		MaterializedView materializedView = new MaterializedView();
		
		materializedView.setName("materializedView");
		materializedView.setParentId(project.getId());
		materializedView.setDefiningSQL("SELECT * FROM "+table.getId());
		
		// call under test (create and get)
		materializedView = entityService.createEntity(adminUserId, materializedView, activityId);
		
		assertEquals(Arrays.asList(foo.getId()), materializedView.getColumnIds());
		
		assertEquals(materializedView, entityService.getEntity(adminUserId, materializedView.getId()));
		
		String updatedSQL = materializedView.getDefiningSQL() + " WHERE foo = 'bar'";
		
		materializedView.setDefiningSQL(updatedSQL);
		
		// call under test (update)
		materializedView = entityService.updateEntity(adminUserId, materializedView, newVersion, activityId);
		
		assertEquals(updatedSQL, materializedView.getDefiningSQL());
		
		String id = materializedView.getId();
		
		// call under test (delete)
		entityService.deleteEntity(adminUserId, id);
		
		assertThrows(NotFoundException.class, ()-> {
			entityService.getEntity(adminUserId, id);
		});
		
	}
	
	@Test
	public void testMaterializedViewWithNullSQL() {
		String activityId = null;
		
		MaterializedView materializedView = new MaterializedView();
		
		materializedView.setName("materializedView");
		materializedView.setParentId(project.getId());
		materializedView.setDefiningSQL(null);
		
		String message = assertThrows(IllegalArgumentException.class, () -> {			
			// call under test (create and get)
			entityService.createEntity(adminUserId, materializedView, activityId);
		}).getMessage();
		
		assertEquals("The definingSQL of the materialized view is required and must not be the empty string.", message);
		
	}

	// Reproduces https://sagebionetworks.jira.com/browse/PLFM-7200
	@Test
	public void testMaterializedViewWithSQLQueryReferencingUnsupportedType() {
		String activityId = null;

		MaterializedView materializedView = new MaterializedView();
		materializedView.setName("materializedView");
		materializedView.setParentId(project.getId());
		materializedView.setDefiningSQL("SELECT * FROM "+ project.getId());

		// call under test (create and get)
		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			entityService.createEntity(adminUserId, materializedView, activityId);
		}).getMessage();

		assertEquals(project.getId() + " is not a table or view", errorMessage);
	}
	
	@Test
	public void testVirtualTableCRUD() {
		
		// create a schema for the referenced tabled.
		ColumnModel foo = new ColumnModel();
		foo.setColumnType(ColumnType.INTEGER);
		foo.setName("foo");
		foo = columnModelManager.createColumnModel(adminUserInfo, foo);
		
		TableEntity table = new TableEntity();
		table.setParentId(project.getId());
		table.setName(UUID.randomUUID().toString());
		table.setColumnIds(Arrays.asList(foo.getId()));
		table = entityService.createEntity(adminUserId, table, null);
		
		boolean newVersion = false;
		String activityId = null;

		VirtualTable virtualTable = new VirtualTable();

		virtualTable.setName("virtualTable");
		virtualTable.setParentId(project.getId());
		virtualTable.setDefiningSQL("SELECT * FROM "+ table.getId());

		// call under test (create and get)
		virtualTable = entityService.createEntity(adminUserId, virtualTable, activityId);

		assertEquals(virtualTable, entityService.getEntity(adminUserId, virtualTable.getId()));

		String updatedSQL = virtualTable.getDefiningSQL() + " WHERE foo = 'bar'";

		virtualTable.setDefiningSQL(updatedSQL);

		// call under test (update)
		virtualTable = entityService.updateEntity(adminUserId, virtualTable, newVersion, activityId);

		assertEquals(updatedSQL, virtualTable.getDefiningSQL());

		String id = virtualTable.getId();

		// call under test (delete)
		entityService.deleteEntity(adminUserId, id);

		assertThrows(NotFoundException.class, ()-> {
			entityService.getEntity(adminUserId, id);
		});
	}

	@Test
	public void testVirtualTableWithNullSQL() {
		String activityId = null;

		VirtualTable virtualTable = new VirtualTable();

		virtualTable.setName("virtualTable");
		virtualTable.setParentId(project.getId());
		virtualTable.setDefiningSQL(null);

		String message = assertThrows(IllegalArgumentException.class, () -> {			
			// call under test (create and get)
			entityService.createEntity(adminUserId, virtualTable, activityId);
		}).getMessage();

		assertEquals("The definingSQL of the virtual table is required and must not be the empty string.", message);

	}
	
	@Test
	public void testCreateTableWithSearchEnabled() {
		TableEntity table = new TableEntity();
		table.setParentId(project.getId());
		table.setName("SampleTable");
		table.setIsSearchEnabled(true);
		
		// Call under test
		table = entityService.createEntity(adminUserId, table, null);
		table = entityService.getEntity(adminUserId, table.getId(), TableEntity.class);
		
		assertTrue(table.getIsSearchEnabled());
		
	}
	
	@Test
	public void testCreateTableWithNullSearchEnabled() {
		TableEntity table = new TableEntity();
		table.setParentId(project.getId());
		table.setName("SampleTable");
		table.setIsSearchEnabled(null);
		
		// Call under test
		table = entityService.createEntity(adminUserId, table, null);
		table = entityService.getEntity(adminUserId, table.getId(), TableEntity.class);
		
		assertNull(table.getIsSearchEnabled());
		
	}
	
	@Test
	public void testUpdateTableWithNullSearchEnabled() {
		TableEntity table = new TableEntity();
		table.setParentId(project.getId());
		table.setName("SampleTable");
		table.setIsSearchEnabled(false);
		
		table = entityService.createEntity(adminUserId, table, null);
		table = entityService.getEntity(adminUserId, table.getId(), TableEntity.class);
		
		assertFalse(table.getIsSearchEnabled());
		
		table.setIsSearchEnabled(null);
		
		// Call under test
		table = entityService.updateEntity(adminUserId, table, false, null);
		
		table = entityService.getEntity(adminUserId, table.getId(), TableEntity.class);
		
		assertFalse(table.getIsSearchEnabled());
		
	}
	
	@Test
	public void testUpdateTableWithSearchEnabled() {
		TableEntity table = new TableEntity();
		table.setParentId(project.getId());
		table.setName("SampleTable");
		table.setIsSearchEnabled(false);
		
		table = entityService.createEntity(adminUserId, table, null);
		table = entityService.getEntity(adminUserId, table.getId(), TableEntity.class);
		
		assertFalse(table.getIsSearchEnabled());
		
		table.setIsSearchEnabled(true);
		
		// Call under test
		table = entityService.updateEntity(adminUserId, table, false, null);
		
		table = entityService.getEntity(adminUserId, table.getId(), TableEntity.class);
		
		assertTrue(table.getIsSearchEnabled());
		
	}
	
	@Test
	public void testDatasetCreationAndUpdateCalculateSizeAndChecksum() {
		// Create a file entity
		FileEntity file = new FileEntity();
		file.setName("FileName");
		file.setParentId(project.getId());
		file.setDataFileHandleId(fileHandle1.getId());
		// Save it
		file = entityService.createEntity(adminUserId, file, null);
		assertNotNull(file);
		toDelete.add(file.getId());
		//create DataSet
		final Dataset dataset = new Dataset();
		dataset.setParentId(project.getId());
		dataset.setVersionComment("1");
		dataset.setName("Dataset");
		dataset.setDescription("Human readable text");
		final List<EntityRef> entityRefList = new ArrayList<>();
		final EntityRef entityRef = new EntityRef();
		entityRef.setEntityId(file.getId());
		entityRef.setVersionNumber(file.getVersionNumber());
		entityRefList.add(entityRef);
		dataset.setItems(entityRefList);

		//call under test
		final Dataset createdDataset = entityService.createEntity(adminUserId, dataset, null);
		assertNotNull(createdDataset);
		assertNotNull(createdDataset.getChecksum());
		assertEquals(fileHandle1.getContentSize(), createdDataset.getSize());

		//update Dataset
		FileEntity file2 = new FileEntity();
		file2.setName("FileName1");
		file2.setParentId(project.getId());
		file2.setDataFileHandleId(fileHandle2.getId());
		// Save it
		file2 = entityService.createEntity(adminUserId, file2, null);
		assertNotNull(file2);
		toDelete.add(file2.getId());

		final EntityRef entityRef2 = new EntityRef();
		entityRef2.setEntityId(file2.getId());
		entityRef2.setVersionNumber(file2.getVersionNumber());
		createdDataset.getItems().add(entityRef2);
		//call under test
		final Dataset updatedDataset = entityService.updateEntity(adminUserId, createdDataset, false, null);
		assertNotNull(updatedDataset);
		assertNotNull(updatedDataset.getChecksum());
		assertEquals(fileHandle1.getContentSize() + fileHandle2.getContentSize(), updatedDataset.getSize());
		toDelete.add(updatedDataset.getId());
	}
	
	// Test to reproduce https://sagebionetworks.jira.com/browse/PLFM-7863
	@Test
	public void testUpdateTableWithLockUnavailableException() throws Exception {
		TableEntity table = new TableEntity();
		table.setParentId(project.getId());
		table.setName("SampleTable");
		table.setIsSearchEnabled(false);
		
		table = entityService.createEntity(adminUserId, table, null);
		table = entityService.getEntity(adminUserId, table.getId(), TableEntity.class);
		
		IdAndVersion idAndVersion = IdAndVersion.parse(table.getId());
		SynchronizedProgressCallback callback = new SynchronizedProgressCallback(5);
		
		assertThrows(TemporarilyUnavailableException.class, () -> {
			// We acquire a lock on the table, so the update will fail
			tableManagerSupport.tryRunWithTableExclusiveLock(callback, new LockContext(ContextType.TableUpdate, idAndVersion), idAndVersion, (ProgressCallback callbackInner) -> {
				TableEntity updateTable = entityService.getEntity(adminUserId, idAndVersion.getId().toString(), TableEntity.class);
				updateTable.setName("Updated name");
				updateTable.setIsSearchEnabled(true);
				entityService.updateEntity(adminUserId, updateTable, false, null);
				return null;
			});	
		});
		
		table = entityService.getEntity(adminUserId, table.getId(), TableEntity.class);
		
		// The failure of the lock acquisition should roll back the main transaction
		assertEquals("SampleTable", table.getName());
		assertFalse(table.getIsSearchEnabled());
	}
}
