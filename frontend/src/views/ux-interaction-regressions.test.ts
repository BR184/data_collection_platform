import { describe, expect, it } from 'vitest';
import collectFormSource from './CollectFormView.vue?raw';
import testingPhaseSource from './TestingPhaseDefinitionView.vue?raw';
import mirrorSettingsSource from './MirrorSettingsView.vue?raw';
import ruleConfigSource from './CodeReviewIllegalRuleConfigView.vue?raw';
import reviewRecordDialogSource from './review-data/ReviewRecordFormDialog.vue?raw';
import reviewProblemDialogSource from './review-data/ReviewProblemItemFormDialog.vue?raw';
import databaseBrowserSource from '../components/DatabaseBrowserView.vue?raw';
import dataScopeBarSource from '../components/data-scope/DataScopeBar.vue?raw';

describe('UX interaction regressions', () => {
  it('keeps edit forms submittable with Enter', () => {
    expect(reviewRecordDialogSource).toContain('@submit.prevent="handleSubmit"');
    expect(reviewProblemDialogSource).toContain('@submit.prevent="handleSubmit"');
    expect(testingPhaseSource).toContain('@submit.prevent="savePhase"');
    expect(databaseBrowserSource).toContain('@submit.prevent="saveCollectFormEdit"');
    expect(collectFormSource).toContain('@submit.prevent="saveForm"');
  });

  it('focuses the first invalid field instead of only showing a toast', () => {
    expect(reviewRecordDialogSource).toContain('focusFirstInvalidFormField(formRef.value)');
    expect(reviewProblemDialogSource).toContain('focusFirstInvalidFormField(formRef.value)');
    expect(testingPhaseSource).toContain('focusFirstInvalidFormField(instance)');
  });

  it('guards destructive and unsaved-change flows', () => {
    expect(collectFormSource).toContain('ElMessageBox.confirm');
    expect(collectFormSource).toContain('作废表单记录');
    expect(ruleConfigSource).toContain('beforeunload');
    expect(ruleConfigSource).toContain('handleBeforeUnload');
  });

  it('shows loading feedback while testing the mirror connection', () => {
    expect(mirrorSettingsSource).toContain('testing');
    expect(mirrorSettingsSource).toContain(':loading="testing"');
  });

  it('keeps mirror source creation separate from selecting an existing source', () => {
    expect(mirrorSettingsSource).toContain('isCreatingNewConfig');
    expect(mirrorSettingsSource).toContain('新增数据源（未保存）');
    expect(mirrorSettingsSource).toContain(':disabled="isCreatingNewConfig"');
    expect(mirrorSettingsSource).toContain('cancelNewConfig');
    expect(mirrorSettingsSource).toContain('savedConfigActionDisabled');
    expect(mirrorSettingsSource).toContain('refreshCurrentStatus');
  });

  it('keeps source table browsing read-only and clearly labeled', () => {
    expect(databaseBrowserSource).toContain("case 'SOURCE'");
    expect(databaseBrowserSource).toContain('来源表为实时只读预览，不支持在此刷新');
    expect(databaseBrowserSource).toContain(':disabled="!currentTableRefreshable"');
    expect(databaseBrowserSource).toContain('来源表为管理员只读预览，本地表无需刷新');
  });

  it('uses Element Plus radio button value instead of label-as-value', () => {
    expect(dataScopeBarSource).toContain(':value="option.value"');
    expect(dataScopeBarSource).not.toContain(':label="option.value"');
  });
});
