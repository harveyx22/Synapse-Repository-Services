package org.sagebionetworks.repo.manager.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.aws.SynapseS3Client;
import org.sagebionetworks.repo.manager.events.EventsCollector;
import org.sagebionetworks.repo.manager.statistics.StatisticsFileEvent;
import org.sagebionetworks.repo.manager.statistics.StatisticsFileEventUtils;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.file.FileHandleDao;
import org.sagebionetworks.repo.model.file.BulkFileDownloadRequest;
import org.sagebionetworks.repo.model.file.BulkFileDownloadResponse;
import org.sagebionetworks.repo.model.file.FileConstants;
import org.sagebionetworks.repo.model.file.FileDownloadCode;
import org.sagebionetworks.repo.model.file.FileDownloadStatus;
import org.sagebionetworks.repo.model.file.FileDownloadSummary;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.file.FileHandleAssociation;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.file.ZipFileFormat;
import org.sagebionetworks.repo.model.jdo.NameValidation;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.amazonaws.event.ProgressEvent;
import com.amazonaws.event.ProgressListener;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

@Service
public class FileHandleSupportImpl implements FileHandleSupport {

	static private Logger log = LogManager.getLogger(FileHandleSupportImpl.class);

	private static final String ONLY_S3_FILE_HANDLES_CAN_BE_DOWNLOADED = "Only S3FileHandles can be downloaded.";
	public static final String PROCESSING_FILE_HANDLE_ID = "Processing FileHandleId :";
	public static final String APPLICATION_ZIP = "application/zip";
	public static final String FILE_EXCEEDS_THE_MAXIMUM_SIZE_LIMIT = "File exceeds the maximum size limit.";
	public static final String RESULT_FILE_HAS_REACHED_THE_MAXIMUM_SIZE = "Result file has reached the maximum size.";
	public static final String FILE_ALREADY_ADDED = "File already added.";

	private FileHandleDao fileHandleDao;
	private SynapseS3Client s3client;
	private FileHandleAuthorizationManager fileHandleAuthorizationManager;
	private FileHandleManager fileHandleManager;
	private EventsCollector statisticsCollector;

	@Autowired
	public FileHandleSupportImpl(FileHandleDao fileHandleDao, SynapseS3Client s3client,
			FileHandleAuthorizationManager fileHandleAuthorizationManager, FileHandleManager fileHandleManager,
			EventsCollector statisticsCollector) {
		super();
		this.fileHandleDao = fileHandleDao;
		this.s3client = s3client;
		this.fileHandleAuthorizationManager = fileHandleAuthorizationManager;
		this.fileHandleManager = fileHandleManager;
		this.statisticsCollector = statisticsCollector;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sagebionetworks.file.worker.BulkDownloadDao#canDownLoadFile(org.
	 * sagebionetworks.repo.model.UserInfo, java.util.List)
	 */
	@Override
	public List<FileHandleAssociationAuthorizationStatus> canDownLoadFile(UserInfo user,
			List<FileHandleAssociation> associations) {
		return fileHandleAuthorizationManager.canDownLoadFile(user, associations);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.sagebionetworks.file.worker.BulkDownloadDao#createTempFile(java.lang.
	 * String, java.lang.String)
	 */
	@Override
	public File createTempFile(String prefix, String suffix) throws IOException {
		return File.createTempFile(prefix, suffix);
	}

	@Override
	public ZipOutputStream createZipOutputStream(File outFile) throws IOException {
		return new ZipOutputStream(new FileOutputStream(outFile));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.sagebionetworks.file.worker.BulkDownloadDao#multipartUploadLocalFile(org.
	 * sagebionetworks.repo.model.UserInfo, java.io.File, java.lang.String,
	 * com.amazonaws.event.ProgressListener)
	 */
	@Override
	public S3FileHandle multipartUploadLocalFile(LocalFileUploadRequest request) {
		return fileHandleManager.uploadLocalFile(request);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.sagebionetworks.file.worker.BulkDownloadDao#getS3FileHandle(java.lang.
	 * String)
	 */
	@Override
	public S3FileHandle getS3FileHandle(String fileHandleId) {
		FileHandle handle = fileHandleDao.get(fileHandleId);
		if (!(handle instanceof S3FileHandle)) {
			throw new IllegalArgumentException(ONLY_S3_FILE_HANDLES_CAN_BE_DOWNLOADED);
		}
		return (S3FileHandle) handle;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sagebionetworks.file.worker.BulkDownloadDao#downloadToTempFile(org.
	 * sagebionetworks.repo.model.file.S3FileHandle)
	 */
	@Override
	public File downloadToTempFile(S3FileHandle fileHandle) throws IOException {
		File tempFile = File.createTempFile("FileHandle" + fileHandle.getId(), ".tmp");
		// download this file to the local machine
		s3client.getObject(new GetObjectRequest(fileHandle.getBucketName(), fileHandle.getKey()), tempFile);
		return tempFile;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.sagebionetworks.file.worker.BulkDownloadDao#addFileToZip(java.util.zip.
	 * ZipOutputStream, java.io.File, java.lang.String)
	 */
	@Override
	public void addFileToZip(ZipOutputStream zipOut, File toAdd, String zipEntryName) throws IOException {
		try(InputStream in = new FileInputStream(toAdd)){
			ZipEntry entry = new ZipEntry(zipEntryName);
			zipOut.putNextEntry(entry);
			// Write the file the zip
			IOUtils.copy(in, zipOut);
			zipOut.closeEntry();
		}
	}

	@Override
	public BulkFileDownloadResponse buildZip(UserInfo user, BulkFileDownloadRequest request) throws IOException {
		// fix for PLFM-6626
		if (request.getZipFileName() != null) {
			NameValidation.validateName(request.getZipFileName());
		}
		// The generated zip will be written to this temp file.
		File tempResultFile = createTempFile("Job", ".zip");
		try (ZipOutputStream zipOut = createZipOutputStream(tempResultFile)){
			/*
			 * The first step is to determine if the user is authorized to download each
			 * requested file. The authorization check is normalized around the associated
			 * object.
			 */
			List<FileHandleAssociationAuthorizationStatus> authResults = canDownLoadFile(user,
					request.getRequestedFiles());

			ZipEntryNameProvider zipEntryNameProvider = createZipEntryNameProvider(request.getZipFileFormat());
			// Track the files added to the zip.
			Set<String> fileIdsInZip = Sets.newHashSet();
			// Build the zip
			List<FileDownloadSummary> results = addFilesToZip(authResults, tempResultFile, zipOut, fileIdsInZip,
					zipEntryNameProvider);

			IOUtils.closeQuietly(zipOut);
			// Is there at least one file in the zip?
			String resultFileHandleId = null;
			if (fileIdsInZip.size() > 0) {
				// upload the result file to S3
				S3FileHandle resultHandle = multipartUploadLocalFile(
						new LocalFileUploadRequest().withFileName(request.getZipFileName())
								.withUserId(user.getId().toString()).withFileToUpload(tempResultFile)
								.withContentType(APPLICATION_ZIP).withListener(new ProgressListener() {
									@Override
									public void progressChanged(ProgressEvent progressEvent) {
									}
								}));
				resultFileHandleId = resultHandle.getId();
			}

			collectDownloadStatistics(user.getId(), results);

			// All of the parts are ready.
			BulkFileDownloadResponse response = new BulkFileDownloadResponse();
			response.setFileSummary(results);
			// added for PLFM-3629
			response.setUserId("" + user.getId());
			response.setResultZipFileHandleId(resultFileHandleId);
			return response;
		} finally {
			tempResultFile.delete();
		}
	}

	/**
	 * 
	 * @param progressCallback
	 * @param message
	 * @param authResults
	 * @param tempResultFile
	 * @param zipOut
	 */
	List<FileDownloadSummary> addFilesToZip(List<FileHandleAssociationAuthorizationStatus> authResults,
			File tempResultFile, ZipOutputStream zipOut, Set<String> fileIdsInZip,
			ZipEntryNameProvider zipEntryNameProvider) {
		// This will be the final summary of results..
		List<FileDownloadSummary> fileSummaries = Lists.newLinkedList();
		// process each request in order.
		for (FileHandleAssociationAuthorizationStatus fhas : authResults) {
			String fileHandleId = fhas.getAssociation().getFileHandleId();
			FileDownloadSummary summary = new FileDownloadSummary();
			summary.setFileHandleId(fileHandleId);
			summary.setAssociateObjectId(fhas.getAssociation().getAssociateObjectId());
			summary.setAssociateObjectType(fhas.getAssociation().getAssociateObjectType());
			fileSummaries.add(summary);
			try {
				String zipEntryName = writeOneFileToZip(zipOut, tempResultFile.length(), fhas, fileIdsInZip,
						zipEntryNameProvider);
				// download this file from S3
				fileIdsInZip.add(fileHandleId);
				summary.setStatus(FileDownloadStatus.SUCCESS);
				summary.setZipEntryName(zipEntryName);
			} catch (BulkFileException e) {
				// known error conditions.
				summary.setStatus(FileDownloadStatus.FAILURE);
				summary.setFailureMessage(e.getMessage());
				summary.setFailureCode(e.getFailureCode());
			} catch (NotFoundException e) {
				// file did not exist
				summary.setStatus(FileDownloadStatus.FAILURE);
				summary.setFailureMessage(e.getMessage());
				summary.setFailureCode(FileDownloadCode.NOT_FOUND);
			} catch (Exception e) {
				// all unknown errors.
				summary.setStatus(FileDownloadStatus.FAILURE);
				summary.setFailureMessage(e.getMessage());
				summary.setFailureCode(FileDownloadCode.UNKNOWN_ERROR);
				log.error("Failed on: " + fhas.getAssociation(), e);
			}
		}
		return fileSummaries;
	}

	/**
	 * Write a single file to the given zip stream.
	 * 
	 * @param zipOut
	 * @param zipFileSize
	 * @param fhas
	 * @param fileIdsInZip
	 * @throws IOException
	 * @return The zip entry name used for this file.
	 */
	String writeOneFileToZip(ZipOutputStream zipOut, long zipFileSize,
			FileHandleAssociationAuthorizationStatus fhas, Set<String> fileIdsInZip,
			ZipEntryNameProvider zipEntryNameProvider) throws IOException {
		String fileHandleId = fhas.getAssociation().getFileHandleId();
		// Is the user authorized to download this file?
		if (!fhas.getStatus().isAuthorized()) {
			throw new BulkFileException(fhas.getStatus().getMessage(), FileDownloadCode.UNAUTHORIZED);
		}
		// Each file handle should only be added once
		if (fileIdsInZip.contains(fileHandleId)) {
			throw new BulkFileException(FILE_ALREADY_ADDED, FileDownloadCode.DUPLICATE);
		}
		// Each file must be less than the max.
		if (zipFileSize > FileConstants.BULK_FILE_DOWNLOAD_MAX_SIZE_BYTES) {
			throw new BulkFileException(RESULT_FILE_HAS_REACHED_THE_MAXIMUM_SIZE, FileDownloadCode.EXCEEDS_SIZE_LIMIT);
		}
		// Get this filehandle.
		S3FileHandle s3Handle = getS3FileHandle(fileHandleId);
		// Each file must be under the max.s
		if (s3Handle.getContentSize() > FileConstants.BULK_FILE_DOWNLOAD_MAX_SIZE_BYTES) {
			throw new BulkFileException(FILE_EXCEEDS_THE_MAXIMUM_SIZE_LIMIT, FileDownloadCode.EXCEEDS_SIZE_LIMIT);
		}
		// This file will be downloaded to this temp.
		File downloadTemp = downloadToTempFile(s3Handle);
		try {
			// The entry name is the path plus file name.
			String zipEntryName = zipEntryNameProvider.createZipEntryName(s3Handle.getFileName(),
					Long.parseLong(s3Handle.getId()));
			// write the file to the zip.
			addFileToZip(zipOut, downloadTemp, zipEntryName);
			return zipEntryName;
		} finally {
			downloadTemp.delete();
		}
	}

	private void collectDownloadStatistics(Long userId, List<FileDownloadSummary> results) {

		List<StatisticsFileEvent> downloadEvents = results.stream()
				// Only collects stats for successful summaries
				.filter(summary -> FileDownloadStatus.SUCCESS.equals(summary.getStatus()))
				.map(summary -> StatisticsFileEventUtils.buildFileDownloadEvent(userId, summary.getFileHandleId(),
						summary.getAssociateObjectId(), summary.getAssociateObjectType()))
				.collect(Collectors.toList());

		if (!downloadEvents.isEmpty()) {
			statisticsCollector.collectEvents(downloadEvents);
		}

	}

	/**
	 * Get the ZipEntryNameProvider to use for the given format.
	 * 
	 * @param format
	 * @return
	 */
	static ZipEntryNameProvider createZipEntryNameProvider(ZipFileFormat format) {
		if (format == null) {
			// for backwards compatibility default to CommandLineCache
			format = ZipFileFormat.CommandLineCache;
		}
		switch (format) {
		case CommandLineCache:
			return new CommandLineCacheZipEntryNameProvider();
		case Flat:
			return new FlatZipEntryNameProvider();
		default:
			throw new IllegalArgumentException("Unknown type: " + format);
		}
	}

}
