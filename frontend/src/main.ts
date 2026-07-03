import { createApp } from 'vue';
import { ElLoading } from './element-plus-services';
import 'element-plus/es/components/loading/style/css';
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import App from './App.vue';
import router from './router';
// 全局配色系统 - 必须在 styles.css 之前导入
import './theme/colors.css';
import './theme/button.css';
import './theme/components.css';
import './styles.css';
import { installFloatingTableScrollbars } from './composables/floating-table-scrollbars';

const app = createApp(App);

app.directive('loading', ElLoading.directive);
app.config.globalProperties.$ELEMENT = {
  select: {
    popperClass: 'platform-select-dropdown',
    fitInputWidth: true,
  },
};
app.use(router).mount('#app');
installFloatingTableScrollbars();
