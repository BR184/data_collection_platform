import { createApp } from 'vue';
import { ElLoading } from './element-plus-services';
import 'element-plus/es/components/loading/style/css';
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import App from './App.vue';
import router from './router';
import './styles.css';

const app = createApp(App);

app.directive('loading', ElLoading.directive);
app.config.globalProperties.$ELEMENT = {
  select: {
    popperClass: 'platform-select-dropdown',
    fitInputWidth: true,
  },
};
app.use(router).mount('#app');
