package org.sagebionetworks.repo.manager.asynch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.audit.dao.ObjectRecordDAO;
import org.sagebionetworks.cloudwatch.Consumer;
import org.sagebionetworks.cloudwatch.ProfileData;
import org.sagebionetworks.repo.manager.AuthorizationManager;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.StackStatusDao;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.asynch.AsynchJobState;
import org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus;
import org.sagebionetworks.repo.model.asynch.AsynchronousRequestBody;
import org.sagebionetworks.repo.model.asynch.ReadOnlyRequestBody;
import org.sagebionetworks.repo.model.auth.CallersContext;
import org.sagebionetworks.repo.model.dao.asynch.AsynchronousJobStatusDAO;
import org.sagebionetworks.repo.model.dbo.asynch.AsynchJobType;
import org.sagebionetworks.repo.model.file.BulkFileDownloadRequest;
import org.sagebionetworks.repo.model.status.StatusEnum;
import org.sagebionetworks.repo.model.table.DownloadFromTableRequest;
import org.sagebionetworks.repo.model.table.DownloadFromTableResult;
import org.sagebionetworks.repo.model.table.UploadToTableRequest;
import org.sagebionetworks.repo.model.table.UploadToTableResult;
import org.sagebionetworks.repo.web.NotFoundException;

import com.amazonaws.services.cloudwatch.model.StandardUnit;

/**
 * Unit test for AsynchJobStatusManagerImpl
 *
 */
@ExtendWith(MockitoExtension.class)
public class AsynchJobStatusManagerImplTest {
	
	@Mock
	AsynchronousJobStatusDAO mockAsynchJobStatusDao;
	@Mock
	AuthorizationManager mockAuthorizationManager;
	@Mock
	StackStatusDao mockStackStatusDao;
	@Mock
	AsynchJobQueuePublisher mockAsynchJobQueuePublisher;
	@Mock
	JobHashProvider mockJobHashProvider;
	@Mock
	ObjectRecordDAO mockObjectRecordDAO;
	@Mock
	StackConfiguration mockStackConfig;
	@Mock
	Consumer mockConsumer;
	@Captor
	ArgumentCaptor<ProfileData> profileCaptor;
	
	@InjectMocks
	AsynchJobStatusManagerImpl manager;
	
	UserInfo user = null;
	AsynchronousJobStatus status;
	String startedJobId;
	String instance;
	long runtimeMS;
	
	@BeforeEach
	public void before() throws DatastoreException, NotFoundException{
		startedJobId = "99999";
	
		user = new UserInfo(false);
		user.setId(007L);
		
		status = new AsynchronousJobStatus();
		status.setStartedByUserId(user.getId());
		status.setJobId("8888");
		status.setRequestBody(new BulkFileDownloadRequest());
		status.setCallersContext(new CallersContext().setSessionId(UUID.randomUUID().toString()));
		
		instance = "test1";

		
		runtimeMS = 567L;
	}

	void setupStartJob() {
		when(mockAsynchJobStatusDao.startJob(any(), any(AsynchronousRequestBody.class))).thenAnswer(new Answer<AsynchronousJobStatus>() {
			@Override
			public AsynchronousJobStatus answer(InvocationOnMock invocation)
					throws Throwable {
				UserInfo user = (UserInfo) invocation.getArguments()[0];
				AsynchronousRequestBody body = (AsynchronousRequestBody) invocation.getArguments()[1];
				AsynchronousJobStatus results = null;
				if(user != null && body != null){
					results = new AsynchronousJobStatus();
					results.setStartedByUserId(user.getId());
					results.setRequestBody(body);
					results.setJobId(startedJobId);
					results.setCallersContext(user.getContext());
				}
				return results;
			}
		});
	}
	
	@Test
	public void testStartJobNulls() throws DatastoreException, NotFoundException{
		assertThrows(IllegalArgumentException.class, ()->{
			manager.startJob(null, null);
		});
	}
	
	@Test
	public void testStartJobBodyNull() throws DatastoreException, NotFoundException{
		assertThrows(IllegalArgumentException.class, ()->{
			manager.startJob(user, null);
		});
	}
	
	@Test
	public void testStartJobBodyUploadHappy() throws DatastoreException, NotFoundException{
		setupStartJob();
		UploadToTableRequest body = new UploadToTableRequest();
		body.setTableId("syn123");
		body.setUploadFileHandleId("456");
		AsynchronousJobStatus status = manager.startJob(user, body);
		assertNotNull(status);
		assertEquals(body, status.getRequestBody());
		assertNull(status.getCallersContext());
		verify(mockAsynchJobQueuePublisher, times(1)).publishMessage(status);
	}
	
	@Test
	public void testGetJobStatusUnauthorizedException() throws DatastoreException, NotFoundException{
		status.setStartedByUserId(user.getId()+1L);
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		assertThrows(UnauthorizedException.class, ()->{
			manager.getJobStatus(user,"999");
		});
	}
	
	@Test
	public void testCancelJobStatusUnauthorizedException() throws DatastoreException, NotFoundException {
		status.setStartedByUserId(user.getId()+1L);
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		assertThrows(UnauthorizedException.class, ()->{
			manager.cancelJob(user, "999");
		});
	}

	@Test
	public void testGetJobStatusHappy() throws DatastoreException, NotFoundException{
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		AsynchronousJobStatus status = manager.getJobStatus(user,"999");
		assertNotNull(status);
		assertNull(status.getCallersContext());
	}
	
	@Test
	public void testLookupStatus(){
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		AsynchronousJobStatus status = manager.lookupJobStatus("999");
		assertNotNull(status);
		assertNotNull(status.getCallersContext());
		assertNotNull(status.getCallersContext().getSessionId());
	}
	
	@Test
	public void testLookupStatusReadOnlyJob() {
		AsynchronousJobStatus status = new AsynchronousJobStatus();
		status.setStartedByUserId(user.getId());
		status.setJobId("8888");
		status.setRequestBody(Mockito.mock(ReadOnlyRequestBody.class));
		status.setJobState(AsynchJobState.PROCESSING);
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		
		// call under test
		manager.lookupJobStatus("8888");
		// Status should not be looked up for RO jobs
		verify(mockStackStatusDao, never()).getCurrentStatus();
	}
	
	@Test
	public void testLookupStatusWriteJobProcessing() {
		AsynchronousJobStatus status = new AsynchronousJobStatus();
		status.setStartedByUserId(user.getId());
		status.setJobId("8888");
		status.setRequestBody(Mockito.mock(AsynchronousRequestBody.class));
		status.setJobState(AsynchJobState.PROCESSING);
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_ONLY);
		
		assertThrows(IllegalStateException.class, ()->{
			// call under test
			manager.lookupJobStatus("8888");
		});
	}
	
	@Test
	public void testLookupStatusWriteJobComplete() {
		AsynchronousJobStatus status = new AsynchronousJobStatus();
		status.setStartedByUserId(user.getId());
		status.setJobId("8888");
		status.setRequestBody(Mockito.mock(AsynchronousRequestBody.class));
		status.setJobState(AsynchJobState.COMPLETE);
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		
		// call under test
		manager.lookupJobStatus("8888");
		verify(mockStackStatusDao, never()).getCurrentStatus();
	}
	
	/**
	 * Should be able to get a completed job while in read-only mode.
	 * @throws DatastoreException
	 * @throws NotFoundException
	 */
	@Test
	public void testGetJobStatusReadOnlyComplete() throws DatastoreException, NotFoundException{
		AsynchronousJobStatus status = new AsynchronousJobStatus();
		status.setStartedByUserId(user.getId());
		status.setJobId("999");
		status.setJobState(AsynchJobState.COMPLETE);
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		// call under test
		AsynchronousJobStatus result = manager.getJobStatus(user,"999");
		assertNotNull(result);
		verify(mockStackStatusDao, never()).getCurrentStatus();
	}
	
	/**
	 * Should be able to get a failed job while in read-only mode.
	 * @throws DatastoreException
	 * @throws NotFoundException
	 */
	@Test
	public void testGetJobStatusReadOnlyFailed() throws DatastoreException, NotFoundException{
		AsynchronousJobStatus status = new AsynchronousJobStatus();
		status.setStartedByUserId(user.getId());
		status.setJobId("999");
		status.setJobState(AsynchJobState.FAILED);
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		AsynchronousJobStatus result = manager.getJobStatus(user,"999");
		assertNotNull(result);
		verify(mockStackStatusDao, never()).getCurrentStatus();
	}
	
	/**
	 * Accessing a PROCESSING job while in read-only mode should trigger an error
	 * 
	 * @throws DatastoreException
	 * @throws NotFoundException
	 */
	@Test
	public void testGetJobStatusReadOnlyProcessing() throws DatastoreException, NotFoundException{
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_ONLY);
		AsynchronousJobStatus status = new AsynchronousJobStatus();
		status.setStartedByUserId(user.getId());
		status.setJobId("999");
		status.setJobState(AsynchJobState.PROCESSING);
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		assertThrows(IllegalStateException.class, ()->{
			manager.getJobStatus(user,"999");
		});
		verify(mockStackStatusDao).getCurrentStatus();
	}
	
	/**
	 * Accessing a PROCESSING job while in DOWN mode should trigger an error
	 * 
	 * @throws DatastoreException
	 * @throws NotFoundException
	 */
	@Test
	public void testGetJobStatusDownProcessing() throws DatastoreException, NotFoundException{
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.DOWN);
		AsynchronousJobStatus status = new AsynchronousJobStatus();
		status.setStartedByUserId(user.getId());
		status.setJobId("999");
		status.setJobState(AsynchJobState.PROCESSING);
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		assertThrows(IllegalStateException.class, ()->{
			manager.getJobStatus(user,"999");
		});
		verify(mockStackStatusDao).getCurrentStatus();
	}
	
	/**
	 * Cannot update the progress of a job in read-only or down
	 */
	@Test
	public void testUpdateProgressReadOnlyMode(){
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_ONLY);
		assertThrows(IllegalStateException.class, ()->{
			manager.updateJobProgress("123", 0L, 100L, "testing");
		});
	}
	
	/**
	 * Cannot update the progress of a job in read-only or down
	 */
	@Test
	public void testUpdateProgressDownMode(){
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.DOWN);
		assertThrows(IllegalStateException.class, ()->{
			manager.updateJobProgress("123", 0L, 100L, "testing");
		});
	}
	
	@Test
	public void testUpdateProgressHappy() throws DatastoreException, NotFoundException{
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		String jobId = "123";
		manager.updateJobProgress(jobId, 0L, 100L, "testing");
	}

	@Test 
	public void testSetCompleteWithReadWrite() throws Exception{
		status.setJobState(AsynchJobState.PROCESSING);
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_WRITE);
		when(mockStackConfig.getStackInstance()).thenReturn(instance);
		
		UploadToTableResult body = new UploadToTableResult();
		body.setRowsProcessed(101L);
		body.setEtag("etag");
		manager.setComplete("456", body);
		verify(mockStackStatusDao).getCurrentStatus();
	}
	
	@Test 
	public void testSetCompleteWithReadOnly() throws Exception{
		status.setJobState(AsynchJobState.PROCESSING);
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.READ_ONLY);
		
		UploadToTableResult body = new UploadToTableResult();
		body.setRowsProcessed(101L);
		body.setEtag("etag");
		assertThrows(IllegalStateException.class, ()->{
			manager.setComplete("456", body);
		});
		verify(mockStackStatusDao).getCurrentStatus();
	}
	
	@Test 
	public void testSetCompleteWithDown() throws Exception{
		status.setJobState(AsynchJobState.PROCESSING);
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		when(mockStackStatusDao.getCurrentStatus()).thenReturn(StatusEnum.DOWN);
		
		UploadToTableResult body = new UploadToTableResult();
		body.setRowsProcessed(101L);
		body.setEtag("etag");
		assertThrows(IllegalStateException.class, ()->{
			manager.setComplete("456", body);
		});
		verify(mockStackStatusDao).getCurrentStatus();
	}
	
	@Test
	public void testGetMetricNamespace() {
		when(mockStackConfig.getStackInstance()).thenReturn(instance);
		//when(mockAsynchJobStatusDao.setComplete(any(String.class), any(AsynchronousResponseBody.class),  any(String.class))).thenReturn(runtimeMS);
		// call under test
		String metricNamespace = manager.getMetricNamespace();
		assertEquals("Asynchronous-Jobs-test1", metricNamespace);
	}
	
	@Test
	public void testPushCloudwatchMetric() {
		// call under test
		manager.pushCloudwatchMetric(runtimeMS, AsynchJobType.ADD_FILES_TO_DOWNLOAD_LIST);
		verify(mockConsumer).addProfileData(profileCaptor.capture());
		ProfileData profile = profileCaptor.getValue();
		assertNotNull(profile);
		assertEquals(new Double(runtimeMS), profile.getValue());
		assertEquals(manager.getMetricNamespace(), profile.getNamespace());
		assertEquals(AsynchJobStatusManagerImpl.METRIC_NAME, profile.getName());
		assertNotNull(profile.getTimestamp());
		Map<String, String> dimension = profile.getDimension();
		assertNotNull(dimension);
		assertEquals(AsynchJobType.ADD_FILES_TO_DOWNLOAD_LIST.name(), dimension.get(AsynchJobStatusManagerImpl.JOB_TYPE));
		assertEquals(StandardUnit.Milliseconds.name(), profile.getUnit());
	}
	
	@Test
	public void testSetCompleteHappy() throws Exception{
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		when(mockStackConfig.getStackInstance()).thenReturn(instance);
		UploadToTableResult body = new UploadToTableResult();
		body.setRowsProcessed(101L);
		body.setEtag("etag");
		// call under test
		manager.setComplete("456", body);
		String requestHash = null;
		verify(mockAsynchJobStatusDao).setComplete("456", body, requestHash);
		verify(mockConsumer).addProfileData(profileCaptor.capture());
		ProfileData profile = profileCaptor.getValue();
		assertNotNull(profile);
		Map<String, String> dimension = profile.getDimension();
		assertNotNull(dimension);
		assertEquals(AsynchJobType.BULK_FILE_DOWNLOAD.name(), dimension.get(AsynchJobStatusManagerImpl.JOB_TYPE));
	}

	
	@Test
	public void testSetCompleteCacheable() throws Exception{
		AsynchronousJobStatus status = new AsynchronousJobStatus();
		status.setStartedByUserId(user.getId());
		status.setJobId("8888");
		DownloadFromTableRequest requestbody = new DownloadFromTableRequest();
		requestbody.setSql("select * from syn123");
		status.setRequestBody(requestbody);
		String requestHash = "aRequestHash";
		when(mockAsynchJobStatusDao.getJobStatus(anyString())).thenReturn(status);
		when(mockJobHashProvider.getJobHash(requestbody)).thenReturn(requestHash);
		
		DownloadFromTableResult resultBody = new DownloadFromTableResult();
		resultBody.setTableId("syn123");
		manager.setComplete("456", resultBody);
		verify(mockAsynchJobStatusDao).setComplete("456", resultBody, requestHash);
		verify(mockConsumer).addProfileData(any(ProfileData.class));
	}
	
	/**
	 * Must be able to set a job failed in any mode.  The failure could be that the stack is not in READ_WRITE mode.
	 */
	@Test
	public void testSetFailedAnyMode(){
		Throwable exception = new Throwable("Failed");
		when(mockAsynchJobStatusDao.setJobFailed(anyString(), any(Throwable.class))).thenReturn("etag");
		// call under tests
		String result = manager.setJobFailed("123", exception);
		assertEquals("etag", result);
		verify(mockStackStatusDao, never()).getCurrentStatus();
		verify(mockAsynchJobStatusDao).setJobFailed("123", exception);
	}
	
	@Test
	public void testStartJobCacheHit(){
		// request
		DownloadFromTableRequest body = new DownloadFromTableRequest();
		body.setEntityId("syn123");
		body.setSql("select * from syn123");
		// setup hash and etag.
		String bodyHash = "aBodyHash";
		when(mockJobHashProvider.getJobHash(body)).thenReturn(bodyHash);
		// Match request to existing job
		AsynchronousJobStatus existingJob = new AsynchronousJobStatus();
		existingJob.setStartedByUserId(user.getId());
		existingJob.setJobId("123456");
		existingJob.setRequestBody(body);
		existingJob.setJobState(AsynchJobState.COMPLETE);
		when(mockAsynchJobStatusDao.findCompletedJobStatus(bodyHash, user.getId())).thenReturn(Arrays.asList(existingJob));
		// call under test.
		AsynchronousJobStatus status = manager.startJob(user, body);
		// The status should match the exiting job
		assertEquals(existingJob, status);
		// The job should not be started.
		verify(mockAsynchJobStatusDao, never()).startJob(any(), any(AsynchronousRequestBody.class));
	}
	
	@Test
	public void testStartJobMultipleCacheHit(){
		// request
		DownloadFromTableRequest body = new DownloadFromTableRequest();
		body.setEntityId("syn123");
		body.setSql("select * from syn123");
		
		DownloadFromTableRequest body2 = new DownloadFromTableRequest();
		body2.setEntityId("syn123");
		body2.setSql("select * from syn123 limit 1");
		
		// setup hash and etag.
		String bodyHash = "aBodyHash";
		when(mockJobHashProvider.getJobHash(body)).thenReturn(bodyHash);
		// Match request to existing job
		List<AsynchronousJobStatus> hits = new LinkedList<AsynchronousJobStatus>();
		// First hit is not a match.
		AsynchronousJobStatus hitOne = new AsynchronousJobStatus();
		hitOne.setStartedByUserId(user.getId());
		hitOne.setJobId("123456");
		hitOne.setRequestBody(body2);
		hitOne.setJobState(AsynchJobState.COMPLETE);
		hits.add(hitOne);
		// this one should match.
		AsynchronousJobStatus hitTwo = new AsynchronousJobStatus();
		hitTwo.setStartedByUserId(user.getId());
		hitTwo.setJobId("123456");
		hitTwo.setRequestBody(body);
		hitTwo.setJobState(AsynchJobState.COMPLETE);
		hits.add(hitTwo);
		
		when(mockAsynchJobStatusDao.findCompletedJobStatus(bodyHash, user.getId())).thenReturn(hits);
		// call under test.
		AsynchronousJobStatus status = manager.startJob(user, body);
		// The status should match the exiting job
		assertEquals(hitTwo, status);
		// The job should not be started.
		verify(mockAsynchJobStatusDao, never()).startJob(any(), any(AsynchronousRequestBody.class));
	}
	
	@Test
	public void testStartJobCacheMiss(){
		setupStartJob();
		// request
		DownloadFromTableRequest body = new DownloadFromTableRequest();
		body.setEntityId("syn123");
		body.setSql("select * from syn123");
		// setup hash and etag.
		String bodyHash = "aBodyHash";
		when(mockJobHashProvider.getJobHash(body)).thenReturn(bodyHash);
		// For this case, no job exists
		List<AsynchronousJobStatus> existingJob = new LinkedList<AsynchronousJobStatus>();
		when(mockAsynchJobStatusDao.findCompletedJobStatus(bodyHash, user.getId())).thenReturn(existingJob);
		// call under test.
		AsynchronousJobStatus status = manager.startJob(user, body);
		assertNotNull(status);
		assertEquals(startedJobId, status.getJobId());
		// The job should be started and published.
		verify(mockAsynchJobStatusDao, times(1)).startJob(any(), any(AsynchronousRequestBody.class));
		verify(mockAsynchJobQueuePublisher, times(1)).publishMessage(status);
	}
	
	/**
	 * A hash is used to lookup an existing job request so we still need to check if request body is equal to the cache hit.
	 */
	@Test
	public void testStartJobCacheHitNotEquals(){
		setupStartJob();
		// request
		DownloadFromTableRequest body = new DownloadFromTableRequest();
		body.setEntityId("syn123");
		body.setSql("select * from syn123");
		// setup hash and etag.
		String bodyHash = "aBodyHash";
		when(mockJobHashProvider.getJobHash(body)).thenReturn(bodyHash);
		// The cached request body does not equal the body for this request. 
		DownloadFromTableRequest cachedBody = new DownloadFromTableRequest();
		cachedBody.setEntityId("syn123");
		cachedBody.setSql("select * from syn123 limit 1");
		AsynchronousJobStatus existingJob = new AsynchronousJobStatus();
		existingJob.setStartedByUserId(user.getId());
		existingJob.setJobId("123456");
		existingJob.setRequestBody(cachedBody);
		// There is a job with this hash but the body does not match the request's body.
		when(mockAsynchJobStatusDao.findCompletedJobStatus(bodyHash, user.getId())).thenReturn(Arrays.asList(existingJob));
		// call under test.
		AsynchronousJobStatus status = manager.startJob(user, body);
		assertNotNull(status);
		assertEquals(startedJobId, status.getJobId());
		// The job should be started and published.
		verify(mockAsynchJobStatusDao, times(1)).startJob(any(), any(AsynchronousRequestBody.class));
		verify(mockAsynchJobQueuePublisher, times(1)).publishMessage(status);
	}
	
	/**
	 * A null jobHash means the job cannot be cached.
	 * 
	 */
	@Test
	public void testStartJobNullHash(){
		setupStartJob();
		// request
		DownloadFromTableRequest body = new DownloadFromTableRequest();
		body.setEntityId("syn123");
		body.setSql("select * from syn123");
		// return null hash
		String bodyHash = null;
		when(mockJobHashProvider.getJobHash(body)).thenReturn(bodyHash);
		// call under test.
		AsynchronousJobStatus status = manager.startJob(user, body);
		assertNotNull(status);
		assertEquals(startedJobId, status.getJobId());
		// The job should be started and published.
		verify(mockAsynchJobStatusDao, times(1)).startJob(any(), any(AsynchronousRequestBody.class));
		verify(mockAsynchJobQueuePublisher, times(1)).publishMessage(status);
		verify(mockAsynchJobStatusDao, never()).findCompletedJobStatus(anyString(), anyLong());
	}

}
