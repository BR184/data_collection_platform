import { nextTick } from 'vue';
import type { FormInstance } from 'element-plus';

type ElementPlusFormField = {
  prop?: string;
  propString?: string;
  validateState?: string;
  $el?: HTMLElement;
};

type FocusableFormInstance = FormInstance & {
  $el?: HTMLElement;
  fields?: ElementPlusFormField[];
  scrollToField?: (prop: string) => void;
};

const focusableSelector = [
  'input:not([disabled])',
  'textarea:not([disabled])',
  'button:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
  '.el-select__wrapper',
].join(', ');

function focusTarget(root?: HTMLElement | null) {
  return root?.querySelector<HTMLElement>(focusableSelector) ?? null;
}

function focusElement(element: HTMLElement) {
  element.focus({ preventScroll: true });
  element.scrollIntoView({ block: 'center', behavior: 'smooth' });
}

export async function focusFirstInvalidFormField(formInstance?: FormInstance) {
  await nextTick();
  const instance = formInstance as FocusableFormInstance | undefined;
  const firstErrorField = instance?.fields?.find((field) => field.validateState === 'error');
  const firstErrorProp = firstErrorField?.propString ?? firstErrorField?.prop;
  if (firstErrorProp) {
    instance?.scrollToField?.(firstErrorProp);
  }

  const target = focusTarget(firstErrorField?.$el) ?? focusTarget(instance?.$el?.querySelector('.is-error'));
  if (target) {
    focusElement(target);
  }
}
