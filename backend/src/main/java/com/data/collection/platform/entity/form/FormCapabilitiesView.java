package com.data.collection.platform.entity.form;

/** 当前账号在本范围可执行的操作。 */
public record FormCapabilitiesView(boolean canCreate, boolean canEditOwn, boolean canVoidOwn) {}
