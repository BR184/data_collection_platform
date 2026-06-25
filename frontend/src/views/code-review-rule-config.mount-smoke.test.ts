import { describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import CodeReviewIllegalRuleConfigView from './CodeReviewIllegalRuleConfigView.vue';
import ruleConfigViewSource from './CodeReviewIllegalRuleConfigView.vue?raw';
import ruleConfigEditorSource from '../components/rule-config/CodeReviewRuleConfigEditor.vue?raw';
import ruleConfigPreviewSource from '../components/rule-config/CodeReviewRuleConfigPreview.vue?raw';

function cssBlock(source: string, selector: string) {
  const start = source.indexOf(`${selector} {`);
  const end = source.indexOf('\n}', start);
  return start >= 0 && end > start ? source.slice(start, end) : '';
}

function jsonResponse(data: unknown) {
  return Promise.resolve({ ok: true, text: () => Promise.resolve(JSON.stringify({ success: true, data })) } as Response);
}

describe('CodeReviewIllegalRuleConfigView mount smoke', () => {
  it('mounts without route errors', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/api/code-review/illegal-records/filter-options')) {
        return jsonResponse({
          requestTypes: [{ label: '合并请求', value: 'merge_request' }],
          projects: [],
          repositoryNames: [],
          illegalTypes: [],
          targetBranches: [],
          mergedBys: [],
          moduleNames: [],
          projectNames: [],
        });
      }
      if (url.includes('/api/code-review/illegal-records/rule-config/preview')) {
        return jsonResponse({
          baseTotal: 10,
          filteredTotal: 10,
          deltaCount: 0,
          retainedRate: 100,
          samples: [],
        });
      }
      return jsonResponse({});
    }));

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/code-review/illegal-records/rule-config', component: CodeReviewIllegalRuleConfigView }],
    });
    await router.push('/code-review/illegal-records/rule-config?projectId=1');
    await router.isReady();
    const wrapper = mount(CodeReviewIllegalRuleConfigView, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();
    expect(wrapper.exists()).toBe(true);
    expect(wrapper.find('.rule-config-main').exists()).toBe(true);
    expect(wrapper.find('.rule-config-context-grid').exists()).toBe(true);
    expect(wrapper.find('.rule-config-sidebar').exists()).toBe(false);
    vi.unstubAllGlobals();
  });

  it('keeps the page fixed while repeated lists own vertical scrolling', () => {
    expect(cssBlock(ruleConfigViewSource, '.rule-config-page')).toContain('overflow: hidden');
    expect(cssBlock(ruleConfigViewSource, '.rule-config-page')).toContain('height: calc(100vh - 98px)');
    expect(cssBlock(ruleConfigViewSource, '.rule-config-layout')).toContain('overflow: hidden');
    expect(cssBlock(ruleConfigEditorSource, '.rule-card-list')).toContain('overflow-y: auto');
    expect(cssBlock(ruleConfigPreviewSource, '.rule-preview-sample-list')).toContain('overflow-y: auto');
    expect(`${ruleConfigViewSource}\n${ruleConfigEditorSource}\n${ruleConfigPreviewSource}`).not.toMatch(/overflow-x:\s*auto/);
  });
});
