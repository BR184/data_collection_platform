package com.data.collection.platform.service.statistics;

interface SystemTestPhaseFilterSource {
  String phaseFilterValue();

  default String phaseLabel() {
    return phaseFilterValue();
  }
}
