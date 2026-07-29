package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.data.collection.platform.config.GitlabMirrorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SyncRunTableTaskHeartbeatServiceTest {

  @Test
  void test_multipleConstructors_springContextCreatesService() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setTableTaskLeaseSeconds(120);
    properties.setExternalQueryTimeoutSeconds(60);

    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(SyncRunTableTaskLeaseService.class, () -> mock(SyncRunTableTaskLeaseService.class));
      context.registerBean(GitlabMirrorProperties.class, () -> properties);
      context.register(SyncRunTableTaskHeartbeatService.class);
      context.refresh();

      SyncRunTableTaskHeartbeatService service =
          context.getBean(SyncRunTableTaskHeartbeatService.class);
      assertThat(service.leaseSeconds()).isEqualTo(120);
    }
  }
}
