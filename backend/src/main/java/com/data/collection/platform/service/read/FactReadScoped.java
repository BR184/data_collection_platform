package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.FactReadSelection;

/** 工厂创建只属于一次读取的不可变查询对象；共享 Spring 服务不保存页面/候选数据身份。 */
public interface FactReadScoped<T> {
  /**
   * 建立持有固定版本组合的读取对象；不改变工厂实例或其它请求。
   *
   * @param selection 已核验且由调用者在读取期间保护的固定身份
   * @return 独立读取对象；其全部事实查询显式使用该身份
   */
  T forRead(FactReadSelection selection);
}
