package com.data.collection.platform.service;

import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** 默认业务日来源：系统当日。 */
@Component
public class SystemBusinessDateProvider implements BusinessDateProvider {

  @Override
  public LocalDate businessDate() {
    return LocalDate.now();
  }
}
