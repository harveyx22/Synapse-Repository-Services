package org.sagebionetworks.repo.manager.file;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.manager.ProjectSettingsManager;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.FileHandleDao;
import org.sagebionetworks.repo.model.dbo.file.CompositeMultipartUploadStatus;
import org.sagebionetworks.repo.model.dbo.file.CreateMultipartRequest;
import org.sagebionetworks.repo.model.dbo.file.MultipartRequestUtils;
import org.sagebionetworks.repo.model.dbo.file.MultipartUploadDAO;
import org.sagebionetworks.repo.model.file.AddPartRequest;
import org.sagebionetworks.repo.model.file.AddPartResponse;
import org.sagebionetworks.repo.model.file.AddPartState;
import org.sagebionetworks.repo.model.file.BatchPresignedUploadUrlRequest;
import org.sagebionetworks.repo.model.file.BatchPresignedUploadUrlResponse;
import org.sagebionetworks.repo.model.file.CloudProviderFileHandleInterface;
import org.sagebionetworks.repo.model.file.CompleteMultipartRequest;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.file.FileHandleAssociation;
import org.sagebionetworks.repo.model.file.GoogleCloudFileHandle;
import org.sagebionetworks.repo.model.file.MultipartRequest;
import org.sagebionetworks.repo.model.file.MultipartUploadCopyRequest;
import org.sagebionetworks.repo.model.file.MultipartUploadRequest;
import org.sagebionetworks.repo.model.file.MultipartUploadState;
import org.sagebionetworks.repo.model.file.MultipartUploadStatus;
import org.sagebionetworks.repo.model.file.PartMD5;
import org.sagebionetworks.repo.model.file.PartPresignedUrl;
import org.sagebionetworks.repo.model.file.PartUtils;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.file.UploadType;
import org.sagebionetworks.repo.model.jdo.NameValidation;
import org.sagebionetworks.repo.model.project.StorageLocationSetting;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.upload.multipart.CloudServiceMultipartUploadDAO;
import org.sagebionetworks.upload.multipart.CloudServiceMultipartUploadDAOProvider;
import org.sagebionetworks.upload.multipart.MultipartUploadUtils;
import org.sagebionetworks.upload.multipart.PresignedUrl;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MultipartManagerV2Impl implements MultipartManagerV2 {

	@Autowired
	private CloudServiceMultipartUploadDAOProvider cloudServiceMultipartUploadDAOProvider;

	@Autowired
	private MultipartUploadDAO multipartUploadDAO;

	@Autowired
	private FileHandleDao fileHandleDao;

	@Autowired
	private ProjectSettingsManager projectSettingsManager;

	@Autowired
	private IdGenerator idGenerator;
	
	@Autowired
	private FileHandleAuthorizationManager authManager;
	

	@Override
	@WriteTransaction
	public MultipartUploadStatus startOrResumeMultipartOperation(UserInfo user, MultipartRequest request, boolean forceRestart) {
		ValidateArgument.required(request, "request");
		
		if (request instanceof MultipartUploadRequest) {
			return startOrResumeMultipartUpload(user, (MultipartUploadRequest) request, forceRestart);
		} else if (request instanceof MultipartUploadCopyRequest) {
			return startOrResumeMultipartUploadCopy(user, (MultipartUploadCopyRequest) request, forceRestart);
		}
		
		throw new UnsupportedOperationException("Request type unsupported: " + request.getClass().getSimpleName());
	}

	@Override
	@WriteTransaction
	public MultipartUploadStatus startOrResumeMultipartUpload(UserInfo user, MultipartUploadRequest request, boolean forceRestart) {
		return startOrResumeMultipartRequest(user, request, forceRestart, this::validateMultipartUpload, this::createNewMultipartUpload);
	}
	
	@Override
	@WriteTransaction
	public MultipartUploadStatus startOrResumeMultipartUploadCopy(UserInfo user, MultipartUploadCopyRequest request, boolean forceRestart) {
		return startOrResumeMultipartRequest(user, request, forceRestart, this::validateMultipartUploadCopy, this::createNewMultipartUploadCopy);
	}
	
	private <T extends MultipartRequest> MultipartUploadStatus startOrResumeMultipartRequest(UserInfo user, T request, boolean forceRestart, MultipartRequestValidator<T> validator, MultipartRequestInitiator<T> initiator) {
		
		validator.validate(user, request);
		
		// The MD5 is used to identify if this upload request already exists for this user.
		String requestMD5Hex = MultipartRequestUtils.calculateMD5AsHex(request);
		
		// Clear all data if this is a forced restart
		if (forceRestart) {
			multipartUploadDAO.deleteUploadStatus(user.getId(), requestMD5Hex);
		}

		// Has an upload already been started for this user and request
		CompositeMultipartUploadStatus status = multipartUploadDAO.getUploadStatus(user.getId(), requestMD5Hex);
		
		if (status == null) {
			StorageLocationSetting storageLocation = projectSettingsManager.getStorageLocationSetting(request.getStorageLocationId());

			// Since the status for this file does not exist, create it.
			status = initiator.initiate(user, request, requestMD5Hex, storageLocation);
		}
		
		// Calculate the parts state for this file.
		String partsState = setupPartState(status);
		
		status.getMultipartUploadStatus().setPartsState(partsState);
		
		return status.getMultipartUploadStatus();
	}
	
	private void validateMultipartRequest(UserInfo user, MultipartRequest request) {
		ValidateArgument.required(user, "UserInfo");
		ValidateArgument.required(user.getId(), "UserInfo.getId");
		ValidateArgument.required(request, "request");
		
		String requestClass = request.getClass().getSimpleName();
		
		ValidateArgument.required(request.getPartSizeBytes(), requestClass + ".PartSizeBytes");

		// anonymous cannot upload. See: PLFM-2621.
		if (AuthorizationUtils.isUserAnonymous(user)) {
			throw new UnauthorizedException("Anonymous cannot upload files.");
		}
	}
	
	private void validateMultipartUpload(UserInfo user, MultipartUploadRequest request) {
		validateMultipartRequest(user, request);
		ValidateArgument.requiredNotEmpty(request.getFileName(), "MultipartUploadRequest.fileName");
		ValidateArgument.required(request.getFileSizeBytes(), "MultipartUploadRequest.fileSizeBytes");
		ValidateArgument.requiredNotEmpty(request.getContentMD5Hex(), "MultipartUploadRequest.MD5Hex");

		//validate file name
		NameValidation.validateName(request.getFileName());
	}
	
	private void validateMultipartUploadCopy(UserInfo user, MultipartUploadCopyRequest request) {
		validateMultipartRequest(user, request);
		ValidateArgument.required(request.getSourceFileHandleAssociation(), "MultipartUploadCopyRequest.sourceFileHandleAssociation");
		ValidateArgument.required(request.getStorageLocationId(), "MultipartUploadCopyRequest.storageLocationId");
		
		if (!StringUtils.isBlank(request.getFileName())) {
			//validate file name
			NameValidation.validateName(request.getFileName());
		}
	}

	/**
	 * Create a new multi-part upload both in the cloud service and the database.
	 * 
	 * @param user
	 * @param request
	 * @param requestHash
	 * @return
	 */
	private CompositeMultipartUploadStatus createNewMultipartUpload(UserInfo user, MultipartUploadRequest request, String requestHash, StorageLocationSetting storageLocation) {
		UploadType uploadType = storageLocation == null ? UploadType.S3 : storageLocation.getUploadType();
		
		// the bucket depends on the upload location used.
		String bucket = MultipartUtils.getBucket(storageLocation);
		// create a new key for this file.
		String key = MultipartUtils.createNewKey(user.getId().toString(), request.getFileName(), storageLocation);
		
		String uploadToken = getCloudServiceMultipartDao(uploadType).initiateMultipartUpload(bucket, key, request);
		
		String requestJson = MultipartRequestUtils.createRequestJSON(request);
		// How many parts will be needed to upload this file?
		int numberParts = PartUtils.calculateNumberOfParts(request.getFileSizeBytes(), request.getPartSizeBytes());
		// Start the upload
		return multipartUploadDAO
				.createUploadStatus(new CreateMultipartRequest(user.getId(),
						requestHash, requestJson, uploadToken, uploadType,
						bucket, key, numberParts, request.getPartSizeBytes()));
	}
	
	private CompositeMultipartUploadStatus createNewMultipartUploadCopy(UserInfo user, MultipartUploadCopyRequest request, String requestHash, StorageLocationSetting storageLocation) {
		UploadType uploadType = storageLocation.getUploadType();
		
		FileHandleAssociation association = request.getSourceFileHandleAssociation();
		
		// Verifies that the user can download the source file
		authManager.canDownLoadFile(user, Arrays.asList(association))
			.stream()
			.filter(status -> status.getStatus().isAuthorized())
			.findFirst()
			.orElseThrow(() -> 
				new UnauthorizedException("The user is not authorized to access the source file.")
			);
		
		// Makes sure the user owns the storage location, note that the storage location id is required in the request
		if (!user.isAdmin() && !user.getId().equals(storageLocation.getCreatedBy())) {
			throw new UnauthorizedException("The user does not own the storage location.");
		}
		
		FileHandle fileHandle = fileHandleDao.get(association.getFileHandleId());
		
		if (fileHandle.getContentSize() == null) {
			throw new IllegalArgumentException("The source file handle does not define its size.");
		}
		
		if (fileHandle.getContentMd5() == null) {
			throw new IllegalArgumentException("The source file handle does not define its content MD5.");
		}
		
		// Sets a file name as it is optional, but we save it in the request (the has won't contain this)
		request.setFileName(StringUtils.isBlank(request.getFileName()) ? fileHandle.getFileName() : request.getFileName());
		
		String requestJson = MultipartRequestUtils.createRequestJSON(request);
		
		// the bucket depends on the upload location used.
		String bucket = MultipartUtils.getBucket(storageLocation);
		// create a new key for this file.
		String key = MultipartUtils.createNewKey(user.getId().toString(), request.getFileName(), storageLocation);
		
		String uploadToken = getCloudServiceMultipartDao(uploadType).initiateMultipartUploadCopy(bucket, key, request, fileHandle);
		
		int numberParts = PartUtils.calculateNumberOfParts(fileHandle.getContentSize(), request.getPartSizeBytes());
		
		// Start the copy
		return multipartUploadDAO
				.createUploadStatus(new CreateMultipartRequest(user.getId(),
						requestHash, requestJson, uploadToken, uploadType,
						bucket, key, numberParts, request.getPartSizeBytes(), fileHandle.getId()));
	}
	
	private CloudServiceMultipartUploadDAO getCloudServiceMultipartDao(UploadType uploadType) {
		return cloudServiceMultipartUploadDAOProvider.getCloudServiceMultipartUploadDao(uploadType);
	}

	/**
	 * Setup the partsState string for a given status multi-part status. For the
	 * cases where the file upload is complete, this string is built without a
	 * database call.
	 * 
	 * @param status
	 */
	public String setupPartState(CompositeMultipartUploadStatus status) {
		if (MultipartUploadState.COMPLETED.equals(status.getMultipartUploadStatus().getState())) {
			// When the upload is done we just create a string of all '1'
			// without hitting the DB.
			return getCompletePartStateString(status.getNumberOfParts());
		} else {
			// Get the parts state from the DAO.
			return multipartUploadDAO.getPartsState(status.getMultipartUploadStatus().getUploadId(), status.getNumberOfParts());
		}
	}

	/**
	 * Create string of '1' of the size of the passed number of parts.
	 * 
	 * @param numberOfParts
	 * @return
	 */
	public static String getCompletePartStateString(int numberOfParts) {
		char[] chars = new char[numberOfParts];
		Arrays.fill(chars, '1');
		return new String(chars);
	}

	@Override
	public BatchPresignedUploadUrlResponse getBatchPresignedUploadUrls(UserInfo user, BatchPresignedUploadUrlRequest request) {

		ValidateArgument.required(request, "BatchPresignedUploadUrlRequest");
		ValidateArgument.required(request.getPartNumbers(), "BatchPresignedUploadUrlRequest.partNumbers");
		
		if (request.getPartNumbers().isEmpty()) {
			throw new IllegalArgumentException("BatchPresignedUploadUrlRequest.partNumbers must contain at least one value");
		}
		
		// lookup this upload.
		CompositeMultipartUploadStatus status = multipartUploadDAO.getUploadStatus(request.getUploadId());

		// validate the caller is the user that started this upload.
		validateStartedBy(user, status);
		
		int numberOfParts = status.getNumberOfParts();
		
		List<PartPresignedUrl> partUrls = new ArrayList<>(request.getPartNumbers().size());
		
		PresignedUrlProvider presignedUrlProvider;
		
		switch (status.getRequestType()) {
		case UPLOAD:
			presignedUrlProvider = this::getPresignedUrlForUpload;
			break;
		case COPY:
			presignedUrlProvider = this::getPresignedUrlForUploadCopy;
			break;
		default:
			throw new IllegalStateException("Unsupported request type: " + status.getRequestType());
		}
		
		CloudServiceMultipartUploadDAO cloudServiceMultipartDao = getCloudServiceMultipartDao(status.getUploadType());
		
		for (Long partNumber : request.getPartNumbers()) {
			ValidateArgument.required(partNumber, "PartNumber cannot be null");
			
			validatePartNumber(partNumber.intValue(), numberOfParts);
			
			PresignedUrl url = presignedUrlProvider.create(status, cloudServiceMultipartDao, partNumber, request.getContentType());
			
			partUrls.add(map(url, partNumber));
		}
		
		BatchPresignedUploadUrlResponse response = new BatchPresignedUploadUrlResponse();
		
		response.setPartPresignedUrls(partUrls);
		
		return response;
	}
	
	private PresignedUrl getPresignedUrlForUpload(CompositeMultipartUploadStatus status, CloudServiceMultipartUploadDAO cloudDao, long partNumber, String contentType) {
		String partKey = MultipartUploadUtils.createPartKey(status.getKey(), partNumber);
		
		return cloudDao.createPartUploadPreSignedUrl(status.getBucket(), partKey, contentType);
	}
	
	private PresignedUrl getPresignedUrlForUploadCopy(CompositeMultipartUploadStatus status, CloudServiceMultipartUploadDAO cloudDao, long partNumber, String contentType) {
		return cloudDao.createPartUploadCopyPresignedUrl(status, partNumber, contentType);
	}
	
	private static PartPresignedUrl map(PresignedUrl presignedUrl, Long partNumber) {
		PartPresignedUrl part = new PartPresignedUrl();
		
		part.setPartNumber(partNumber);
		part.setUploadPresignedUrl(presignedUrl.getUrl().toString());
		part.setRequestHeaders(presignedUrl.getSignedHeaders());
		
		return part;
	}

	/**
	 * Validate a part number is within range.
	 * 
	 * @param partNumber
	 * @param numberOfParts
	 */
	public static void validatePartNumber(int partNumber, int numberOfParts) {
		if (partNumber < 1) {
			throw new IllegalArgumentException(
					"Part numbers cannot be less than one.");
		}
		if (numberOfParts < 1) {
			throw new IllegalArgumentException(
					"Number of parts cannot be less than one.");
		}
		if (partNumber > numberOfParts) {
			throw new IllegalArgumentException(
					"Part number cannot be larger than number of parts. Number of parts: "
							+ numberOfParts + ", provided part number: "
							+ partNumber);
		}
	}

	/**
	 * Validate the caller started the given upload.
	 * 
	 * @param user
	 */
	public static void validateStartedBy(UserInfo user,
			CompositeMultipartUploadStatus composite) {
		ValidateArgument.required(user, "UserInfo");
		ValidateArgument.required(composite, "CompositeMultipartUploadStatus");
		ValidateArgument.required(composite.getMultipartUploadStatus(),
				"CompositeMultipartUploadStatus.multipartUploadStatus");
		ValidateArgument
				.required(composite.getMultipartUploadStatus().getStartedBy(),
						"CompositeMultipartUploadStatus.multipartUploadStatus.startedBy");
		Long startedBy = Long.parseLong(composite.getMultipartUploadStatus()
				.getStartedBy());
		if (!startedBy.equals(user.getId())) {
			throw new UnauthorizedException(
					"Only the user that started a multipart upload can get part upload pre-signed URLs for that file upload.");
		}
	}

	@WriteTransaction
	@Override
	public AddPartResponse addMultipartPart(UserInfo user, String uploadId, Integer partNumber, String partMD5Hex) {
		ValidateArgument.required(user, "UserInfo");
		ValidateArgument.required(uploadId, "uploadId");
		ValidateArgument.required(partNumber, "partNumber");
		ValidateArgument.required(partMD5Hex, "partMD5Hex");
		// lookup this upload.
		CompositeMultipartUploadStatus composite = multipartUploadDAO.getUploadStatus(uploadId);
		// block add if the upload is complete
		if (MultipartUploadState.COMPLETED.equals(composite.getMultipartUploadStatus().getState())){
			throw new IllegalArgumentException("Cannot add parts to completed file upload.");
		}
		
		validatePartNumber(partNumber, composite.getNumberOfParts());
		// validate the user started this upload.
		validateStartedBy(user, composite);
		
		AddPartResponse response = new AddPartResponse();
		
		response.setPartNumber(new Long(partNumber));
		response.setUploadId(uploadId);

		CloudServiceMultipartUploadDAO cloudDao = getCloudServiceMultipartDao(composite.getUploadType());
		
		try {
			switch (composite.getRequestType()) {
			case UPLOAD:
				validatePartAddedForUpload(cloudDao, composite, partNumber, partMD5Hex);
				break;
			case COPY:
				validatePartAddedForUploadCopy(cloudDao, composite, partNumber, partMD5Hex);
				break;
			default:
				throw new IllegalStateException("Unsupported request type: " + composite.getRequestType());
			}
			
			// added the part successfully.
			multipartUploadDAO.addPartToUpload(uploadId, partNumber, partMD5Hex);
			response.setAddPartState(AddPartState.ADD_SUCCESS);
		} catch (Exception e) {
			response.setErrorMessage(e.getMessage());
			response.setAddPartState(AddPartState.ADD_FAILED);
			String errorDetails = ExceptionUtils.getStackTrace(e);
			multipartUploadDAO.setPartToFailed(uploadId, partNumber, errorDetails);
		}
		return response;
	}
	
	private void validatePartAddedForUpload(CloudServiceMultipartUploadDAO cloudDao, CompositeMultipartUploadStatus status, long partNumber, String partMD5Hex) {
		String partKey = MultipartUploadUtils.createPartKey(status.getKey(), partNumber);
		
		cloudDao.validateAndAddPart(
				new AddPartRequest(status.getMultipartUploadStatus().getUploadId(), status
						.getUploadToken(), status.getBucket(), status
						.getKey(), partKey, partMD5Hex, partNumber, status.getNumberOfParts())
		);
	}
	
	private void validatePartAddedForUploadCopy(CloudServiceMultipartUploadDAO cloudDao, CompositeMultipartUploadStatus status, long partNumber, String partMD5Hex) {
		cloudDao.validatePartCopy(status, partNumber, partMD5Hex);
	}


	@WriteTransaction
	@Override
	public MultipartUploadStatus completeMultipartUpload(UserInfo user,
			String uploadId) {
		ValidateArgument.required(user, "UserInfo");
		ValidateArgument.required(uploadId, "uploadId");
		
		CompositeMultipartUploadStatus composite = multipartUploadDAO.getUploadStatus(uploadId);
		
		// validate the user started this upload.
		validateStartedBy(user, composite);
		
		// Is the upload already complete?
		if (MultipartUploadState.COMPLETED.equals(composite.getMultipartUploadStatus().getState())) {
			// nothing left to do.
			return prepareCompleteStatus(composite);
		}
		
		// Are all parts added?
		List<PartMD5> addedParts = multipartUploadDAO.getAddedPartMD5s(uploadId);
		
		validateParts(composite.getNumberOfParts(), addedParts);
		
		// complete the upload
		CompleteMultipartRequest request = new CompleteMultipartRequest();
		
		request.setUploadId(Long.valueOf(uploadId));
		request.setNumberOfParts(composite.getNumberOfParts().longValue());
		request.setAddedParts(addedParts);
		request.setBucket(composite.getBucket());
		request.setKey(composite.getKey());
		request.setUploadToken(composite.getUploadToken());
		
		long fileContentSize = getCloudServiceMultipartDao(composite.getUploadType()).completeMultipartUpload(request);
		
		FileHandleCreateRequest fileHandleRequest;
		
		switch (composite.getRequestType()) {
		case UPLOAD:
			fileHandleRequest = createFileHandleRequestForUpload(composite, getOriginalRequest(uploadId, MultipartUploadRequest.class));
			break;
		case COPY:
			fileHandleRequest = createFileHandleRequestForUploadCopy(composite, getOriginalRequest(uploadId, MultipartUploadCopyRequest.class));
			break;
		default:
			throw new IllegalStateException("Unsupported request type: " + composite.getRequestType());
		}
		
		// create a file handle to represent this file.
		String resultFileHandleId = createFileHandle(fileContentSize, composite, fileHandleRequest).getId();
		
		// complete the upload.
		composite = multipartUploadDAO.setUploadComplete(uploadId, resultFileHandleId);
		
		return prepareCompleteStatus(composite);
	}
	
	private FileHandleCreateRequest createFileHandleRequestForUpload(CompositeMultipartUploadStatus composite, MultipartUploadRequest request) {
		return new FileHandleCreateRequest(request.getFileName(), request.getContentType(), request.getContentMD5Hex(), request.getStorageLocationId(), request.getGeneratePreview());
	}

	private FileHandleCreateRequest createFileHandleRequestForUploadCopy(CompositeMultipartUploadStatus composite, MultipartUploadCopyRequest request) {
		FileHandle fileHandle = fileHandleDao.get(composite.getSourceFileHandleId().toString());
		
		// Note that the filename in the request is optional, but we do save the file handle name in the request if not supplied
		return new FileHandleCreateRequest(request.getFileName(), fileHandle.getContentType(), fileHandle.getContentMd5(), request.getStorageLocationId(), request.getGeneratePreview());
		
	}

	/**
	 * Validate there is one part for the expected number of parts.
	 * 
	 * @param numberOfParts
	 * @param addedParts
	 */
	public static void validateParts(int numberOfParts,
			List<PartMD5> addedParts) {
		if (addedParts.size() < numberOfParts) {
			int missingPartCount = numberOfParts
					- addedParts.size();
			throw new IllegalArgumentException(
					"Missing "
							+ missingPartCount
							+ " part(s).  All parts must be successfully added before a file upload can be completed.");
		}
	}
	
	/**
	 * Prepare a COMPLETED status for a given composite.
	 * @param composite
	 * @return
	 */
	public static MultipartUploadStatus prepareCompleteStatus(CompositeMultipartUploadStatus composite){
		ValidateArgument.required(composite, "CompositeMultipartUploadStatus");
		ValidateArgument.required(composite.getMultipartUploadStatus(), "MultipartUploadStatus");
		ValidateArgument.required(composite.getMultipartUploadStatus().getResultFileHandleId(), "ResultFileHandleId");
		ValidateArgument.required(composite.getNumberOfParts(), "NumberOfParts");
		MultipartUploadStatus status = composite.getMultipartUploadStatus();
		if(!MultipartUploadState.COMPLETED.equals(status.getState())){
			throw new IllegalArgumentException("Expected a COMPLETED state");
		}
		// Build a state string of all '1's.
		String completePartsState = getCompletePartStateString(composite.getNumberOfParts());
		status.setPartsState(completePartsState);
		return status;
	}

	<T extends MultipartRequest> T getOriginalRequest(String uploadId, Class<T> clazz) {
		String requestJson = multipartUploadDAO.getUploadRequest(uploadId);
		
		return MultipartRequestUtils.getRequestFromJson(requestJson, clazz);
	}
	
	/**
	 * Create an S3FileHandle for a multi-part upload.
	 * 
	 * @param fileSize
	 * @param composite
	 * @param request
	 * @return
	 */
	CloudProviderFileHandleInterface createFileHandle(long fileSize, CompositeMultipartUploadStatus composite, FileHandleCreateRequest request){
		// Convert all of the data to a file handle.
		CloudProviderFileHandleInterface fileHandle;
		if (composite.getUploadType().equals(UploadType.S3)) {
			fileHandle = new S3FileHandle();
		} else if (composite.getUploadType().equals(UploadType.GOOGLECLOUDSTORAGE)) {
			fileHandle = new GoogleCloudFileHandle();
		} else {
			throw new IllegalArgumentException("Cannot create a FileHandle from a multipart upload with upload type " + composite.getUploadType());
		}
		fileHandle.setFileName(request.getFileName());
		fileHandle.setContentType(request.getContentType());
		fileHandle.setBucketName(composite.getBucket());
		fileHandle.setKey(composite.getKey());
		fileHandle.setCreatedBy(composite.getMultipartUploadStatus().getStartedBy());
		fileHandle.setCreatedOn(new Date(System.currentTimeMillis()));
		fileHandle.setEtag(UUID.randomUUID().toString());
		fileHandle.setContentMd5(request.getContentMD5());
		fileHandle.setStorageLocationId(request.getStorageLocationId());
		fileHandle.setContentSize(fileSize);
		fileHandle.setId(idGenerator.generateNewId(IdType.FILE_IDS).toString());
		
		// By default a preview should be created.
		if(request.getGeneratePreview() != null && !request.getGeneratePreview()){
			fileHandle.setPreviewId(fileHandle.getId());
		}
		// dao creates the files handle.
		return (CloudProviderFileHandleInterface) fileHandleDao.createFile(fileHandle);
	}

	@Override
	public void truncateAll() {
		this.multipartUploadDAO.truncateAll();
	}
	
	// Internal interfaces and DTOs
	
	@FunctionalInterface
	private static interface MultipartRequestValidator<T extends MultipartRequest> {
		
		void validate(UserInfo user, T request);
		
	}
	
	@FunctionalInterface
	private static interface MultipartRequestInitiator<T extends MultipartRequest> {
		
		CompositeMultipartUploadStatus initiate(UserInfo user, T request, String requestHash, StorageLocationSetting storageLocation);
		
	}
	
	@FunctionalInterface
	private static interface PresignedUrlProvider {
		
		PresignedUrl create(CompositeMultipartUploadStatus status, CloudServiceMultipartUploadDAO cloudDao, long partNumber, String contentType);
	
	}
	
	// Internal DTO for customizing the file handle data
	static class FileHandleCreateRequest {
		
		private String fileName;
		private String contentType;
		private String contentMD5;
		private Long storageLocationId;
		private Boolean generatePreview;
		
		FileHandleCreateRequest(String fileName, String contentType, String contentMD5, Long storageLocationId, Boolean generatePreview) {
			this.fileName = fileName;
			this.contentType = contentType;
			this.contentMD5 = contentMD5;
			this.storageLocationId = storageLocationId;
			this.generatePreview = generatePreview;
		}
		
		public String getFileName() {
			return fileName;
		}
		
		public String getContentType() {
			return contentType;
		}
		
		public String getContentMD5() {
			return contentMD5;
		}
		
		public Long getStorageLocationId() {
			return storageLocationId;
		}

		public Boolean getGeneratePreview() {
			return generatePreview;
		}
		
	}

}
