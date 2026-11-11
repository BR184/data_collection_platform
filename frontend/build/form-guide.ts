import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { marked } from 'marked';
import type { Plugin } from 'vite';

/** 将随包维护的说明 Markdown 生成为离线页面，开发与发布使用同一内容源。 */
export function formGuide(): Plugin {
  let root: string;
  const render = (): string => {
    const markdown = readFileSync(resolve(root, 'public/help/forms/guide.md'), 'utf8');
    return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>表单系统使用说明</title>
<style>
body{margin:0;background:#f5f7fa;color:#263445;font:16px/1.8 system-ui,"Microsoft YaHei",sans-serif}
main{max-width:960px;margin:0 auto;padding:32px 24px 80px;background:white}
h1{font-size:28px}h2{margin-top:48px;border-bottom:1px solid #dce3eb;padding-bottom:8px}h3{margin-top:32px}
img{display:block;max-width:100%;height:auto;margin:20px auto;border:1px solid #dce3eb;border-radius:8px}
a{color:#006dcc}code{background:#f1f4f8;padding:2px 5px;border-radius:3px;overflow-wrap:anywhere}
table{border-collapse:collapse;width:100%;font-size:14px}th,td{border:1px solid #dce3eb;padding:10px;text-align:left;vertical-align:top;overflow-wrap:anywhere}
blockquote{margin:16px 0;padding:8px 16px;border-left:3px solid #0078d4;background:#f5f8fc}
nav{font-size:14px;text-align:right}hr{border:0;border-top:1px solid #dce3eb}
@media(max-width:600px){main{padding:20px 12px 48px}body{font-size:15px}th,td{padding:6px}}
</style></head><body><main><nav><a href="guide.md" download="表单系统使用说明.md">下载 Markdown</a></nav>${marked.parse(markdown, { async: false })}</main></body></html>`;
  };
  return {
    name: 'form-guide',
    configResolved(config) { root = config.root; },
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (!['/help/forms/', '/help/forms/index.html'].includes((req.url ?? '').split('?')[0])) return next();
        res.setHeader('Content-Type', 'text/html; charset=utf-8');
        res.end(render());
      });
    },
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'help/forms/index.html', source: render() });
    },
  };
}
