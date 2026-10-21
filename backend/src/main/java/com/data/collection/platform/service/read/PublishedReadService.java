package com.data.collection.platform.service.read;

import com.data.collection.platform.config.ReadFallbackProperties;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.PublishedReadContext;
import com.data.collection.platform.entity.read.PublishedSourceContext;
import com.data.collection.platform.entity.read.PublishedDatasetReadContext;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadPublicationDomain;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationState;
import com.data.collection.platform.entity.read.ReadView;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 公开读取：建立、续约、释放页面读取视图，并在读取事务内锁定版本。
 *
 * <p>公开请求只能读取 PUBLISHED（含仍在服务中的 RETIRING）版本，绝不接受前端提交的候选标识。
 * 请求在短 REPEATABLE READ 事务内物化输入，并对涉及的全部已发布记录按 {@code publication_id} 顺序取共享锁，
 * 使回收不能在读取期间删除其修订。该事务必须允许加锁，因此不挂在只读事务上，且不加入外层事务。
 */
@Service
public class PublishedReadService {
  private final ReadPublicationRepository publicationRepository;
  private final ReadViewRepository viewRepository;
  private final ReadFallbackProperties properties;
  private final TransactionTemplate readTransaction;

  public PublishedReadService(
      ReadPublicationRepository publicationRepository,
      ReadViewRepository viewRepository,
      ReadFallbackProperties properties,
      PlatformTransactionManager transactionManager) {
    this.publicationRepository = publicationRepository;
    this.viewRepository = viewRepository;
    this.properties = properties;
    this.readTransaction = new TransactionTemplate(transactionManager);
    this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    // FOR KEY SHARE 不能在只读事务中执行，因此读取包装器保持可写语义，业务逻辑仍只查询。
    this.readTransaction.setReadOnly(false);
  }

  /**
   * 冻结各所需域的当前已发布身份，建立页面读取视图。
   *
   * @param request 建立请求
   * @return 已冻结的读取视图
   * @throws ReadViewException 任一域尚无完整产出时抛出 {@code READ_VIEW_UNAVAILABLE}
   */
  @Transactional
  public ReadView acquireView(AcquireReadViewRequest request) {
    viewRepository.lockClientPreparation(request.subject(), request.sessionId(), request.clientViewId());
    ReadViewRepository.ReadViewRow existing = viewRepository.findClientView(request.subject(), request.sessionId(), request.clientViewId()).orElse(null);
    if (existing != null) {
      if (!existing.pageKey().equals(request.pageKey()) || !existing.scopeKey().equals(request.scopeKey())
          || !java.util.Objects.equals(existing.businessDate(), request.businessDate())
          || !java.util.Objects.equals(existing.ruleVersion(), request.ruleVersion())) {
        throw new IllegalArgumentException("相同页面准备身份不能修改页面、范围、业务日或规则");
      }
      return loadOwnedView(existing.viewId(), request.subject(), request.sessionId());
    }
    Map<ReadPublicationDomainKey, PublishedSourceContext> contexts = new LinkedHashMap<>();
    Map<ReadPublicationDomainKey, UUID> domainIds = new LinkedHashMap<>();
    List<ChosenPublication> chosen = new java.util.ArrayList<>();
    for (ReadPublicationDomainKey key : request.domains()) {
      UUID domainId = publicationRepository.findDomain(key).map(ReadPublicationDomain::id).orElse(null);
      ReadPublication current = domainId == null ? null : publicationRepository.findCurrentPublication(domainId).orElse(null);
      if (current == null || current.state() != ReadPublicationState.PUBLISHED) {
        throw ReadViewException.unavailable("该页面尚无完整产出：" + key);
      }
      chosen.add(new ChosenPublication(key, domainId, current.id()));
    }
    // 获取/续约/回收统一按 PostgreSQL UUID 的字节顺序（标准 UUID 字符串顺序）锁定，避免多源反向锁。
    chosen.sort(java.util.Comparator.comparing(value -> value.publicationId().toString()));
    for (ChosenPublication choice : chosen) {
      ReadPublication locked = publicationRepository.lockPublicationForShare(choice.publicationId()).orElse(null);
      if (locked == null || locked.state() != ReadPublicationState.PUBLISHED) {
        throw ReadViewException.rebindRequired(null, "完整版本已被回收，请重新获取当前视图");
      }
      // 指针后来前移不会推翻这次已经选中且持锁的完整输入；它仍是可读版本，不能把发布竞态报成无产出。
      contexts.put(choice.key(), contextOf(choice.key(), locked.epochId(), locked.sequenceNo(), locked.id(), locked.dataAsOf()));
      domainIds.put(choice.key(), choice.domainId());
    }
    UUID viewId = UUID.randomUUID();
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    Instant expiresAt = now.plusSeconds(properties.getPageLeaseSeconds());
    Instant rebindDeadline = now.plusSeconds(properties.getHistoricalViewMaxAgeMinutes() * 60L);
    viewRepository.insertViewContext(
        viewId,
        request.subject(),
        request.sessionId(),
        request.pageKey(),
        request.scopeKey(),
        "PUBLISHED",
        request.businessDate(),
        request.ruleVersion(),
        expiresAt,
        rebindDeadline,
        request.clientViewId());
    for (Map.Entry<ReadPublicationDomainKey, PublishedSourceContext> entry : contexts.entrySet()) {
      viewRepository.insertViewPublication(
          viewId, domainIds.get(entry.getKey()), entry.getValue().publicationId());
    }
    return new ReadView(
        viewId,
        request.subject(),
        request.pageKey(),
        request.scopeKey(),
        request.businessDate(),
        request.ruleVersion(),
        expiresAt,
        rebindDeadline,
        contexts);
  }

  /**
   * 续约本人/本会话的读取视图。
   *
   * @param viewId 读取视图标识
   * @param subject 会话主体
   * @param sessionId 当前认证会话的服务端身份摘要
   * @return 续约后的视图
   * @throws ReadViewException 视图已过期、已回收，或已出现新版本且达到安全换版时限时抛出
   *     {@code READ_VIEW_REBIND_REQUIRED}
   */
  @Transactional
  public ReadView renewView(UUID viewId, String subject, String sessionId) {
    // 与回收在同一发布记录上串行化；先锁版本再锁视图，不能只凭先前读到的 PUBLISHED 状态续约。
    List<ReadViewRepository.ReadViewBinding> bindings = viewRepository.findBindings(viewId);
    for (ReadViewRepository.ReadViewBinding binding : bindings) {
      ReadPublication locked = publicationRepository.lockPublicationForShare(binding.publicationId())
          .orElseThrow(() -> ReadViewException.rebindRequired(viewId, "读取版本已被回收，请重新绑定视图"));
      if (locked.state() != ReadPublicationState.PUBLISHED) {
        throw ReadViewException.rebindRequired(viewId, "读取版本正在回收，请重新绑定视图");
      }
    }
    ReadViewRepository.ReadViewRow row = lockView(viewId, subject, sessionId);
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (now.isAfter(row.expiresAt())) {
      throw ReadViewException.rebindRequired(viewId, "读取视图租约已过期，请重新绑定视图");
    }
    boolean superseded = false;
    for (ReadViewRepository.ReadViewBinding binding : bindings) {
      if (binding.state() == ReadPublicationState.RETIRING) {
        throw ReadViewException.rebindRequired(viewId, "读取视图引用的版本正在回收，请重新绑定视图");
      }
      if (binding.state() != ReadPublicationState.PUBLISHED) {
        throw ReadViewException.rebindRequired(viewId, "读取视图引用的版本已不可读，请重新绑定视图");
      }
      ReadPublication current =
          publicationRepository.findCurrentPublication(binding.domainId()).orElse(null);
      if (current != null && !current.id().equals(binding.publicationId())) {
        superseded = true;
      }
    }
    if (superseded && row.rebindDeadline() != null && !now.isBefore(row.rebindDeadline())) {
      throw ReadViewException.rebindRequired(viewId, "已出现新版本且达到安全换版时限，请重新绑定视图");
    }
    // 续约按可见页面心跳间隔节流：读取不再每次都更新视图行，避免与并发读取争抢同一行的 KEY SHARE 锁。
    Instant expiresAt = row.expiresAt();
    if (!now.plusSeconds(properties.getHeartbeatSeconds()).isBefore(row.expiresAt())) {
      expiresAt = now.plusSeconds(properties.getPageLeaseSeconds());
      viewRepository.extendLease(viewId, expiresAt, row.rebindDeadline());
    }
    return buildView(row, bindings, expiresAt);
  }

  /** 释放页面租约；丢包由租约自然过期收敛。 */
  @Transactional
  public void releaseView(UUID viewId, String subject, String sessionId) {
    ReadViewRepository.ReadViewRow row =
        viewRepository.findView(viewId).orElse(null);
    if (row == null) {
      return;
    }
    requireOwnership(row, subject, sessionId);
    viewRepository.deleteView(viewId);
  }

  /** 加载本人当前会话的读取视图；另一个会话即使账号相同也不能复用该身份。 */
  public ReadView loadOwnedView(UUID viewId, String subject, String sessionId) {
    ReadViewRepository.ReadViewRow row =
        viewRepository.findView(viewId).orElseThrow(
            () -> ReadViewException.rebindRequired(viewId, "读取视图不存在或已释放"));
    requireOwnership(row, subject, sessionId);
    if (Instant.now().isAfter(row.expiresAt())) {
      throw ReadViewException.rebindRequired(viewId, "读取视图租约已过期，请重新绑定视图");
    }
    return buildView(row, viewRepository.findBindings(viewId), row.expiresAt());
  }

  /** 取指定域的已发布读上下文。 */
  public PublishedReadContext contextFor(ReadView view, ReadPublicationDomainKey key) {
    if (view.requireDomain(key) instanceof PublishedReadContext fact) { return fact; }
    throw new IllegalArgumentException("该域不是事实域");
  }

  /**
   * 在短 REPEATABLE READ 事务内锁定视图与其全部已发布记录后执行读取。
   *
   * <p>读取期间回收无法删除这些版本；事务结束即释放锁并归还连接，不跨 HTTP 或等待用户操作。
   * 同页并发读取与续约会更新同一视图行，PostgreSQL 对 RR 事务取 {@code FOR KEY SHARE} 报 40001；
   * 此时按 {@link ReadSerializationRetry} 重建独立读事务，而不是让页面读取失败。
   *
   * @param view 已冻结的读取视图
   * @param reader 读取逻辑，必须是纯读取，重试会再次执行
   * @return 读取结果
   */
  public <T> T withinPublishedRead(ReadView view, Function<ReadView, T> reader) {
    return ReadSerializationRetry.execute(readTransaction, () -> reader.apply(lockPublishedView(view)));
  }

  private ReadView lockPublishedView(ReadView view) {
    List<ReadViewRepository.ReadViewBinding> bindings = viewRepository.findBindings(view.viewId());
    for (ReadViewRepository.ReadViewBinding binding : bindings) {
      ReadPublication locked =
          publicationRepository.lockPublicationForShare(binding.publicationId()).orElseThrow(
        () ->
            ReadViewException.rebindRequired(
                view.viewId(), "读取视图引用的版本已被回收，请重新绑定视图"));
      if (!locked.publiclyReadable()) {
        throw ReadViewException.rebindRequired(
            view.viewId(), "读取视图引用的版本已不可读，请重新绑定视图");
      }
    }
    // 统一为发布记录（按 ID 排序）→ 视图行，与续约/回收同序；不能用调用者自行构造的域绑定查询。
    ReadViewRepository.ReadViewRow row = viewRepository.lockViewForShare(view.viewId()).orElseThrow(
        () -> ReadViewException.rebindRequired(view.viewId(), "读取视图已释放，请重新绑定视图"));
    if (!row.subject().equals(view.subject()) || !row.pageKey().equals(view.pageKey())
        || !row.scopeKey().equals(view.scopeKey())) {
      throw new IllegalArgumentException("读取视图的主体、页面或范围与已冻结身份不一致");
    }
    if (Instant.now().isAfter(row.expiresAt())) {
      throw ReadViewException.rebindRequired(view.viewId(), "读取视图租约已过期，请重新绑定视图");
    }
    return buildView(row, bindings, row.expiresAt());
  }

  /**
   * 下钻建立指定目标页的独立租约并保留父视图全部完整发布点、业务日和最迟换版时限。
   * 不接受任意发布 ID；父租约释放后子租约仍保护原版本，不能延长历史版本的最大保留期。
   */
  public ReadView forkView(UUID parentId, String subject, String sessionId, String pageKey, String scopeKey, UUID clientViewId) {
    // 父视图行同样以 FOR KEY SHARE 锁定，并发续约/回收更新时按同一策略重建独立事务。
    return ReadSerializationRetry.execute(readTransaction, () -> {
      viewRepository.lockClientPreparation(subject, sessionId, clientViewId);
      ReadView parent = lockPublishedView(loadOwnedView(parentId, subject, sessionId));
      Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      if (parent.rebindDeadline() != null && !now.isBefore(parent.rebindDeadline())) {
        throw ReadViewException.rebindRequired(parentId, "该完整视图已达到换版期限，请先更新看板");
      }
      var existing = viewRepository.findClientView(subject, sessionId, clientViewId).orElse(null);
      if (existing != null) {
        ReadView replay = loadOwnedView(existing.viewId(), subject, sessionId);
        if (!replay.pageKey().equals(pageKey) || !replay.scopeKey().equals(scopeKey)
            || !java.util.Objects.equals(replay.businessDate(), parent.businessDate())
            || !java.util.Objects.equals(replay.ruleVersion(), parent.ruleVersion())
            || !replay.domains().equals(parent.domains())) {
          throw new IllegalArgumentException("相同下钻准备身份不能更换父版本或范围");
        }
        return replay;
      }
      UUID childId = UUID.randomUUID();
      Instant expires = now.plusSeconds(properties.getPageLeaseSeconds());
      if (parent.rebindDeadline() != null && expires.isAfter(parent.rebindDeadline())) { expires = parent.rebindDeadline(); }
      viewRepository.insertViewContext(childId, subject, sessionId, pageKey, scopeKey, "PUBLISHED",
          parent.businessDate(), parent.ruleVersion(), expires, parent.rebindDeadline(), clientViewId);
      for (var binding : viewRepository.findBindings(parent.viewId())) {
        viewRepository.insertViewPublication(childId, binding.domainId(), binding.publicationId());
      }
      return new ReadView(childId, subject, pageKey, scopeKey, parent.businessDate(), parent.ruleVersion(),
          expires, parent.rebindDeadline(), parent.domains());
    });
  }

  private ReadViewRepository.ReadViewRow lockView(UUID viewId, String subject, String sessionId) {
    ReadViewRepository.ReadViewRow row =
        viewRepository.lockViewForUpdate(viewId).orElseThrow(
            () -> ReadViewException.rebindRequired(viewId, "读取视图不存在或已释放"));
    requireOwnership(row, subject, sessionId);
    return row;
  }

  private static void requireOwnership(ReadViewRepository.ReadViewRow row, String subject, String sessionId) {
    if (!row.subject().equals(subject) || !java.util.Objects.equals(row.sessionId(), sessionId)) {
      throw new org.springframework.security.access.AccessDeniedException("读取视图不属于当前账号或会话");
    }
  }

  private ReadView buildView(
      ReadViewRepository.ReadViewRow row,
      List<ReadViewRepository.ReadViewBinding> bindings,
      Instant expiresAt) {
    Map<ReadPublicationDomainKey, PublishedSourceContext> contexts = new LinkedHashMap<>();
    for (ReadViewRepository.ReadViewBinding binding : bindings) {
      contexts.put(
          binding.domainKey(),
          contextOf(binding.domainKey(), binding.epochId(), binding.sequence(), binding.publicationId(), binding.dataAsOf()));
    }
    return new ReadView(
        row.viewId(),
        row.subject(),
        row.pageKey(),
        row.scopeKey(),
        row.businessDate(),
        row.ruleVersion(),
        expiresAt,
        row.rebindDeadline(),
        contexts);
  }

  private record ChosenPublication(ReadPublicationDomainKey key, UUID domainId, UUID publicationId) {}

  private static PublishedSourceContext contextOf(ReadPublicationDomainKey key, UUID epoch, long sequence,
      UUID publicationId, Instant dataAsOf) {
    return key.factType().equals("DATASET")
        ? new PublishedDatasetReadContext(key.sourceInstance(), key.tableKey(), epoch, sequence, publicationId, dataAsOf)
        : new PublishedReadContext(key.sourceInstance(), PublicationFactTable.ofFactType(key.factType()),
            epoch, sequence, publicationId, dataAsOf);
  }
}
