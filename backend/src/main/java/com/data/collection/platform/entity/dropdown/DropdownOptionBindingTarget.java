package com.data.collection.platform.entity.dropdown;

/** 字段改绑目标：新建空白配置、复制当前配置，或绑定到既有配置（共用入口）。 */
public enum DropdownOptionBindingTarget {
  /** 新建空白配置并绑定。 */
  NEW,
  /** 复制当前绑定的配置为新配置并绑定（拆分的默认路径）。 */
  COPY,
  /** 绑定到既有配置，与该配置的其他字段共用。 */
  CONFIG
}
