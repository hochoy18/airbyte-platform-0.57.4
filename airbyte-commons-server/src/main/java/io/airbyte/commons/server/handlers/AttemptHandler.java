/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.server.handlers;

import com.google.common.annotations.VisibleForTesting;
import io.airbyte.api.model.generated.AttemptInfoRead;
import io.airbyte.api.model.generated.AttemptStats;
import io.airbyte.api.model.generated.CreateNewAttemptNumberResponse;
import io.airbyte.api.model.generated.InternalOperationResult;
import io.airbyte.api.model.generated.SaveAttemptSyncConfigRequestBody;
import io.airbyte.api.model.generated.SaveStatsRequestBody;
import io.airbyte.api.model.generated.SetWorkflowInAttemptRequestBody;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.server.converters.ApiPojoConverters;
import io.airbyte.commons.server.converters.JobConverter;
import io.airbyte.commons.server.errors.BadRequestException;
import io.airbyte.commons.server.errors.IdNotFoundKnownException;
import io.airbyte.commons.server.errors.UnprocessableContentException;
import io.airbyte.commons.server.handlers.helpers.JobCreationAndStatusUpdateHelper;
import io.airbyte.commons.temporal.TemporalUtils;
import io.airbyte.config.AttemptFailureSummary;
import io.airbyte.config.JobConfig;
import io.airbyte.config.JobOutput;
import io.airbyte.config.StandardSyncOutput;
import io.airbyte.config.StreamSyncStats;
import io.airbyte.config.SyncStats;
import io.airbyte.config.helpers.LogClientSingleton;
import io.airbyte.config.persistence.StatePersistence;
import io.airbyte.featureflag.Connection;
import io.airbyte.featureflag.DeleteFullRefreshState;
import io.airbyte.featureflag.FeatureFlagClient;
import io.airbyte.metrics.lib.OssMetricsRegistry;
import io.airbyte.persistence.job.JobPersistence;
import io.airbyte.persistence.job.models.Job;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.StreamDescriptor;
import io.airbyte.protocol.models.SyncMode;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AttemptHandler. Javadocs suppressed because api docs should be used as source of truth.
 */
@Singleton
public class AttemptHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(AttemptHandler.class);
  private static final int FIRST_ATTEMPT = 0;

  private final JobPersistence jobPersistence;
  private final StatePersistence statePersistence;

  private final JobConverter jobConverter;
  private final JobCreationAndStatusUpdateHelper jobCreationAndStatusUpdateHelper;
  private final Path workspaceRoot;
  private final FeatureFlagClient featureFlagClient;

  public AttemptHandler(final JobPersistence jobPersistence,
                        final StatePersistence statePersistence,
                        final JobConverter jobConverter,
                        final FeatureFlagClient featureFlagClient,
                        final JobCreationAndStatusUpdateHelper jobCreationAndStatusUpdateHelper,
                        @Named("workspaceRoot") final Path workspaceRoot) {
    this.jobPersistence = jobPersistence;
    this.statePersistence = statePersistence;
    this.jobConverter = jobConverter;
    this.jobCreationAndStatusUpdateHelper = jobCreationAndStatusUpdateHelper;
    this.featureFlagClient = featureFlagClient;
    this.workspaceRoot = workspaceRoot;
  }

  public CreateNewAttemptNumberResponse createNewAttemptNumber(final long jobId) throws IOException {
    final Job job;
    try {
      job = jobPersistence.getJob(jobId);
    } catch (final RuntimeException e) {
      throw new UnprocessableContentException(String.format("Could not find jobId: %s", jobId), e);
    }

    final Path jobRoot = TemporalUtils.getJobRoot(workspaceRoot, String.valueOf(jobId), job.getAttemptsCount());
    final Path logFilePath = jobRoot.resolve(LogClientSingleton.LOG_FILENAME);
    final int persistedAttemptNumber = jobPersistence.createAttempt(jobId, logFilePath);

    // We cannot easily do this in a transaction as the attempt and state tables are in separate logical
    // databases.
    final var removeFullRefreshStreamState =
        job.getConfigType().equals(JobConfig.ConfigType.SYNC) || job.getConfigType().equals(JobConfig.ConfigType.REFRESH);
    if (removeFullRefreshStreamState) {
      if (featureFlagClient.boolVariation(DeleteFullRefreshState.INSTANCE, new Connection(job.getScope()))) {
        LOGGER.info("Clearing full refresh state..");
        final var stateToClear = getFullRefreshStreams(job.getConfig().getSync().getConfiguredAirbyteCatalog(), job.getId());
        if (!stateToClear.isEmpty()) {
          statePersistence.bulkDelete(UUID.fromString(job.getScope()), stateToClear);
        }
      }
    }

    jobCreationAndStatusUpdateHelper.emitJobToReleaseStagesMetric(OssMetricsRegistry.ATTEMPT_CREATED_BY_RELEASE_STAGE, job);
    jobCreationAndStatusUpdateHelper.emitAttemptCreatedEvent(job, persistedAttemptNumber);

    return new CreateNewAttemptNumberResponse().attemptNumber(persistedAttemptNumber);
  }

  @VisibleForTesting
  Set<StreamDescriptor> getFullRefreshStreams(ConfiguredAirbyteCatalog catalog, long id) {
    if (catalog == null) {
      throw new BadRequestException("Missing configured catalog for job: " + id);
    }
    final var configuredStreams = catalog.getStreams();
    if (configuredStreams == null) {
      throw new BadRequestException("Missing configured catalog stream for job: " + id);
    }

    return configuredStreams.stream()
        .filter(s -> s.getSyncMode().equals(SyncMode.FULL_REFRESH))
        .map(s -> new StreamDescriptor().withName(s.getStream().getName()).withNamespace(s.getStream().getNamespace()))
        .collect(Collectors.toSet());
  }

  public AttemptInfoRead getAttemptForJob(final long jobId, final int attemptNo) throws IOException {
    final Optional<AttemptInfoRead> read = jobPersistence.getAttemptForJob(jobId, attemptNo)
        .map(jobConverter::getAttemptInfoRead);

    if (read.isEmpty()) {
      throw new IdNotFoundKnownException(
          String.format("Could not find attempt for job_id: %d and attempt no: %d", jobId, attemptNo),
          String.format("%d_%d", jobId, attemptNo));
    }

    return read.get();
  }

  public AttemptStats getAttemptCombinedStats(final long jobId, final int attemptNo) throws IOException {
    final SyncStats stats = jobPersistence.getAttemptCombinedStats(jobId, attemptNo);

    if (stats == null) {
      throw new IdNotFoundKnownException(
          String.format("Could not find attempt stats for job_id: %d and attempt no: %d", jobId, attemptNo),
          String.format("%d_%d", jobId, attemptNo));
    }

    return new AttemptStats()
        .recordsEmitted(stats.getRecordsEmitted())
        .bytesEmitted(stats.getBytesEmitted())
        .bytesCommitted(stats.getBytesCommitted())
        .recordsCommitted(stats.getRecordsCommitted())
        .estimatedRecords(stats.getEstimatedRecords())
        .estimatedBytes(stats.getEstimatedBytes());
  }

  public InternalOperationResult setWorkflowInAttempt(final SetWorkflowInAttemptRequestBody requestBody) {
    try {
      jobPersistence.setAttemptTemporalWorkflowInfo(requestBody.getJobId(),
          requestBody.getAttemptNumber(), requestBody.getWorkflowId(), requestBody.getProcessingTaskQueue());
    } catch (final IOException ioe) {
      LOGGER.error("IOException when setting temporal workflow in attempt;", ioe);
      return new InternalOperationResult().succeeded(false);
    }
    return new InternalOperationResult().succeeded(true);
  }

  public InternalOperationResult saveStats(final SaveStatsRequestBody requestBody) {
    try {
      final var stats = requestBody.getStats();
      final var streamStats = requestBody.getStreamStats().stream()
          .map(s -> new StreamSyncStats()
              .withStreamName(s.getStreamName())
              .withStreamNamespace(s.getStreamNamespace())
              .withStats(new SyncStats()
                  .withBytesEmitted(s.getStats().getBytesEmitted())
                  .withRecordsEmitted(s.getStats().getRecordsEmitted())
                  .withBytesCommitted(s.getStats().getBytesCommitted())
                  .withRecordsCommitted(s.getStats().getRecordsCommitted())
                  .withEstimatedBytes(s.getStats().getEstimatedBytes())
                  .withEstimatedRecords(s.getStats().getEstimatedRecords())))
          .collect(Collectors.toList());

      jobPersistence.writeStats(requestBody.getJobId(), requestBody.getAttemptNumber(),
          stats.getEstimatedRecords(), stats.getEstimatedBytes(),
          stats.getRecordsEmitted(), stats.getBytesEmitted(),
          stats.getRecordsCommitted(), stats.getBytesCommitted(),
          streamStats);

    } catch (final IOException ioe) {
      LOGGER.error("IOException when setting temporal workflow in attempt;", ioe);
      return new InternalOperationResult().succeeded(false);
    }

    return new InternalOperationResult().succeeded(true);
  }

  public InternalOperationResult saveSyncConfig(final SaveAttemptSyncConfigRequestBody requestBody) {
    try {
      jobPersistence.writeAttemptSyncConfig(
          requestBody.getJobId(),
          requestBody.getAttemptNumber(),
          ApiPojoConverters.attemptSyncConfigToInternal(requestBody.getSyncConfig()));
    } catch (final IOException ioe) {
      LOGGER.error("IOException when saving AttemptSyncConfig for attempt;", ioe);
      return new InternalOperationResult().succeeded(false);
    }
    return new InternalOperationResult().succeeded(true);
  }

  @SuppressWarnings("PMD")
  public void failAttempt(final int attemptNumber, final long jobId, final Object rawFailureSummary, final Object rawSyncOutput)
      throws IOException {
    AttemptFailureSummary failureSummary = null;
    if (rawFailureSummary != null) {
      try {
        failureSummary = Jsons.convertValue(rawFailureSummary, AttemptFailureSummary.class);
      } catch (final Exception e) {
        throw new BadRequestException("Unable to parse failureSummary.", e);
      }
    }
    StandardSyncOutput output = null;
    if (rawSyncOutput != null) {
      try {
        output = Jsons.convertValue(rawSyncOutput, StandardSyncOutput.class);
      } catch (final Exception e) {
        throw new BadRequestException("Unable to parse standardSyncOutput.", e);
      }
    }

    jobCreationAndStatusUpdateHelper.traceFailures(failureSummary);

    jobPersistence.failAttempt(jobId, attemptNumber);
    jobPersistence.writeAttemptFailureSummary(jobId, attemptNumber, failureSummary);

    if (output != null) {
      final JobOutput jobOutput = new JobOutput().withSync(output);
      jobPersistence.writeOutput(jobId, attemptNumber, jobOutput);
    }

    final Job job = jobPersistence.getJob(jobId);
    jobCreationAndStatusUpdateHelper.emitJobToReleaseStagesMetric(OssMetricsRegistry.ATTEMPT_FAILED_BY_RELEASE_STAGE, job);
    jobCreationAndStatusUpdateHelper.trackFailures(failureSummary);
  }

}
