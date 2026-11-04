import com.fasterxml.jackson.databind.ObjectMapper;
import com.huayun.config.DataSourceContextHolder;
import com.huayun.entity.MergeRequest;
import com.huayun.entity.MergeRequestDetail;
import com.huayun.service.GitLabApiTool;
import com.huayun.service.MergeRequestDetailService;
import com.huayun.service.MergeRequestUrlService;
import com.huayun.service.dao.GitProjectDAO;
import com.huayun.service.impl.JsoupServiceImpl;
import com.huayun.service.impl.MergeRequestDAOImpl;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** 离线调用真实MR解析入口，外部详情、评论及目录读取均使用替身。 */
public class LegacyMrApiTest {
    private TimeZone previousZone;
    private static final String MERGED_AT = "2025-10-18T08:53:48.362+08:00";

    @Before public void prepare() {
        previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        org.springframework.core.env.StandardEnvironment environment = new org.springframework.core.env.StandardEnvironment();
        Map<String, Object> properties = new HashMap<>();
        properties.put("cc.project.id", "9");
        properties.put("dgm.project.id", "79");
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("fixture", properties));
        org.springframework.beans.factory.support.DefaultListableBeanFactory factory =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerSingleton("environment", environment);
        new com.huayun.utils.ApplicationContextUtils().postProcessBeanFactory(factory);
        DataSourceContextHolder.getContext().setDataSourceFlag("default");
    }

    @After public void cleanup() {
        TimeZone.setDefault(previousZone);
        DataSourceContextHolder.clearContext();
    }

    @Test public void knownProjectUsesApiWhenCurrentHtmlHasNoOldLabelClass() throws Exception {
        assertEquals("CC2026R2", parse("merged", MERGED_AT, "项目： CC2026R2").getProjectName());
    }

    @Test public void knownStateUsesApiWhenCurrentHtmlHasNoOldStatusClass() throws Exception {
        assertEquals("MERGED", parse("merged", MERGED_AT, "项目：CC2026R2").getStatus());
    }

    @Test public void knownMergeTimeAndPersonUseSameApiResponse() throws Exception {
        MergeRequest result = parse("merged", MERGED_AT, "项目：CC2026R2");
        assertEquals("2025-10-18 08:53:48", result.getMergedTime());
        assertEquals("田川", result.getMergedUserName());
        assertEquals("装配", result.getModuleName());
    }

    @Test public void utcAndOffsetDescribeSameStoredTime() throws Exception {
        assertEquals(parse("merged", MERGED_AT, "项目：CC2026R2").getMergedTime(),
                parse("merged", "2025-10-18T00:53:48.362Z", "项目：CC2026R2").getMergedTime());
    }

    @Test public void openAndClosedRetainLegacyStateNamesAndEmptyMergeMetadata() throws Exception {
        for (String state : Arrays.asList("opened", "closed")) {
            MergeRequest result = parse(state, null, "项目：CC2026R2");
            assertEquals(state.equals("opened") ? "OPEN" : "CLOSED", result.getStatus());
            assertEquals("", result.getMergedTime());
            assertEquals("", result.getMergedUserName());
        }
    }

    @Test public void missingProjectKeepsDefaultAndFirstProjectLabelWins() throws Exception {
        assertEquals("未标注项目名", parse("merged", MERGED_AT).getProjectName());
        assertEquals("CC2026R2", parse("merged", MERGED_AT,
                "项目：CC2026R2", "项目：CC2026R3").getProjectName());
    }

    @Test public void noScanLabelKeepsExistingExemption() throws Exception {
        MergeRequest result = parse("merged", MERGED_AT, "项目：CC2026R2", "无需走查扫描");
        assertEquals("无需标注", result.getProjectName());
        assertEquals("无需标注", result.getModuleName());
    }

    @Test public void dgmSharesApiMetadataWithoutChangingCredentials() throws Exception {
        DataSourceContextHolder.setContext(DataSourceContextHolder.createDGMContext());
        MergeRequest result = parse("merged", MERGED_AT, "项目：DGM2026R2");
        assertEquals("DGM2026R2", result.getProjectName());
        assertEquals("MERGED", result.getStatus());
    }

    @Test public void existingApiDtoConstructorRemainsAvailable() throws Exception {
        assertNotNull(com.huayun.entity.mergeRequest.MergeRequest.class.getConstructor(
                String.class, String.class, String.class, String.class, List.class, String.class));
    }

    @Test public void scanExplanationUsesApiLabelWithoutChangingExemption() throws Exception {
        assertEquals(com.huayun.entity.IllegalTypeList.BUG_COUNT_RESULT_PASS,
                parse("merged", MERGED_AT, "项目：CC2026R2", "扫描问题已说明").getBugCountResult());
    }

    @Test public void apiEvidenceReachesExistingPersistenceObject() throws Exception {
        com.huayun.entity.SpiderCrowncadData row = new com.huayun.entity.SpiderCrowncadData(
                new com.huayun.entity.bo.ExcelBo(parse("merged", MERGED_AT, "项目：CC2026R2")));
        assertEquals("CC2026R2", row.getProjectName());
        assertEquals("MERGED", row.getStatus());
        assertEquals("2025-10-18 08:53:48", row.getMergedTime());
        assertEquals(java.time.LocalDateTime.of(2025, 10, 18, 8, 53, 48), row.getMergedLocalDateTime());
        assertEquals("田川", row.getMergedUserName());
    }

    private MergeRequest parse(String state, String mergedAt, String... projectLabels) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "35673");
        payload.put("iid", "24509");
        payload.put("project_id", "9");
        payload.put("state", state);
        payload.put("merged_at", mergedAt);
        payload.put("merged_by", Collections.singletonMap("name", "田川"));
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        labels.add("模块：装配");
        labels.addAll(Arrays.asList(projectLabels));
        payload.put("labels", labels);
        ObjectMapper mapper = new ObjectMapper();
        com.huayun.entity.mergeRequest.MergeRequest api = mapper.readValue(
                mapper.writeValueAsString(payload), com.huayun.entity.mergeRequest.MergeRequest.class);
        Element row = Jsoup.parse("<li class='merge-request merged'>"
                + "<time class='merge_request_updated_ago' datetime='2025-10-18T00:53:48Z'></time>"
                + "<span class='issuable-reference'>!24509</span>"
                + "<span class='merge-request-title'>删除配合多余代码</span><span class='author'>作者</span>"
                + "<a class='gl-link gl-label-link'><span class='gl-label-text'>项目：CC2026R2</span></a>"
                + "<span class='gl-badge badge badge-info'>Merged</span></li>").selectFirst("li");
        Method method = MergeRequestDAOImpl.class.getDeclaredMethod(
                "parseMergeRequest", Element.class, String.class, String.class);
        method.setAccessible(true);
        try {
            @SuppressWarnings("unchecked") List<MergeRequest> result = (List<MergeRequest>) method.invoke(
                    new StubDao(api), row, "http://fixture.invalid/project/merge_requests", "9");
            assertEquals(1, result.size());
            return result.get(0);
        } catch (InvocationTargetException error) {
            throw new AssertionError("真实MR解析入口异常", error.getCause());
        }
    }

    private static <T> T stub(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> method.getReturnType() == String.class ? "fixture" :
                        method.getReturnType() == Integer.class ? Integer.valueOf(1) : null));
    }

    private static final class StubApi extends GitLabApiTool {
        private final com.huayun.entity.mergeRequest.MergeRequest response;
        StubApi(com.huayun.entity.mergeRequest.MergeRequest response) { super(null); this.response = response; }
        @Override public com.huayun.entity.mergeRequest.MergeRequest getMergeRequest(String projectId, String iid) {
            return response;
        }
    }

    private static final class StubDao extends MergeRequestDAOImpl {
        StubDao(com.huayun.entity.mergeRequest.MergeRequest api) {
            super(stub(MergeRequestUrlService.class), stub(MergeRequestDetailService.class), null, null,
                    new JsoupServiceImpl(), new StubApi(api), null, stub(GitProjectDAO.class), null, null);
        }
        @Override public List<MergeRequestDetail> parseMergeRequestDetail(String status, String iid,
                String baseUrl, String projectId, List<String> labels) {
            MergeRequestDetail detail = new MergeRequestDetail();
            detail.setCodeWalkthroughDate(new Date(1760748828362L));
            return Collections.singletonList(detail);
        }
        @Override public String parseCodeScanResult(String iid, String baseUrl) { return "已进行代码扫描"; }
        @Override public Map<String, Object> getRobotDiscussion(String projectId, String iid) {
            return Collections.singletonMap("bugCount", 0);
        }
        @Override public String getAnnotationRateResult(String projectId, String iid,
                Map<String, Object> discussion, List<String> labels) { return "通过"; }
        @Override public Double getAnnotationRate(Map<String, Object> discussion, String projectId, String iid) {
            return 20.0;
        }
    }
}
