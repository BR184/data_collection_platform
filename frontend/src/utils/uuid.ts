/**
 * 生成满足 UUID v4 形状的标识。
 *
 * 内网现场以 `http://<内网IP>:<端口>` 明文访问时不属于安全上下文，浏览器不提供
 * `crypto.randomUUID`（Chrome 92+ 只对 https、localhost 暴露），调用处会直接抛
 * `crypto.randomUUID is not a function`；`crypto.getRandomValues` 在不安全上下文同样可用，
 * 因此按 randomUUID → getRandomValues → Math.random 逐级降级。
 *
 * 后端 `/api/read-views` 的 `clientViewId` 是 `@NotNull UUID`，所以**每一级降级都必须返回合法
 * UUID 形状**，不能用 `view-<时间戳>` 之类的自定义前缀，否则请求会被判 400。
 */
export function createUuid(): string {
  const webCrypto = globalThis.crypto;
  if (typeof webCrypto?.randomUUID === 'function') {
    return webCrypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (typeof webCrypto?.getRandomValues === 'function') {
    webCrypto.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
