package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.TagGroupAdminResponse;
import com.data.collection.platform.entity.TagGroupsResponse;
import com.data.collection.platform.security.RequireRole;
import com.data.collection.platform.service.TagGroupService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TagGroupController {
  private final TagGroupService tagGroupService;

  public TagGroupController(TagGroupService tagGroupService) {
    this.tagGroupService = tagGroupService;
  }

  @GetMapping("/api/tag-groups")
  public ApiResponse<TagGroupsResponse> getTagGroups(
      @RequestParam(defaultValue = "issue") String domain) {
    return ApiResponse.success(tagGroupService.getTagGroups(domain));
  }

  @PostMapping("/api/admin/reload-tag-mappings")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Void> reloadTagMappings() {
    tagGroupService.reload();
    return ApiResponse.success("标签组映射已重新加载", null);
  }

  @GetMapping("/api/admin/tag-groups/all")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<List<TagGroupAdminResponse>> getAllTagGroups() {
    return ApiResponse.success(tagGroupService.getAllTagGroups());
  }
}
