import { describe, expect, it } from 'vitest';
import { delayWritebackSwitchPrompt } from './delay-writeback-switch-prompt';

describe('delayWritebackSwitchPrompt', () => {
  it('tells the operator that enabling performs real GitLab API writes', () => {
    const prompt = delayWritebackSwitchPrompt(true);

    expect(prompt.title).toBe('开启延期标签写回');
    expect(prompt.confirmButtonText).toBe('确认开启');
    expect(prompt.message).toContain('真实调用 GitLab API');
    expect(prompt.message).toContain('响应已延期');
    expect(prompt.message).toContain('解决已延期');
    expect(prompt.message).not.toContain('仍不会写回');
  });

  it('warns that enabling still writes nothing when the GitLab web address is missing', () => {
    const prompt = delayWritebackSwitchPrompt(true, false);

    expect(prompt.message).toContain('当前未填写 GitLab Web 地址，保存后仍不会写回。');
  });

  it('explains that disabling keeps monitoring delay facts but stops writing labels', () => {
    const prompt = delayWritebackSwitchPrompt(false);

    expect(prompt.title).toBe('关闭延期标签写回');
    expect(prompt.confirmButtonText).toBe('确认关闭');
    expect(prompt.message).toContain('仍会监控延期事实');
    expect(prompt.message).toContain('已排队的写回任务会被跳过');
  });
});
