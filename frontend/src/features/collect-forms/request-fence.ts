export interface RequestToken {
  id: number;
  scope: string;
}

/**
 * 为同一页面的查询/提交建立顺序栅栏：只有最新一次且仍在原作用域的请求可以写回状态。
 */
export class RequestFence {
  private sequence = 0;
  private closed = false;

  next(scope: string): RequestToken {
    this.sequence += 1;
    return { id: this.sequence, scope };
  }

  isCurrent(token: RequestToken): boolean {
    return !this.closed && token.id === this.sequence;
  }

  invalidate(): void {
    this.sequence += 1;
  }

  close(): void {
    this.closed = true;
    this.sequence += 1;
  }
}
