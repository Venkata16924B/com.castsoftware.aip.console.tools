package com.castsoftware.aip.console.tools.core.services;

import com.castsoftware.aip.console.tools.core.dto.ApiInfoDto;
import com.castsoftware.aip.console.tools.core.dto.upload.ChunkedUploadDto;
import com.castsoftware.aip.console.tools.core.dto.upload.ChunkedUploadMetadataRequest;
import com.castsoftware.aip.console.tools.core.dto.upload.ChunkedUploadStatus;
import com.castsoftware.aip.console.tools.core.dto.upload.CreateUploadRequest;
import com.castsoftware.aip.console.tools.core.exceptions.ApiCallException;
import com.castsoftware.aip.console.tools.core.exceptions.UploadException;
import com.castsoftware.aip.console.tools.core.utils.ApiEndpointHelper;
import lombok.extern.java.Log;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

@Log
public class ChunkedUploadServiceImpl implements ChunkedUploadService {

    /**
     * Maximum chunk size allowed by AIP Console
     */
    private static final int MAX_CHUNK_SIZE = 50 * 1024 * 1024;
    /**
     * Default chunk size if none is provided
     */
    private static final int DEFAULT_CHUNK_SIZE = 10 * 1024 * 1024;
    private static final long EXTRACT_SLEEP_TIME = TimeUnit.SECONDS.toMillis(10);
    private static final long LOG_INFO_TIME_THRESHOLD = TimeUnit.MINUTES.toMillis(5);

    private RestApiService restApiService;

    private int chunkSize = DEFAULT_CHUNK_SIZE;

    private final long extractPollSleep;

    public ChunkedUploadServiceImpl(RestApiService restApiService) {
        this.restApiService = restApiService;
        this.extractPollSleep = EXTRACT_SLEEP_TIME;
    }

    public ChunkedUploadServiceImpl(RestApiService restApiService, int maxChunkSize) {
        this.restApiService = restApiService;
        this.chunkSize = Math.min(maxChunkSize, MAX_CHUNK_SIZE);
        this.extractPollSleep = EXTRACT_SLEEP_TIME;
    }

    public ChunkedUploadServiceImpl(RestApiService restApiService, int maxChunkSize, long extractPollSleep) {
        this.restApiService = restApiService;
        this.chunkSize = Math.min(maxChunkSize, MAX_CHUNK_SIZE);
        this.extractPollSleep = extractPollSleep;
    }

    @Override
    public boolean uploadFile(String appGuid, File archiveFile) throws UploadException {
        if (StringUtils.isBlank(appGuid)) {
            throw new UploadException("No Application GUID provided.");
        }

        Path archivePath = archiveFile != null ? archiveFile.toPath() : null;
        if (archivePath == null || !Files.exists(archiveFile.toPath())) {
            throw new UploadException("No file provided for upload");
        }

        long fileSize;
        try {
            fileSize = Files.size(archivePath);
        } catch (IOException e) {
            throw new UploadException("Unable to get archive size for given file " + archivePath, e);
        }
        ApiInfoDto aipConsoleInfo = restApiService.getAipConsoleApiInfo();
        try (InputStream is = new BufferedInputStream(Files.newInputStream(archivePath))) {
            return uploadAndExtractInputStream(appGuid, FilenameUtils.getName(archivePath.toString()), fileSize, is, aipConsoleInfo.isEnablePackagePathCheck());
        } catch (IOException e) {
            throw new UploadException("Unable to read file", e);
        }
    }

    @Override
    public boolean uploadInputStream(String appGuid, String fileName, long fileSize, InputStream content)
            throws UploadException {
        ApiInfoDto aipConsoleInfo = restApiService.getAipConsoleApiInfo();
        return uploadAndExtractInputStream(appGuid, fileName, fileSize, content, aipConsoleInfo.isEnablePackagePathCheck());
    }

    @Override
    public boolean uploadAndExtractInputStream(String appGuid, String fileName, long fileSize, InputStream content, boolean extract)
            throws UploadException {
        String createUploadEndpoint = ApiEndpointHelper.getApplicationCreateUploadPath(appGuid);
        CreateUploadRequest request = new CreateUploadRequest();
        request.setFileName(fileName);
        request.setFileSize(fileSize);

        ChunkedUploadDto dto;
        try {
            log.info("Creating a new upload for application");
            log.fine("Params : " + createUploadEndpoint + "\n" + request.toString());
            dto = restApiService.postForEntity(createUploadEndpoint, request, ChunkedUploadDto.class);
        } catch (ApiCallException e) {
            log.log(Level.SEVERE, "Error while trying to create upload", e);
            throw new UploadException("Unable to create upload", e);
        }

        if (dto == null || StringUtils.isBlank(dto.getGuid())) {
            throw new UploadException("Upload was not created on AIP Console");
        }
        String uploadChunkEndpoint = ApiEndpointHelper.getApplicationUploadPath(appGuid, dto.getGuid());
        int currentChunk = 1;
        try {
            long currentOffset = 0;
            int totalChunks = (int) Math.ceil((double) fileSize / (double) chunkSize);
            log.info("Starting chunks uploads. Expected number of chunks is " + totalChunks);
            while (currentOffset < fileSize) {
                byte[] buffer = new byte[chunkSize];
                // IOUtils.read will try to fill the buffer (unless it arrives at EOF)
                int nbBytesRead = IOUtils.read(content, buffer);
                log.fine("Read " + nbBytesRead + " from file");
                if (nbBytesRead < 0) {
                    throw new UploadException("No more content to read but expected file size was not attained. Is a process modifying the file being read ?");
                }
                if (nbBytesRead == 0) {
                    log.fine("No content could be read from the file, but end of file was not reached. Trying again.");
                    continue;
                }

                ChunkedUploadMetadataRequest metadata = new ChunkedUploadMetadataRequest();
                metadata.setChunkSize(nbBytesRead);

                Map<String, String> metadataHeaderMap = new HashMap<>();
                metadataHeaderMap.put("Content-Type", "application/json");

                Map<String, String> contentHeaderMap = new HashMap<>();
                contentHeaderMap.put("Content-Disposition", "form-data; name=content; filename=filechunk");
                contentHeaderMap.put("Content-Type", "application/octet-stream");

                Map<String, Map<String, String>> headers = new HashMap<>();
                headers.put("metadata", metadataHeaderMap);
                headers.put("content", contentHeaderMap);

                Map<String, Object> body = new HashMap<>();
                body.put("metadata", metadata);
                if (nbBytesRead < chunkSize) {
                    body.put("content", ArrayUtils.subarray(buffer, 0, nbBytesRead));
                } else {
                    body.put("content", buffer);
                }

                log.info(String.format("Uploading chunk %s of %s", currentChunk, totalChunks));
                log.fine("Uploading a chunk of " + nbBytesRead + " bytes");

                dto = restApiService.exchangeMultipartForEntity("PATCH", uploadChunkEndpoint, headers, body, ChunkedUploadDto.class);
                currentOffset += nbBytesRead;
                currentChunk++;

                assert dto != null;
                assert dto.getCurrentOffset() == currentOffset;
            }

        } catch (ApiCallException | IOException e) {
            log.info("Error occurred during upload. Trying to delete before failing.");
            try {
                restApiService.deleteForEntity(uploadChunkEndpoint, null, String.class);
            } catch (ApiCallException inner) {
                if (dto != null) {
                    log.warning("Unable to remove failed upload with GUID '" + dto.getGuid() + "'");
                }
            }
            throw new UploadException("Error occurred while uploading chunk number " + currentChunk, e);
        }

        // if enablePackagePath is true, we need to extract manually
        log.info("Should extract content ? " + extract);
        boolean uploadComplete = StringUtils.equalsAnyIgnoreCase(dto.getStatus(), ChunkedUploadStatus.UPLOADED.name(), "completed");
        // return if enablePackagePath is false or the upload was not complete
        if (!uploadComplete || !extract) {
            return uploadComplete;
        }

        log.info("Extracting archive on AIP Console");
        long waitTime = 0;
        String extractEndpoint = ApiEndpointHelper.getApplicationExtractUploadPath(appGuid, dto.getGuid());
        while (StringUtils.equalsAnyIgnoreCase(dto.getStatus(), ChunkedUploadStatus.UPLOADED.name(), ChunkedUploadStatus.EXTRACTING.name())) {
            try {
                dto = restApiService.putForEntity(extractEndpoint, null, ChunkedUploadDto.class);
                Thread.sleep(extractPollSleep);
                waitTime += extractPollSleep;
                // Notify every X minutes (check LOG_INFO_TIME_THRESHOLD for value) that we're still waiting for extraction from AIP Console
                if (waitTime > LOG_INFO_TIME_THRESHOLD) {
                    waitTime = 0;
                    log.info("Waiting for AIP Console to finish extraction. Current status is " + dto.getStatus());
                }
            } catch (InterruptedException e) {
                log.log(Level.WARNING, "Thread.sleep was interrupted. Trying to continue polling AIP Console", e);
            } catch (ApiCallException e) {
                log.log(Level.SEVERE, "Unable to extract source code archive on AIP Console", e);
                throw new UploadException("Failed to extract source code in AIP Console", e);
            }
        }
        return StringUtils.equalsIgnoreCase(dto.getStatus(), "EXTRACTED");
    }
}
