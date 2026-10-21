package com.data.collection.platform.service.read;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.entity.read.ReadModelRules;
import com.data.collection.platform.entity.read.ReadView;
import com.data.collection.platform.entity.read.ReadViewMetadata;
import com.data.collection.platform.security.AuthSessionSupport;
import com.data.collection.platform.service.BusinessDateProvider;
import com.data.collection.platform.service.PlatformPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** HTTP 页面身份的唯一解析器：当前权限、认证会话、页面/范围与已发布来源闭包一起核验。 */
@Service
public class ReadViewApplicationService {
  private static final int MAX_PREPARATION_ATTEMPTS = 3;
  private final ReadPageCatalog pages;
  private final PublishedReadService reads;
  private final FactReadSelectionService selections;
  private final PlatformPermissionService permissions;
  private final BusinessDateProvider dates;
  private final JsonUtils json;
  private final ReadViewFreshnessService freshness;
  private final ReadViewPreparationService preparation;

  public ReadViewApplicationService(ReadPageCatalog pages, PublishedReadService reads, FactReadSelectionService selections,
      PlatformPermissionService permissions, BusinessDateProvider dates, JsonUtils json, ReadViewFreshnessService freshness,
      ReadViewPreparationService preparation) {
    this.pages = pages;
    this.reads = reads;
    this.selections = selections;
    this.permissions = permissions;
    this.dates = dates;
    this.json = json;
    this.freshness = freshness;
    this.preparation = preparation;
  }

  /** 建立当前页面完整视图；重复客户端准备身份幂等，公开请求不能传 publication/candidate ID。 */
  public ReadView acquire(HttpServletRequest request, String pageKey, Map<String, String> scope, UUID clientViewId) {
    AuthUserResponse user = requirePage(request, pageKey);
    Map<String, String> normalized = pages.normalizeScope(scope);
    String session = sessionIdentity(request);
    String scopeKey = scopeIdentity(normalized);
    for (int attempt = 1; ; attempt++) {
      try {
        return preparation.prepare(user.username(), session, pageKey, scopeKey, normalized, clientViewId);
      } catch (ReadViewException error) {
        // 准备事务已经回滚，重新取得完整提交视图；不能重试无完整产出或既有视图过期。
        if (attempt >= MAX_PREPARATION_ATTEMPTS || error.code() != ReadViewException.Code.READ_VIEW_REBIND_REQUIRED
            || error.viewId() != null) { throw error; }
      }
    }
  }

  /** 续约前重新验证当前页面权限与会话；身份本身不能授予旧权限。 */
  public ReadView renew(HttpServletRequest request, UUID id) {
    AuthUserResponse user = authenticated(request);
    String session = sessionIdentity(request);
    ReadView view = reads.loadOwnedView(id, user.username(), session);
    permissions.requirePermission(user, pages.viewPermission(view.pageKey()));
    requireRules(view);
    return reads.renewView(id, user.username(), session);
  }

  /** 下钻复制已冻结来源；核验父页及目标页当前权限，目标闭包必须已包含在父完整视图中。 */
  public ReadView fork(HttpServletRequest request, UUID parentId, String targetPage, Map<String, String> scope, UUID clientViewId) {
    AuthUserResponse user = authenticated(request);
    String session = sessionIdentity(request);
    ReadView parent = reads.loadOwnedView(parentId, user.username(), session);
    permissions.requirePermission(user, pages.viewPermission(parent.pageKey()));
    requireRules(parent);
    String page = targetPage == null || targetPage.isBlank() ? parent.pageKey() : targetPage;
    permissions.requirePermission(user, pages.viewPermission(page));
    Map<String, String> normalized = pages.normalizeScope(scope);
    if (!page.equals(parent.pageKey()) && !parent.domains().keySet().containsAll(
        reads.withinPublishedRead(parent,fixed -> pages.domainsForView(page,normalized,fixed)))) {
      throw ReadViewException.unavailable("目标页面的完整来源未包含在当前看板中，请先更新完整画面");
    }
    return reads.forkView(parentId, user.username(), session, page, scopeIdentity(normalized), clientViewId);
  }

  /** 关闭/替换页面释放本人会话租约；权限已撤销也可以释放，不能释放其它账号或会话。 */
  public void release(HttpServletRequest request, UUID id) {
    AuthUserResponse user = authenticated(request);
    reads.releaseView(id, user.username(), sessionIdentity(request));
  }

  /** 将本次 HTTP 读取明确绑定到页面/范围；未带身份的独立主读取也走同一完整版本语义。 */
  public ReadView resolve(HttpServletRequest request, String pageKey, Map<String, String> scope) {
    AuthUserResponse user = requirePage(request, pageKey);
    String value = request.getHeader("X-Read-Context-Id");
    if (value == null || value.isBlank()) { value = request.getParameter("readContextId"); }
    if (value == null || value.isBlank()) { return acquire(request, pageKey, scope, UUID.randomUUID()); }
    ReadView view = reads.loadOwnedView(UUID.fromString(value), user.username(), sessionIdentity(request));
    if (!view.pageKey().equals(pageKey) || !view.scopeKey().equals(scopeIdentity(pages.normalizeScope(scope)))) {
      throw new AccessDeniedException("读取身份不属于当前页面或业务范围");
    }
    requireRules(view);
    return view;
  }

  /** 普通事实读取在固定完整视图事务中执行，实际读请求续约，返回未缓存的请求披露。 */
  public <T> ReadOutcome<T> readFacts(HttpServletRequest request, String pageKey, Map<String, String> parameters,
      java.util.function.Function<com.data.collection.platform.entity.read.FactReadSelection, T> reader) {
    Map<String, String> scope = pages.scopeFromParameters(parameters);
    ReadView resolved = resolve(request, pageKey, scope);
    ReadView renewed = reads.renewView(resolved.viewId(), authenticated(request).username(), sessionIdentity(request));
    T data = reads.withinPublishedRead(renewed, actual -> reader.apply(selections.fromView(actual)));
    return new ReadOutcome<>(data, metadata(request, renewed));
  }

  /** 跨事实/数据集页面共用完整闭包与租约；调用者显式接收强类型组合，不查当前可变来源。 */
  public <T> ReadOutcome<T> read(HttpServletRequest request, String pageKey, Map<String, String> parameters,
      java.util.function.Function<com.data.collection.platform.entity.read.ReadSelection, T> reader) {
    ReadView resolved = resolve(request, pageKey, pages.scopeFromParameters(parameters));
    ReadView renewed = reads.renewView(resolved.viewId(), authenticated(request).username(), sessionIdentity(request));
    T data = reads.withinPublishedRead(renewed, actual -> reader.apply(freshness.selection(actual)));
    return new ReadOutcome<>(data, metadata(request, renewed));
  }

  /** 传输身份参数不能混入业务筛选或共享缓存键。 */
  public Map<String, String> businessParameters(Map<String, String> parameters) {
    Map<String, String> result = new java.util.LinkedHashMap<>(parameters == null ? Map.of() : parameters);
    result.remove("readContextId");
    return java.util.Collections.unmodifiableMap(result);
  }

  /** 读取 Servlet 查询参数的业务单值表示，身份参数随后由统一传输边界剔除。 */
  public Map<String, String> parameters(HttpServletRequest request) {
    Map<String, String> result = new java.util.LinkedHashMap<>();
    request.getParameterMap().forEach((key, values) -> {
      if (values.length > 0) { result.put(key, values[0]); }
    });
    return java.util.Collections.unmodifiableMap(result);
  }

  /** 已物化业务数据与本次请求的实时元数据，二者在缓存与传输层保持明确边界。 */
  public record ReadOutcome<T>(T data, ReadViewMetadata metadata) {
    /** 返回平台既有 envelope，并只在传输时注入读取披露。 */
    public com.data.collection.platform.common.response.ApiResponse<T> response() {
      return com.data.collection.platform.common.response.ApiResponse.success(data).withReadView(metadata);
    }

    /** 下载沿用原 MIME/文件名/字节，仅在传输头声明已经核验的页面身份。 */
    public <R> org.springframework.http.ResponseEntity<R> download(org.springframework.http.ResponseEntity<R> response) {
      org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
      headers.putAll(response.getHeaders());
      headers.set("X-Read-Context-Id", metadata.readContextId().toString());
      return new org.springframework.http.ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }
  }

  /** 从当前权限与实时生产状态组装披露，不能把管理员细节放入共享业务缓存。 */
  public ReadViewMetadata metadata(HttpServletRequest request, ReadView view) {
    return freshness.metadata(view, authenticated(request));
  }

  private AuthUserResponse requirePage(HttpServletRequest request, String pageKey) {
    AuthUserResponse user = authenticated(request);
    permissions.requirePermission(user, pages.viewPermission(pageKey));
    return user;
  }

  private static AuthUserResponse authenticated(HttpServletRequest request) {
    AuthUserResponse user = AuthSessionSupport.currentUser(request);
    if (!user.authenticated()) {
      throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("请先登录");
    }
    return user;
  }

  private static void requireRules(ReadView view) {
    if (!ReadModelRules.VERSION.equals(view.ruleVersion())) {
      throw ReadViewException.rebindRequired(view.viewId(), "页面规则已更新，请重新准备读取身份");
    }
  }

  private String scopeIdentity(Map<String, String> scope) { return hash(json.toJson(scope)); }

  private static String sessionIdentity(HttpServletRequest request) {
    // 存储会话摘要，避免把真实认证 Cookie 凭据带入可查询的读取元数据。
    return hash(request.getSession(true).getId());
  }

  private static String hash(String value) {
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 不可用", error);
    }
  }
}
