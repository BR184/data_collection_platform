package com.data.collection.platform.service.form;

import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.FormRecordView;
import com.data.collection.platform.security.PlatformPermissionCodes;
import org.springframework.stereotype.Component;

/** 把权威行转换为页面视图，并按本人归属与当前权限计算可操作能力。 */
@Component
public class FormRecordViewFactory {

  public FormRecordView toView(
      FormRecordRow row,
      FormActorContext actor,
      boolean writable,
      String createdByName) {
    boolean own =
        row.createdBy() != null
            && actor != null
            && row.createdBy().equals(actor.userId());
    return new FormRecordView(
        Long.toString(row.id()),
        row.recordVersion(),
        row.deleted(),
        row.legacyIncomplete(),
        row.legacyMissingFields(),
        row.fields(),
        row.fieldNames(),
        row.snapshotValues(),
        row.createdBy(),
        createdByName,
        row.templateVersion(),
        Long.toString(row.originVersionId()),
        row.createdAt(),
        row.updatedAt(),
        writable
            && !row.deleted()
            && own
            && actor.hasPermission(PlatformPermissionCodes.FORM_RECORD_EDIT_OWN),
        writable
            && !row.deleted()
            && own
            && actor.hasPermission(PlatformPermissionCodes.FORM_RECORD_DELETE_OWN));
  }
}
