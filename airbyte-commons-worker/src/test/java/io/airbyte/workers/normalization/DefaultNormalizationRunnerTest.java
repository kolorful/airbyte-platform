/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.normalization;

import static io.airbyte.commons.logging.LoggingHelper.RESET;
import static io.airbyte.workers.process.Metadata.JOB_TYPE_KEY;
import static io.airbyte.workers.process.Metadata.NORMALIZE_STEP;
import static io.airbyte.workers.process.Metadata.SYNC_JOB;
import static io.airbyte.workers.process.Metadata.SYNC_STEP_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import io.airbyte.commons.constants.WorkerConstants;
import io.airbyte.commons.io.IOs;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.logging.LoggingHelper.Color;
import io.airbyte.config.Configs.WorkerEnvironment;
import io.airbyte.config.EnvConfigs;
import io.airbyte.config.helpers.LogClientSingleton;
import io.airbyte.config.helpers.LogConfigs;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.workers.WorkerConfigs;
import io.airbyte.workers.config.WorkerConfigsProvider.ResourceType;
import io.airbyte.workers.exception.WorkerException;
import io.airbyte.workers.process.AirbyteIntegrationLauncher;
import io.airbyte.workers.process.ConnectorResourceRequirements;
import io.airbyte.workers.process.ProcessFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DefaultNormalizationRunnerTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultNormalizationRunnerTest.class);

  private static final String JOB_ID = "0";
  private static final int JOB_ATTEMPT = 0;
  private static final UUID CONNECTION_ID = null;
  private static final UUID WORKSPACE_ID = null;

  private static final String NORMALIZATION_IMAGE = "airbyte/normalization";
  private static final String NORMALIZATION_TAG = "42.42.42";
  private static final String INTEGRATION_TYPE = "postgres";

  private static Path logJobRoot;

  static {
    try {
      logJobRoot = Files.createTempDirectory(Path.of("/tmp"), "mdc_test");
      LogClientSingleton.getInstance().setJobMdc(WorkerEnvironment.DOCKER, LogConfigs.EMPTY, logJobRoot);
    } catch (final IOException e) {
      LOGGER.error(e.getMessage());
    }
  }

  private WorkerConfigs workerConfigs;
  private Path jobRoot;
  private ProcessFactory processFactory;
  private Process process;
  private JsonNode config;
  private ConfiguredAirbyteCatalog catalog;

  @BeforeEach
  void setup() throws IOException, WorkerException {
    workerConfigs = new WorkerConfigs(new EnvConfigs());
    jobRoot = Files.createDirectories(Files.createTempDirectory("test"));
    processFactory = mock(ProcessFactory.class);
    process = mock(Process.class);

    config = mock(JsonNode.class);
    catalog = mock(ConfiguredAirbyteCatalog.class);

    final Map<String, String> files = ImmutableMap.of(
        WorkerConstants.DESTINATION_CONFIG_JSON_FILENAME, Jsons.serialize(config),
        WorkerConstants.DESTINATION_CATALOG_JSON_FILENAME, Jsons.serialize(catalog));

    final ConnectorResourceRequirements expectedResourceRequirements =
        AirbyteIntegrationLauncher.buildGenericConnectorResourceRequirements(new WorkerConfigs(new EnvConfigs()).getResourceRequirements());
    when(processFactory.create(ResourceType.NORMALIZATION, NORMALIZE_STEP, JOB_ID, JOB_ATTEMPT, CONNECTION_ID, WORKSPACE_ID, jobRoot,
        getTaggedImageName(NORMALIZATION_IMAGE, NORMALIZATION_TAG), false, false, files, null,
        expectedResourceRequirements,
        null,
        Map.of(JOB_TYPE_KEY, SYNC_JOB, SYNC_STEP_KEY, NORMALIZE_STEP),
        Map.of(),
        Map.of(),
        Collections.emptyMap(), "run",
        "--integration-type", INTEGRATION_TYPE,
        "--config", WorkerConstants.DESTINATION_CONFIG_JSON_FILENAME,
        "--catalog", WorkerConstants.DESTINATION_CATALOG_JSON_FILENAME))
            .thenReturn(process);
    when(process.getInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    when(process.getErrorStream()).thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
  }

  @AfterEach
  public void tearDown() throws IOException {
    // The log file needs to be present and empty
    final Path logFile = logJobRoot.resolve(LogClientSingleton.LOG_FILENAME);
    if (Files.exists(logFile)) {
      Files.delete(logFile);
    }
    Files.createFile(logFile);
  }

  @Test
  void test() throws Exception {
    final NormalizationRunner runner =
        new DefaultNormalizationRunner(processFactory, getTaggedImageName(NORMALIZATION_IMAGE, NORMALIZATION_TAG), INTEGRATION_TYPE);

    when(process.exitValue()).thenReturn(0);

    assertTrue(runner.normalize(JOB_ID, JOB_ATTEMPT, CONNECTION_ID, WORKSPACE_ID, jobRoot, config, catalog, workerConfigs.getResourceRequirements()));
  }

  @Test
  void testLog() throws Exception {

    final NormalizationRunner runner =
        new DefaultNormalizationRunner(processFactory, getTaggedImageName(NORMALIZATION_IMAGE, NORMALIZATION_TAG), INTEGRATION_TYPE);

    when(process.exitValue()).thenReturn(0);

    assertTrue(runner.normalize(JOB_ID, JOB_ATTEMPT, CONNECTION_ID, WORKSPACE_ID, jobRoot, config, catalog, workerConfigs.getResourceRequirements()));

    final Path logPath = logJobRoot.resolve(LogClientSingleton.LOG_FILENAME);
    final Stream<String> logs = IOs.readFile(logPath).lines();

    logs
        .filter(line -> !line.contains("EnvConfigs(getEnvOrDefault)"))
        .forEach(line -> {
          org.assertj.core.api.Assertions.assertThat(line)
              .startsWith(Color.GREEN_BACKGROUND.getCode() + "normalization" + RESET);
        });
  }

  @Test
  void testClose() throws Exception {
    when(process.isAlive()).thenReturn(true).thenReturn(false);

    final NormalizationRunner runner =
        new DefaultNormalizationRunner(processFactory, getTaggedImageName(NORMALIZATION_IMAGE, NORMALIZATION_TAG), INTEGRATION_TYPE);
    runner.normalize(JOB_ID, JOB_ATTEMPT, CONNECTION_ID, WORKSPACE_ID, jobRoot, config, catalog, workerConfigs.getResourceRequirements());
    runner.close();

    verify(process).waitFor();
  }

  @Test
  void testFailure() throws Exception {
    when(process.exitValue()).thenReturn(1);

    final NormalizationRunner runner =
        new DefaultNormalizationRunner(processFactory, getTaggedImageName(NORMALIZATION_IMAGE, NORMALIZATION_TAG), INTEGRATION_TYPE);
    assertFalse(
        runner.normalize(JOB_ID, JOB_ATTEMPT, CONNECTION_ID, WORKSPACE_ID, jobRoot, config, catalog, workerConfigs.getResourceRequirements()));

    verify(process).waitFor();

    assertThrows(WorkerException.class, runner::close);
  }

  @Test
  void testFailureWithTraceMessage() throws Exception {
    when(process.exitValue()).thenReturn(1);

    final String errorTraceString = """
                                    {"type": "TRACE", "trace": {
                                      "type": "ERROR", "emitted_at": 123.0, "error": {
                                        "message": "Something went wrong in normalization.", "internal_message": "internal msg",
                                        "stack_trace": "abc.xyz", "failure_type": "system_error"}}}
                                    """.replace("\n", "");
    when(process.getInputStream()).thenReturn(new ByteArrayInputStream(errorTraceString.getBytes(StandardCharsets.UTF_8)));

    final NormalizationRunner runner = new DefaultNormalizationRunner(processFactory, getTaggedImageName(NORMALIZATION_IMAGE, NORMALIZATION_TAG),
        INTEGRATION_TYPE);
    assertFalse(
        runner.normalize(JOB_ID, JOB_ATTEMPT, CONNECTION_ID, WORKSPACE_ID, jobRoot, config, catalog, workerConfigs.getResourceRequirements()));

    assertEquals(1, runner.getTraceMessages().count());

    verify(process).waitFor();

    assertThrows(WorkerException.class, runner::close);
  }

  @Test
  void testFailureWithDbtError() throws Exception {
    when(process.exitValue()).thenReturn(1);

    final String dbtErrorString = """
                                  [info ] [MainThread]: Completed with 1 error and 0 warnings:
                                  [info ] [MainThread]:
                                  [error] [MainThread]: Database Error in model xyz (models/generated/airbyte_incremental/abc/xyz.sql)
                                  [error] [MainThread]:   1292 (22007): Truncated incorrect DOUBLE value: 'ABC'
                                  [error] [MainThread]:   compiled SQL at ../build/run/airbyte_utils/models/generated/airbyte_incremental/abc/xyz.sql
                                  [info ] [MainThread]:
                                  [info ] [MainThread]: Done. PASS=1 WARN=0 ERROR=1 SKIP=0 TOTAL=2
                                  """;
    when(process.getInputStream()).thenReturn(new ByteArrayInputStream(dbtErrorString.getBytes(StandardCharsets.UTF_8)));

    final NormalizationRunner runner =
        new DefaultNormalizationRunner(processFactory, getTaggedImageName(NORMALIZATION_IMAGE, NORMALIZATION_TAG), INTEGRATION_TYPE);
    assertFalse(
        runner.normalize(JOB_ID, JOB_ATTEMPT, CONNECTION_ID, WORKSPACE_ID, jobRoot, config, catalog, workerConfigs.getResourceRequirements()));

    assertEquals(1, runner.getTraceMessages().count());

    verify(process).waitFor();

    assertThrows(WorkerException.class, runner::close);
  }

  @SuppressWarnings("LineLength")
  @Test
  void testFailureWithDbtErrorJsonFormat() throws Exception {
    when(process.exitValue()).thenReturn(1);

    final String dbtErrorString =
        """
        {"code": "Q035", "data": {"description": "table model public.start_products", "execution_time": 0.1729569435119629, "index": 1, "status": "error", "total": 2}, "invocation_id": "6ada8ee5-11c1-4239-8bd0-7e45178217c5", "level": "error", "log_version": 1, "msg": "1 of 2 ERROR creating table model public.start_products................................................................. [\\u001b[31mERROR\\u001b[0m in 0.17s]", "node_info": {"materialized": "table", "node_finished_at": null, "node_name": "start_products", "node_path": "generated/airbyte_incremental/public/start_products.sql", "node_started_at": "2022-07-18T15:04:27.036328", "node_status": "compiling", "resource_type": "model", "type": "node_status", "unique_id": "model.airbyte_utils.start_products"}, "pid": 14, "thread_name": "Thread-1", "ts": "2022-07-18T15:04:27.215077Z", "type": "log_line"}
        """;
    when(process.getInputStream()).thenReturn(new ByteArrayInputStream(dbtErrorString.getBytes(StandardCharsets.UTF_8)));

    final NormalizationRunner runner =
        new DefaultNormalizationRunner(processFactory, getTaggedImageName(NORMALIZATION_IMAGE, NORMALIZATION_TAG), INTEGRATION_TYPE);
    assertFalse(
        runner.normalize(JOB_ID, JOB_ATTEMPT, CONNECTION_ID, WORKSPACE_ID, jobRoot, config, catalog, workerConfigs.getResourceRequirements()));

    assertEquals(1, runner.getTraceMessages().count());

    verify(process).waitFor();

    assertThrows(WorkerException.class, runner::close);
  }

  static String getTaggedImageName(final String repository, final String tag) {
    return repository + ":" + tag;
  }

}
