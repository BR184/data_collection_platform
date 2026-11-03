package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.IssueScopeDimension;
import com.data.collection.platform.service.PageRecordSnapshotService;
import java.util.stream.Collectors;

/** 组合范围来源版本：保留每个原成员的事实版本，并附加分组与目录指纹。 */
final class BiScopeSourceVersions {
  private BiScopeSourceVersions() {}

  static String systemTest(PageRecordSnapshotService snapshotService, BiProductVersionScope scope) {
    String memberVersions = scope.memberBusinessKeys().stream()
        .sorted()
        .map(memberKey -> snapshotService.issueFactSourceVersion(
            GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
            scope.projectId(),
            IssueScopeDimension.TESTING_PHASE,
            memberKey))
        .collect(Collectors.joining("|"));
    return memberVersions + "|scope:" + scope.fingerprint();
  }
}
