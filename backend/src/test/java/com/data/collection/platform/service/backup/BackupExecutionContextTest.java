package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BackupExecutionContextTest {
  @Test
  void test_requestStop_duringRemoteOperationCancelsSessionAndWaitsForExit() throws Exception {
    BackupStateRepository stateRepository = mock(BackupStateRepository.class);
    when(stateRepository.isOwnedActive(anyLong(), anyString(), any())).thenReturn(true);
    BackupExecutionContext context =
        new BackupExecutionContext(15L, UUID.randomUUID().toString(), stateRepository, Clock.systemUTC());
    TestBackupRemoteStorage storage = new TestBackupRemoteStorage();
    CountDownLatch uploadStarted = new CountDownLatch(1);
    CountDownLatch releaseUpload = new CountDownLatch(1);
    storage.onUpload = () -> {
      uploadStarted.countDown();
      try {
        if (!releaseUpload.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("upload cancellation was not delivered");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("upload was interrupted", interrupted);
      }
    };
    storage.onCancel = releaseUpload::countDown;
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> worker = executor.submit(() -> {
        context.beginWorker();
        context.beginExternalOperation();
        context.attachStorage(storage);
        try {
          storage.upload(Path.of("backup.dump"), "/remote", "backup.dump");
        } finally {
          storage.close();
          context.detachStorage(storage);
          context.endExternalOperation();
          context.endWorker();
        }
      });
      assertThat(uploadStarted.await(5, TimeUnit.SECONDS)).isTrue();

      context.requestStop("lease revoked");

      assertThat(context.awaitStopped(Duration.ofSeconds(5))).isTrue();
      worker.get(5, TimeUnit.SECONDS);
      assertThat(storage.operations).contains("cancel");
    } finally {
      releaseUpload.countDown();
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
