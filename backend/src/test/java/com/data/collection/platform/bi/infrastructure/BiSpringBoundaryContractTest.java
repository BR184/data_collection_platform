package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.bi.application.BiCatTestPageService;
import com.data.collection.platform.bi.application.BiCodingPageService;
import com.data.collection.platform.bi.application.BiReviewPageService;
import com.data.collection.platform.bi.application.BiSystemTestPageService;
import com.data.collection.platform.bi.domain.BiCodingCalculator;
import com.data.collection.platform.bi.domain.BiReviewCalculator;
import com.data.collection.platform.bi.domain.BiSystemTestCalculator;
import java.lang.annotation.Annotation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

class BiSpringBoundaryContractTest {
  @Test
  void keepsInternalServicesCalculatorsAndRepositoriesOutsideSpringScanning() {
    List<Class<?>> internalTypes = List.of(
        BiCatTestPageService.class,
        BiCodingPageService.class,
        BiReviewPageService.class,
        BiSystemTestPageService.class,
        BiCodingCalculator.class,
        BiReviewCalculator.class,
        BiSystemTestCalculator.class,
        BiPlatformProductVersionAdapter.class,
        BiPlatformReviewSourceAdapter.class,
        BiPlatformCodingSourceAdapter.class,
        BiCodingCommitFactRepository.class,
        BiPlatformSystemTestSourceAdapter.class,
        BiCatTestSourceAdapter.class);

    internalTypes.forEach(type -> assertThat(hasSpringStereotype(type)).as(type.getName()).isFalse());
    assertThat(BiDashboardRuntimeFactory.class.isAnnotationPresent(Component.class)).isTrue();
    assertThat(BiDashboardRuntimeManager.class.isAnnotationPresent(Component.class)).isTrue();
  }

  private boolean hasSpringStereotype(Class<?> type) {
    return hasAnnotation(type, Component.class)
        || hasAnnotation(type, Service.class)
        || hasAnnotation(type, Repository.class);
  }

  private boolean hasAnnotation(Class<?> type, Class<? extends Annotation> annotation) {
    return type.isAnnotationPresent(annotation);
  }
}
