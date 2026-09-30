import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import ReviewProblemItemFormDialog from './ReviewProblemItemFormDialog.vue';
import ReviewRecordFormDialog from './ReviewRecordFormDialog.vue';
import {
  createEmptyProblemItemForm,
  createEmptyReviewRecordForm,
} from '../review-data-management';
import { createEmptyReviewDataFilterOptions } from './useReviewDataRecords';

function formStubs(validate: () => Promise<boolean>) {
  const DialogStub = defineComponent({
    name: 'ElDialog',
    props: { modelValue: { type: Boolean, default: false } },
    setup(props, { slots }) {
      return () => props.modelValue
        ? h('section', [slots.default?.(), slots.footer?.()])
        : null;
    },
  });
  const FormStub = defineComponent({
    name: 'ElForm',
    inheritAttrs: false,
    setup(_, { attrs, expose, slots }) {
      expose({ validate });
      return () => h('form', attrs, slots.default?.());
    },
  });
  const ButtonStub = defineComponent({
    name: 'ElButton',
    inheritAttrs: false,
    props: { disabled: Boolean, loading: Boolean },
    setup(props, { attrs, slots }) {
      return () => h('button', {
        ...attrs,
        disabled: props.disabled || props.loading,
      }, slots.default?.());
    },
  });
  const SlotStub = defineComponent({
    setup(_, { slots }) {
      return () => h('div', slots.default?.());
    },
  });
  return {
    ElDialog: DialogStub,
    ElForm: FormStub,
    ElFormItem: SlotStub,
    ElButton: ButtonStub,
    ElAlert: SlotStub,
    ElInput: true,
    ElInputNumber: true,
    ElDatePicker: true,
    SmartSelect: true,
  };
}

function recordModel() {
  return {
    ...createEmptyReviewRecordForm(),
    projectName: 'project',
    title: 'review',
    moduleName: 'module',
    reviewType: 'design',
    reviewDate: '2026-09-24',
    reviewOwner: 'owner',
    reviewExperts: ['expert'],
    reviewProduct: 'document',
    authorName: 'author',
    reviewVersion: 'v1',
  };
}

function problemModel() {
  return {
    ...createEmptyProblemItemForm(),
    reviewerName: 'expert',
    reviewCategory: 'design',
    documentPosition: '1.1',
    problemCategory: 'logic',
    problemDescription: 'problem',
    ownerName: 'owner',
    problemStatus: 'open',
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe('review form dialog session binding', () => {
  it('submits the record payload with the session that passed form validation', async () => {
    const validate = vi.fn<() => Promise<boolean>>().mockResolvedValue(true);
    const wrapper = mount(ReviewRecordFormDialog, {
      props: {
        visible: true,
        saving: false,
        ready: true,
        sessionId: 11,
        modelValue: recordModel(),
        filterOptions: createEmptyReviewDataFilterOptions(),
        editMode: false,
      },
      global: { stubs: formStubs(validate) },
    });

    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(validate).toHaveBeenCalledTimes(1);
    expect(wrapper.emitted('submit')?.[0]).toEqual([
      expect.objectContaining({
        sessionId: 11,
        payload: expect.objectContaining({
          projectName: 'project',
          title: 'review',
          reviewExperts: ['expert'],
        }),
      }),
    ]);
    wrapper.unmount();
  });

  it('does not submit a record form after its session changes during validation', async () => {
    const pendingValidation = deferred<boolean>();
    const validate = vi.fn(() => pendingValidation.promise);
    const wrapper = mount(ReviewRecordFormDialog, {
      props: {
        visible: true,
        saving: false,
        ready: true,
        sessionId: 11,
        modelValue: recordModel(),
        filterOptions: createEmptyReviewDataFilterOptions(),
        editMode: false,
      },
      global: { stubs: formStubs(validate) },
    });

    await wrapper.find('form').trigger('submit');
    await wrapper.setProps({ sessionId: 12 });
    pendingValidation.resolve(true);
    await flushPromises();

    expect(wrapper.emitted('submit')).toBeUndefined();
    wrapper.unmount();
  });

  it('submits the problem payload with the session that passed form validation', async () => {
    const validate = vi.fn<() => Promise<boolean>>().mockResolvedValue(true);
    const wrapper = mount(ReviewProblemItemFormDialog, {
      props: {
        visible: true,
        saving: false,
        ready: true,
        sessionId: 21,
        modelValue: problemModel(),
        filterOptions: createEmptyReviewDataFilterOptions(),
        editMode: true,
      },
      global: { stubs: formStubs(validate) },
    });

    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(validate).toHaveBeenCalledTimes(1);
    expect(wrapper.emitted('submit')?.[0]).toEqual([
      expect.objectContaining({
        sessionId: 21,
        payload: expect.objectContaining({
          reviewerName: 'expert',
          problemDescription: 'problem',
          ownerName: 'owner',
        }),
      }),
    ]);
    wrapper.unmount();
  });

  it('does not submit a problem form after its session changes during validation', async () => {
    const pendingValidation = deferred<boolean>();
    const validate = vi.fn(() => pendingValidation.promise);
    const wrapper = mount(ReviewProblemItemFormDialog, {
      props: {
        visible: true,
        saving: false,
        ready: true,
        sessionId: 21,
        modelValue: problemModel(),
        filterOptions: createEmptyReviewDataFilterOptions(),
        editMode: true,
      },
      global: { stubs: formStubs(validate) },
    });

    await wrapper.find('form').trigger('submit');
    await wrapper.setProps({ sessionId: 22 });
    pendingValidation.resolve(true);
    await flushPromises();

    expect(wrapper.emitted('submit')).toBeUndefined();
    wrapper.unmount();
  });
});
