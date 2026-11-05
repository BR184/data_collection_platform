package com.data.collection.platform.service.form;

import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.security.AuthSessionSupport;
import com.data.collection.platform.service.PlatformPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 将登录会话转换为表单操作上下文；权限集合由角色实时解析。 */
@Component
public class FormActorResolver {

  private final PlatformPermissionService permissionService;

  public FormActorResolver(PlatformPermissionService permissionService) {
    this.permissionService = permissionService;
  }

  public FormActorContext resolve(HttpServletRequest request) {
    AuthUserResponse user = AuthSessionSupport.currentUser(request);
    Set<String> permissions = permissionService.permissionsForRoles(user.roleCodes());
    return new FormActorContext(user.username(), user.displayName(), permissions);
  }
}
