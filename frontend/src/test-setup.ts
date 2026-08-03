class ResizeObserverTestStub implements ResizeObserver {
  observe(_target: Element, _options?: ResizeObserverOptions): void {}

  unobserve(_target: Element): void {}

  disconnect(): void {}
}

Object.defineProperty(globalThis, 'ResizeObserver', {
  configurable: true,
  writable: true,
  value: ResizeObserverTestStub,
});

Object.defineProperty(globalThis, 'requestAnimationFrame', {
  configurable: true,
  writable: true,
  value: (callback: FrameRequestCallback) => {
    callback(0);
    return 0;
  },
});

Object.defineProperty(globalThis, 'cancelAnimationFrame', {
  configurable: true,
  writable: true,
  value: (_handle: number) => undefined,
});

Object.defineProperties(URL, {
  createObjectURL: {
    configurable: true,
    writable: true,
    value: () => 'blob:test-download',
  },
  revokeObjectURL: {
    configurable: true,
    writable: true,
    value: (_url: string) => undefined,
  },
});
