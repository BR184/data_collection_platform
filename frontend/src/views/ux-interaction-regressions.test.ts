import { describe, expect, it } from 'vitest';
import collectFormSource from './CollectFormView.vue?raw';
import testingPhaseSource from './TestingPhaseDefinitionView.vue?raw';
import mirrorSettingsSource from './MirrorSettingsView.vue?raw';
import ruleConfigSource from './CodeReviewIllegalRuleConfigView.vue?raw';
import reviewRecordDialogSource from './review-data/ReviewRecordFormDialog.vue?raw';
import reviewProblemDialogSource from './review-data/ReviewProblemItemFormDialog.vue?raw';
import databaseBrowserSource from '../components/DatabaseBrowserView.vue?raw';

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
});
