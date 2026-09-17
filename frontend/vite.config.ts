import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import { manualChunks } from './build/manual-chunks';

const apiProxyTarget = process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:18080';

export default defineConfig(({ command }) => ({
  plugins: [
    vue(),
    Components({
      // 开发时持续维护类型声明；生产构建只消费已提交声明，避免构建过程改写源码文件。
      dts: command === 'serve' ? 'src/components.d.ts' : false,
      resolvers: [ElementPlusResolver({ importStyle: 'css' })],
    }),
  ],
  build: {
    chunkSizeWarningLimit: 850,
    rollupOptions: {
      output: {
        manualChunks,
      },
    },
  },
  server: {
    host: '0.0.0.0',
    port: 18181,
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
    server: {
      deps: {
        inline: ['element-plus'],
      },
    },
  },
}));
