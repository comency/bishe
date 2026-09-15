import { createApp } from 'vue'
import App from './App.vue'
import { pinia } from './stores'
import { createAppRouter } from './router'
import './styles.css'

createApp(App).use(pinia).use(createAppRouter(pinia)).mount('#app')
